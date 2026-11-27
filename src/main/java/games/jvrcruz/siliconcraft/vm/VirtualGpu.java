package games.jvrcruz.siliconcraft.vm;

import games.jvrcruz.siliconcraft.data.GpuData;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Dedicated virtual graphics processor executing JIT-compiled rasterization,
 * VRAM sprite storage/blitting, and double-buffered presentation alongside {@link VirtualCpu}.
 *
 * <p>Locking hierarchy (strictly acyclic): {@code renderLock} -> {@code queueLock} / {@code frontBufferLock}.
 * Command enqueueing acquires only {@code queueLock} using a zero-allocation primitive ring buffer
 * and wakes any attached GPU worker thread. Default slice budget targets GameCube Flipper GPU speeds
 * (162+ MHz at 5,000 slices/sec).
 */
public class VirtualGpu {
    public enum State {
        OFF,
        IDLE,
        RUNNING,
        ERROR
    }

    public static final int FB_WIDTH = VirtualCpu.FB_WIDTH;
    public static final int FB_HEIGHT = VirtualCpu.FB_HEIGHT;
    public static final int FB_PIXELS = FB_WIDTH * FB_HEIGHT;
    public static final int DEFAULT_BACKGROUND = 0xFF051009;
    public static final int MAX_COMMAND_QUEUE = 65536; // Must remain power of 2
    private static final int QUEUE_MASK = MAX_COMMAND_QUEUE - 1;
    public static final int BASE_OPS_PER_SLICE = 32_400; // 32,400 * 5,000 slices/sec = 162 MHz (GameCube Flipper)
    public static final int OPS_PER_COMPUTE_UNIT = 2048;

    static final byte CMD_CLEAR = 0;
    static final byte CMD_SET_PIXEL = 1;
    static final byte CMD_FILL_RECT = 2;
    static final byte CMD_DRAW_LINE = 3;
    static final byte CMD_DRAW_CIRCLE = 4;
    static final byte CMD_FILL_TRIANGLE = 5;
    static final byte CMD_BLIT_PIXELS = 6;
    static final byte CMD_BLIT_VRAM = 7;
    static final byte CMD_SWAP = 8;

    private volatile State state = State.OFF;
    private volatile boolean installed = false;
    private volatile boolean fbDirty = false;
    private volatile int displayMode = 0;
    private volatile boolean processingBatch = false;
    private volatile Thread workerThread = null;
    private volatile Thread syncWorkerThread = null;
    private volatile boolean jitEnabled = true;
    private volatile int jitTracesCompiled = 0;
    private volatile long jitTraceHits = 0L;

    private String archName = "";
    private int vramCapacityBytes = 0;
    private int vramCapacityInts = 0;
    private int computeUnits = 0;
    private Set<String> supportedFeatures = Set.of();
    private int[] vram = new int[0];

    private final int[] backBuffer = new int[FB_PIXELS];
    private final int[] frontBuffer = new int[FB_PIXELS];

    // Zero-allocation primitive ring buffer (guarded by queueLock)
    private byte[] cmdKind;
    private int[] cmdA0;
    private int[] cmdA1;
    private int[] cmdA2;
    private int[] cmdA3;
    private int[] cmdA4;
    private int[] cmdA5;
    private int[] cmdA6;
    private int[][] cmdPixels;
    private int queueHead = 0;
    private int queueTail = 0;
    private int queueCount = 0;

    // Staging batch arrays (guarded by renderLock)
    private byte[] batchKind;
    private int[] batchA0;
    private int[] batchA1;
    private int[] batchA2;
    private int[] batchA3;
    private int[] batchA4;
    private int[] batchA5;
    private int[] batchA6;
    private int[][] batchPixels;

    private final GpuJitCompiler.TraceCache traceCache = new GpuJitCompiler.TraceCache();

    private final ReentrantLock renderLock = new ReentrantLock();
    private final Object queueLock = new Object();
    private final Object frontBufferLock = new Object();

    private volatile long commandsExecuted = 0L;
    private volatile long gpuCycles = 0L;
    private volatile int framesPresented = 0;

    public VirtualGpu() {
        Arrays.fill(backBuffer, DEFAULT_BACKGROUND);
        Arrays.fill(frontBuffer, DEFAULT_BACKGROUND);
    }

    public static final int MAX_RAM_MODULES = 8;
    public static final int RAM_MODULE_BYTES = 2048 * 1024;

    public static int calculateRequiredSilicon(int opCount) {
        int ops = Math.max(1, opCount);
        return Math.min(12, 1 + (ops / 4));
    }

    public static int calculateRequiredRedstone(int opCount) {
        int ops = Math.max(1, opCount);
        return Math.min(16, 1 + (ops / 3));
    }

    public static int calculateRequiredEnergy(int ramModules) {
        return 600 + Math.max(0, Math.min(MAX_RAM_MODULES, ramModules)) * 250;
    }

    public static int calculateRequiredEnergy(int opCount, int ramModules) {
        int ops = Math.max(0, opCount);
        int rams = Math.max(0, Math.min(MAX_RAM_MODULES, ramModules));
        return 200 + ops * 60 + rams * 250;
    }

    public static int calculateMaxProgress(int ramModules) {
        return 60 + Math.max(0, Math.min(MAX_RAM_MODULES, ramModules)) * 20;
    }

