package games.jvrcruz.siliconcraft.vm;

import games.jvrcruz.siliconcraft.blockentity.ComputerBlockEntity;
import games.jvrcruz.siliconcraft.compiler.Assembler;
import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import games.jvrcruz.siliconcraft.compiler.n2.N2Compiler;
import games.jvrcruz.siliconcraft.data.GpuData;
import games.jvrcruz.siliconcraft.data.ProgramEntry;
import games.jvrcruz.siliconcraft.data.ProgramSavedData;
import games.jvrcruz.siliconcraft.data.RomData;
import games.jvrcruz.siliconcraft.data.ScProjectFile;
import games.jvrcruz.siliconcraft.data.SocData;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class BinaryBytecodeTest {

    @Test
    void instructionsAreFixed64BitWordsAndDecodeAcrossAllFormats() {
        var asm = Assembler.assemble(List.of(
                "NOP",
                "MOV A, 0x12345678",
                "MOV [100], -42",
                "MOV [0x123456], 0x01020304",
                "OUT \"Line\"",
                "OUTH \"Hi\", 0",
                "FBRECT 10, 20, 30, 40, 0xFF112233",
                "HLT"
        ));
        assertTrue(asm.success(), asm.errors().toString());
        byte[] scbc = asm.bytecode();
        assertTrue(BinaryBytecode.isScbc(scbc));

        ByteBuffer buf = ByteBuffer.wrap(scbc).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        buf.get(magic);
        assertEquals("SCBC", new String(magic, StandardCharsets.US_ASCII));
        assertEquals(BinaryBytecode.VERSION, Short.toUnsignedInt(buf.getShort()));
        buf.getShort(); // flags
        int instrCount = buf.getInt();
        assertEquals(8, instrCount);

        BinaryBytecode.ProgramImage img = BinaryBytecode.decodeScbc(scbc);
        assertEquals(8, img.code().length);
        assertEquals(8, BinaryBytecode.getInstructionCount(scbc));

        long w0 = img.code()[0];
        long w1 = img.code()[1];
        long w2 = img.code()[2];
        long w3 = img.code()[3];
        long w4 = img.code()[4];
        long w5 = img.code()[5];
        long w6 = img.code()[6];
        long w7 = img.code()[7];

        assertEquals(BinaryBytecode.OP_NOP, BinaryBytecode.extractOpcode(w0));
        assertEquals(BinaryBytecode.FMT_0, BinaryBytecode.extractFormat(w0));

        assertEquals(BinaryBytecode.OP_MOV, BinaryBytecode.extractOpcode(w1));
        assertEquals(BinaryBytecode.FMT_0, BinaryBytecode.extractFormat(w1));
        assertEquals(0x12345678, BinaryBytecode.extractImm32(w1));

        assertEquals(BinaryBytecode.OP_MOV, BinaryBytecode.extractOpcode(w2));
        assertEquals(BinaryBytecode.FMT_1, BinaryBytecode.extractFormat(w2));
        assertEquals(100, BinaryBytecode.extractFmt1Val0(w2));
        assertEquals(-42, BinaryBytecode.extractFmt1Val1(w2));

        // Huge 2-operand immediates spill to FMT_3 + .ext
        assertEquals(BinaryBytecode.OP_MOV, BinaryBytecode.extractOpcode(w3));
        assertEquals(BinaryBytecode.FMT_3, BinaryBytecode.extractFormat(w3));

        // OUT sets FMT_2 newline flag=1; OUTH "Hi", 0 clears newline flag=0
        assertEquals(BinaryBytecode.OP_OUT, BinaryBytecode.extractOpcode(w4));
        assertEquals(BinaryBytecode.FMT_2, BinaryBytecode.extractFormat(w4));
        assertEquals(1, BinaryBytecode.extractFlags(w4));
        assertEquals("Line", img.rodata()[BinaryBytecode.extractImm32(w4)]);

        assertEquals(BinaryBytecode.OP_OUTH, BinaryBytecode.extractOpcode(w5));
        assertEquals(BinaryBytecode.FMT_2, BinaryBytecode.extractFormat(w5));
        assertEquals(0, BinaryBytecode.extractFlags(w5));
        assertEquals("Hi", img.rodata()[BinaryBytecode.extractImm32(w5)]);

        // 5-operand FBRECT uses FMT_3 + .ext
        assertEquals(BinaryBytecode.OP_FBRECT, BinaryBytecode.extractOpcode(w6));
        assertEquals(BinaryBytecode.FMT_3, BinaryBytecode.extractFormat(w6));
        assertEquals(5, BinaryBytecode.extractFlags(w6));

        assertEquals(BinaryBytecode.OP_HLT, BinaryBytecode.extractOpcode(w7));
        assertEquals(Set.of("NOP", "MOV", "OUT", "OUTH", "FBRECT", "HLT"),
                BinaryBytecode.getRequiredInstructions(scbc));
    }

    @Test
    void dataDirectivesAndIncbinPopulateRamOnReset(@TempDir Path tmp) throws Exception {
        Files.write(tmp.resolve("blob.bin"), new byte[]{(byte) 0xAB, 0x05});
        var asm = Assembler.assemble(List.of(
                "ORG 120",
                "DB 10, \"OK\"",
                "DW 0x1234",
                "DL 0x11223344",
                "INCBIN \"blob.bin\"",
                "MOV A, [120]",
                "OUT A",
                "MOV B, [125]",
                "OUT B",
                "HLT"
        ), tmp);
        assertTrue(asm.success(), asm.errors().toString());

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 512, asm.bytecode(), false, 0);
        // Corrupt RAM then reset -> static data segments re-initialize
        cpu.getRam()[120] = 999;
        cpu.reset();
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState(), cpu.getErrorMessage());
        assertEquals(10, cpu.getRam()[120]);
        assertEquals('O', cpu.getRam()[121]);
        assertEquals('K', cpu.getRam()[122]);
        assertEquals(0x34, cpu.getRam()[123]);
        assertEquals(0x12, cpu.getRam()[124]);
        // DL encodes a 24-bit word (3 bytes little-endian)
        assertEquals(0x44, cpu.getRam()[125]);
        assertEquals(0x33, cpu.getRam()[126]);
        assertEquals(0x22, cpu.getRam()[127]);
        assertEquals(0xAB, cpu.getRam()[128]);
        assertEquals(0x05, cpu.getRam()[129]);
        assertEquals(List.of("10", "68", "[EXECUTION HALTED]"), cpu.getTerminalOutput());
    }

    @Test
    void sexecRunsRawScbcBinaryFromStorageDrive() {
        var child = Assembler.assemble(List.of(
                "MOV A, 77",
                "OUT A",
                "HLT"
        ));
        assertTrue(child.success(), child.errors().toString());

        var boot = Assembler.assemble(List.of(
                "SEXEC 0",
                "HLT"
        ));
        assertTrue(boot.success(), boot.errors().toString());

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 1024, boot.bytecode(), false, 1024);
        cpu.setDriveBinary(0, child.bytecode());
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState(), cpu.getErrorMessage());
        assertEquals(77, cpu.getRegA());
        assertTrue(cpu.getTerminalOutput().contains("77"));
    }

    @Test
    void programSavedDataAndSocNbtMigrateLegacyAssemblyListsToBinary() {
        CompoundTag savedTag = new CompoundTag();
        savedTag.putBoolean("DefaultsInitialized", true);

        CompoundTag legacyEntry = new CompoundTag();
        legacyEntry.putString("Id", "legacy_prog");
        legacyEntry.putString("Name", "legacy.sc");
        legacyEntry.putString("ArchName", "ASM");
        ListTag linesTag = new ListTag();
        linesTag.add(StringTag.valueOf("MOV A, 123"));
        linesTag.add(StringTag.valueOf("OUT A"));
        linesTag.add(StringTag.valueOf("HLT"));
        legacyEntry.put("Lines", linesTag);

        ListTag list = new ListTag();
        list.add(legacyEntry);
        savedTag.put("Programs", list);

        ProgramSavedData loaded = ProgramSavedData.load(savedTag, RegistryAccess.EMPTY);
        assertEquals(1, loaded.getPrograms().size());
        ProgramEntry migrated = loaded.getPrograms().getFirst();
        assertTrue(BinaryBytecode.isScbc(migrated.bytecode()));
        assertEquals(3, migrated.getInstructionCount());

        CompoundTag reSaved = loaded.save(new CompoundTag(), RegistryAccess.EMPTY);
        ProgramSavedData reloaded = ProgramSavedData.load(reSaved, RegistryAccess.EMPTY);
        assertArrayEquals(migrated.bytecode(), reloaded.getPrograms().getFirst().bytecode());

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 512, reloaded.getPrograms().getFirst().bytecode(), false, 0);
        cpu.powerOn();
        cpu.tick(50);
        assertEquals(VirtualCpu.State.HALTED, cpu.getState(), cpu.getErrorMessage());
        assertTrue(cpu.getTerminalOutput().contains("123"));

        // Default demo programs also assemble to valid 64-bit SCBC binary and run cleanly
        ProgramSavedData defaults = new ProgramSavedData();
        assertEquals(2, defaults.getPrograms().size());
        for (ProgramEntry entry : defaults.getPrograms()) {
            assertTrue(BinaryBytecode.isScbc(entry.bytecode()));
            VirtualCpu demoCpu = new VirtualCpu();
            demoCpu.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 512, entry.bytecode(), false, 0);
            demoCpu.powerOn();
            demoCpu.tick(200);
            assertEquals(VirtualCpu.State.HALTED, demoCpu.getState(), demoCpu.getErrorMessage());
        }

        // Legacy SocData "Bios" list migrates to binary BIOS
        CompoundTag legacySocTag = new CompoundTag();
        ListTag biosLines = new ListTag();
        biosLines.add(StringTag.valueOf("MOV B, 99"));
        biosLines.add(StringTag.valueOf("HLT"));
        legacySocTag.put("Bios", biosLines);
        legacySocTag.putInt("RamCapacity", 4096);
        legacySocTag.put("Instructions", new ListTag());
        SocData migratedSoc = deserializeSocViaReflection(legacySocTag);
        assertTrue(BinaryBytecode.isScbc(migratedSoc.bios()));
        assertEquals(2, migratedSoc.getBiosInstructionCount());

        // RomData binary wrapper check
        RomData rom = new RomData(migrated.bytecode());
        assertEquals(3, rom.getInstructionCount());
        assertTrue(rom.bytecode().length > 0);
        assertEquals(0, RomData.EMPTY.bytecode().length);
    }

    @Test
    void rustCompilerAndJavaAssemblerEmitIdentical64BitBinaryAndExecuteEqually(@TempDir Path tmp) throws Exception {
        Path compilerDir = Path.of("compiler").toAbsolutePath().normalize();
        Path releaseBin = compilerDir.resolve("target/release/scc");
        assertTrue(Files.isExecutable(releaseBin),
                "Expected release scc binary at " + releaseBin + " (run cargo build --release in compiler/)");

        // 1. Assembly parity check between Rust scc and Java Assembler
        String asmSrc = """
                MOV A, 12
                MOV B, 30
                ADD A, B
                OUT "Sum=", 0
                OUTH A
                HLT
                """;
        Path asmFile = tmp.resolve("parity.asm");
        Path asmScFile = tmp.resolve("parity.sc");
        Path asmObjFile = tmp.resolve("parity.o");
        Files.writeString(asmFile, asmSrc);

        Process asmProc = new ProcessBuilder(
                releaseBin.toString(),
                "asm",
                asmFile.toString(),
                "--emit-bytecode", asmObjFile.toString(),
                "-o", asmScFile.toString(),
                "-q"
        ).redirectErrorStream(true).start();
        String asmProcOut = new String(asmProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, asmProc.waitFor(), "scc asm failed: " + asmProcOut);

        byte[] rustObj = Files.readAllBytes(asmObjFile);
        byte[] rustSc = Files.readAllBytes(asmScFile);

        var javaAsm = Assembler.assemble(asmSrc.lines().toList());
        assertTrue(javaAsm.success(), javaAsm.errors().toString());
        assertArrayEquals(javaAsm.bytecode(), rustObj,
                "Rust and Java assemblers must emit byte-for-byte identical SCBC binaries");

        ScProjectFile.ParsedProject parsedAsmSc = ScProjectFile.parseBytes(rustSc, null);
        assertTrue(parsedAsmSc.hasMetadata());
        assertEquals(3, parsedAsmSc.manifest().sections().size());
        assertEquals(ScProjectFile.SECTION_MANIFEST, parsedAsmSc.manifest().sections().get(0).id());
        assertEquals(0, parsedAsmSc.manifest().sections().get(0).offset());
        assertEquals(ScProjectFile.SECTION_METADATA, parsedAsmSc.manifest().sections().get(1).id());
        assertEquals(ScProjectFile.SECTION_PROGRAM, parsedAsmSc.manifest().sections().get(2).id());
        assertArrayEquals(rustObj, parsedAsmSc.bytecode());
        assertEquals(6, parsedAsmSc.getInstructionCount());

        // 2. Full N2 compilation with Rust scc -> SCPK v3 (3 sections) & --no-metadata (2 sections) -> Java VirtualCpu execution
        String n2Src = """
                import <std>

                func factorial(n)
                    if n <= 1 then
                        return 1
                    end
                    return n * factorial(n - 1)
                end

                func main()
                    print(factorial(5))
                    print("rust-n2-ok")
                end
                """;
        Path n2File = tmp.resolve("fact.n2");
        Path n2ScFile = tmp.resolve("fact.sc");
        Path n2StrippedFile = tmp.resolve("fact_stripped.sc");
        Files.writeString(n2File, n2Src);

        Process n2Proc = new ProcessBuilder(
                releaseBin.toString(),
                n2File.toString(),
                "-o", n2ScFile.toString(),
                "-q"
        ).redirectErrorStream(true).start();
        String n2ProcOut = new String(n2Proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, n2Proc.waitFor(), "scc n2 build failed: " + n2ProcOut);

        Process n2StrippedProc = new ProcessBuilder(
                releaseBin.toString(),
                n2File.toString(),
                "--no-metadata",
                "-o", n2StrippedFile.toString(),
                "-q"
        ).redirectErrorStream(true).start();
        assertEquals(0, n2StrippedProc.waitFor());

        byte[] n2ScBytes = Files.readAllBytes(n2ScFile);
        ScProjectFile.ParsedProject parsedN2 = ScProjectFile.parseBytes(n2ScBytes, null);
        assertTrue(parsedN2.hasMetadata());
        assertEquals("fact.sc", parsedN2.name());
        assertEquals("N2", parsedN2.archName());
        assertTrue(parsedN2.getInstructionCount() > 0);

        byte[] strippedBytes = Files.readAllBytes(n2StrippedFile);
        ScProjectFile.ParsedProject parsedStripped = ScProjectFile.parseBytes(strippedBytes, "custom_stripped.sc");
        assertFalse(parsedStripped.hasMetadata());
        assertEquals(2, parsedStripped.manifest().sections().size());
        assertEquals("custom_stripped.sc", parsedStripped.name());
        assertArrayEquals(parsedN2.bytecode(), parsedStripped.bytecode());
        assertTrue(strippedBytes.length < n2ScBytes.length);

        VirtualCpu rustCpu = new VirtualCpu();
        rustCpu.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 65536, parsedN2.bytecode(), false, 0);
        rustCpu.powerOn();
        rustCpu.tick(5000);
        assertEquals(VirtualCpu.State.HALTED, rustCpu.getState(), rustCpu.getErrorMessage());
        assertEquals(List.of("120", "rust-n2-ok", "[EXECUTION HALTED]"), rustCpu.getTerminalOutput());

        // Compare against Java N2Compiler + Assembler
        var javaN2 = N2Compiler.compile(n2Src);
        assertTrue(javaN2.success(), javaN2.errors().toString());
        var javaN2Asm = Assembler.assemble(javaN2.assembly());
        assertTrue(javaN2Asm.success(), javaN2Asm.errors().toString());
        assertArrayEquals(javaN2Asm.bytecode(), parsedN2.bytecode(),
                "Rust N2 pipeline and Java N2 pipeline must produce identical SCBC binaries");
    }

    private static SocData deserializeSocViaReflection(CompoundTag tag) throws RuntimeException {
        try {
            var method = ComputerBlockEntity.class.getDeclaredMethod("deserializeSocTag", CompoundTag.class);
            method.setAccessible(true);
            return (SocData) method.invoke(null, tag);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
