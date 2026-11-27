package games.jvrcruz.siliconcraft;

import games.jvrcruz.siliconcraft.compiler.Assembler;
import games.jvrcruz.siliconcraft.data.ScProjectFile;
import games.jvrcruz.siliconcraft.network.ChunkedTransferStore;
import games.jvrcruz.siliconcraft.vm.BinaryBytecode;
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
    public void testParseBinaryAndGzipScFilesAndExecuteOnCpu() throws Exception {
        var assembled = Assembler.assemble(List.of(
                "MOV A, 42",
                "OUT A",
                "HLT"
        ));
        assertTrue(assembled.success(), assembled.errors().toString());
        byte[] scBytes = ScProjectFile.buildBytes(
                "demo_app",
                "N2",
                "n2",
                assembled.bytecode(),
                List.of("MOV", "OUT", "HLT")
        );
        assertEquals('S', (char) scBytes[0]);
        assertEquals('C', (char) scBytes[1]);
        assertEquals('P', (char) scBytes[2]);
        assertEquals('K', (char) scBytes[3]);

        ScProjectFile.Manifest manifest = ScProjectFile.parseManifest(scBytes);
        assertEquals(3, manifest.sections().size());
        assertEquals(ScProjectFile.SECTION_MANIFEST, manifest.sections().get(0).id());
        assertEquals(0, manifest.sections().get(0).offset());
        assertEquals(ScProjectFile.SECTION_METADATA, manifest.sections().get(1).id());
        assertEquals(ScProjectFile.SECTION_PROGRAM, manifest.sections().get(2).id());

        ScProjectFile.ParsedProject plain = ScProjectFile.parseBytes(scBytes, null);
        assertTrue(plain.hasMetadata());
        assertEquals("demo_app.sc", plain.name());
        assertEquals("N2", plain.archName());
        assertEquals("n2", plain.target());
        assertArrayEquals(assembled.bytecode(), plain.bytecode());
        assertEquals(3, plain.getInstructionCount());
        assertEquals(List.of("MOV", "OUT", "HLT"), plain.requiredInstructions());

        // Optional metadata omitted -> 2 mandatory sections (manifest first, program second)
        byte[] strippedSc = ScProjectFile.buildBytes("ignored", "N2", "n2", assembled.bytecode(), List.of("MOV", "OUT", "HLT"), false);
        ScProjectFile.Manifest strippedManifest = ScProjectFile.parseManifest(strippedSc);
        assertEquals(2, strippedManifest.sections().size());
        assertEquals(ScProjectFile.SECTION_MANIFEST, strippedManifest.sections().get(0).id());
        assertEquals(0, strippedManifest.sections().get(0).offset());
        assertEquals(ScProjectFile.SECTION_PROGRAM, strippedManifest.sections().get(1).id());

        ScProjectFile.ParsedProject stripped = ScProjectFile.parseBytes(strippedSc, "minimal_prog.sc");
        assertFalse(stripped.hasMetadata());
        assertEquals("minimal_prog.sc", stripped.name());
        assertArrayEquals(assembled.bytecode(), stripped.bytecode());
        assertEquals(Set.of("MOV", "OUT", "HLT"), Set.copyOf(stripped.requiredInstructions()));

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(Set.copyOf(plain.requiredInstructions()), 256, plain.bytecode(), false, 0);
        cpu.powerOn();
        cpu.tick(50);
        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertTrue(cpu.getTerminalOutput().contains("42"));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(scBytes);
        }

        ScProjectFile.ParsedProject compressed = ScProjectFile.parseBytes(baos.toByteArray(), "/tmp/custom_build.sc");
        assertEquals("custom_build.sc", compressed.name());
        assertArrayEquals(plain.bytecode(), compressed.bytecode());
        assertEquals(plain.requiredInstructions(), compressed.requiredInstructions());
    }

    @Test
    public void testInvalidScFilesRejected() {
        assertThrows(IllegalArgumentException.class, () -> ScProjectFile.parseBytes(new byte[0], "a.sc"));
        assertThrows(IllegalArgumentException.class, () -> ScProjectFile.parseBytes(
                "{\"format\":\"siliconcraft-sc\",\"version\":1,\"bytecode\":[\"HLT\"]}".getBytes(StandardCharsets.UTF_8),
                "a.sc"
        ));

        byte[] emptyScbc = BinaryBytecode.encodeScbc(BinaryBytecode.ProgramImage.EMPTY);
        assertThrows(IllegalArgumentException.class, () -> ScProjectFile.buildBytes(
                "empty.sc", "N2", "n2", emptyScbc, List.of()
        ));

        var validAsm = Assembler.assemble(List.of("HLT"));
        byte[] validSc = ScProjectFile.buildBytes("ok.sc", "N2", "n2", validAsm.bytecode(), List.of("HLT"));
        byte[] badVersion = validSc.clone();
        badVersion[4] = 99;
        badVersion[5] = 0;
        assertThrows(IllegalArgumentException.class, () -> ScProjectFile.parseBytes(badVersion, "a.sc"));

        byte[] badMagic = validSc.clone();
        badMagic[0] = 'X';
        assertThrows(IllegalArgumentException.class, () -> ScProjectFile.parseBytes(badMagic, "a.sc"));

        // Corrupt entry 0 so manifest is not marked as the first section
        byte[] nonManifestFirst = validSc.clone();
        nonManifestFirst[12] = 0x03;
        assertThrows(IllegalArgumentException.class, () -> ScProjectFile.parseBytes(nonManifestFirst, "a.sc"));

        // Corrupt section_count to 1 (missing program section)
        byte[] singleSection = validSc.clone();
        singleSection[8] = 1;
        singleSection[9] = 0;
        assertThrows(IllegalArgumentException.class, () -> ScProjectFile.parseBytes(singleSection, "a.sc"));
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
        int count = 40_000;
        long[] words = new long[count];
        long nopWord = BinaryBytecode.encodeFmt0(BinaryBytecode.OP_NOP, 0, 0, 0, 0, 0, 0, 0);
        java.util.Arrays.fill(words, nopWord);
        byte[] scbc = BinaryBytecode.encodeScbc(new BinaryBytecode.ProgramImage(words, null, null, List.of()));
        byte[] raw = ScProjectFile.buildBytes("big.sc", "N2", "n2", scbc, List.of("NOP"));

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
        assertEquals(count, parsed.getInstructionCount());
        assertArrayEquals(scbc, parsed.bytecode());
    }
}