    public static int calculateMaxProgress(int opCount, int ramModules) {
        int ops = Math.max(0, opCount);
        int rams = Math.max(0, Math.min(MAX_RAM_MODULES, ramModules));
        return 40 + ops * 8 + rams * 20;
    }

    public static String formatVram(int bytes) {
        if (bytes >= 1024 * 1024 && bytes % (1024 * 1024) == 0) {
            return (bytes / (1024 * 1024)) + " MB";
        }
        if (bytes >= 1024) {
            return (bytes / 1024) + " KB";
        }
        return bytes + " Bytes";
    }

    private void ensureCommandBuffersAllocatedLocked() {
        if (batchKind == null) {
            batchKind = new byte[MAX_COMMAND_QUEUE];
            batchA0 = new int[MAX_COMMAND_QUEUE];
            batchA1 = new int[MAX_COMMAND_QUEUE];
            batchA2 = new int[MAX_COMMAND_QUEUE];
            batchA3 = new int[MAX_COMMAND_QUEUE];
            batchA4 = new int[MAX_COMMAND_QUEUE];
            batchA5 = new int[MAX_COMMAND_QUEUE];
            batchA6 = new int[MAX_COMMAND_QUEUE];
            batchPixels = new int[MAX_COMMAND_QUEUE][];
        }
        synchronized (queueLock) {
            if (cmdKind == null) {
                cmdKind = new byte[MAX_COMMAND_QUEUE];
                cmdA0 = new int[MAX_COMMAND_QUEUE];
                cmdA1 = new int[MAX_COMMAND_QUEUE];
                cmdA2 = new int[MAX_COMMAND_QUEUE];
                cmdA3 = new int[MAX_COMMAND_QUEUE];
                cmdA4 = new int[MAX_COMMAND_QUEUE];
                cmdA5 = new int[MAX_COMMAND_QUEUE];
                cmdA6 = new int[MAX_COMMAND_QUEUE];
                cmdPixels = new int[MAX_COMMAND_QUEUE][];
            }
        }
    }

    private void clearQueueLocked() {
        if (cmdPixels != null && queueCount > 0) {
            int firstPart = Math.min(queueCount, MAX_COMMAND_QUEUE - queueHead);
            Arrays.fill(cmdPixels, queueHead, queueHead + firstPart, null);
            int secondPart = queueCount - firstPart;
            if (secondPart > 0) {
                Arrays.fill(cmdPixels, 0, secondPart, null);
            }
        }
        queueHead = 0;
        queueTail = 0;
        queueCount = 0;
    }

    public void configure(GpuData data) {
        if (data == null || !data.isPresent()) {
            configureHardware(false, "", 0, 0, List.of());
            return;
        }
        configureHardware(true, data.archName(), data.vramCapacity(), data.computeUnits(), data.features());
    }

    public void configureHardware(boolean present, String archName, int vramBytes, int computeUnits) {
        configureHardware(present, archName, vramBytes, computeUnits, List.of());
    }

    public void configureHardware(boolean present, String archName, int vramBytes, int computeUnits, List<String> features) {
        renderLock.lock();
        try {
            if (!present) {
                this.installed = false;
                this.archName = "";
                this.vramCapacityBytes = 0;
                this.vramCapacityInts = 0;
                this.computeUnits = 0;
                this.supportedFeatures = Set.of();
                this.vram = new int[0];
                synchronized (queueLock) {
                    clearQueueLocked();
                    this.state = State.OFF;
                }
                return;
            }
            ensureCommandBuffersAllocatedLocked();
            this.installed = true;
            this.archName = archName == null ? "" : archName;
            this.vramCapacityBytes = Math.max(0, vramBytes);
            this.vramCapacityInts = this.vramCapacityBytes / 4;
            this.computeUnits = Math.max(1, computeUnits);
            this.supportedFeatures = (features == null || features.isEmpty()) ? Set.of() : Set.copyOf(new HashSet<>(features));
            if (this.vram.length > this.vramCapacityInts) {
                this.vram = Arrays.copyOf(this.vram, this.vramCapacityInts);
            }
            synchronized (queueLock) {
                if (this.state == State.OFF) {
                    this.state = queueCount == 0 ? State.IDLE : State.RUNNING;
                }
            }
        } finally {
            renderLock.unlock();
        }
    }

    public boolean supportsFeature(String feature) {
        if (!installed) return false;
        return supportedFeatures.isEmpty() || supportedFeatures.contains(feature);
    }

    public void powerOn() {
        reset();
        synchronized (queueLock) {
            this.state = installed ? (queueCount == 0 ? State.IDLE : State.RUNNING) : State.OFF;
        }
    }

    public void powerOff() {
        renderLock.lock();
        try {
            synchronized (queueLock) {
                clearQueueLocked();
                this.state = State.OFF;
            }
            this.fbDirty = true;
        } finally {
            renderLock.unlock();
        }
    }

    public void reset() {
        renderLock.lock();
        try {
            synchronized (queueLock) {
                clearQueueLocked();
                this.state = installed ? State.IDLE : State.OFF;
            }
            this.displayMode = 0;
            Arrays.fill(this.backBuffer, DEFAULT_BACKGROUND);
            synchronized (frontBufferLock) {
                Arrays.fill(this.frontBuffer, DEFAULT_BACKGROUND);
            }
            this.commandsExecuted = 0L;
            this.gpuCycles = 0L;
            this.framesPresented = 0;
            this.fbDirty = true;
        } finally {
            renderLock.unlock();
        }
    }

