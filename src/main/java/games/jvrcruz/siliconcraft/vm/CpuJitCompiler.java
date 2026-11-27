package games.jvrcruz.siliconcraft.vm;

import games.jvrcruz.siliconcraft.Siliconcraft;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ASM-based JIT compiler translating SiliconCraft 64-bit {@code SCBC} bytecode directly into
 * Java 21 JVM bytecode using {@link MethodHandles.Lookup#defineHiddenClass}.
 *
 * <p>Programs are split into fixed-size instruction chunks (128 instructions per method) and
 * compiled lazily on first execution into small hidden classes. This avoids huge constant pools,
 * 64 KB method bytecode limits, and OOM spikes when loading multi-megabyte binaries while
 * preserving direct JVM branches for intra-chunk loops, cached registers/flags in JVM local
 * variables, zero-boxing stack ops, and full exception/IVT parity.
 */
public final class CpuJitCompiler implements Opcodes {

    static final int CHUNK_SHIFT = 7;
    static final int CHUNK_SIZE = 1 << CHUNK_SHIFT; // 128 instructions per chunk method
    static final int MAX_RESIDENT_CHUNKS = 2048;    // Cap live hidden classes (~262k active instructions)

    private static final String CPU_INTERNAL = "games/jvrcruz/siliconcraft/vm/VirtualCpu";
    private static final String STATE_DESC = "Lgames/jvrcruz/siliconcraft/vm/VirtualCpu$State;";
    private static final String STATE_INTERNAL = "games/jvrcruz/siliconcraft/vm/VirtualCpu$State";
    private static final String PROGRAM_INTERNAL = "games/jvrcruz/siliconcraft/vm/CpuJitCompiler$CompiledCpuProgram";
    private static final String CHUNK_INTERNAL = "games/jvrcruz/siliconcraft/vm/CpuJitCompiler$CompiledChunk";
    private static final AtomicInteger CLASS_COUNTER = new AtomicInteger(1);

    // Local variable indices for runChunk(VirtualCpu cpu, int pc, int executed, int maxCycles, long startNanos)
    private static final int L_CPU = 0;
    private static final int L_PC = 1;
    private static final int L_EXECUTED = 2;
    private static final int L_MAX_CYCLES = 3;
    private static final int L_START_NANOS = 4; // long occupies 4 and 5
    private static final int L_REGA = 6;
    private static final int L_REGB = 7;
    private static final int L_REGC = 8;
    private static final int L_REGD = 9;
    private static final int L_FLAGZ = 10;
    private static final int L_FLAGS = 11;
    private static final int L_CYCLES = 12;
    private static final int L_RAM = 13;
    private static final int L_CUR_PC = 14;
    private static final int L_TMP0 = 15;
    private static final int L_TMP1 = 16;
    private static final int L_TMP2 = 17;
    private static final int L_TMP3 = 18;
    private static final int L_TMP4 = 19;

    private CpuJitCompiler() {}

    public interface CompiledCpuProgram {
        int execute(VirtualCpu cpu, int maxCycles, long startNanos);

        default int getCompiledChunkCount() {
            return 0;
        }
    }

    public interface CompiledChunk {
        long run(VirtualCpu cpu, int pc, int executed, int maxCycles, long startNanos);
    }

    public static CompiledCpuProgram compile(
            long[] code,
            BinaryBytecode.ExtOperand[] ext,
            boolean[] supportedOpcodeIds
    ) {
        return new LazyCompiledCpuProgram(code, ext, supportedOpcodeIds, MAX_RESIDENT_CHUNKS);
    }

    static CompiledCpuProgram compileForTest(
            long[] code,
            BinaryBytecode.ExtOperand[] ext,
            boolean[] supportedOpcodeIds,
            int maxResidentChunks
    ) {
        return new LazyCompiledCpuProgram(code, ext, supportedOpcodeIds, Math.max(1, maxResidentChunks));
    }

    private static final class LazyCompiledCpuProgram implements CompiledCpuProgram {
        private final long[] code;
        private final int codeLen;
        private final BinaryBytecode.ExtOperand[] ext;
        private final boolean[] supportedOpcodeIds;
        private final int numChunks;
        private final int maxResidentChunks;
        private final CompiledChunk[] chunks;
        private final boolean[] chunkFailed;
        private final boolean[] chunkRecentlyUsed;
        private final int[] residentRing;
        private int residentCount = 0;
        private int ringCursor = 0;
        private int compiledChunkCount = 0;

        LazyCompiledCpuProgram(
                long[] code,
                BinaryBytecode.ExtOperand[] ext,
                boolean[] supportedOpcodeIds,
                int maxResidentChunks
        ) {
            this.code = code == null ? new long[0] : code;
            this.codeLen = this.code.length;
            this.ext = ext == null ? new BinaryBytecode.ExtOperand[0] : ext;
            this.supportedOpcodeIds = supportedOpcodeIds == null ? new boolean[0] : supportedOpcodeIds;
            this.numChunks = this.codeLen == 0 ? 0 : ((this.codeLen + CHUNK_SIZE - 1) >>> CHUNK_SHIFT);
            this.maxResidentChunks = Math.max(1, maxResidentChunks);
            this.chunks = new CompiledChunk[this.numChunks];
            this.chunkFailed = new boolean[this.numChunks];
            if (this.numChunks > this.maxResidentChunks) {
                this.chunkRecentlyUsed = new boolean[this.numChunks];
                this.residentRing = new int[this.maxResidentChunks];
            } else {
                this.chunkRecentlyUsed = null;
                this.residentRing = null;
            }
        }

        @Override
        public int getCompiledChunkCount() {
            return compiledChunkCount;
        }

        @Override
        public int execute(VirtualCpu cpu, int maxCycles, long startNanos) {
            if (codeLen == 0) {
                cpu.jitTerminateEof();
                return 0;
            }

            int pc = cpu.pc;
            int executed = 0;
            cpu.jitSliceYielded = false;

            while (cpu.jitProgram == this
                    && cpu.state == VirtualCpu.State.RUNNING
                    && executed < maxCycles
                    && !Thread.currentThread().isInterrupted()) {
                if (cpu.pendingAssemblyError != null) {
                    cpu.jitHandlePendingAssemblyError();
                    break;
                }
                if (pc < 0 || pc >= codeLen) {
                    cpu.jitTerminateEof();
                    break;
                }

                int chunkIdx = pc >>> CHUNK_SHIFT;
                if (chunkIdx < 0 || chunkIdx >= numChunks) {
                    cpu.jitTerminateEof();
                    break;
                }

                if (chunkFailed[chunkIdx]) {
                    if (executed > 0 && (executed & 63) == 0 && cpu.jitPeriodicCheck(cpu.cycles, startNanos)) {
                        break;
                    }
                    cpu.executeNextInstruction();
                    executed++;
                    pc = cpu.pc;
                    if (cpu.sleepTicksRemaining > 0) {
                        break;
                    }
                    continue;
                }

                CompiledChunk chunk = chunks[chunkIdx];
                if (chunk == null) {
                    if (executed > 0 && (System.nanoTime() - startNanos > cpu.getActiveSliceBudgetNanos())) {
                        cpu.jitSliceYielded = true;
                        break;
                    }
                    chunk = compileAndCacheChunk(chunkIdx);
                    if (chunk == null) {
                        chunkFailed[chunkIdx] = true;
                        continue;
                    }
                } else if (chunkRecentlyUsed != null) {
                    chunkRecentlyUsed[chunkIdx] = true;
                }

                long packed = chunk.run(cpu, pc, executed, maxCycles, startNanos);
                executed = (int) (packed >>> 32);
                pc = (int) packed;
                if (cpu.jitSliceYielded || cpu.sleepTicksRemaining > 0) {
                    break;
                }
            }

            return executed;
        }

        private CompiledChunk compileAndCacheChunk(int chunkIdx) {
            CompiledChunk compiled;
            try {
                int start = chunkIdx << CHUNK_SHIFT;
                int end = Math.min(codeLen, start + CHUNK_SIZE);
                compiled = compileChunk(start, end, code, codeLen, ext, supportedOpcodeIds);
            } catch (LinkageError | RuntimeException e) {
                Siliconcraft.LOGGER.warn("CPU JIT chunk {} compilation failed, falling back to interpreter", chunkIdx, e);
                return null;
            }

            if (residentRing != null) {
                if (residentCount < maxResidentChunks) {
                    residentRing[residentCount++] = chunkIdx;
                } else {
                    while (true) {
                        int candidate = residentRing[ringCursor];
                        if (chunkRecentlyUsed[candidate]) {
                            chunkRecentlyUsed[candidate] = false;
                            ringCursor = (ringCursor + 1) % maxResidentChunks;
                        } else {
                            chunks[candidate] = null;
                            residentRing[ringCursor] = chunkIdx;
                            ringCursor = (ringCursor + 1) % maxResidentChunks;
                            break;
                        }
                    }
                }
                chunkRecentlyUsed[chunkIdx] = true;
            }

            chunks[chunkIdx] = compiled;
            compiledChunkCount++;
            return compiled;
        }
    }

    private static CompiledChunk compileChunk(
            int chunkStart,
            int chunkEnd,
            long[] code,
            int codeLen,
            BinaryBytecode.ExtOperand[] ext,
            boolean[] supportedOpcodeIds
    ) {
        String className = "games/jvrcruz/siliconcraft/vm/CpuJitChunk_" + CLASS_COUNTER.getAndIncrement();
        ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL, className, null, "java/lang/Object", new String[]{CHUNK_INTERNAL});

        emitDefaultConstructor(cw);

        // Instance bridge run(VirtualCpu, int pc, int executed, int maxCycles, long startNanos) -> runChunk(...)
        MethodVisitor bridge = cw.visitMethod(ACC_PUBLIC, "run", "(L" + CPU_INTERNAL + ";IIIJ)J", null, null);
        bridge.visitCode();
        bridge.visitVarInsn(ALOAD, 1);
        bridge.visitVarInsn(ILOAD, 2);
        bridge.visitVarInsn(ILOAD, 3);
        bridge.visitVarInsn(ILOAD, 4);
        bridge.visitVarInsn(LLOAD, 5);
        bridge.visitMethodInsn(INVOKESTATIC, className, "runChunk", "(L" + CPU_INTERNAL + ";IIIJ)J", false);
        bridge.visitInsn(LRETURN);
        bridge.visitMaxs(0, 0);
        bridge.visitEnd();

        emitChunkMethod(cw, chunkStart, chunkEnd, code, codeLen, ext, supportedOpcodeIds);

        cw.visitEnd();
        byte[] bytes = cw.toByteArray();
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup().defineHiddenClass(
                    bytes, true, MethodHandles.Lookup.ClassOption.NESTMATE
            );
            return (CompiledChunk) lookup.lookupClass().getConstructor().newInstance();
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to define CPU JIT chunk class", t);
        }
    }

    private static void emitDefaultConstructor(ClassWriter cw) {
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }

    private static void emitChunkMethod(
            ClassWriter cw,
            int chunkStart,
            int chunkEnd,
            long[] code,
            int codeLen,
            BinaryBytecode.ExtOperand[] ext,
            boolean[] supportedOpcodeIds
    ) {
        int count = chunkEnd - chunkStart;
        MethodVisitor mv = cw.visitMethod(
                ACC_PRIVATE | ACC_STATIC,
                "runChunk",
                "(L" + CPU_INTERNAL + ";IIIJ)J",
                null,
                null
        );
        mv.visitCode();

        // Initialize cached locals BEFORE tryStart so exception handler always has defined locals
        emitReloadLocals(mv);
        mv.visitVarInsn(ILOAD, L_PC);
        mv.visitVarInsn(ISTORE, L_CUR_PC);

        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label catchHandler = new Label();
        mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/Exception");

        mv.visitLabel(tryStart);

        Label[] instLabels = new Label[count];
        for (int k = 0; k < count; k++) {
            instLabels[k] = new Label();
        }
        Label exitOutOfChunk = new Label();

        if (count == 1) {
            mv.visitVarInsn(ILOAD, L_PC);
            pushConst(mv, chunkStart);
            mv.visitJumpInsn(IF_ICMPNE, exitOutOfChunk);
        } else {
            mv.visitVarInsn(ILOAD, L_PC);
            if (chunkStart != 0) {
                pushConst(mv, chunkStart);
                mv.visitInsn(ISUB);
            }
            mv.visitTableSwitchInsn(0, count - 1, exitOutOfChunk, instLabels);
        }

        for (int k = 0; k < count; k++) {
            int i = chunkStart + k;
            long word = code[i];
            int opcodeId = BinaryBytecode.extractOpcode(word);
            int nextPc = i + 1;

            mv.visitLabel(instLabels[k]);

            // 1. Cycle budget check BEFORE executing instruction
            Label budgetOk = new Label();
            mv.visitVarInsn(ILOAD, L_EXECUTED);
            mv.visitVarInsn(ILOAD, L_MAX_CYCLES);
            mv.visitJumpInsn(IF_ICMPLT, budgetOk);
            emitSyncLocals(mv, i);
            emitReturnPackedConstPc(mv, i);
            mv.visitLabel(budgetOk);

            // 2. Periodic check every 64 cycles (after cycle 0): timer trigger, thread interrupt, wall-clock cap
            Label afterPeriodic = new Label();
            mv.visitVarInsn(ILOAD, L_EXECUTED);
            mv.visitJumpInsn(IFEQ, afterPeriodic);
            mv.visitVarInsn(ILOAD, L_EXECUTED);
            mv.visitIntInsn(BIPUSH, 63);
            mv.visitInsn(IAND);
            mv.visitJumpInsn(IFNE, afterPeriodic);

            mv.visitVarInsn(ALOAD, L_CPU);
            mv.visitVarInsn(ILOAD, L_CYCLES);
            mv.visitVarInsn(LLOAD, L_START_NANOS);
            mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitPeriodicCheck", "(IJ)Z", false);
            mv.visitJumpInsn(IFEQ, afterPeriodic);
            emitSyncLocals(mv, i);
            emitReturnPackedConstPc(mv, i);
            mv.visitLabel(afterPeriodic);

            // 3. Interrupt check at instruction boundary
            Label afterIrq = new Label();
            mv.visitVarInsn(ALOAD, L_CPU);
            mv.visitFieldInsn(GETFIELD, CPU_INTERNAL, "interruptQueueNonEmpty", "Z");
            mv.visitJumpInsn(IFEQ, afterIrq);
            mv.visitVarInsn(ALOAD, L_CPU);
            mv.visitFieldInsn(GETFIELD, CPU_INTERNAL, "interruptsEnabled", "Z");
            mv.visitJumpInsn(IFEQ, afterIrq);
            mv.visitVarInsn(ALOAD, L_CPU);
            mv.visitFieldInsn(GETFIELD, CPU_INTERNAL, "inInterruptHandler", "Z");
            mv.visitJumpInsn(IFNE, afterIrq);

            emitSyncLocals(mv, i);
            mv.visitVarInsn(ALOAD, L_CPU);
            mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitCheckAndDispatchInterrupt", "()Z", false);
            mv.visitJumpInsn(IFEQ, afterIrq);
            mv.visitVarInsn(ALOAD, L_CPU);
            mv.visitFieldInsn(GETFIELD, CPU_INTERNAL, "pc", "I");
            mv.visitVarInsn(ISTORE, L_PC);
            emitReturnPackedLocalPc(mv);
            mv.visitLabel(afterIrq);

            // 4. Record curPc and advance cycle counters (matching VirtualCpu line 617-619)
            pushConst(mv, i);
            mv.visitVarInsn(ISTORE, L_CUR_PC);
            mv.visitIincInsn(L_CYCLES, 1);
            mv.visitIincInsn(L_EXECUTED, 1);

            // 5. NOP is always permitted even if omitted from ISA
            if (opcodeId == BinaryBytecode.OP_NOP) {
                continue;
            }

            // 6. Supported ISA validation baked in at compile time
            if (opcodeId <= 0 || opcodeId >= supportedOpcodeIds.length || !supportedOpcodeIds[opcodeId]) {
                emitSyncLocals(mv, nextPc);
                mv.visitVarInsn(ALOAD, L_CPU);
                pushConst(mv, i);
                mv.visitLdcInsn(word);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitTriggerIllegalOpcode", "(IJ)V", false);
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitFieldInsn(GETFIELD, CPU_INTERNAL, "pc", "I");
                mv.visitVarInsn(ISTORE, L_PC);
                emitReturnPackedLocalPc(mv);
                continue;
            }

            // 7. Emit instruction lowering
            emitInstruction(mv, i, nextPc, word, chunkStart, chunkEnd, codeLen, ext, instLabels);
        }

        // Fallthrough past last instruction in chunk
        mv.visitLabel(exitOutOfChunk);
        emitSyncLocals(mv, chunkEnd);
        emitReturnPackedConstPc(mv, chunkEnd);

        mv.visitLabel(tryEnd);

        // Exception handler: exception reference on top of stack
        mv.visitLabel(catchHandler);
        mv.visitVarInsn(ASTORE, L_TMP4);
        mv.visitVarInsn(ILOAD, L_CUR_PC);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IADD);
        mv.visitVarInsn(ISTORE, L_PC);
        emitSyncLocalsWithPcLocal(mv);
        mv.visitVarInsn(ALOAD, L_CPU);
        mv.visitVarInsn(ALOAD, L_TMP4);
        mv.visitVarInsn(ILOAD, L_CUR_PC);
        mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitHandleException", "(Ljava/lang/Exception;I)V", false);
        mv.visitVarInsn(ALOAD, L_CPU);
        mv.visitFieldInsn(GETFIELD, CPU_INTERNAL, "pc", "I");
        mv.visitVarInsn(ISTORE, L_PC);
        emitReturnPackedLocalPc(mv);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitInstruction(
            MethodVisitor mv,
            int curPc,
            int nextPc,
            long word,
            int chunkStart,
            int chunkEnd,
            int codeLen,
            BinaryBytecode.ExtOperand[] ext,
            Label[] instLabels
    ) {
        int opcodeId = BinaryBytecode.extractOpcode(word);
        int opCount = BinaryBytecode.extractOpCount(word);

        switch (opcodeId) {
            case BinaryBytecode.OP_MOV -> {
                if (opCount != 2) {
                    emitThrowIllegalArg(mv, "MOV requires dest, src");
                    return;
                }
                emitReadOperand(mv, word, 1, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitWriteOperandFromLocal(mv, word, 0, L_TMP0, nextPc, ext, false);
            }
            case BinaryBytecode.OP_ADD -> {
                if (opCount != 2) {
                    emitThrowIllegalArg(mv, "ADD requires dest, src");
                    return;
                }
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitReadOperand(mv, word, 1, nextPc, ext);
                mv.visitVarInsn(ILOAD, L_TMP0);
                mv.visitInsn(IADD);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitWriteOperandFromLocal(mv, word, 0, L_TMP0, nextPc, ext, true);
            }
            case BinaryBytecode.OP_SUB -> {
                if (opCount != 2) {
                    emitThrowIllegalArg(mv, "SUB requires dest, src");
                    return;
                }
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitReadOperand(mv, word, 1, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP1);
                mv.visitVarInsn(ILOAD, L_TMP0);
                mv.visitVarInsn(ILOAD, L_TMP1);
                mv.visitInsn(ISUB);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitWriteOperandFromLocal(mv, word, 0, L_TMP0, nextPc, ext, true);
            }
            case BinaryBytecode.OP_MUL -> {
                if (opCount != 2) {
                    emitThrowIllegalArg(mv, "MUL requires dest, src");
                    return;
                }
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitReadOperand(mv, word, 1, nextPc, ext);
                mv.visitVarInsn(ILOAD, L_TMP0);
                mv.visitInsn(IMUL);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitWriteOperandFromLocal(mv, word, 0, L_TMP0, nextPc, ext, true);
            }
            case BinaryBytecode.OP_DIV -> {
                if (opCount != 2) {
                    emitThrowIllegalArg(mv, "DIV requires dest, src");
                    return;
                }
                emitReadOperand(mv, word, 1, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP1);
                Label nonZero = new Label();
                mv.visitVarInsn(ILOAD, L_TMP1);
                mv.visitJumpInsn(IFNE, nonZero);
                mv.visitMethodInsn(INVOKESTATIC, CPU_INTERNAL, "jitThrowDivZero", "()V", false);
                mv.visitLabel(nonZero);
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitVarInsn(ILOAD, L_TMP1);
                mv.visitInsn(IDIV);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitWriteOperandFromLocal(mv, word, 0, L_TMP0, nextPc, ext, true);
            }
            case BinaryBytecode.OP_MOD -> {
                if (opCount != 2) {
                    emitThrowIllegalArg(mv, "MOD requires dest, src");
                    return;
                }
                emitReadOperand(mv, word, 1, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP1);
                Label nonZero = new Label();
                mv.visitVarInsn(ILOAD, L_TMP1);
                mv.visitJumpInsn(IFNE, nonZero);
                mv.visitMethodInsn(INVOKESTATIC, CPU_INTERNAL, "jitThrowModZero", "()V", false);
                mv.visitLabel(nonZero);
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitVarInsn(ILOAD, L_TMP1);
                mv.visitInsn(IREM);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitWriteOperandFromLocal(mv, word, 0, L_TMP0, nextPc, ext, true);
            }
            case BinaryBytecode.OP_INC -> {
                if (opCount != 1) {
                    emitThrowIllegalArg(mv, "INC requires dest");
                    return;
                }
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitInsn(ICONST_1);
                mv.visitInsn(IADD);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitWriteOperandFromLocal(mv, word, 0, L_TMP0, nextPc, ext, true);
            }
            case BinaryBytecode.OP_DEC -> {
                if (opCount != 1) {
                    emitThrowIllegalArg(mv, "DEC requires dest");
                    return;
                }
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitInsn(ICONST_1);
                mv.visitInsn(ISUB);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitWriteOperandFromLocal(mv, word, 0, L_TMP0, nextPc, ext, true);
            }
            case BinaryBytecode.OP_CMP -> {
                if (opCount != 2) {
                    emitThrowIllegalArg(mv, "CMP requires reg, val");
                    return;
                }
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitReadOperand(mv, word, 1, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP1);
                mv.visitVarInsn(ILOAD, L_TMP0);
                mv.visitVarInsn(ILOAD, L_TMP1);
                mv.visitInsn(ISUB);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitUpdateFlagsFromLocal(mv, L_TMP0);
            }
            case BinaryBytecode.OP_AND -> {
                if (opCount != 2) {
                    emitThrowIllegalArg(mv, "AND requires dest, src");
                    return;
                }
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitReadOperand(mv, word, 1, nextPc, ext);
                mv.visitVarInsn(ILOAD, L_TMP0);
                mv.visitInsn(IAND);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitWriteOperandFromLocal(mv, word, 0, L_TMP0, nextPc, ext, true);
            }
            case BinaryBytecode.OP_OR -> {
                if (opCount != 2) {
                    emitThrowIllegalArg(mv, "OR requires dest, src");
                    return;
                }
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitReadOperand(mv, word, 1, nextPc, ext);
                mv.visitVarInsn(ILOAD, L_TMP0);
                mv.visitInsn(IOR);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitWriteOperandFromLocal(mv, word, 0, L_TMP0, nextPc, ext, true);
            }
            case BinaryBytecode.OP_XOR -> {
                if (opCount != 2) {
                    emitThrowIllegalArg(mv, "XOR requires dest, src");
                    return;
                }
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitReadOperand(mv, word, 1, nextPc, ext);
                mv.visitVarInsn(ILOAD, L_TMP0);
                mv.visitInsn(IXOR);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitWriteOperandFromLocal(mv, word, 0, L_TMP0, nextPc, ext, true);
            }
            case BinaryBytecode.OP_NOT -> {
                if (opCount != 1) {
                    emitThrowIllegalArg(mv, "NOT requires dest");
                    return;
                }
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitInsn(ICONST_M1);
                mv.visitInsn(IXOR);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitWriteOperandFromLocal(mv, word, 0, L_TMP0, nextPc, ext, true);
            }
            case BinaryBytecode.OP_SHL -> {
                if (opCount != 2) {
                    emitThrowIllegalArg(mv, "SHL requires dest, count");
                    return;
                }
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitReadOperand(mv, word, 1, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP1);
                mv.visitVarInsn(ILOAD, L_TMP0);
                mv.visitVarInsn(ILOAD, L_TMP1);
                mv.visitInsn(ISHL);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitWriteOperandFromLocal(mv, word, 0, L_TMP0, nextPc, ext, true);
            }
            case BinaryBytecode.OP_SHR -> {
                if (opCount != 2) {
                    emitThrowIllegalArg(mv, "SHR requires dest, count");
                    return;
                }
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitReadOperand(mv, word, 1, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP1);
                mv.visitVarInsn(ILOAD, L_TMP0);
                mv.visitVarInsn(ILOAD, L_TMP1);
                mv.visitInsn(ISHR);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitWriteOperandFromLocal(mv, word, 0, L_TMP0, nextPc, ext, true);
            }
            case BinaryBytecode.OP_JMP -> emitJump(mv, word, opCount, nextPc, chunkStart, chunkEnd, codeLen, ext, instLabels, JumpCond.ALWAYS);
            case BinaryBytecode.OP_JZ -> emitJump(mv, word, opCount, nextPc, chunkStart, chunkEnd, codeLen, ext, instLabels, JumpCond.Z);
            case BinaryBytecode.OP_JNZ -> emitJump(mv, word, opCount, nextPc, chunkStart, chunkEnd, codeLen, ext, instLabels, JumpCond.NZ);
            case BinaryBytecode.OP_JS -> emitJump(mv, word, opCount, nextPc, chunkStart, chunkEnd, codeLen, ext, instLabels, JumpCond.S);
            case BinaryBytecode.OP_JNS -> emitJump(mv, word, opCount, nextPc, chunkStart, chunkEnd, codeLen, ext, instLabels, JumpCond.NS);
            case BinaryBytecode.OP_JL -> emitJump(mv, word, opCount, nextPc, chunkStart, chunkEnd, codeLen, ext, instLabels, JumpCond.L);
            case BinaryBytecode.OP_JLE -> emitJump(mv, word, opCount, nextPc, chunkStart, chunkEnd, codeLen, ext, instLabels, JumpCond.LE);
            case BinaryBytecode.OP_JG -> emitJump(mv, word, opCount, nextPc, chunkStart, chunkEnd, codeLen, ext, instLabels, JumpCond.G);
            case BinaryBytecode.OP_JGE -> emitJump(mv, word, opCount, nextPc, chunkStart, chunkEnd, codeLen, ext, instLabels, JumpCond.GE);
            case BinaryBytecode.OP_CALL -> {
                if (opCount != 1) {
                    emitThrowIllegalArg(mv, "CALL requires target");
                    return;
                }
                int mode = BinaryBytecode.extractOpMode(word, 0, ext);
                if (mode == BinaryBytecode.MODE_IMM) {
                    int rawTarget = BinaryBytecode.extractOpImm(word, 0, ext);
                    int target = Math.max(0, Math.min(rawTarget, codeLen));
                    mv.visitVarInsn(ALOAD, L_CPU);
                    pushConst(mv, nextPc);
                    mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitPushCall", "(I)V", false);
                    if (target >= chunkStart && target < chunkEnd) {
                        mv.visitJumpInsn(GOTO, instLabels[target - chunkStart]);
                    } else {
                        emitSyncLocals(mv, target);
                        emitReturnPackedConstPc(mv, target);
                    }
                } else {
                    emitReadOperand(mv, word, 0, nextPc, ext);
                    mv.visitVarInsn(ISTORE, L_TMP0);
                    mv.visitVarInsn(ALOAD, L_CPU);
                    pushConst(mv, nextPc);
                    mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitPushCall", "(I)V", false);
                    emitClampToCodeLen(mv, L_TMP0, codeLen);
                    mv.visitVarInsn(ISTORE, L_PC);
                    emitSyncLocalsWithPcLocal(mv);
                    emitReturnPackedLocalPc(mv);
                }
            }
            case BinaryBytecode.OP_RET -> {
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitExecRet", "()I", false);
                mv.visitVarInsn(ISTORE, L_PC);
                emitSyncLocalsWithPcLocal(mv);
                emitReturnPackedLocalPc(mv);
            }
            case BinaryBytecode.OP_PUSH -> {
                if (opCount != 1) {
                    emitThrowIllegalArg(mv, "PUSH requires value");
                    return;
                }
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP0);
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitVarInsn(ILOAD, L_TMP0);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitPushData", "(I)V", false);
            }
            case BinaryBytecode.OP_POP -> {
                if (opCount != 1) {
                    emitThrowIllegalArg(mv, "POP requires dest");
                    return;
                }
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitPopData", "()I", false);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitWriteOperandFromLocal(mv, word, 0, L_TMP0, nextPc, ext, false);
            }
            case BinaryBytecode.OP_IVEC -> {
                if (opCount != 2) {
                    emitThrowIllegalArg(mv, "IVEC requires vector, handler");
                    return;
                }
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitReadOperand(mv, word, 1, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP1);
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitVarInsn(ILOAD, L_TMP0);
                mv.visitVarInsn(ILOAD, L_TMP1);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitExecIvec", "(II)V", false);
            }
            case BinaryBytecode.OP_IRET -> {
                emitSyncLocals(mv, nextPc);
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitExecIret", "()I", false);
                mv.visitVarInsn(ISTORE, L_PC);
                emitReturnPackedLocalPc(mv);
            }
            case BinaryBytecode.OP_CLI -> {
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitInsn(ICONST_0);
                mv.visitFieldInsn(PUTFIELD, CPU_INTERNAL, "interruptsEnabled", "Z");
            }
            case BinaryBytecode.OP_STI -> {
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitInsn(ICONST_1);
                mv.visitFieldInsn(PUTFIELD, CPU_INTERNAL, "interruptsEnabled", "Z");
            }
            case BinaryBytecode.OP_SLEEP -> {
                if (opCount != 1) {
                    emitThrowIllegalArg(mv, "SLEEP requires ticks");
                    return;
                }
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP0);
                Label noSleep = new Label();
                mv.visitVarInsn(ILOAD, L_TMP0);
                mv.visitJumpInsn(IFLE, noSleep);
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitVarInsn(ILOAD, L_TMP0);
                mv.visitFieldInsn(PUTFIELD, CPU_INTERNAL, "sleepTicksRemaining", "I");
                emitSyncLocals(mv, nextPc);
                emitReturnPackedConstPc(mv, nextPc);
                mv.visitLabel(noSleep);
            }
            case BinaryBytecode.OP_TIME -> {
                if (opCount != 1) {
                    emitThrowIllegalArg(mv, "TIME requires dest");
                    return;
                }
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitReadTime", "()I", false);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitWriteOperandFromLocal(mv, word, 0, L_TMP0, nextPc, ext, false);
            }
            case BinaryBytecode.OP_RAND -> {
                if (opCount != 2) {
                    emitThrowIllegalArg(mv, "RAND requires dest, max");
                    return;
                }
                emitReadOperand(mv, word, 1, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP1);
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitVarInsn(ILOAD, L_TMP1);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitNextRand", "(I)I", false);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitWriteOperandFromLocal(mv, word, 0, L_TMP0, nextPc, ext, false);
            }
            case BinaryBytecode.OP_INPAD -> {
                if (opCount < 1 || opCount > 2) {
                    emitThrowIllegalArg(mv, "INPAD requires dest [, pad]");
                    return;
                }
                if (opCount == 2) {
                    emitReadOperand(mv, word, 1, nextPc, ext);
                    mv.visitVarInsn(ISTORE, L_TMP1);
                    mv.visitVarInsn(ALOAD, L_CPU);
                    mv.visitVarInsn(ILOAD, L_TMP1);
                } else {
                    mv.visitVarInsn(ALOAD, L_CPU);
                    mv.visitInsn(ICONST_0);
                }
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "getPadState", "(I)I", false);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitWriteOperandFromLocal(mv, word, 0, L_TMP0, nextPc, ext, false);
            }
            case BinaryBytecode.OP_INAXIS -> {
                if (opCount != 3) {
                    emitThrowIllegalArg(mv, "INAXIS requires destination, pad, axis");
                    return;
                }
                emitReadOperand(mv, word, 1, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP1);
                emitReadOperand(mv, word, 2, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP2);
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitVarInsn(ILOAD, L_TMP1);
                mv.visitVarInsn(ILOAD, L_TMP2);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "getPadAxis", "(II)I", false);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitWriteOperandFromLocal(mv, word, 0, L_TMP0, nextPc, ext, false);
            }
            case BinaryBytecode.OP_FBWIDTH -> {
                if (opCount != 1) {
                    emitThrowIllegalArg(mv, "FBWIDTH requires dest");
                    return;
                }
                mv.visitIntInsn(SIPUSH, VirtualCpu.FB_WIDTH);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitWriteOperandFromLocal(mv, word, 0, L_TMP0, nextPc, ext, false);
            }
            case BinaryBytecode.OP_FBHEIGHT -> {
                if (opCount != 1) {
                    emitThrowIllegalArg(mv, "FBHEIGHT requires dest");
                    return;
                }
                mv.visitIntInsn(SIPUSH, VirtualCpu.FB_HEIGHT);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitWriteOperandFromLocal(mv, word, 0, L_TMP0, nextPc, ext, false);
            }
            case BinaryBytecode.OP_FBMODE -> {
                if (opCount != 1) {
                    emitThrowIllegalArg(mv, "FBMODE requires mode");
                    return;
                }
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP0);
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitVarInsn(ILOAD, L_TMP0);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitFbMode", "(I)V", false);
            }
            case BinaryBytecode.OP_FBSET -> {
                if (opCount != 3) {
                    emitThrowIllegalArg(mv, "FBSET requires x, y, color");
                    return;
                }
                emitReadThreeOps(mv, word, 0, 1, 2, nextPc, ext);
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitVarInsn(ILOAD, L_TMP0);
                mv.visitVarInsn(ILOAD, L_TMP1);
                mv.visitVarInsn(ILOAD, L_TMP2);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitFbSet", "(III)V", false);
            }
            case BinaryBytecode.OP_FBGET -> {
                if (opCount != 3) {
                    emitThrowIllegalArg(mv, "FBGET requires dest, x, y");
                    return;
                }
                emitReadOperand(mv, word, 1, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP1);
                emitReadOperand(mv, word, 2, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP2);
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitVarInsn(ILOAD, L_TMP1);
                mv.visitVarInsn(ILOAD, L_TMP2);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitFbGet", "(II)I", false);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitWriteOperandFromLocal(mv, word, 0, L_TMP0, nextPc, ext, false);
            }
            case BinaryBytecode.OP_FBCLR -> {
                if (opCount != 1) {
                    emitThrowIllegalArg(mv, "FBCLR requires color");
                    return;
                }
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP0);
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitVarInsn(ILOAD, L_TMP0);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitFbClr", "(I)V", false);
            }
            case BinaryBytecode.OP_FBRECT -> {
                if (opCount != 5) {
                    emitThrowIllegalArg(mv, "FBRECT requires x, y, w, h, color");
                    return;
                }
                emitCall5IntCpu(mv, word, nextPc, ext, "jitFbRect");
            }
            case BinaryBytecode.OP_FBLINE -> {
                if (opCount != 5) {
                    emitThrowIllegalArg(mv, "FBLINE requires x1, y1, x2, y2, color");
                    return;
                }
                emitCall5IntCpu(mv, word, nextPc, ext, "jitFbLine");
            }
            case BinaryBytecode.OP_FBLIT -> {
                if (opCount != 5) {
                    emitThrowIllegalArg(mv, "FBLIT requires x, y, ram_src, w, h");
                    return;
                }
                emitCall5IntCpu(mv, word, nextPc, ext, "jitFbLit");
            }
            case BinaryBytecode.OP_FBSWAP -> {
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitFbSwap", "()V", false);
            }
            case BinaryBytecode.OP_GFXSEL -> {
                if (opCount != 1) {
                    emitThrowIllegalArg(mv, "GFXSEL requires mode");
                    return;
                }
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP0);
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitVarInsn(ILOAD, L_TMP0);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitGfxSel", "(I)Z", false);
                mv.visitVarInsn(ISTORE, L_FLAGZ);
                mv.visitInsn(ICONST_0);
                mv.visitVarInsn(ISTORE, L_FLAGS);
            }
            case BinaryBytecode.OP_GFXSTAT -> {
                if (opCount < 1 || opCount > 2) {
                    emitThrowIllegalArg(mv, "GFXSTAT requires dest [, query]");
                    return;
                }
                if (opCount == 2) {
                    emitReadOperand(mv, word, 1, nextPc, ext);
                    mv.visitVarInsn(ISTORE, L_TMP1);
                    mv.visitVarInsn(ALOAD, L_CPU);
                    mv.visitVarInsn(ILOAD, L_TMP1);
                } else {
                    mv.visitVarInsn(ALOAD, L_CPU);
                    mv.visitInsn(ICONST_0);
                }
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitGfxStat", "(I)I", false);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitWriteOperandFromLocal(mv, word, 0, L_TMP0, nextPc, ext, true);
            }
            case BinaryBytecode.OP_GPUCLR -> {
                if (opCount != 1) {
                    emitThrowIllegalArg(mv, "GPUCLR requires color");
                    return;
                }
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP0);
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitVarInsn(ILOAD, L_TMP0);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitGpuClr", "(I)V", false);
            }
            case BinaryBytecode.OP_GPURECT -> {
                if (opCount != 5) {
                    emitThrowIllegalArg(mv, "GPURECT requires x, y, w, h, color");
                    return;
                }
                emitCall5IntCpu(mv, word, nextPc, ext, "jitGpuRect");
            }
            case BinaryBytecode.OP_GPULINE -> {
                if (opCount != 5) {
                    emitThrowIllegalArg(mv, "GPULINE requires x1, y1, x2, y2, color");
                    return;
                }
                emitCall5IntCpu(mv, word, nextPc, ext, "jitGpuLine");
            }
            case BinaryBytecode.OP_GPUCIRC -> {
                if (opCount < 4 || opCount > 5) {
                    emitThrowIllegalArg(mv, "GPUCIRC requires cx, cy, r, color [, filled]");
                    return;
                }
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitReadOperand(mv, word, 1, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP1);
                emitReadOperand(mv, word, 2, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP2);
                emitReadOperand(mv, word, 3, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP3);
                if (opCount == 5) {
                    emitReadOperand(mv, word, 4, nextPc, ext);
                    mv.visitVarInsn(ISTORE, L_TMP4);
                } else {
                    mv.visitInsn(ICONST_1);
                    mv.visitVarInsn(ISTORE, L_TMP4);
                }
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitVarInsn(ILOAD, L_TMP0);
                mv.visitVarInsn(ILOAD, L_TMP1);
                mv.visitVarInsn(ILOAD, L_TMP2);
                mv.visitVarInsn(ILOAD, L_TMP3);
                mv.visitVarInsn(ILOAD, L_TMP4);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitGpuCirc", "(IIIII)V", false);
            }
            case BinaryBytecode.OP_GPUTRI -> {
                if (opCount != 1) {
                    emitThrowIllegalArg(mv, "GPUTRI requires addr");
                    return;
                }
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP0);
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitVarInsn(ILOAD, L_TMP0);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitGpuTri", "(I)V", false);
            }
            case BinaryBytecode.OP_GPUVRAM -> {
                if (opCount != 3) {
                    emitThrowIllegalArg(mv, "GPUVRAM requires vram_offset, ram_src, count");
                    return;
                }
                emitReadThreeOps(mv, word, 0, 1, 2, nextPc, ext);
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitVarInsn(ILOAD, L_TMP0);
                mv.visitVarInsn(ILOAD, L_TMP1);
                mv.visitVarInsn(ILOAD, L_TMP2);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitGpuVram", "(III)V", false);
            }
            case BinaryBytecode.OP_GPUBLIT -> {
                if (opCount != 5) {
                    emitThrowIllegalArg(mv, "GPUBLIT requires x, y, vram_offset, w, h");
                    return;
                }
                emitCall5IntCpu(mv, word, nextPc, ext, "jitGpuBlit");
            }
            case BinaryBytecode.OP_GPUSWAP -> {
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitGpuSwap", "()V", false);
            }
            case BinaryBytecode.OP_GPUSYNC -> {
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitGpuSync", "()V", false);
            }
            case BinaryBytecode.OP_APUSH -> {
                if (opCount != 1) {
                    emitThrowIllegalArg(mv, "APUSH requires sample");
                    return;
                }
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP0);
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitVarInsn(ILOAD, L_TMP0);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitAPushVal", "(I)V", false);
            }
            case BinaryBytecode.OP_ABUFW -> {
                if (opCount != 2) {
                    emitThrowIllegalArg(mv, "ABUFW requires ram_src, count");
                    return;
                }
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitReadOperand(mv, word, 1, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP1);
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitVarInsn(ILOAD, L_TMP0);
                mv.visitVarInsn(ILOAD, L_TMP1);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitABufwVal", "(II)V", false);
            }
            case BinaryBytecode.OP_ACLR -> {
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitAClr", "()V", false);
            }
            case BinaryBytecode.OP_ASTAT -> {
                if (opCount != 1) {
                    emitThrowIllegalArg(mv, "ASTAT requires dest");
                    return;
                }
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitAStat", "()I", false);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitWriteOperandFromLocal(mv, word, 0, L_TMP0, nextPc, ext, false);
            }
            case BinaryBytecode.OP_ATONE -> {
                if (opCount != 2) {
                    emitThrowIllegalArg(mv, "ATONE requires freq, duration_ms");
                    return;
                }
                emitReadOperand(mv, word, 0, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP0);
                emitReadOperand(mv, word, 1, nextPc, ext);
                mv.visitVarInsn(ISTORE, L_TMP1);
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitVarInsn(ILOAD, L_TMP0);
                mv.visitVarInsn(ILOAD, L_TMP1);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitAToneVal", "(II)V", false);
            }
            case BinaryBytecode.OP_APLAY -> {
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitAPlay", "()V", false);
            }
            case BinaryBytecode.OP_HLT -> {
                emitSyncLocals(mv, nextPc);
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitExecHlt", "()V", false);
                emitReturnPackedConstPc(mv, nextPc);
            }
            default -> {
                // Tier 3 complex/IO instructions: sync locals, call jitExecComplex(word, curPc), reload & handle control change
                emitSyncLocals(mv, nextPc);
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitLdcInsn(word);
                pushConst(mv, curPc);
                mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, "jitExecComplex", "(JI)V", false);
                emitReloadLocals(mv);

                // If state != RUNNING || cpu.pc != nextPc || cpu.sleepTicksRemaining > 0 || cpu.jitProgram == null -> return cpu.pc
                Label stayInChunk = new Label();
                Label leaveChunk = new Label();
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitFieldInsn(GETFIELD, CPU_INTERNAL, "state", STATE_DESC);
                mv.visitFieldInsn(GETSTATIC, STATE_INTERNAL, "RUNNING", STATE_DESC);
                mv.visitJumpInsn(IF_ACMPNE, leaveChunk);

                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitFieldInsn(GETFIELD, CPU_INTERNAL, "pc", "I");
                pushConst(mv, nextPc);
                mv.visitJumpInsn(IF_ICMPNE, leaveChunk);

                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitFieldInsn(GETFIELD, CPU_INTERNAL, "sleepTicksRemaining", "I");
                mv.visitJumpInsn(IFGT, leaveChunk);

                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitFieldInsn(GETFIELD, CPU_INTERNAL, "jitProgram", "L" + PROGRAM_INTERNAL + ";");
                mv.visitJumpInsn(IFNULL, leaveChunk);

                mv.visitJumpInsn(GOTO, stayInChunk);

                mv.visitLabel(leaveChunk);
                mv.visitVarInsn(ALOAD, L_CPU);
                mv.visitFieldInsn(GETFIELD, CPU_INTERNAL, "pc", "I");
                mv.visitVarInsn(ISTORE, L_PC);
                emitReturnPackedLocalPc(mv);

                mv.visitLabel(stayInChunk);
            }
        }
    }

    private enum JumpCond {
        ALWAYS, Z, NZ, S, NS, L, LE, G, GE
    }

    private static void emitJump(
            MethodVisitor mv,
            long word,
            int opCount,
            int nextPc,
            int chunkStart,
            int chunkEnd,
            int codeLen,
            BinaryBytecode.ExtOperand[] ext,
            Label[] instLabels,
            JumpCond cond
    ) {
        Label skipJump = new Label();
        switch (cond) {
            case ALWAYS -> {}
            case Z -> {
                mv.visitVarInsn(ILOAD, L_FLAGZ);
                mv.visitJumpInsn(IFEQ, skipJump);
            }
            case NZ -> {
                mv.visitVarInsn(ILOAD, L_FLAGZ);
                mv.visitJumpInsn(IFNE, skipJump);
            }
            case S -> {
                mv.visitVarInsn(ILOAD, L_FLAGS);
                mv.visitJumpInsn(IFEQ, skipJump);
            }
            case NS -> {
                mv.visitVarInsn(ILOAD, L_FLAGS);
                mv.visitJumpInsn(IFNE, skipJump);
            }
            case L -> { // flagS && !flagZ
                mv.visitVarInsn(ILOAD, L_FLAGS);
                mv.visitJumpInsn(IFEQ, skipJump);
                mv.visitVarInsn(ILOAD, L_FLAGZ);
                mv.visitJumpInsn(IFNE, skipJump);
            }
            case LE -> { // flagS || flagZ
                Label taken = new Label();
                mv.visitVarInsn(ILOAD, L_FLAGS);
                mv.visitJumpInsn(IFNE, taken);
                mv.visitVarInsn(ILOAD, L_FLAGZ);
                mv.visitJumpInsn(IFEQ, skipJump);
                mv.visitLabel(taken);
            }
            case G -> { // !flagS && !flagZ
                mv.visitVarInsn(ILOAD, L_FLAGS);
                mv.visitJumpInsn(IFNE, skipJump);
                mv.visitVarInsn(ILOAD, L_FLAGZ);
                mv.visitJumpInsn(IFNE, skipJump);
            }
            case GE -> { // !flagS
                mv.visitVarInsn(ILOAD, L_FLAGS);
                mv.visitJumpInsn(IFNE, skipJump);
            }
        }

        if (opCount != 1) {
            emitThrowIllegalArg(mv, "Jump requires target");
            if (cond != JumpCond.ALWAYS) {
                mv.visitLabel(skipJump);
            }
            return;
        }

        int mode = BinaryBytecode.extractOpMode(word, 0, ext);
        if (mode == BinaryBytecode.MODE_IMM) {
            int rawTarget = BinaryBytecode.extractOpImm(word, 0, ext);
            int target = Math.max(0, Math.min(rawTarget, codeLen));
            if (target >= chunkStart && target < chunkEnd) {
                mv.visitJumpInsn(GOTO, instLabels[target - chunkStart]);
            } else {
                emitSyncLocals(mv, target);
                emitReturnPackedConstPc(mv, target);
            }
        } else {
            emitReadOperand(mv, word, 0, nextPc, ext);
            mv.visitVarInsn(ISTORE, L_TMP0);
            emitClampToCodeLen(mv, L_TMP0, codeLen);
            mv.visitVarInsn(ISTORE, L_PC);
            emitSyncLocalsWithPcLocal(mv);
            emitReturnPackedLocalPc(mv);
        }

        if (cond != JumpCond.ALWAYS) {
            mv.visitLabel(skipJump);
        }
    }

    private static void emitClampToCodeLen(MethodVisitor mv, int valLocal, int codeLen) {
        // Math.max(0, Math.min(val, codeLen))
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ILOAD, valLocal);
        pushConst(mv, codeLen);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(II)I", false);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(II)I", false);
    }

    private static void emitReadThreeOps(MethodVisitor mv, long word, int i0, int i1, int i2, int nextPc, BinaryBytecode.ExtOperand[] ext) {
        emitReadOperand(mv, word, i0, nextPc, ext);
        mv.visitVarInsn(ISTORE, L_TMP0);
        emitReadOperand(mv, word, i1, nextPc, ext);
        mv.visitVarInsn(ISTORE, L_TMP1);
        emitReadOperand(mv, word, i2, nextPc, ext);
        mv.visitVarInsn(ISTORE, L_TMP2);
    }

    private static void emitCall5IntCpu(MethodVisitor mv, long word, int nextPc, BinaryBytecode.ExtOperand[] ext, String methodName) {
        for (int idx = 0; idx < 5; idx++) {
            emitReadOperand(mv, word, idx, nextPc, ext);
            mv.visitVarInsn(ISTORE, L_TMP0 + idx);
        }
        mv.visitVarInsn(ALOAD, L_CPU);
        for (int idx = 0; idx < 5; idx++) {
            mv.visitVarInsn(ILOAD, L_TMP0 + idx);
        }
        mv.visitMethodInsn(INVOKEVIRTUAL, CPU_INTERNAL, methodName, "(IIIII)V", false);
    }

    private static void emitReadOperand(MethodVisitor mv, long word, int idx, int nextPc, BinaryBytecode.ExtOperand[] ext) {
        int count = BinaryBytecode.extractOpCount(word);
        if (idx >= count) {
            emitThrowIllegalArg(mv, "Missing operand " + idx);
            mv.visitInsn(ICONST_0); // unreachable placeholder for verifier
            return;
        }
        int mode = BinaryBytecode.extractOpMode(word, idx, ext);
        int reg = BinaryBytecode.extractOpReg(word, idx, ext);
        int imm = BinaryBytecode.extractOpImm(word, idx, ext);

        switch (mode) {
            case BinaryBytecode.MODE_REG -> emitLoadReg(mv, reg, nextPc);
            case BinaryBytecode.MODE_IMM -> pushConst(mv, imm);
            case BinaryBytecode.MODE_RAM_IMM -> {
                mv.visitVarInsn(ALOAD, L_RAM);
                pushConst(mv, imm);
                mv.visitMethodInsn(INVOKESTATIC, CPU_INTERNAL, "jitReadRam", "([II)I", false);
            }
            case BinaryBytecode.MODE_RAM_REG -> {
                mv.visitVarInsn(ALOAD, L_RAM);
                emitLoadReg(mv, reg, nextPc);
                mv.visitMethodInsn(INVOKESTATIC, CPU_INTERNAL, "jitReadRam", "([II)I", false);
            }
            case BinaryBytecode.MODE_RAM_REG_OFF -> {
                mv.visitVarInsn(ALOAD, L_RAM);
                emitLoadReg(mv, reg, nextPc);
                pushConst(mv, imm);
                mv.visitInsn(IADD);
                mv.visitMethodInsn(INVOKESTATIC, CPU_INTERNAL, "jitReadRam", "([II)I", false);
            }
            default -> {
                emitThrowIllegalArg(mv, "Invalid source operand mode: " + mode);
                mv.visitInsn(ICONST_0);
            }
        }
    }

    private static void emitWriteOperandFromLocal(
            MethodVisitor mv,
            long word,
            int idx,
            int valLocal,
            int nextPc,
            BinaryBytecode.ExtOperand[] ext,
            boolean updateFlags
    ) {
        int count = BinaryBytecode.extractOpCount(word);
        if (idx >= count) {
            emitThrowIllegalArg(mv, "Missing destination operand " + idx);
            return;
        }
        int mode = BinaryBytecode.extractOpMode(word, idx, ext);
        int reg = BinaryBytecode.extractOpReg(word, idx, ext);
        int imm = BinaryBytecode.extractOpImm(word, idx, ext);

        switch (mode) {
            case BinaryBytecode.MODE_REG -> {
                switch (reg) {
                    case BinaryBytecode.REG_A -> {
                        mv.visitVarInsn(ILOAD, valLocal);
                        mv.visitVarInsn(ISTORE, L_REGA);
                    }
                    case BinaryBytecode.REG_B -> {
                        mv.visitVarInsn(ILOAD, valLocal);
                        mv.visitVarInsn(ISTORE, L_REGB);
                    }
                    case BinaryBytecode.REG_C -> {
                        mv.visitVarInsn(ILOAD, valLocal);
                        mv.visitVarInsn(ISTORE, L_REGC);
                    }
                    case BinaryBytecode.REG_D -> {
                        mv.visitVarInsn(ILOAD, valLocal);
                        mv.visitVarInsn(ISTORE, L_REGD);
                    }
                    case BinaryBytecode.REG_PC -> {
                        mv.visitVarInsn(ILOAD, valLocal);
                        mv.visitVarInsn(ISTORE, L_PC);
                        if (updateFlags) {
                            emitUpdateFlagsFromLocal(mv, valLocal);
                        }
                        emitSyncLocalsWithPcLocal(mv);
                        emitReturnPackedLocalPc(mv);
                        return;
                    }
                    default -> {
                        emitThrowIllegalArg(mv, "Cannot write to register " + reg);
                        return;
                    }
                }
            }
            case BinaryBytecode.MODE_RAM_IMM -> {
                mv.visitVarInsn(ALOAD, L_RAM);
                pushConst(mv, imm);
                mv.visitVarInsn(ILOAD, valLocal);
                mv.visitMethodInsn(INVOKESTATIC, CPU_INTERNAL, "jitWriteRam", "([III)V", false);
            }
            case BinaryBytecode.MODE_RAM_REG -> {
                mv.visitVarInsn(ALOAD, L_RAM);
                emitLoadReg(mv, reg, nextPc);
                mv.visitVarInsn(ILOAD, valLocal);
                mv.visitMethodInsn(INVOKESTATIC, CPU_INTERNAL, "jitWriteRam", "([III)V", false);
            }
            case BinaryBytecode.MODE_RAM_REG_OFF -> {
                mv.visitVarInsn(ALOAD, L_RAM);
                emitLoadReg(mv, reg, nextPc);
                pushConst(mv, imm);
                mv.visitInsn(IADD);
                mv.visitVarInsn(ILOAD, valLocal);
                mv.visitMethodInsn(INVOKESTATIC, CPU_INTERNAL, "jitWriteRam", "([III)V", false);
            }
            default -> {
                emitThrowIllegalArg(mv, "Cannot write to destination");
                return;
            }
        }

        if (updateFlags) {
            emitUpdateFlagsFromLocal(mv, valLocal);
        }
    }

    private static void emitLoadReg(MethodVisitor mv, int reg, int nextPc) {
        switch (reg) {
            case BinaryBytecode.REG_A -> mv.visitVarInsn(ILOAD, L_REGA);
            case BinaryBytecode.REG_B -> mv.visitVarInsn(ILOAD, L_REGB);
            case BinaryBytecode.REG_C -> mv.visitVarInsn(ILOAD, L_REGC);
            case BinaryBytecode.REG_D -> mv.visitVarInsn(ILOAD, L_REGD);
            case BinaryBytecode.REG_PC -> pushConst(mv, nextPc);
            case BinaryBytecode.REG_FLAGS -> {
                mv.visitVarInsn(ILOAD, L_FLAGZ);
                mv.visitVarInsn(ILOAD, L_FLAGS);
                mv.visitInsn(ICONST_1);
                mv.visitInsn(ISHL);
                mv.visitInsn(IOR);
            }
            default -> mv.visitInsn(ICONST_0);
        }
    }

    private static void emitUpdateFlagsFromLocal(MethodVisitor mv, int valLocal) {
        // flagZ = (val == 0)
        Label zFalse = new Label();
        Label zEnd = new Label();
        mv.visitVarInsn(ILOAD, valLocal);
        mv.visitJumpInsn(IFNE, zFalse);
        mv.visitInsn(ICONST_1);
        mv.visitJumpInsn(GOTO, zEnd);
        mv.visitLabel(zFalse);
        mv.visitInsn(ICONST_0);
        mv.visitLabel(zEnd);
        mv.visitVarInsn(ISTORE, L_FLAGZ);

        // flagS = (val >>> 31)
        mv.visitVarInsn(ILOAD, valLocal);
        mv.visitIntInsn(BIPUSH, 31);
        mv.visitInsn(IUSHR);
        mv.visitVarInsn(ISTORE, L_FLAGS);
    }

    private static void emitReloadLocals(MethodVisitor mv) {
        mv.visitVarInsn(ALOAD, L_CPU);
        mv.visitFieldInsn(GETFIELD, CPU_INTERNAL, "regA", "I");
        mv.visitVarInsn(ISTORE, L_REGA);

        mv.visitVarInsn(ALOAD, L_CPU);
        mv.visitFieldInsn(GETFIELD, CPU_INTERNAL, "regB", "I");
        mv.visitVarInsn(ISTORE, L_REGB);

        mv.visitVarInsn(ALOAD, L_CPU);
        mv.visitFieldInsn(GETFIELD, CPU_INTERNAL, "regC", "I");
        mv.visitVarInsn(ISTORE, L_REGC);

        mv.visitVarInsn(ALOAD, L_CPU);
        mv.visitFieldInsn(GETFIELD, CPU_INTERNAL, "regD", "I");
        mv.visitVarInsn(ISTORE, L_REGD);

        mv.visitVarInsn(ALOAD, L_CPU);
        mv.visitFieldInsn(GETFIELD, CPU_INTERNAL, "flagZ", "Z");
        mv.visitVarInsn(ISTORE, L_FLAGZ);

        mv.visitVarInsn(ALOAD, L_CPU);
        mv.visitFieldInsn(GETFIELD, CPU_INTERNAL, "flagS", "Z");
        mv.visitVarInsn(ISTORE, L_FLAGS);

        mv.visitVarInsn(ALOAD, L_CPU);
        mv.visitFieldInsn(GETFIELD, CPU_INTERNAL, "cycles", "I");
        mv.visitVarInsn(ISTORE, L_CYCLES);

        mv.visitVarInsn(ALOAD, L_CPU);
        mv.visitFieldInsn(GETFIELD, CPU_INTERNAL, "ram", "[I");
        mv.visitVarInsn(ASTORE, L_RAM);
    }

    private static void emitSyncLocals(MethodVisitor mv, int constPc) {
        pushConst(mv, constPc);
        mv.visitVarInsn(ISTORE, L_PC);
        emitSyncLocalsWithPcLocal(mv);
    }

    private static void emitSyncLocalsWithPcLocal(MethodVisitor mv) {
        mv.visitVarInsn(ALOAD, L_CPU);
        mv.visitVarInsn(ILOAD, L_REGA);
        mv.visitFieldInsn(PUTFIELD, CPU_INTERNAL, "regA", "I");

        mv.visitVarInsn(ALOAD, L_CPU);
        mv.visitVarInsn(ILOAD, L_REGB);
        mv.visitFieldInsn(PUTFIELD, CPU_INTERNAL, "regB", "I");

        mv.visitVarInsn(ALOAD, L_CPU);
        mv.visitVarInsn(ILOAD, L_REGC);
        mv.visitFieldInsn(PUTFIELD, CPU_INTERNAL, "regC", "I");

        mv.visitVarInsn(ALOAD, L_CPU);
        mv.visitVarInsn(ILOAD, L_REGD);
        mv.visitFieldInsn(PUTFIELD, CPU_INTERNAL, "regD", "I");

        mv.visitVarInsn(ALOAD, L_CPU);
        mv.visitVarInsn(ILOAD, L_FLAGZ);
        mv.visitFieldInsn(PUTFIELD, CPU_INTERNAL, "flagZ", "Z");

        mv.visitVarInsn(ALOAD, L_CPU);
        mv.visitVarInsn(ILOAD, L_FLAGS);
        mv.visitFieldInsn(PUTFIELD, CPU_INTERNAL, "flagS", "Z");

        mv.visitVarInsn(ALOAD, L_CPU);
        mv.visitVarInsn(ILOAD, L_CYCLES);
        mv.visitFieldInsn(PUTFIELD, CPU_INTERNAL, "cycles", "I");

        mv.visitVarInsn(ALOAD, L_CPU);
        mv.visitVarInsn(ILOAD, L_PC);
        mv.visitFieldInsn(PUTFIELD, CPU_INTERNAL, "pc", "I");
    }

    private static void emitReturnPackedConstPc(MethodVisitor mv, int pc) {
        mv.visitVarInsn(ILOAD, L_EXECUTED);
        mv.visitInsn(I2L);
        mv.visitIntInsn(BIPUSH, 32);
        mv.visitInsn(LSHL);
        long low = pc & 0xFFFFFFFFL;
        if (low != 0L) {
            mv.visitLdcInsn(low);
            mv.visitInsn(LOR);
        }
        mv.visitInsn(LRETURN);
    }

    private static void emitReturnPackedLocalPc(MethodVisitor mv) {
        mv.visitVarInsn(ILOAD, L_EXECUTED);
        mv.visitInsn(I2L);
        mv.visitIntInsn(BIPUSH, 32);
        mv.visitInsn(LSHL);
        mv.visitVarInsn(ILOAD, L_PC);
        mv.visitInsn(I2L);
        mv.visitLdcInsn(0xFFFFFFFFL);
        mv.visitInsn(LAND);
        mv.visitInsn(LOR);
        mv.visitInsn(LRETURN);
    }

    private static void emitThrowIllegalArg(MethodVisitor mv, String msg) {
        mv.visitTypeInsn(NEW, "java/lang/IllegalArgumentException");
        mv.visitInsn(DUP);
        mv.visitLdcInsn(msg);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(ATHROW);
    }

    private static void pushConst(MethodVisitor mv, int val) {
        if (val >= -1 && val <= 5) {
            mv.visitInsn(ICONST_0 + val);
        } else if (val >= Byte.MIN_VALUE && val <= Byte.MAX_VALUE) {
            mv.visitIntInsn(BIPUSH, val);
        } else if (val >= Short.MIN_VALUE && val <= Short.MAX_VALUE) {
            mv.visitIntInsn(SIPUSH, val);
        } else {
            mv.visitLdcInsn(val);
        }
    }

    private static final class SafeClassWriter extends ClassWriter {
        SafeClassWriter(int flags) {
            super(flags);
        }

        @Override
        protected ClassLoader getClassLoader() {
            return CpuJitCompiler.class.getClassLoader();
        }
    }
}
