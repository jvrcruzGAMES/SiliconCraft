package games.jvrcruz.siliconcraft.vm;

import games.jvrcruz.siliconcraft.Siliconcraft;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ASM-based JIT compiler translating {@link VirtualGpu} primitive command batches and repeated
 * frame command sequences into native JVM bytecode via {@link MethodHandles.Lookup#defineHiddenClass}.
 */
public final class GpuJitCompiler implements Opcodes {

    public static final int MAX_TRACE_LENGTH = 48;
    public static final int MIN_TRACE_LENGTH = 2;
    public static final int TRACE_COMPILE_THRESHOLD = 2;
    public static final int MAX_COMPILED_TRACES = 64;

    private static final String GPU_INTERNAL = "games/jvrcruz/siliconcraft/vm/VirtualGpu";
    private static final String PIPELINE_INTERNAL = "games/jvrcruz/siliconcraft/vm/GpuJitCompiler$CompiledGpuPipeline";
    private static final String TRACE_INTERNAL = "games/jvrcruz/siliconcraft/vm/GpuJitCompiler$CompiledGpuTrace";
    private static final AtomicInteger CLASS_COUNTER = new AtomicInteger(1);

    private static final CompiledGpuPipeline SHARED_PIPELINE = compilePipeline();

    private GpuJitCompiler() {}

    public interface CompiledGpuPipeline {
        int executeBatch(
                VirtualGpu gpu,
                int start,
                int count,
                byte[] kinds,
                int[] a0,
                int[] a1,
                int[] a2,
                int[] a3,
                int[] a4,
                int[] a5,
                int[] a6,
                int[][] pixels,
                int[] backBuffer,
                int[] vram
        );
    }

    public interface CompiledGpuTrace {
        void executeTrace(
                VirtualGpu gpu,
                int start,
                int[] a0,
                int[] a1,
                int[] a2,
                int[] a3,
                int[] a4,
                int[] a5,
                int[] a6,
                int[][] pixels,
                int[] backBuffer,
                int[] vram
        );
    }

    static CompiledGpuPipeline getSharedPipeline() {
        return SHARED_PIPELINE;
    }

    static final class TraceKey {
        final byte[] pattern;
        final int hash;

        TraceKey(byte[] src, int offset, int len) {
            this.pattern = Arrays.copyOfRange(src, offset, offset + len);
            this.hash = Arrays.hashCode(this.pattern);
        }

        private TraceKey(byte[] pattern, int hash) {
            this.pattern = pattern;
            this.hash = hash;
        }

        static TraceKey probe(byte[] src, int offset, int len) {
            int h = 1;
            for (int i = 0; i < len; i++) {
                h = 31 * h + src[offset + i];
            }
            return new TraceKey(null, h);
        }

        boolean matches(byte[] src, int offset, int len) {
            if (pattern == null || pattern.length != len) {
                return false;
            }
            return Arrays.equals(pattern, 0, len, src, offset, offset + len);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof TraceKey other)) return false;
            return hash == other.hash && Arrays.equals(pattern, other.pattern);
        }
    }

    static final class TraceEntry {
        int hits;
        CompiledGpuTrace compiled;
    }

    static final class TraceCache {
        private final LinkedHashMap<TraceKey, TraceEntry> entries = new LinkedHashMap<>(32, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<TraceKey, TraceEntry> eldest) {
                return size() > MAX_COMPILED_TRACES;
            }
        };

        TraceEntry lookupOrTrack(byte[] kinds, int offset, int len, VirtualGpu gpu) {
            TraceEntry found = null;
            int probeHash = 1;
            for (int i = 0; i < len; i++) {
                probeHash = 31 * probeHash + kinds[offset + i];
            }
            for (Map.Entry<TraceKey, TraceEntry> e : entries.entrySet()) {
                TraceKey k = e.getKey();
                if (k.hash == probeHash && k.matches(kinds, offset, len)) {
                    found = e.getValue();
                    // touch for LRU access order
                    entries.get(k);
                    break;
                }
            }
            if (found == null) {
                TraceKey key = new TraceKey(kinds, offset, len);
                found = new TraceEntry();
                entries.put(key, found);
            }
            found.hits++;
            if (found.compiled == null && found.hits >= TRACE_COMPILE_THRESHOLD) {
                try {
                    found.compiled = compileTrace(kinds, offset, len);
                    gpu.recordJitTraceCompiled();
                } catch (LinkageError | RuntimeException e) {
                    Siliconcraft.LOGGER.warn("GPU trace JIT compilation failed, using shared JIT pipeline", e);
                }
            }
            return found;
        }

        void clear() {
            entries.clear();
        }
    }

    static CompiledGpuPipeline compilePipeline() {
        String className = "games/jvrcruz/siliconcraft/vm/GpuJitPipeline_" + CLASS_COUNTER.getAndIncrement();
        ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL, className, null, "java/lang/Object", new String[]{PIPELINE_INTERNAL});

        emitDefaultConstructor(cw);

        // executeBatch(VirtualGpu gpu, int start, int count, byte[] kinds, int[] a0..a6, int[][] pixels, int[] backBuffer, int[] vram)
        // Locals: 0=this, 1=gpu, 2=start, 3=count, 4=kinds, 5=a0, 6=a1, 7=a2, 8=a3, 9=a4, 10=a5, 11=a6, 12=pixels, 13=backBuffer, 14=vram
        // 15=i, 16=end, 17=kind, 18=tmpX, 19=tmpY
        MethodVisitor mv = cw.visitMethod(
                ACC_PUBLIC,
                "executeBatch",
                "(L" + GPU_INTERNAL + ";II[B[I[I[I[I[I[I[I[[I[I[I)I",
                null,
                null
        );
        mv.visitCode();

        mv.visitVarInsn(ILOAD, 2); // start
        mv.visitVarInsn(ILOAD, 3); // count
        mv.visitInsn(IADD);
        mv.visitVarInsn(ISTORE, 16); // end = start + count

        mv.visitVarInsn(ILOAD, 2);
        mv.visitVarInsn(ISTORE, 15); // i = start

        Label loopHead = new Label();
        Label loopEnd = new Label();
        Label nextIter = new Label();

        mv.visitLabel(loopHead);
        mv.visitVarInsn(ILOAD, 15);
        mv.visitVarInsn(ILOAD, 16);
        mv.visitJumpInsn(IF_ICMPGE, loopEnd);

        // switch (kinds[i] & 0xFF)
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 15);
        mv.visitInsn(BALOAD);
        mv.visitIntInsn(SIPUSH, 0xFF);
        mv.visitInsn(IAND);

        Label[] caseLabels = new Label[9];
        for (int k = 0; k < caseLabels.length; k++) {
            caseLabels[k] = new Label();
        }
        mv.visitTableSwitchInsn(0, 8, nextIter, caseLabels);

        for (int k = 0; k < caseLabels.length; k++) {
            mv.visitLabel(caseLabels[k]);
            emitCommandByLocalIndex(mv, k, 15, 5, 12, 13, 14, 1, 18, 19, nextIter);
            mv.visitJumpInsn(GOTO, nextIter);
        }

        mv.visitLabel(nextIter);
        mv.visitIincInsn(15, 1);
        mv.visitJumpInsn(GOTO, loopHead);

        mv.visitLabel(loopEnd);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitInsn(IRETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        return defineHiddenInstance(cw.toByteArray(), CompiledGpuPipeline.class);
    }

    static CompiledGpuTrace compileTrace(byte[] kinds, int offset, int len) {
        String className = "games/jvrcruz/siliconcraft/vm/GpuJitTrace_" + CLASS_COUNTER.getAndIncrement();
        ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL, className, null, "java/lang/Object", new String[]{TRACE_INTERNAL});

        emitDefaultConstructor(cw);

        // executeTrace(VirtualGpu gpu, int start, int[] a0..a6, int[][] pixels, int[] backBuffer, int[] vram)
        // Locals: 0=this, 1=gpu, 2=start, 3=a0, 4=a1, 5=a2, 6=a3, 7=a4, 8=a5, 9=a6, 10=pixels, 11=backBuffer, 12=vram
        // 13=idx, 14=tmpX, 15=tmpY
        MethodVisitor mv = cw.visitMethod(
                ACC_PUBLIC,
                "executeTrace",
                "(L" + GPU_INTERNAL + ";I[I[I[I[I[I[I[I[[I[I[I)V",
                null,
                null
        );
        mv.visitCode();

        for (int k = 0; k < len; k++) {
            int kind = kinds[offset + k] & 0xFF;
            if (k == 0) {
                mv.visitVarInsn(ILOAD, 2);
                mv.visitVarInsn(ISTORE, 13);
            } else {
                mv.visitVarInsn(ILOAD, 2);
                pushConst(mv, k);
                mv.visitInsn(IADD);
                mv.visitVarInsn(ISTORE, 13);
            }
            Label stepDone = new Label();
            emitCommandByLocalIndex(mv, kind, 13, 3, 10, 11, 12, 1, 14, 15, stepDone);
            mv.visitLabel(stepDone);
        }

        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        return defineHiddenInstance(cw.toByteArray(), CompiledGpuTrace.class);
    }

    private static void emitCommandByLocalIndex(
            MethodVisitor mv,
            int kind,
            int idxLocal,
            int a0BaseLocal,
            int pixelsLocal,
            int backBufferLocal,
            int vramLocal,
            int gpuLocal,
            int tmpXLocal,
            int tmpYLocal,
            Label doneLabel
    ) {
        switch (kind) {
            case VirtualGpu.CMD_CLEAR -> {
                mv.visitVarInsn(ALOAD, backBufferLocal);
                mv.visitVarInsn(ALOAD, a0BaseLocal);
                mv.visitVarInsn(ILOAD, idxLocal);
                mv.visitInsn(IALOAD);
                mv.visitMethodInsn(INVOKESTATIC, "java/util/Arrays", "fill", "([II)V", false);
            }
            case VirtualGpu.CMD_SET_PIXEL -> {
                // x = a0[idx]; y = a1[idx];
                mv.visitVarInsn(ALOAD, a0BaseLocal);
                mv.visitVarInsn(ILOAD, idxLocal);
                mv.visitInsn(IALOAD);
                mv.visitVarInsn(ISTORE, tmpXLocal);

                mv.visitVarInsn(ALOAD, a0BaseLocal + 1);
                mv.visitVarInsn(ILOAD, idxLocal);
                mv.visitInsn(IALOAD);
                mv.visitVarInsn(ISTORE, tmpYLocal);

                mv.visitVarInsn(ILOAD, tmpXLocal);
                mv.visitJumpInsn(IFLT, doneLabel);
                mv.visitVarInsn(ILOAD, tmpXLocal);
                mv.visitIntInsn(SIPUSH, VirtualGpu.FB_WIDTH);
                mv.visitJumpInsn(IF_ICMPGE, doneLabel);

                mv.visitVarInsn(ILOAD, tmpYLocal);
                mv.visitJumpInsn(IFLT, doneLabel);
                mv.visitVarInsn(ILOAD, tmpYLocal);
                mv.visitIntInsn(SIPUSH, VirtualGpu.FB_HEIGHT);
                mv.visitJumpInsn(IF_ICMPGE, doneLabel);

                mv.visitVarInsn(ALOAD, backBufferLocal);
                mv.visitVarInsn(ILOAD, tmpYLocal);
                mv.visitIntInsn(SIPUSH, VirtualGpu.FB_WIDTH);
                mv.visitInsn(IMUL);
                mv.visitVarInsn(ILOAD, tmpXLocal);
                mv.visitInsn(IADD);
                mv.visitVarInsn(ALOAD, a0BaseLocal + 2);
                mv.visitVarInsn(ILOAD, idxLocal);
                mv.visitInsn(IALOAD);
                mv.visitInsn(IASTORE);
            }
            case VirtualGpu.CMD_FILL_RECT -> {
                for (int arg = 0; arg < 5; arg++) {
                    mv.visitVarInsn(ALOAD, a0BaseLocal + arg);
                    mv.visitVarInsn(ILOAD, idxLocal);
                    mv.visitInsn(IALOAD);
                }
                mv.visitVarInsn(ALOAD, backBufferLocal);
                mv.visitMethodInsn(INVOKESTATIC, GPU_INTERNAL, "jitRasterizeFillRect", "(IIIII[I)V", false);
            }
            case VirtualGpu.CMD_DRAW_LINE -> {
                for (int arg = 0; arg < 5; arg++) {
                    mv.visitVarInsn(ALOAD, a0BaseLocal + arg);
                    mv.visitVarInsn(ILOAD, idxLocal);
                    mv.visitInsn(IALOAD);
                }
                mv.visitVarInsn(ALOAD, backBufferLocal);
                mv.visitMethodInsn(INVOKESTATIC, GPU_INTERNAL, "jitRasterizeLine", "(IIIII[I)V", false);
            }
            case VirtualGpu.CMD_DRAW_CIRCLE -> {
                for (int arg = 0; arg < 5; arg++) {
                    mv.visitVarInsn(ALOAD, a0BaseLocal + arg);
                    mv.visitVarInsn(ILOAD, idxLocal);
                    mv.visitInsn(IALOAD);
                }
                mv.visitVarInsn(ALOAD, backBufferLocal);
                mv.visitMethodInsn(INVOKESTATIC, GPU_INTERNAL, "jitRasterizeCircle", "(IIIII[I)V", false);
            }
            case VirtualGpu.CMD_FILL_TRIANGLE -> {
                for (int arg = 0; arg < 7; arg++) {
                    mv.visitVarInsn(ALOAD, a0BaseLocal + arg);
                    mv.visitVarInsn(ILOAD, idxLocal);
                    mv.visitInsn(IALOAD);
                }
                mv.visitVarInsn(ALOAD, backBufferLocal);
                mv.visitMethodInsn(INVOKESTATIC, GPU_INTERNAL, "jitRasterizeTriangle", "(IIIIIII[I)V", false);
            }
            case VirtualGpu.CMD_BLIT_PIXELS -> {
                mv.visitVarInsn(ALOAD, a0BaseLocal);
                mv.visitVarInsn(ILOAD, idxLocal);
                mv.visitInsn(IALOAD);
                mv.visitVarInsn(ALOAD, a0BaseLocal + 1);
                mv.visitVarInsn(ILOAD, idxLocal);
                mv.visitInsn(IALOAD);
                mv.visitVarInsn(ALOAD, pixelsLocal);
                mv.visitVarInsn(ILOAD, idxLocal);
                mv.visitInsn(AALOAD);
                mv.visitVarInsn(ALOAD, a0BaseLocal + 2);
                mv.visitVarInsn(ILOAD, idxLocal);
                mv.visitInsn(IALOAD);
                mv.visitVarInsn(ALOAD, a0BaseLocal + 3);
                mv.visitVarInsn(ILOAD, idxLocal);
                mv.visitInsn(IALOAD);
                mv.visitVarInsn(ALOAD, a0BaseLocal + 4);
                mv.visitVarInsn(ILOAD, idxLocal);
                mv.visitInsn(IALOAD);
                mv.visitVarInsn(ALOAD, backBufferLocal);
                mv.visitMethodInsn(INVOKESTATIC, GPU_INTERNAL, "jitRasterizeBlitPixels", "(II[IIII[I)V", false);

                // Clear pixels[idx] = null so sprite buffers aren't retained
                mv.visitVarInsn(ALOAD, pixelsLocal);
                mv.visitVarInsn(ILOAD, idxLocal);
                mv.visitInsn(ACONST_NULL);
                mv.visitInsn(AASTORE);
            }
            case VirtualGpu.CMD_BLIT_VRAM -> {
                for (int arg = 0; arg < 6; arg++) {
                    mv.visitVarInsn(ALOAD, a0BaseLocal + arg);
                    mv.visitVarInsn(ILOAD, idxLocal);
                    mv.visitInsn(IALOAD);
                }
                mv.visitVarInsn(ALOAD, vramLocal);
                mv.visitVarInsn(ALOAD, backBufferLocal);
                mv.visitMethodInsn(INVOKESTATIC, GPU_INTERNAL, "jitRasterizeBlitVram", "(IIIIII[I[I)V", false);
            }
            case VirtualGpu.CMD_SWAP -> {
                mv.visitVarInsn(ALOAD, gpuLocal);
                mv.visitMethodInsn(INVOKEVIRTUAL, GPU_INTERNAL, "jitSwapBuffers", "()V", false);
            }
            default -> {
                // ignore unknown
            }
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

    private static <T> T defineHiddenInstance(byte[] bytecode, Class<T> iface) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup().defineHiddenClass(
                    bytecode, true, MethodHandles.Lookup.ClassOption.NESTMATE
            );
            return iface.cast(lookup.lookupClass().getConstructor().newInstance());
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to define GPU JIT class", t);
        }
    }

    private static final class SafeClassWriter extends ClassWriter {
        SafeClassWriter(int flags) {
            super(flags);
        }

        @Override
        protected ClassLoader getClassLoader() {
            return GpuJitCompiler.class.getClassLoader();
        }
    }
}