    public boolean isSameConfig(GpuData data) {
        if (data == null || !data.isPresent()) {
            return !this.installed;
        }
        return isSameConfigHardware(true, data.archName(), data.vramCapacity(), data.computeUnits(), data.features());
    }

    public boolean isSameConfigHardware(boolean present, String archName, int vramBytes, int computeUnits) {
        return isSameConfigHardware(present, archName, vramBytes, computeUnits, List.of());
    }

    public boolean isSameConfigHardware(boolean present, String archName, int vramBytes, int computeUnits, List<String> features) {
        if (!present) {
            return !this.installed;
        }
        String normalizedArch = archName == null ? "" : archName;
        Set<String> targetFeatures = (features == null || features.isEmpty()) ? Set.of() : Set.copyOf(new HashSet<>(features));
        return this.installed
                && this.archName.equals(normalizedArch)
                && this.vramCapacityBytes == Math.max(0, vramBytes)
                && this.computeUnits == Math.max(1, computeUnits)
                && this.supportedFeatures.equals(targetFeatures);
    }

    public boolean isInstalled() {
        return installed;
    }

    public State getState() {
        return state;
    }

    public String getArchName() {
        return archName;
    }

    public int getVramCapacityBytes() {
        return vramCapacityBytes;
    }

    public int getVramCapacityInts() {
        return vramCapacityInts;
    }

    public int getComputeUnits() {
        return computeUnits;
    }

    public int getDisplayMode() {
        return displayMode;
    }

    public void setDisplayMode(int displayMode) {
        if (this.displayMode != displayMode) {
            this.displayMode = displayMode;
            this.fbDirty = true;
            wakeSyncWorker();
        }
    }

    public boolean isFbDirty() {
        return fbDirty;
    }

    public void setFbDirty(boolean fbDirty) {
        this.fbDirty = fbDirty;
        if (fbDirty) {
            wakeSyncWorker();
        }
    }

    public int[] getFrontBuffer() {
        return frontBuffer;
    }

    public int[] snapshotFrontBuffer() {
        synchronized (frontBufferLock) {
            return frontBuffer.clone();
        }
    }

    public int consumeFrontBuffer(int[] dest) {
        synchronized (frontBufferLock) {
            if (dest != null && dest.length >= FB_PIXELS) {
                System.arraycopy(frontBuffer, 0, dest, 0, FB_PIXELS);
            }
            fbDirty = false;
            return displayMode;
        }
    }

    public int[] getBackBuffer() {
        return backBuffer;
    }

    public long getCommandsExecuted() {
        return commandsExecuted;
    }

    public int getPendingCommands() {
        synchronized (queueLock) {
            return queueCount;
        }
    }

    public long getGpuCycles() {
        return gpuCycles;
    }

    public int getFramesPresented() {
        return framesPresented;
    }

    public boolean isJitEnabled() {
        return jitEnabled;
    }

    public void setJitEnabled(boolean jitEnabled) {
        this.jitEnabled = jitEnabled;
    }

    public boolean isJitActive() {
        return jitEnabled && GpuJitCompiler.getSharedPipeline() != null;
    }

    public int getJitTracesCompiled() {
        return jitTracesCompiled;
    }

    public long getJitTraceHits() {
        return jitTraceHits;
    }

    void recordJitTraceCompiled() {
        this.jitTracesCompiled++;
    }

    public boolean isBusy() {
        if (processingBatch) {
            return true;
        }
        synchronized (queueLock) {
            return queueCount > 0;
        }
    }

    public boolean hasPendingWork() {
        if (!installed || state == State.OFF) {
            return false;
        }
        synchronized (queueLock) {
            return queueCount > 0;
        }
    }

    public void attachWorkerThread(Thread worker) {
        this.workerThread = worker;
    }

    public void detachWorkerThread(Thread worker) {
        if (this.workerThread == worker) {
            this.workerThread = null;
        }
    }

    private void wakeWorker() {
        Thread worker = this.workerThread;
        if (worker != null) {
            LockSupport.unpark(worker);
        }
    }

    public void attachSyncWorker(Thread syncWorker) {
        this.syncWorkerThread = syncWorker;
    }

    public void detachSyncWorker(Thread syncWorker) {
        if (this.syncWorkerThread == syncWorker) {
            this.syncWorkerThread = null;
        }
    }

    public void wakeSyncWorker() {
        Thread syncWorker = this.syncWorkerThread;
        if (syncWorker != null) {
            LockSupport.unpark(syncWorker);
        }
    }

    public int getBatchBudget() {
        return BASE_OPS_PER_SLICE + computeUnits * OPS_PER_COMPUTE_UNIT;
    }

