package games.jvrcruz.siliconcraft;

import games.jvrcruz.siliconcraft.network.ChunkedTransferStore;
import games.jvrcruz.siliconcraft.network.PayloadCompression;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class LargePayloadCodecTest {

    @Test
    void projectFilesCompressionHandlesSingleLineBeyondMinecraft32kLimit() {
        String longLine = "let data = [" + "1234,".repeat(40_000) + "0];";
        assertTrue(longLine.length() > 179_326, "Expected line larger than crashing 179,326-char case");

        Map<String, List<String>> files = new LinkedHashMap<>();
        files.put("main.n2", List.of("fn main() {", longLine, "}"));
        files.put("lib/sprites.n2", List.of("const sprite = \"ABCDEFGH\";"));

        byte[] compressed = PayloadCompression.compressProjectFiles(files);
        assertTrue(compressed.length < PayloadCompression.MAX_WIRE_BYTES, "Compressed wire payload should fit safely in packet limit");

        Map<String, List<String>> decoded = PayloadCompression.decompressProjectFiles(compressed);
        assertEquals(files, decoded);
        assertEquals(List.copyOf(files.keySet()), List.copyOf(decoded.keySet()));
    }

    @Test
    void stringListCompressionHandlesUtf8StringsBeyond32kAnd64kWriteUtfLimits() {
        String hugeUtf8Line = "DB \"" + "ΩαβγXYZ0123456789".repeat(12_000) + "\"";
        assertTrue(hugeUtf8Line.getBytes(StandardCharsets.UTF_8).length > 200_000);

        List<String> lines = List.of("JMP start", hugeUtf8Line, "HLT");
        byte[] compressed = PayloadCompression.compressStringList(lines);
        assertTrue(compressed.length < PayloadCompression.MAX_WIRE_BYTES);

        List<String> decoded = PayloadCompression.decompressStringList(compressed);
        assertEquals(lines, decoded);
    }

    @Test
    void malformedLengthInCompressedStreamIsRejectedByBudgetCheck() {
        byte[] malicious = PayloadCompression.compress(out -> out.writeInt(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> PayloadCompression.decompressStringList(malicious));
    }

    @Test
    void chunkedUploadReassemblesProjectsExceedingSinglePacketLimit() {
        // Build a project whose compressed payload is > 2.2 MB (above Netty's 2 MiB single-packet cap)
        Random rng = new Random(12345L);
        StringBuilder noisyData = new StringBuilder(2_800_000);
        for (int i = 0; i < 2_800_000; i++) {
            noisyData.append((char) ('!' + rng.nextInt(90)));
        }
        Map<String, List<String>> files = new LinkedHashMap<>();
        files.put("main.n2", List.of("fn main() { hlt(); }"));
        files.put("assets/blob.n2", List.of("// " + noisyData));

        byte[] compressed = PayloadCompression.compressProjectFiles(files);
        assertTrue(compressed.length > 2_097_152, "Expected compressed payload > 2 MiB to exercise multi-chunk upload");

        List<byte[]> chunks = ChunkedTransferStore.splitIntoChunks(compressed);
        assertTrue(chunks.size() > 8);

        ChunkedTransferStore<String> store = new ChunkedTransferStore<>();
        UUID playerId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();

        assertNull(store.startTransfer(playerId, transferId, "giant_project.zip", chunks.size(), compressed.length));
        assertEquals(1, store.activeTransferCount());

        ChunkedTransferStore.ChunkOutcome<String> finalOutcome = null;
        for (int i = 0; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).length <= ChunkedTransferStore.CHUNK_SIZE);
            ChunkedTransferStore.ChunkOutcome<String> outcome = store.acceptChunk(playerId, transferId, i, chunks.get(i));
            if (i < chunks.size() - 1) {
                assertEquals(ChunkedTransferStore.Status.ACCEPTED, outcome.status());
            } else {
                finalOutcome = outcome;
            }
        }

        assertNotNull(finalOutcome);
        assertEquals(ChunkedTransferStore.Status.COMPLETE, finalOutcome.status());
        assertEquals("giant_project.zip", finalOutcome.metadata());
        assertEquals(0, store.activeTransferCount());

        Map<String, List<String>> decoded = PayloadCompression.decompressProjectFiles(finalOutcome.payload());
        assertEquals(files, decoded);
    }

    @Test
    void chunkedTransferRejectsOversizedOutOfOrderAndExpiredTransfers() {
        AtomicLong clock = new AtomicLong(1_000L);
        ChunkedTransferStore<String> store = new ChunkedTransferStore<>(1024, 4096, 5_000L, clock::get);
        UUID playerId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();

        // Rejects oversized totalBytes
        assertNotNull(store.startTransfer(playerId, transferId, "meta", 5, 5000));

        // Rejects mismatched chunk count vs size
        assertNotNull(store.startTransfer(playerId, transferId, "meta", 1, 2000));

        // Valid start then out-of-order chunk
        assertNull(store.startTransfer(playerId, transferId, "meta", 2, 1500));
        var badOrder = store.acceptChunk(playerId, transferId, 1, new byte[1024]);
        assertEquals(ChunkedTransferStore.Status.REJECTED, badOrder.status());
        assertEquals(0, store.activeTransferCount());

        // Timeout expiration
        assertNull(store.startTransfer(playerId, transferId, "meta", 2, 1500));
        clock.addAndGet(6_000L);
        var expired = store.acceptChunk(playerId, transferId, 0, new byte[1024]);
        assertEquals(ChunkedTransferStore.Status.REJECTED, expired.status());
        assertEquals(0, store.activeTransferCount());
    }
}
