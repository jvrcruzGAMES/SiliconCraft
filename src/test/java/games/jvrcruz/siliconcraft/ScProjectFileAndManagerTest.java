package games.jvrcruz.siliconcraft;

import games.jvrcruz.siliconcraft.data.ScProjectFile;
import games.jvrcruz.siliconcraft.network.ChunkedTransferStore;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

public class ScProjectFileAndManagerTest {

    @Test
    public void testParseJsonAndGzipScFilesAndExecuteOnCpu() throws Exception {
        String json = """
                {
                  "format": "siliconcraft-sc",
                  "version": 1,
                  "name": "demo_app",
                  "target": "n2",
                  "requiredInstructions": ["MOV", "OUT", "HLT"],
                  "bytecode": [
                    "MOV A, 42",
                    "OUT A",
                    "HLT"
                  ]
                }
                """;

        ScProjectFile.ParsedProject plain = ScProjectFile.parseBytes(json.getBytes(StandardCharsets.UTF_8), null);
        assertEquals("demo_app.sc", plain.name());
        assertEquals("N2", plain.archName());
        assertEquals("n2", plain.target());
        assertEquals(List.of("MOV A, 42", "OUT A", "HLT"), plain.bytecode());
        assertEquals(List.of("MOV", "OUT", "HLT"), plain.requiredInstructions());

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(Set.copyOf(plain.requiredInstructions()), 256, plain.bytecode(), false, 0);
        cpu.powerOn();
        cpu.tick(50);
        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertTrue(cpu.getTerminalOutput().contains("42"));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        }

        ScProjectFile.ParsedProject compressed = ScProjectFile.parseBytes(baos.toByteArray(), "/tmp/custom_build.sc");
        assertEquals("custom_build.sc", compressed.name());
        assertEquals(plain.bytecode(), compressed.bytecode());
        assertEquals(plain.requiredInstructions(), compressed.requiredInstructions());
    }

    @Test
    public void testInvalidScFilesRejected() {
        assertThrows(IllegalArgumentException.class, () -> ScProjectFile.parseBytes(new byte[0], "a.sc"));
        assertThrows(IllegalArgumentException.class, () -> ScProjectFile.parseJson("{\"format\":\"wrong\",\"version\":1,\"bytecode\":[\"HLT\"]}", "a.sc"));
        assertThrows(IllegalArgumentException.class, () -> ScProjectFile.parseJson("{\"format\":\"siliconcraft-sc\",\"version\":99,\"bytecode\":[\"HLT\"]}", "a.sc"));
        assertThrows(IllegalArgumentException.class, () -> ScProjectFile.parseJson("{\"format\":\"siliconcraft-sc\",\"version\":1,\"bytecode\":[]}", "a.sc"));
    }

    @Test
    public void testRenameValidation() {
        List<String> others = List.of("counter.sc", "fibonacci.sc");
        assertThrows(IllegalArgumentException.class, () -> ScProjectFile.validateRenameName("   ", others));
        assertThrows(IllegalArgumentException.class, () -> ScProjectFile.validateRenameName("bad/name.sc", others));
        assertThrows(IllegalArgumentException.class, () -> ScProjectFile.validateRenameName("bad\\name.sc", others));
        assertThrows(IllegalArgumentException.class, () -> ScProjectFile.validateRenameName("Counter.sc", others));
        assertThrows(IllegalArgumentException.class, () -> ScProjectFile.validateRenameName("x".repeat(ScProjectFile.MAX_NAME_LENGTH + 1), others));

        assertEquals("arcade.sc", ScProjectFile.validateRenameName(" arcade.sc ", others));
    }

    @Test
    public void testChunkedTransferRoundtripForSc() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"format\":\"siliconcraft-sc\",\"version\":1,\"name\":\"big.sc\",\"target\":\"n2\",\"bytecode\":[");
        int count = 40000;
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            sb.append("\"NOP\"");
        }
        sb.append("]}");
        byte[] raw = sb.toString().getBytes(StandardCharsets.UTF_8);

        List<byte[]> chunks = ChunkedTransferStore.splitIntoChunks(raw);
        assertFalse(chunks.isEmpty());

        ChunkedTransferStore<String> store = new ChunkedTransferStore<>();
        UUID player = UUID.randomUUID();
        UUID transfer = UUID.randomUUID();

        assertNull(store.startTransfer(player, transfer, "big.sc", chunks.size(), raw.length));
        ChunkedTransferStore.ChunkOutcome<String> last = null;
        for (int i = 0; i < chunks.size(); i++) {
            last = store.acceptChunk(player, transfer, i, chunks.get(i));
        }
        assertNotNull(last);
        assertEquals(ChunkedTransferStore.Status.COMPLETE, last.status());
        ScProjectFile.ParsedProject parsed = ScProjectFile.parseBytes(last.payload(), last.metadata());
        assertEquals("big.sc", parsed.name());
        assertEquals(count, parsed.bytecode().size());
    }
}