    private void enqueueRaw(byte kind, int a0, int a1, int a2, int a3, int a4, int a5, int a6, int[] pixels) {
        if (!installed) {
            throw new IllegalStateException("No GPU installed");
        }
        boolean enqueued = false;
        synchronized (queueLock) {
            if (cmdKind != null && queueCount < MAX_COMMAND_QUEUE) {
                int slot = queueTail;
                cmdKind[slot] = kind;
                cmdA0[slot] = a0;
                cmdA1[slot] = a1;
                cmdA2[slot] = a2;
                cmdA3[slot] = a3;
                cmdA4[slot] = a4;
                cmdA5[slot] = a5;
                cmdA6[slot] = a6;
                cmdPixels[slot] = pixels;
                queueTail = (slot + 1) & QUEUE_MASK;
                queueCount++;
                state = State.RUNNING;
                enqueued = true;
            }
        }
        if (!enqueued) {
            flush();
            synchronized (queueLock) {
                int slot = queueTail;
                cmdKind[slot] = kind;
                cmdA0[slot] = a0;
                cmdA1[slot] = a1;
                cmdA2[slot] = a2;
                cmdA3[slot] = a3;
                cmdA4[slot] = a4;
                cmdA5[slot] = a5;
                cmdA6[slot] = a6;
                cmdPixels[slot] = pixels;
                queueTail = (slot + 1) & QUEUE_MASK;
                queueCount++;
                state = State.RUNNING;
            }
        }
        wakeWorker();
    }

    public void submitClear(int color) {
        enqueueRaw(CMD_CLEAR, normalizeOpaqueColor(color), 0, 0, 0, 0, 0, 0, null);
    }

    public void submitSetPixel(int x, int y, int color) {
        enqueueRaw(CMD_SET_PIXEL, x, y, normalizeOpaqueColor(color), 0, 0, 0, 0, null);
    }

    public void submitFillRect(int x, int y, int w, int h, int color) {
        enqueueRaw(CMD_FILL_RECT, x, y, w, h, normalizeOpaqueColor(color), 0, 0, null);
    }

    public void submitDrawLine(int x0, int y0, int x1, int y1, int color) {
        enqueueRaw(CMD_DRAW_LINE, x0, y0, x1, y1, normalizeOpaqueColor(color), 0, 0, null);
    }

    public void submitDrawCircle(int cx, int cy, int r, int color, boolean filled) {
        enqueueRaw(CMD_DRAW_CIRCLE, cx, cy, r, normalizeOpaqueColor(color), filled ? 1 : 0, 0, 0, null);
    }

    public void submitFillTriangle(int x0, int y0, int x1, int y1, int x2, int y2, int color) {
        enqueueRaw(CMD_FILL_TRIANGLE, x0, y0, x1, y1, x2, y2, normalizeOpaqueColor(color), null);
    }

    public void submitBlitFromRam(int dx, int dy, int[] ram, int ramSrc, int w, int h, boolean alphaBlend) {
        if (!installed) {
            throw new IllegalStateException("No GPU installed");
        }
        if (w <= 0 || h <= 0) return;
        int total = Math.multiplyExact(w, h);
        int[] copy;
        if (ramSrc >= 0 && (long) ramSrc + total <= ram.length) {
            if (alphaBlend) {
                copy = Arrays.copyOfRange(ram, ramSrc, ramSrc + total);
            } else {
                copy = new int[total];
                for (int i = 0; i < total; i++) {
                    copy[i] = normalizeOpaqueColor(ram[ramSrc + i]);
                }
            }
        } else {
            copy = new int[total];
            for (int i = 0; i < total; i++) {
                int idx = ramSrc + i;
                if (idx >= 0 && idx < ram.length) {
                    copy[i] = alphaBlend ? ram[idx] : normalizeOpaqueColor(ram[idx]);
                } else if (!alphaBlend) {
                    copy[i] = 0xFF000000;
                }
            }
        }
        enqueueRaw(CMD_BLIT_PIXELS, dx, dy, w, h, alphaBlend ? 1 : 0, 0, 0, copy);
    }

    public void uploadVram(int vramOffset, int[] ram, int ramSrc, int count) {
        if (!installed) {
            throw new IllegalStateException("No GPU installed");
        }
        if (count < 0 || vramOffset < 0 || ramSrc < 0) {
            throw new IndexOutOfBoundsException("Negative VRAM/RAM upload offset or count");
        }
        if (count == 0) return;
        if ((long) vramOffset + count > vramCapacityInts) {
            throw new IndexOutOfBoundsException("VRAM upload out of bounds: offset=" + vramOffset + ", count=" + count + ", capacityInts=" + vramCapacityInts);
        }
        if ((long) ramSrc + count > ram.length) {
            throw new IndexOutOfBoundsException("CPU RAM source out of bounds for GPUVRAM upload");
        }
        renderLock.lock();
        try {
            ensureVramAllocated(vramOffset + count);
            System.arraycopy(ram, ramSrc, vram, vramOffset, count);
            gpuCycles += Math.max(1, count / 64);
        } finally {
            renderLock.unlock();
        }
    }

    public void submitBlitFromVram(int dx, int dy, int vramOffset, int w, int h, boolean alphaBlend) {
        if (!installed) {
            throw new IllegalStateException("No GPU installed");
        }
        if (w <= 0 || h <= 0) return;
        if (vramOffset < 0 || (long) vramOffset + (long) w * h > vramCapacityInts) {
            throw new IndexOutOfBoundsException("GPUVRAM blit out of bounds: offset=" + vramOffset);
        }
        enqueueRaw(CMD_BLIT_VRAM, dx, dy, vramOffset, w, h, alphaBlend ? 1 : 0, 0, null);
    }

    public void submitSwap() {
        enqueueRaw(CMD_SWAP, 0, 0, 0, 0, 0, 0, 0, null);
        flush();
    }

