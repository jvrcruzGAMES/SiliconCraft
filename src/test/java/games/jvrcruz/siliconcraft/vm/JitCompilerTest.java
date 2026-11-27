package games.jvrcruz.siliconcraft.vm;

import games.jvrcruz.siliconcraft.compiler.Assembler;
import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class JitCompilerTest {

    private static VirtualCpu runProgram(List<String> lines, int ramSize, int maxCycles, boolean jitEnabled) {
        return runProgram(InstructionValidator.ALL_INSTRUCTIONS_SET, lines, ramSize, maxCycles, jitEnabled);
    }

    private static VirtualCpu runProgram(Set<String> isa, List<String> lines, int ramSize, int maxCycles, boolean jitEnabled) {
        var asm = Assembler.assemble(lines);
        assertTrue(asm.success(), "Assembly failed: " + asm.errors());
        VirtualCpu cpu = new VirtualCpu();
        cpu.setJitEnabled(jitEnabled);
        cpu.configure(isa, ramSize, asm.bytecode(), false, 0);
        cpu.powerOn();
        cpu.tick(maxCycles);
        return cpu;
    }

    private static void assertCpuParity(VirtualCpu expectedInterp, VirtualCpu actualJit, int ramCheckWords) {
        assertEquals(expectedInterp.getState(), actualJit.getState(), "State mismatch: jit err=" + actualJit.getErrorMessage());
        assertEquals(expectedInterp.getPc(), actualJit.getPc(), "PC mismatch");
        assertEquals(expectedInterp.getRegA(), actualJit.getRegA(), "RegA mismatch");
        assertEquals(expectedInterp.getRegB(), actualJit.getRegB(), "RegB mismatch");
        assertEquals(expectedInterp.getRegC(), actualJit.getRegC(), "RegC mismatch");
        assertEquals(expectedInterp.getRegD(), actualJit.getRegD(), "RegD mismatch");
        assertEquals(expectedInterp.isFlagZ(), actualJit.isFlagZ(), "FlagZ mismatch");
        assertEquals(expectedInterp.isFlagS(), actualJit.isFlagS(), "FlagS mismatch");
        assertEquals(expectedInterp.getCycles(), actualJit.getCycles(), "Cycles mismatch");
        assertEquals(expectedInterp.getErrorMessage(), actualJit.getErrorMessage(), "Error message mismatch");
        assertEquals(expectedInterp.getTerminalOutput(), actualJit.getTerminalOutput(), "Terminal output mismatch");
        assertEquals(List.copyOf(expectedInterp.getCallStack()), List.copyOf(actualJit.getCallStack()), "Call stack mismatch");
        assertEquals(List.copyOf(expectedInterp.getDataStack()), List.copyOf(actualJit.getDataStack()), "Data stack mismatch");
        for (int i = 0; i < ramCheckWords; i++) {
            assertEquals(expectedInterp.getRam()[i], actualJit.getRam()[i], "RAM[" + i + "] mismatch");
        }
    }

    @Test
    void cpuJitMatchesInterpreterAcrossAluFlagsBranchesAndAddressingModes() {
        List<String> program = List.of(
                "MOV A, 15",
                "MOV B, -4",
                "ADD A, B",          // A = 11, Z=0, S=0
                "MOV C, FLAGS",      // C = 0
                "MOV [0], C",
                "SUB B, 10",         // B = -14, Z=0, S=1
                "MOV D, FLAGS",      // D = 2
                "MOV [1], D",
                "CMP A, 11",         // Z=1, S=0
                "MOV [2], FLAGS",    // 1
                "JNZ fail",
                "MOV A, -5",
                "CMP A, 5",          // -10 -> S=1, Z=0 (signed <)
                "JL lt_ok",
                "JMP fail",
                "lt_ok:",
                "JLE le_ok",
                "JMP fail",
                "le_ok:",
                "MOV A, 20",
                "CMP A, 10",         // +10 -> S=0, Z=0 (signed >)
                "JG gt_ok",
                "JMP fail",
                "gt_ok:",
                "JGE ge_ok",
                "JMP fail",
                "ge_ok:",
                "MOV B, 10",
                "MOV [B], 1234",
                "MOV [B + 3], 5678",
                "MOV A, [B + 3]",
                "ADD A, [B]",        // 6912
                "MUL A, 2",          // 13824
                "DIV A, 3",          // 4608
                "MOD A, 100",        // 8
                "SHL A, 4",          // 128
                "SHR A, 2",          // 32
                "OR A, 5",           // 37
                "XOR A, 3",          // 38
                "AND A, 0xFF",       // 38
                "NOT B",             // ~10 = -11
                "INC B",             // -10
                "DEC B",             // -11
                "MOV [4], B",
                "MOV C, PC",         // nextPc
                "MOV [5], C",
                "OUT \"VAL=\", 0",
                "OUT A",
                "HLT",
                "fail:",
                "MOV [99], -1",
                "HLT"
        );

        VirtualCpu interp = runProgram(program, 256, 1000, false);
        VirtualCpu jit = runProgram(program, 256, 1000, true);

        assertTrue(jit.isJitCompiled());
        assertEquals(VirtualCpu.State.HALTED, jit.getState());
        assertEquals(38, jit.getRegA());
        assertEquals(-11, jit.getRam()[4]);
        assertCpuParity(interp, jit, 128);
    }

    @Test
    void cpuJitMatchesInterpreterOnRecursionStackPcWriteAndGalloc() {
        List<String> program = List.of(
                "GALLOC 0, 128",
                "GALLOC 16, 128",
                "MOV [10], A",
                "MOV [A], 0xCAFE",
                "PUSH 11",
                "PUSH 22",
                "MOV A, 6",
                "CALL fact",
                "MOV [11], A",
                "POP C",
                "POP D",
                "MOV [12], C",
                "MOV [13], D",
                "MOV PC, done_label",
                "MOV [14], 999", // skipped by MOV PC
                "done_label:",
                "HLT",
                "fact:",
                "CMP A, 1",
                "JLE fact_base",
                "PUSH A",
                "DEC A",
                "CALL fact",
                "POP B",
                "MUL A, B",
                "RET",
                "fact_base:",
                "MOV A, 1",
                "RET"
        );

        VirtualCpu interp = runProgram(program, 512, 5000, false);
        VirtualCpu jit = runProgram(program, 512, 5000, true);

        assertEquals(VirtualCpu.State.HALTED, jit.getState());
        assertEquals(720, jit.getRam()[11]);
        assertEquals(22, jit.getRam()[12]);
        assertEquals(11, jit.getRam()[13]);
        assertEquals(0, jit.getRam()[14]);
        assertCpuParity(interp, jit, 256);
    }

    @Test
    void cpuJitPreservesExactExceptionMessagesAndIvtHandlers() {
        // 1. Unhandled div by zero exact message
        List<String> divZero = List.of("MOV A, 10", "MOV B, 0", "DIV A, B", "HLT");
        VirtualCpu divInterp = runProgram(divZero, 64, 50, false);
        VirtualCpu divJit = runProgram(divZero, 64, 50, true);
        assertEquals(VirtualCpu.State.ERROR, divJit.getState());
        assertTrue(divJit.getErrorMessage().contains("Exception not handled: Division by zero"));
        assertCpuParity(divInterp, divJit, 64);

        // 2. Unhandled out-of-bounds exact message
        List<String> oob = List.of("MOV A, [500]", "HLT");
        VirtualCpu oobInterp = runProgram(oob, 64, 50, false);
        VirtualCpu oobJit = runProgram(oob, 64, 50, true);
        assertEquals(VirtualCpu.State.ERROR, oobJit.getState());
        assertTrue(oobJit.getErrorMessage().contains("Exception not handled: RAM address 500 out of bounds"));
        assertCpuParity(oobInterp, oobJit, 64);

        // 3. Handled div zero, OOB, stack fault, illegal opcode, and software INT
        List<String> handled = List.of(
                "IVEC 0, h_div",
                "IVEC 2, h_oob",
                "IVEC 3, h_stk",
                "IVEC 5, h_sw",
                "MOV A, 10",
                "DIV A, 0",
                "MOV [0], A",
                "MOV A, [999]",
                "MOV [1], A",
                "POP B",
                "MOV [2], B",
                "INT 5",
                "MOV [3], A",
                "HLT",
                "h_div:",
                "MOV A, 111",
                "IRET",
                "h_oob:",
                "MOV A, 222",
                "IRET",
                "h_stk:",
                "MOV B, 333",
                "IRET",
                "h_sw:",
                "MOV A, 444",
                "IRET"
        );
        VirtualCpu hInterp = runProgram(handled, 64, 500, false);
        VirtualCpu hJit = runProgram(handled, 64, 500, true);
        assertEquals(VirtualCpu.State.HALTED, hJit.getState());
        assertEquals(111, hJit.getRam()[0]);
        assertEquals(222, hJit.getRam()[1]);
        assertEquals(333, hJit.getRam()[2]);
        assertEquals(444, hJit.getRam()[3]);
        assertCpuParity(hInterp, hJit, 16);

        // 4. Illegal instruction handled via VEC_ILLEGAL_OPCODE (1)
        Set<String> noMulIsa = Set.of("MOV", "IVEC", "IRET", "HLT");
        List<String> illegalProg = List.of(
                "IVEC 1, h_ill",
                "MOV A, 7",
                "MUL A, 3",
                "MOV [0], A",
                "HLT",
                "h_ill:",
                "MOV A, 777",
                "IRET"
        );
        VirtualCpu illInterp = runProgram(noMulIsa, illegalProg, 64, 200, false);
        VirtualCpu illJit = runProgram(noMulIsa, illegalProg, 64, 200, true);
        assertEquals(VirtualCpu.State.HALTED, illJit.getState());
        assertEquals(777, illJit.getRam()[0]);
        assertCpuParity(illInterp, illJit, 16);
    }

    @Test
    void cpuJitHandlesInterruptsSleepEmptyEofAndSexec() {
        // Keyboard IRQ + SLEEP + IRET
        List<String> irqProg = List.of(
                "IVEC 10, on_key",
                "SLEEP 2",
                "wait_loop:",
                "CMP [0], 0",
                "JZ wait_loop",
                "HLT",
                "on_key:",
                "MOV [1], A",
                "MOV [0], 1",
                "IRET"
        );
        var asm = Assembler.assemble(irqProg);
        assertTrue(asm.success());
        VirtualCpu jit = new VirtualCpu();
        jit.setJitEnabled(true);
        jit.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 64, asm.bytecode(), false, 0);
        jit.powerOn();
        VirtualCpu interp = new VirtualCpu();
        interp.setJitEnabled(false);
        interp.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 64, asm.bytecode(), false, 0);
        interp.powerOn();
        interp.tick(20);

        jit.tick(20); // executes SLEEP 2 and pauses
        assertEquals(VirtualCpu.State.RUNNING, jit.getState());
        assertEquals(interp.getSleepTicksRemaining(), jit.getSleepTicksRemaining());
        assertEquals(2, jit.getSleepTicksRemaining());
        jit.tick(20); // decrements sleepTicksRemaining -> 1
        assertEquals(1, jit.getSleepTicksRemaining());

        jit.queueKeyboardInterrupt('K');
        jit.tick(50);
        assertEquals(VirtualCpu.State.HALTED, jit.getState());
        assertEquals('K', jit.getRam()[1]);

        // Empty program terminates cleanly at EOF
        VirtualCpu emptyJit = new VirtualCpu();
        emptyJit.setJitEnabled(true);
        emptyJit.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 64, new byte[0], false, 0);
        emptyJit.powerOn();
        emptyJit.tick(10);
        assertEquals(VirtualCpu.State.HALTED, emptyJit.getState());
        assertTrue(emptyJit.getTerminalOutput().contains("[PROGRAM TERMINATED: END OF FILE]"));

        // SEXEC replaces program and recompiles JIT
        var driveProg = Assembler.assemble(List.of("MOV A, 9876", "MOV [5], A", "HLT"));
        assertTrue(driveProg.success());
        var launcher = Assembler.assemble(List.of("MOV A, 1234", "SEXEC 0", "HLT"));
        assertTrue(launcher.success());

        VirtualCpu sexecJit = new VirtualCpu();
        sexecJit.setJitEnabled(true);
        sexecJit.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 64, launcher.bytecode(), true, 1024);
        sexecJit.setDriveBinary(0, driveProg.bytecode());
        sexecJit.powerOn();
        sexecJit.tick(100);
        assertEquals(VirtualCpu.State.HALTED, sexecJit.getState(), sexecJit.getErrorMessage());
        assertEquals(9876, sexecJit.getRegA());
        assertEquals(9876, sexecJit.getRam()[5]);
        assertTrue(sexecJit.getJitCompileCount() >= 2, "SEXEC should recompile target binary");
    }

    @Test
    void cpuJitMultiChunkCrossChunkBranchesAndCallsMatchInterpreter() {
        // Build > 280 instructions spanning chunks 0 (0..127), 1 (128..255), and 2 (256+)
        List<String> lines = new ArrayList<>();
        lines.add("MOV A, 1");
        lines.add("JMP chunk2_start");
        for (int i = 0; i < 130; i++) {
            lines.add("NOP");
        }
        lines.add("chunk1_fn:");
        lines.add("ADD A, 40");
        lines.add("RET");
        for (int i = 0; i < 130; i++) {
            lines.add("NOP");
        }
        lines.add("chunk2_start:");
        lines.add("CALL chunk1_fn");
        lines.add("MUL A, 3");
        lines.add("MOV [0], A");
        lines.add("HLT");

        assertTrue(lines.size() > CpuJitCompiler.CHUNK_SIZE * 2);

        VirtualCpu interp = runProgram(lines, 64, 500, false);
        VirtualCpu jit = runProgram(lines, 64, 500, true);

        assertEquals(VirtualCpu.State.HALTED, jit.getState());
        assertEquals(123, jit.getRam()[0]);
        assertCpuParity(interp, jit, 16);
    }

    @Test
    void gpuJitPipelineAndTraceCacheProduceBitExactOutputVsFallback() {
        VirtualGpu gpuJit = new VirtualGpu();
        gpuJit.configureHardware(true, "Flipper-JIT", VirtualGpu.RAM_MODULE_BYTES, 4);
        gpuJit.setJitEnabled(true);
        gpuJit.powerOn();

        VirtualGpu gpuInterp = new VirtualGpu();
        gpuInterp.configureHardware(true, "Flipper-Interp", VirtualGpu.RAM_MODULE_BYTES, 4);
        gpuInterp.setJitEnabled(false);
        gpuInterp.powerOn();

        int[] sprite = new int[]{
                0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF,
                0x80112233, 0x00000000, 0xFF445566, 0xFF778899
        };
        gpuJit.uploadVram(0, sprite, 0, sprite.length);
        gpuInterp.uploadVram(0, sprite, 0, sprite.length);

        assertTrue(gpuJit.isJitActive());
        assertFalse(gpuInterp.isJitActive());

        // Submit 6 identical multi-command frames to trigger dynamic trace JIT (threshold = 2)
        for (int frame = 0; frame < 6; frame++) {
            for (VirtualGpu gpu : List.of(gpuJit, gpuInterp)) {
                gpu.submitClear(0xFF102030);
                gpu.submitFillRect(20, 30, 80, 40, 0xFFAA5500);
                gpu.submitDrawLine(0, 0, 200, 120, 0xFF00FFAA);
                gpu.submitDrawCircle(300, 200, 25, 0xFFFF2288, true);
                gpu.submitDrawCircle(360, 200, 20, 0xFF22FFFF, false);
                gpu.submitFillTriangle(400, 50, 460, 140, 350, 130, 0xFF99CC00);
                gpu.submitBlitFromVram(500, 100, 0, 4, 2, true);
                gpu.submitSetPixel(600, 300, 0xFFABCDEF);
                gpu.submitSwap();
            }
        }

        gpuJit.flush();
        gpuInterp.flush();

        assertTrue(gpuJit.getJitTracesCompiled() > 0, "Repeated frame sequence should compile a trace");
        assertTrue(gpuJit.getJitTraceHits() > 0, "Repeated frames should hit compiled trace cache");
        assertArrayEquals(gpuInterp.snapshotFrontBuffer(), gpuJit.snapshotFrontBuffer(),
                "GPU JIT output must match interpreter pixel-for-pixel");
    }

    @Test
    void gameCubeClockSliceTargetsExecuteCleanlyWithinBudget() {
        assertEquals(485_000, ComputerThreadManager.CYCLES_PER_SLICE);
        assertEquals(32_400, VirtualGpu.BASE_OPS_PER_SLICE);

        // Warm up CPU JIT and measure a full 485,000-cycle slice
        List<String> tightLoop = List.of(
                "MOV A, 0",
                "loop:",
                "INC A",
                "JMP loop"
        );
        var asm = Assembler.assemble(tightLoop);
        assertTrue(asm.success());
        VirtualCpu cpu = new VirtualCpu();
        cpu.setJitEnabled(true);
        cpu.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 256, asm.bytecode(), false, 0);
        cpu.powerOn();

        cpu.tick(ComputerThreadManager.CYCLES_PER_SLICE); // warm compile + OSR
        int before = cpu.getRegA();
        long t0 = System.nanoTime();
        cpu.tick(ComputerThreadManager.CYCLES_PER_SLICE);
        long cpuElapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        int delta = cpu.getRegA() - before;

        assertTrue(delta > 100_000, "485k JIT slice should execute well over 100k loop iterations, got " + delta);
        assertTrue(cpuElapsedMs < 15, "485k JIT slice should run in a few ms, took " + cpuElapsedMs + " ms");

        // Verify GPU processes a full 32,400-op GameCube batch cleanly
        VirtualGpu gpu = new VirtualGpu();
        gpu.configureHardware(true, "Flipper-Bench", VirtualGpu.RAM_MODULE_BYTES, 4);
        gpu.powerOn();
        int ops = VirtualGpu.BASE_OPS_PER_SLICE;
        for (int i = 0; i < ops - 1; i++) {
            gpu.submitSetPixel(i % VirtualGpu.FB_WIDTH, (i / VirtualGpu.FB_WIDTH) % VirtualGpu.FB_HEIGHT, 0xFF00AAFF);
        }
        gpu.submitSwap();
        long g0 = System.nanoTime();
        gpu.flush();
        long gpuElapsedMs = (System.nanoTime() - g0) / 1_000_000L;

        assertEquals(ops, gpu.getCommandsExecuted());
        assertTrue(gpuElapsedMs < 50, "32.4k GPU batch should complete cleanly, took " + gpuElapsedMs + " ms");
    }

    @Test
    void hugeMillionInstructionProgramCompilesLazilyAndEvictsChunksSafely() {
        int totalInstructions = 1_300_000; // Matches 10MB+ real-world binaries (> 10,150 chunks)
        long[] code = new long[totalInstructions];
        List<BinaryBytecode.ExtOperand> ext = new ArrayList<>();
        long nop = BinaryBytecode.encodeInstruction(BinaryBytecode.OP_NOP, List.of(), 0, ext);
        Arrays.fill(code, nop);

        int subPc = 650_000;
        int farPc = 1_299_000;

        // Chunk 0: MOV A, 10; JMP farPc
        code[0] = BinaryBytecode.encodeInstruction(BinaryBytecode.OP_MOV, List.of(
                new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_REG, BinaryBytecode.REG_A, 0),
                new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_IMM, 0, 10)
        ), 0, ext);
        code[1] = BinaryBytecode.encodeInstruction(BinaryBytecode.OP_JMP, List.of(
                new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_IMM, 0, farPc)
        ), 0, ext);

        // Middle chunk: ADD A, 5; RET
        code[subPc] = BinaryBytecode.encodeInstruction(BinaryBytecode.OP_ADD, List.of(
                new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_REG, BinaryBytecode.REG_A, 0),
                new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_IMM, 0, 5)
        ), 0, ext);
        code[subPc + 1] = BinaryBytecode.encodeInstruction(BinaryBytecode.OP_RET, List.of(), 0, ext);

        // Far chunk: ADD A, 32; CALL subPc; MUL A, 2; MOV [0], A; HLT
        code[farPc] = BinaryBytecode.encodeInstruction(BinaryBytecode.OP_ADD, List.of(
                new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_REG, BinaryBytecode.REG_A, 0),
                new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_IMM, 0, 32)
        ), 0, ext);
        code[farPc + 1] = BinaryBytecode.encodeInstruction(BinaryBytecode.OP_CALL, List.of(
                new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_IMM, 0, subPc)
        ), 0, ext);
        code[farPc + 2] = BinaryBytecode.encodeInstruction(BinaryBytecode.OP_MUL, List.of(
                new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_REG, BinaryBytecode.REG_A, 0),
                new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_IMM, 0, 2)
        ), 0, ext);
        code[farPc + 3] = BinaryBytecode.encodeInstruction(BinaryBytecode.OP_MOV, List.of(
                new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_RAM_IMM, 0, 0),
                new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_REG, BinaryBytecode.REG_A, 0)
        ), 0, ext);
        code[farPc + 4] = BinaryBytecode.encodeInstruction(BinaryBytecode.OP_HLT, List.of(), 0, ext);

        byte[] scbc = BinaryBytecode.encodeScbc(new BinaryBytecode.ProgramImage(
                code, new String[0], ext.toArray(new BinaryBytecode.ExtOperand[0]), List.of()
        ));

        VirtualCpu jit = new VirtualCpu();
        jit.setJitEnabled(true);
        jit.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 256, scbc, false, 0);
        jit.powerOn();

        VirtualCpu interp = new VirtualCpu();
        interp.setJitEnabled(false);
        interp.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 256, scbc, false, 0);
        interp.powerOn();

        long t0 = System.nanoTime();
        for (int s = 0; s < 5 && jit.getState() == VirtualCpu.State.RUNNING; s++) {
            jit.tick(1000);
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        interp.tick(1000);

        assertEquals(VirtualCpu.State.HALTED, jit.getState(), jit.getErrorMessage());
        assertEquals(94, jit.getRegA());
        assertEquals(94, jit.getRam()[0]);
        assertCpuParity(interp, jit, 16);
        assertTrue(jit.jitProgram.getCompiledChunkCount() <= 10,
                "Only reached chunks should compile, got " + jit.jitProgram.getCompiledChunkCount());
        assertTrue(elapsedMs < 1000, "Lazy 1.3M-instruction JIT should initialize & run cleanly, took " + elapsedMs + " ms");

        // Exercise cache eviction across > maxResidentChunks distinct chunks and re-enter evicted chunk 0
        int residentCap = 16;
        int totalSweepChunks = 24;
        int sweepCount = totalSweepChunks * CpuJitCompiler.CHUNK_SIZE;
        long[] sweepCode = new long[sweepCount];
        long incA = BinaryBytecode.encodeInstruction(BinaryBytecode.OP_INC, List.of(
                new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_REG, BinaryBytecode.REG_A, 0)
        ), 0, ext);
        Arrays.fill(sweepCode, incA);
        // Chunk 0 header:
        //   0: CMP B, 0
        //   1: JZ 4
        //   2: INC A
        //   3: HLT
        //   4: MOV B, 1
        sweepCode[0] = BinaryBytecode.encodeInstruction(BinaryBytecode.OP_CMP, List.of(
                new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_REG, BinaryBytecode.REG_B, 0),
                new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_IMM, 0, 0)
        ), 0, ext);
        sweepCode[1] = BinaryBytecode.encodeInstruction(BinaryBytecode.OP_JZ, List.of(
                new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_IMM, 0, 4)
        ), 0, ext);
        sweepCode[2] = incA;
        sweepCode[3] = BinaryBytecode.encodeInstruction(BinaryBytecode.OP_HLT, List.of(), 0, ext);
        sweepCode[4] = BinaryBytecode.encodeInstruction(BinaryBytecode.OP_MOV, List.of(
                new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_REG, BinaryBytecode.REG_B, 0),
                new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_IMM, 0, 1)
        ), 0, ext);
        // End of sweep jumps back to evicted chunk 0 (pc = 0)
        sweepCode[sweepCount - 1] = BinaryBytecode.encodeInstruction(BinaryBytecode.OP_JMP, List.of(
                new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_IMM, 0, 0)
        ), 0, ext);

        byte[] sweepScbc = BinaryBytecode.encodeScbc(new BinaryBytecode.ProgramImage(
                sweepCode, new String[0], ext.toArray(new BinaryBytecode.ExtOperand[0]), List.of()
        ));
        VirtualCpu sweepCpu = new VirtualCpu();
        sweepCpu.setJitEnabled(true);
        sweepCpu.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 256, sweepScbc, false, 0);
        sweepCpu.powerOn();
        boolean[] isaMask = new boolean[BinaryBytecode.MAX_OPCODE_ID + 1];
        Arrays.fill(isaMask, true);
        sweepCpu.jitProgram = CpuJitCompiler.compileForTest(
                sweepCode, ext.toArray(new BinaryBytecode.ExtOperand[0]), isaMask, residentCap
        );
        for (int s = 0; s < 10 && sweepCpu.getState() == VirtualCpu.State.RUNNING; s++) {
            sweepCpu.tick(ComputerThreadManager.CYCLES_PER_SLICE, 200_000_000L);
        }
        assertEquals(VirtualCpu.State.HALTED, sweepCpu.getState(), sweepCpu.getErrorMessage());
        // Instructions 5 .. (sweepCount - 2) plus re-entry INC A at pc=2 => sweepCount - 5 total increments
        assertEquals(sweepCount - 5, sweepCpu.getRegA());
        assertEquals(1, sweepCpu.getRegB());
        assertEquals(totalSweepChunks + 1, sweepCpu.jitProgram.getCompiledChunkCount());
    }

    @Test
    void boundedStacksRaiseStackFaultInsteadOfOutOfMemory() {
        // Unhandled infinite CALL recursion
        List<String> recCall = List.of("loop:", "CALL loop");
        VirtualCpu callCpu = runProgram(recCall, 64, 200_000, true);
        assertEquals(VirtualCpu.State.ERROR, callCpu.getState());
        assertTrue(callCpu.getErrorMessage().contains("Call stack overflow"), callCpu.getErrorMessage());

        // Handled CALL overflow via VEC_STACK_FAULT (3)
        List<String> handledCall = List.of(
                "IVEC 3, on_fault",
                "loop:",
                "CALL loop",
                "HLT",
                "on_fault:",
                "MOV [0], 777",
                "HLT"
        );
        VirtualCpu handledCpu = runProgram(handledCall, 64, 200_000, true);
        assertEquals(VirtualCpu.State.HALTED, handledCpu.getState(), handledCpu.getErrorMessage());
        assertEquals(777, handledCpu.getRam()[0]);

        // Unhandled infinite PUSH loop
        List<String> pushLoop = List.of("loop:", "PUSH 42", "JMP loop");
        VirtualCpu pushCpu = runProgram(pushLoop, 64, 200_000, true);
        assertEquals(VirtualCpu.State.ERROR, pushCpu.getState());
        assertTrue(pushCpu.getErrorMessage().contains("Data stack overflow"), pushCpu.getErrorMessage());

        // Recursive interrupt loop hits bounded interrupt stack limit
        List<String> intLoop = List.of("IVEC 5, isr", "INT 5", "HLT", "isr:", "INT 5", "IRET");
        VirtualCpu intCpu = runProgram(intLoop, 64, 20_000, true);
        assertEquals(VirtualCpu.State.ERROR, intCpu.getState());
        assertTrue(intCpu.getErrorMessage().contains("Interrupt stack overflow"), intCpu.getErrorMessage());
    }

    @Test
    void boundedAudioQueueAndLineBufferPreventUnboundedGrowth() {
        List<String> audioProg = List.of(
                "ATONE 440, 1000000",
                "ABUFW 0, 1000000",
                "ASTAT A",
                "MOV [0], A",
                "HLT"
        );
        VirtualCpu audioCpu = runProgram(audioProg, 256, 100, true);
        assertEquals(VirtualCpu.State.HALTED, audioCpu.getState(), audioCpu.getErrorMessage());
        assertTrue(audioCpu.getRam()[0] <= 262_144, "Audio queue must remain strictly bounded");
        byte[] drained = audioCpu.pollAudioSamples();
        assertNotNull(drained);
        assertEquals(audioCpu.getRam()[0], drained.length);
        assertEquals(0, audioCpu.jitAStat());

        List<String> outProg = List.of(
                "MOV A, 1500",
                "loop:",
                "OUT \"AAAA\", 0",
                "DEC A",
                "JNZ loop",
                "HLT"
        );
        VirtualCpu outCpu = runProgram(outProg, 64, 20_000, true);
        assertEquals(VirtualCpu.State.HALTED, outCpu.getState(), outCpu.getErrorMessage());
        assertFalse(outCpu.getTerminalOutput().isEmpty());
        assertTrue(outCpu.getTerminalOutput().size() <= 61);
    }
}