    public int getBackPixel(int x, int y) {
        renderLock.lock();
        try {
            flushLocked();
            if (x >= 0 && x < FB_WIDTH && y >= 0 && y < FB_HEIGHT) {
                return backBuffer[y * FB_WIDTH + x];
            }
            return 0;
        } finally {
            renderLock.unlock();
        }
    }

    public int getFrontPixel(int x, int y) {
        flush();
        if (x >= 0 && x < FB_WIDTH && y >= 0 && y < FB_HEIGHT) {
            synchronized (frontBufferLock) {
                return frontBuffer[y * FB_WIDTH + x];
            }
        }
        return 0;
    }

    public int readVramInt(int offset) {
        if (!installed || offset < 0 || offset >= vramCapacityInts) return 0;
        renderLock.lock();
        try {
            if (offset >= vram.length) return 0;
            return vram[offset];
        } finally {
            renderLock.unlock();
        }
    }

    public int tick(int maxOps) {
        if (!installed || state == State.OFF || maxOps <= 0) {
            return 0;
        }
        renderLock.lock();
        try {
            return executeBatchLocked(maxOps);
        } finally {
            renderLock.unlock();
        }
    }

    /**
     * Non-blocking tick used on CPU worker ticks: if a dedicated GPU worker is already actively
     * executing a batch, returns immediately instead of stalling the CPU thread.
     */
    public int tryTick(int maxOps) {
        if (!installed || state == State.OFF || maxOps <= 0) {
            return 0;
        }
        synchronized (queueLock) {
            if (queueCount == 0) {
                if (state == State.RUNNING) {
                    state = State.IDLE;
                }
                return 0;
            }
        }
        if (!renderLock.tryLock()) {
            return 0;
        }
        try {
            return executeBatchLocked(maxOps);
        } finally {
            renderLock.unlock();
        }
    }

    public void flush() {
        if (!installed) {
            return;
        }
        renderLock.lock();
        try {
            flushLocked();
        } finally {
            renderLock.unlock();
        }
    }

    private int executeBatchLocked(int maxOps) {
        if (!installed || state == State.OFF || batchKind == null) {
            return 0;
        }
        int toCopy;
        synchronized (queueLock) {
            if (queueCount == 0) {
                if (state == State.RUNNING) {
                    state = State.IDLE;
                }
                return 0;
            }
            toCopy = Math.min(maxOps, queueCount);
            int firstPart = Math.min(toCopy, MAX_COMMAND_QUEUE - queueHead);
            int secondPart = toCopy - firstPart;

            System.arraycopy(cmdKind, queueHead, batchKind, 0, firstPart);
            System.arraycopy(cmdA0, queueHead, batchA0, 0, firstPart);
            System.arraycopy(cmdA1, queueHead, batchA1, 0, firstPart);
            System.arraycopy(cmdA2, queueHead, batchA2, 0, firstPart);
            System.arraycopy(cmdA3, queueHead, batchA3, 0, firstPart);
            System.arraycopy(cmdA4, queueHead, batchA4, 0, firstPart);
            System.arraycopy(cmdA5, queueHead, batchA5, 0, firstPart);
            System.arraycopy(cmdA6, queueHead, batchA6, 0, firstPart);
            System.arraycopy(cmdPixels, queueHead, batchPixels, 0, firstPart);
            Arrays.fill(cmdPixels, queueHead, queueHead + firstPart, null);

            if (secondPart > 0) {
                System.arraycopy(cmdKind, 0, batchKind, firstPart, secondPart);
                System.arraycopy(cmdA0, 0, batchA0, firstPart, secondPart);
                System.arraycopy(cmdA1, 0, batchA1, firstPart, secondPart);
                System.arraycopy(cmdA2, 0, batchA2, firstPart, secondPart);
                System.arraycopy(cmdA3, 0, batchA3, firstPart, secondPart);
                System.arraycopy(cmdA4, 0, batchA4, firstPart, secondPart);
                System.arraycopy(cmdA5, 0, batchA5, firstPart, secondPart);
                System.arraycopy(cmdA6, 0, batchA6, firstPart, secondPart);
                System.arraycopy(cmdPixels, 0, batchPixels, firstPart, secondPart);
                Arrays.fill(cmdPixels, 0, secondPart, null);
            }

            queueHead = (queueHead + toCopy) & QUEUE_MASK;
            queueCount -= toCopy;
            processingBatch = true;
        }

        try {
            if (jitEnabled) {
                executeBatchJitLocked(toCopy);
            } else {
                for (int i = 0; i < toCopy; i++) {
                    executeCommandAt(i);
                }
            }
            commandsExecuted += toCopy;
            gpuCycles += toCopy;
        } finally {
            synchronized (queueLock) {
                processingBatch = false;
                if (installed && state != State.OFF) {
                    state = queueCount == 0 ? State.IDLE : State.RUNNING;
                }
            }
        }
        return toCopy;
    }

    private void executeBatchJitLocked(int count) {
        GpuJitCompiler.CompiledGpuPipeline pipeline = GpuJitCompiler.getSharedPipeline();
        int[] currentVram = this.vram;
        int cursor = 0;

        while (cursor < count) {
            int segEnd = cursor;
            while (segEnd < count && batchKind[segEnd] != CMD_SWAP) {
                segEnd++;
            }
            if (segEnd < count && batchKind[segEnd] == CMD_SWAP) {
                segEnd++;
            }
            int segLen = segEnd - cursor;
            if (segLen >= GpuJitCompiler.MIN_TRACE_LENGTH && segLen <= GpuJitCompiler.MAX_TRACE_LENGTH) {
                GpuJitCompiler.TraceEntry entry = traceCache.lookupOrTrack(batchKind, cursor, segLen, this);
                if (entry.compiled != null) {
                    entry.compiled.executeTrace(
                            this, cursor, batchA0, batchA1, batchA2, batchA3, batchA4, batchA5, batchA6,
                            batchPixels, backBuffer, currentVram
                    );
                    jitTraceHits++;
                } else {
                    pipeline.executeBatch(
                            this, cursor, segLen, batchKind, batchA0, batchA1, batchA2, batchA3, batchA4, batchA5, batchA6,
                            batchPixels, backBuffer, currentVram
                    );
                }
            } else {
                pipeline.executeBatch(
                        this, cursor, segLen, batchKind, batchA0, batchA1, batchA2, batchA3, batchA4, batchA5, batchA6,
                        batchPixels, backBuffer, currentVram
                );
            }
            cursor = segEnd;
        }
    }

    private void executeCommandAt(int i) {
        switch (batchKind[i]) {
            case CMD_CLEAR -> Arrays.fill(backBuffer, batchA0[i]);
            case CMD_SET_PIXEL -> {
                int x = batchA0[i];
                int y = batchA1[i];
                if (x >= 0 && x < FB_WIDTH && y >= 0 && y < FB_HEIGHT) {
                    backBuffer[y * FB_WIDTH + x] = batchA2[i];
                }
            }
            case CMD_FILL_RECT -> jitRasterizeFillRect(batchA0[i], batchA1[i], batchA2[i], batchA3[i], batchA4[i], backBuffer);
            case CMD_DRAW_LINE -> jitRasterizeLine(batchA0[i], batchA1[i], batchA2[i], batchA3[i], batchA4[i], backBuffer);
            case CMD_DRAW_CIRCLE -> jitRasterizeCircle(batchA0[i], batchA1[i], batchA2[i], batchA3[i], batchA4[i], backBuffer);
            case CMD_FILL_TRIANGLE -> jitRasterizeTriangle(batchA0[i], batchA1[i], batchA2[i], batchA3[i], batchA4[i], batchA5[i], batchA6[i], backBuffer);
            case CMD_BLIT_PIXELS -> {
                int[] pixels = batchPixels[i];
                batchPixels[i] = null;
                if (pixels != null) {
                    jitRasterizeBlitPixels(batchA0[i], batchA1[i], pixels, batchA2[i], batchA3[i], batchA4[i], backBuffer);
                }
            }
            case CMD_BLIT_VRAM -> jitRasterizeBlitVram(batchA0[i], batchA1[i], batchA2[i], batchA3[i], batchA4[i], batchA5[i], vram, backBuffer);
            case CMD_SWAP -> jitSwapBuffers();
            default -> {
            }
        }
    }

    private void flushLocked() {
        if (!installed) {
            return;
        }
        while (true) {
            int drained = executeBatchLocked(MAX_COMMAND_QUEUE);
            synchronized (queueLock) {
                if (queueCount == 0) {
                    if (state == State.RUNNING) {
                        state = State.IDLE;
                    }
                    break;
                }
            }
            if (drained == 0) {
                break;
            }
        }
    }

    private void ensureVramAllocated(int requiredSize) {
        if (requiredSize <= vram.length) return;
        int capped = Math.min(vramCapacityInts, Math.max(requiredSize, Math.min(vramCapacityInts, Math.max(4096, vram.length * 2))));
        vram = Arrays.copyOf(vram, capped);
    }

    private static int normalizeOpaqueColor(int color) {
        return (color & 0xFF000000) == 0 ? (color | 0xFF000000) : color;
    }

    void jitSwapBuffers() {
        synchronized (frontBufferLock) {
            System.arraycopy(backBuffer, 0, frontBuffer, 0, FB_PIXELS);
            framesPresented++;
            displayMode = 1;
            fbDirty = true;
        }
        wakeSyncWorker();
    }

    private static void plot(int[] backBuffer, int x, int y, int color) {
        if (x >= 0 && x < FB_WIDTH && y >= 0 && y < FB_HEIGHT) {
            backBuffer[y * FB_WIDTH + x] = color;
        }
    }

    private static void drawHorizontalSpan(int[] backBuffer, int xStart, int xEnd, int y, int color) {
        if (y < 0 || y >= FB_HEIGHT) return;
        int xs = Math.max(0, xStart);
        int xe = Math.min(FB_WIDTH - 1, xEnd);
        if (xs <= xe) {
            int row = y * FB_WIDTH;
            Arrays.fill(backBuffer, row + xs, row + xe + 1, color);
        }
    }

    static void jitRasterizeFillRect(int rx, int ry, int rw, int rh, int color, int[] backBuffer) {
        if (rw <= 0 || rh <= 0) return;
        int xStart = Math.max(0, rx);
        int xEnd = Math.min(FB_WIDTH, rx + rw);
        int yStart = Math.max(0, ry);
        int yEnd = Math.min(FB_HEIGHT, ry + rh);
        if (xEnd > xStart && yEnd > yStart) {
            for (int y = yStart; y < yEnd; y++) {
                int rowOffset = y * FB_WIDTH;
                Arrays.fill(backBuffer, rowOffset + xStart, rowOffset + xEnd, color);
            }
        }
    }

    static void jitRasterizeLine(int x0, int y0, int x1, int y1, int color, int[] backBuffer) {
        long dx = Math.abs((long) x1 - x0);
        long dy = Math.abs((long) y1 - y0);
        boolean xMajor = dx >= dy;
        long majorLength = xMajor ? dx : dy;
        long minorLength = xMajor ? dy : dx;
        long majorStart = xMajor ? x0 : y0;
        long minorStart = xMajor ? y0 : x0;
        int majorDirection = (xMajor ? x0 <= x1 : y0 <= y1) ? 1 : -1;
        int minorDirection = (xMajor ? y0 <= y1 : x0 <= x1) ? 1 : -1;
        int majorLimit = (xMajor ? FB_WIDTH : FB_HEIGHT) - 1;

        long firstStep = Math.max(0, majorDirection > 0 ? -majorStart : majorStart - majorLimit);
        long lastStep = Math.min(majorLength, majorDirection > 0 ? majorLimit - majorStart : majorStart);
        if (firstStep > lastStep) return;

        if (majorLength == 0) {
            int ix = (int) (xMajor ? majorStart : minorStart);
            int iy = (int) (xMajor ? minorStart : majorStart);
            if (ix >= 0 && ix < FB_WIDTH && iy >= 0 && iy < FB_HEIGHT) {
                backBuffer[iy * FB_WIDTH + ix] = color;
            }
            return;
        }

        if (firstStep <= 1_000_000L && minorLength <= 1_000_000L) {
            long initNum = firstStep * minorLength + (majorLength - 1) / 2;
            long minorStep = initNum / majorLength;
            long err = initNum % majorLength;
            long major = majorStart + firstStep * majorDirection;
            long minor = minorStart + minorStep * minorDirection;

            for (long step = firstStep; step <= lastStep; step++) {
                int ix = (int) (xMajor ? major : minor);
                int iy = (int) (xMajor ? minor : major);
                if (ix >= 0 && ix < FB_WIDTH && iy >= 0 && iy < FB_HEIGHT) {
                    backBuffer[iy * FB_WIDTH + ix] = color;
                }
                major += majorDirection;
                err += minorLength;
                if (err >= majorLength) {
                    err -= majorLength;
                    minor += minorDirection;
                }
            }
            return;
        }

        for (long step = firstStep; step <= lastStep; step++) {
            long minorStep = Long.divideUnsigned(step * minorLength + (majorLength - 1) / 2, majorLength);
            long major = majorStart + step * majorDirection;
            long minor = minorStart + minorStep * minorDirection;
            long x = xMajor ? major : minor;
            long y = xMajor ? minor : major;
            if (x >= 0 && x < FB_WIDTH && y >= 0 && y < FB_HEIGHT) {
                backBuffer[(int) y * FB_WIDTH + (int) x] = color;
            }
        }
    }

    static void jitRasterizeCircle(int cx, int cy, int r, int color, int filledInt, int[] backBuffer) {
        if (r < 0) return;
        boolean filled = filledInt != 0;
        if (r == 0) {
            plot(backBuffer, cx, cy, color);
            return;
        }
        int x = 0;
        int y = r;
        int d = 3 - 2 * r;
        while (y >= x) {
            if (filled) {
                drawHorizontalSpan(backBuffer, cx - y, cx + y, cy + x, color);
                drawHorizontalSpan(backBuffer, cx - y, cx + y, cy - x, color);
                drawHorizontalSpan(backBuffer, cx - x, cx + x, cy + y, color);
                drawHorizontalSpan(backBuffer, cx - x, cx + x, cy - y, color);
            } else {
                plot(backBuffer, cx + x, cy + y, color);
                plot(backBuffer, cx - x, cy + y, color);
                plot(backBuffer, cx + x, cy - y, color);
                plot(backBuffer, cx - x, cy - y, color);
                plot(backBuffer, cx + y, cy + x, color);
                plot(backBuffer, cx - y, cy + x, color);
                plot(backBuffer, cx + y, cy - x, color);
                plot(backBuffer, cx - y, cy - x, color);
            }
            if (d < 0) {
                d = d + 4 * x + 6;
            } else {
                d = d + 4 * (x - y) + 10;
                y--;
            }
            x++;
        }
    }

    static void jitRasterizeTriangle(int x0, int y0, int x1, int y1, int x2, int y2, int color, int[] backBuffer) {
        if (y0 > y1) { int t = y0; y0 = y1; y1 = t; t = x0; x0 = x1; x1 = t; }
        if (y1 > y2) { int t = y1; y1 = y2; y2 = t; t = x1; x1 = x2; x2 = t; }
        if (y0 > y1) { int t = y0; y0 = y1; y1 = t; t = x0; x0 = x1; x1 = t; }

        int totalHeight = y2 - y0;
        if (totalHeight == 0) {
            int minX = Math.min(x0, Math.min(x1, x2));
            int maxX = Math.max(x0, Math.max(x1, x2));
            drawHorizontalSpan(backBuffer, minX, maxX, y0, color);
            return;
        }

        int yMin = Math.max(0, y0);
        int yMax = Math.min(FB_HEIGHT - 1, y2);
        for (int y = yMin; y <= yMax; y++) {
            boolean secondHalf = y > y1 || y1 == y0;
            int segHeight = secondHalf ? (y2 - y1) : (y1 - y0);
            if (segHeight == 0) segHeight = 1;
            long alphaNum = y - y0;
            long betaNum = secondHalf ? (y - y1) : (y - y0);

            int ax = (int) (x0 + ((long) (x2 - x0) * alphaNum) / totalHeight);
            int bx = secondHalf
                    ? (int) (x1 + ((long) (x2 - x1) * betaNum) / segHeight)
                    : (int) (x0 + ((long) (x1 - x0) * betaNum) / segHeight);
            if (ax > bx) {
                int t = ax;
                ax = bx;
                bx = t;
            }
            int xs = Math.max(0, ax);
            int xe = Math.min(FB_WIDTH - 1, bx);
            if (xs <= xe) {
                int row = y * FB_WIDTH;
                Arrays.fill(backBuffer, row + xs, row + xe + 1, color);
            }
        }
    }

    static void jitRasterizeBlitPixels(int dx, int dy, int[] src, int w, int h, int alphaBlendInt, int[] backBuffer) {
        if (src == null) return;
        boolean alphaBlend = alphaBlendInt != 0;
        rasterizeBlitArray(dx, dy, src, 0, w, h, alphaBlend, !alphaBlend, backBuffer);
    }

    static void jitRasterizeBlitVram(int dx, int dy, int vramOffset, int w, int h, int alphaBlendInt, int[] vram, int[] backBuffer) {
        if (vram != null && vramOffset < vram.length) {
            rasterizeBlitArray(dx, dy, vram, vramOffset, w, h, alphaBlendInt != 0, false, backBuffer);
        }
    }

    private static void rasterizeBlitArray(int dx, int dy, int[] src, int baseOffset, int w, int h, boolean alphaBlend, boolean srcOpaqueNormalized, int[] backBuffer) {
        if (w <= 0 || h <= 0) return;
        int pyStart = Math.max(0, -dy);
        int pyEnd = Math.min(h, FB_HEIGHT - dy);
        int pxStart = Math.max(0, -dx);
        int pxEnd = Math.min(w, FB_WIDTH - dx);
        if (pyStart >= pyEnd || pxStart >= pxEnd) return;

        int spanWidth = pxEnd - pxStart;
        boolean fastInBounds = baseOffset >= 0 && (long) baseOffset + (long) w * h <= src.length;

        if (!alphaBlend && fastInBounds) {
            if (srcOpaqueNormalized) {
                for (int py = pyStart; py < pyEnd; py++) {
                    int srcRow = baseOffset + py * w + pxStart;
                    int dstRow = (dy + py) * FB_WIDTH + dx + pxStart;
                    System.arraycopy(src, srcRow, backBuffer, dstRow, spanWidth);
                }
            } else {
                for (int py = pyStart; py < pyEnd; py++) {
                    int srcRow = baseOffset + py * w;
                    int dstRow = (dy + py) * FB_WIDTH + dx;
                    for (int px = pxStart; px < pxEnd; px++) {
                        backBuffer[dstRow + px] = normalizeOpaqueColor(src[srcRow + px]);
                    }
                }
            }
            return;
        }

        if (alphaBlend && fastInBounds) {
            for (int py = pyStart; py < pyEnd; py++) {
                int srcRow = baseOffset + py * w;
                int dstRow = (dy + py) * FB_WIDTH + dx;
                for (int px = pxStart; px < pxEnd; px++) {
                    int c = src[srcRow + px];
                    int a = (c >>> 24) & 0xFF;
                    if (a == 0) continue;
                    int dstIdx = dstRow + px;
                    if (a == 255) {
                        backBuffer[dstIdx] = c;
                    } else {
                        int dst = backBuffer[dstIdx];
                        int invA = 255 - a;
                        int r = (((c >> 16) & 0xFF) * a + ((dst >> 16) & 0xFF) * invA) / 255;
                        int g = (((c >> 8) & 0xFF) * a + ((dst >> 8) & 0xFF) * invA) / 255;
                        int b = ((c & 0xFF) * a + (dst & 0xFF) * invA) / 255;
                        backBuffer[dstIdx] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                }
            }
            return;
        }

        for (int py = pyStart; py < pyEnd; py++) {
            int rowStart = (dy + py) * FB_WIDTH;
            int srcRow = baseOffset + py * w;
            for (int px = pxStart; px < pxEnd; px++) {
                int idx = srcRow + px;
                if (idx < 0 || idx >= src.length) continue;
                int c = src[idx];
                int dstIdx = rowStart + dx + px;
                if (!alphaBlend) {
                    backBuffer[dstIdx] = normalizeOpaqueColor(c);
                } else {
                    int a = (c >>> 24) & 0xFF;
                    if (a == 0) continue;
                    if (a == 255) {
                        backBuffer[dstIdx] = c;
                    } else {
                        int dst = backBuffer[dstIdx];
                        int invA = 255 - a;
                        int r = (((c >> 16) & 0xFF) * a + ((dst >> 16) & 0xFF) * invA) / 255;
                        int g = (((c >> 8) & 0xFF) * a + ((dst >> 8) & 0xFF) * invA) / 255;
                        int b = ((c & 0xFF) * a + (dst & 0xFF) * invA) / 255;
                        backBuffer[dstIdx] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                }
            }
        }
    }
}
