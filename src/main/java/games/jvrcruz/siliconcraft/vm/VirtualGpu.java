package games.jvrcruz.siliconcraft.vm;

import games.jvrcruz.siliconcraft.data.GpuData;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dedicated virtual graphics processor executing hardware-accelerated rasterization,
 * VRAM sprite storage/blitting, and double-buffered presentation alongside {@link VirtualCpu}.
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
    public static final int MAX_COMMAND_QUEUE = 4096;
    public static final int BASE_OPS_PER_SLICE = 256;

    private volatile State state = State.OFF;
    private volatile boolean installed = false;
    private volatile boolean fbDirty = false;
    private volatile int displayMode = 0;

    private String archName = "";
    private int vramCapacityBytes = 0;
    private int vramCapacityInts = 0;
    private int computeUnits = 0;
    private Set<String> supportedFeatures = Set.of();
    private int[] vram = new int[0];

    private final int[] backBuffer = new int[FB_PIXELS];
    private final int[] frontBuffer = new int[FB_PIXELS];
    private final Deque<GpuCommand> commandQueue = new ArrayDeque<>();

    private long commandsExecuted = 0L;
    private long gpuCycles = 0L;
    private int framesPresented = 0;

    private sealed interface GpuCommand {
        record Clear(int color) implements GpuCommand {}
        record SetPixel(int x, int y, int color) implements GpuCommand {}
        record FillRect(int x, int y, int w, int h, int color) implements GpuCommand {}
        record DrawLine(int x0, int y0, int x1, int y1, int color) implements GpuCommand {}
        record DrawCircle(int cx, int cy, int r, int color, boolean filled) implements GpuCommand {}
        record FillTriangle(int x0, int y0, int x1, int y1, int x2, int y2, int color) implements GpuCommand {}
        record BlitPixels(int dx, int dy, int[] pixels, int w, int h, boolean alphaBlend) implements GpuCommand {}
        record BlitVram(int dx, int dy, int vramOffset, int w, int h, boolean alphaBlend) implements GpuCommand {}
        record Swap() implements GpuCommand {}
    }

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
        if (!present) {
            this.installed = false;
            this.archName = "";
            this.vramCapacityBytes = 0;
            this.vramCapacityInts = 0;
            this.computeUnits = 0;
            this.supportedFeatures = Set.of();
            this.vram = new int[0];
            this.commandQueue.clear();
            this.state = State.OFF;
            return;
        }
        this.installed = true;
        this.archName = archName == null ? "" : archName;
        this.vramCapacityBytes = Math.max(0, vramBytes);
        this.vramCapacityInts = this.vramCapacityBytes / 4;
        this.computeUnits = Math.max(1, computeUnits);
        this.supportedFeatures = (features == null || features.isEmpty()) ? Set.of() : Set.copyOf(new HashSet<>(features));
        if (this.vram.length > this.vramCapacityInts) {
            this.vram = Arrays.copyOf(this.vram, this.vramCapacityInts);
        }
        if (this.state == State.OFF) {
            this.state = State.IDLE;
        }
    }

    public boolean supportsFeature(String feature) {
        if (!installed) return false;
        return supportedFeatures.isEmpty() || supportedFeatures.contains(feature);
    }

    public void powerOn() {
        reset();
        this.state = installed ? State.IDLE : State.OFF;
    }

    public void powerOff() {
        this.commandQueue.clear();
        this.state = State.OFF;
        this.fbDirty = true;
    }

    public void reset() {
        this.commandQueue.clear();
        this.displayMode = 0;
        Arrays.fill(this.backBuffer, DEFAULT_BACKGROUND);
        Arrays.fill(this.frontBuffer, DEFAULT_BACKGROUND);
        this.commandsExecuted = 0L;
        this.gpuCycles = 0L;
        this.framesPresented = 0;
        this.fbDirty = true;
        this.state = installed ? State.IDLE : State.OFF;
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
        }
    }

    public boolean isFbDirty() {
        return fbDirty;
    }

    public void setFbDirty(boolean fbDirty) {
        this.fbDirty = fbDirty;
    }

    public int[] getFrontBuffer() {
        return frontBuffer;
    }

    public int[] getBackBuffer() {
        return backBuffer;
    }

    public long getCommandsExecuted() {
        return commandsExecuted;
    }

    public int getPendingCommands() {
        return commandQueue.size();
    }

    public long getGpuCycles() {
        return gpuCycles;
    }

    public int getFramesPresented() {
        return framesPresented;
    }

    public boolean isBusy() {
        return !commandQueue.isEmpty();
    }

    public int getBatchBudget() {
        return BASE_OPS_PER_SLICE + computeUnits * 64;
    }

    private void enqueue(GpuCommand cmd) {
        if (!installed) {
            throw new IllegalStateException("No GPU installed");
        }
        if (commandQueue.size() >= MAX_COMMAND_QUEUE) {
            flush();
        }
        commandQueue.addLast(cmd);
        state = State.RUNNING;
    }

    public void submitClear(int color) {
        enqueue(new GpuCommand.Clear(normalizeOpaqueColor(color)));
    }

    public void submitSetPixel(int x, int y, int color) {
        enqueue(new GpuCommand.SetPixel(x, y, normalizeOpaqueColor(color)));
    }

    public void submitFillRect(int x, int y, int w, int h, int color) {
        enqueue(new GpuCommand.FillRect(x, y, w, h, normalizeOpaqueColor(color)));
    }

    public void submitDrawLine(int x0, int y0, int x1, int y1, int color) {
        enqueue(new GpuCommand.DrawLine(x0, y0, x1, y1, normalizeOpaqueColor(color)));
    }

    public void submitDrawCircle(int cx, int cy, int r, int color, boolean filled) {
        enqueue(new GpuCommand.DrawCircle(cx, cy, r, normalizeOpaqueColor(color), filled));
    }

    public void submitFillTriangle(int x0, int y0, int x1, int y1, int x2, int y2, int color) {
        enqueue(new GpuCommand.FillTriangle(x0, y0, x1, y1, x2, y2, normalizeOpaqueColor(color)));
    }

    public void submitBlitFromRam(int dx, int dy, int[] ram, int ramSrc, int w, int h, boolean alphaBlend) {
        if (!installed) {
            throw new IllegalStateException("No GPU installed");
        }
        if (w <= 0 || h <= 0) return;
        int total = Math.multiplyExact(w, h);
        int[] copy = new int[total];
        for (int i = 0; i < total; i++) {
            int idx = ramSrc + i;
            if (idx >= 0 && idx < ram.length) {
                copy[i] = alphaBlend ? ram[idx] : normalizeOpaqueColor(ram[idx]);
            }
        }
        enqueue(new GpuCommand.BlitPixels(dx, dy, copy, w, h, alphaBlend));
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
        ensureVramAllocated(vramOffset + count);
        System.arraycopy(ram, ramSrc, vram, vramOffset, count);
        gpuCycles += Math.max(1, count / 64);
    }

    public void submitBlitFromVram(int dx, int dy, int vramOffset, int w, int h, boolean alphaBlend) {
        if (!installed) {
            throw new IllegalStateException("No GPU installed");
        }
        if (w <= 0 || h <= 0) return;
        if (vramOffset < 0 || (long) vramOffset + (long) w * h > vramCapacityInts) {
            throw new IndexOutOfBoundsException("GPUVRAM blit out of bounds: offset=" + vramOffset);
        }
        enqueue(new GpuCommand.BlitVram(dx, dy, vramOffset, w, h, alphaBlend));
    }

    public void submitSwap() {
        enqueue(new GpuCommand.Swap());
        flush();
    }

    public int getBackPixel(int x, int y) {
        flush();
        if (x >= 0 && x < FB_WIDTH && y >= 0 && y < FB_HEIGHT) {
            return backBuffer[y * FB_WIDTH + x];
        }
        return 0;
    }

    public int getFrontPixel(int x, int y) {
        flush();
        if (x >= 0 && x < FB_WIDTH && y >= 0 && y < FB_HEIGHT) {
            return frontBuffer[y * FB_WIDTH + x];
        }
        return 0;
    }

    public int readVramInt(int offset) {
        if (!installed || offset < 0 || offset >= vramCapacityInts) return 0;
        if (offset >= vram.length) return 0;
        return vram[offset];
    }

    public int tick(int maxOps) {
        if (!installed || state == State.OFF) {
            return 0;
        }
        int processed = 0;
        while (processed < maxOps && !commandQueue.isEmpty()) {
            GpuCommand cmd = commandQueue.pollFirst();
            executeCommand(cmd);
            processed++;
            commandsExecuted++;
            gpuCycles++;
        }
        if (commandQueue.isEmpty()) {
            state = State.IDLE;
        }
        return processed;
    }

    public void flush() {
        if (!installed || commandQueue.isEmpty()) {
            if (installed && state == State.RUNNING) {
                state = State.IDLE;
            }
            return;
        }
        while (!commandQueue.isEmpty()) {
            GpuCommand cmd = commandQueue.pollFirst();
            executeCommand(cmd);
            commandsExecuted++;
            gpuCycles++;
        }
        state = State.IDLE;
    }

    private void ensureVramAllocated(int requiredSize) {
        if (requiredSize <= vram.length) return;
        int capped = Math.min(vramCapacityInts, Math.max(requiredSize, Math.min(vramCapacityInts, Math.max(4096, vram.length * 2))));
        vram = Arrays.copyOf(vram, capped);
    }

    private static int normalizeOpaqueColor(int color) {
        return (color & 0xFF000000) == 0 ? (color | 0xFF000000) : color;
    }

    private void executeCommand(GpuCommand cmd) {
        switch (cmd) {
            case GpuCommand.Clear c -> {
                Arrays.fill(backBuffer, c.color());
            }
            case GpuCommand.SetPixel p -> {
                if (p.x() >= 0 && p.x() < FB_WIDTH && p.y() >= 0 && p.y < FB_HEIGHT) {
                    backBuffer[p.y() * FB_WIDTH + p.x()] = p.color();
                }
            }
            case GpuCommand.FillRect r -> rasterizeFillRect(r.x(), r.y(), r.w(), r.h(), r.color());
            case GpuCommand.DrawLine l -> rasterizeLine(l.x0(), l.y0(), l.x1(), l.y1(), l.color());
            case GpuCommand.DrawCircle c -> rasterizeCircle(c.cx(), c.cy(), c.r(), c.color(), c.filled());
            case GpuCommand.FillTriangle t -> rasterizeTriangle(t.x0(), t.y0(), t.x1(), t.y1(), t.x2(), t.y2(), t.color());
            case GpuCommand.BlitPixels b -> rasterizeBlitArray(b.dx(), b.dy(), b.pixels(), 0, b.w(), b.h(), b.alphaBlend());
            case GpuCommand.BlitVram v -> {
                if (v.vramOffset() < vram.length) {
                    rasterizeBlitArray(v.dx(), v.dy(), vram, v.vramOffset(), v.w(), v.h(), v.alphaBlend());
                }
            }
            case GpuCommand.Swap s -> {
                System.arraycopy(backBuffer, 0, frontBuffer, 0, FB_PIXELS);
                framesPresented++;
                displayMode = 1;
                fbDirty = true;
            }
        }
    }

    private void plot(int x, int y, int color) {
        if (x >= 0 && x < FB_WIDTH && y >= 0 && y < FB_HEIGHT) {
            backBuffer[y * FB_WIDTH + x] = color;
        }
    }

    private void drawHorizontalSpan(int xStart, int xEnd, int y, int color) {
        if (y < 0 || y >= FB_HEIGHT) return;
        int xs = Math.max(0, xStart);
        int xe = Math.min(FB_WIDTH - 1, xEnd);
        if (xs <= xe) {
            int row = y * FB_WIDTH;
            Arrays.fill(backBuffer, row + xs, row + xe + 1, color);
        }
    }

    private void rasterizeFillRect(int rx, int ry, int rw, int rh, int color) {
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

    private void rasterizeLine(int x0, int y0, int x1, int y1, int color) {
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
        for (long step = firstStep; step <= lastStep; step++) {
            long minorStep = majorLength == 0 ? 0
                    : Long.divideUnsigned(step * minorLength + (majorLength - 1) / 2, majorLength);
            long major = majorStart + step * majorDirection;
            long minor = minorStart + minorStep * minorDirection;
            long x = xMajor ? major : minor;
            long y = xMajor ? minor : major;
            if (x >= 0 && x < FB_WIDTH && y >= 0 && y < FB_HEIGHT) {
                backBuffer[(int) y * FB_WIDTH + (int) x] = color;
            }
        }
    }

    private void rasterizeCircle(int cx, int cy, int r, int color, boolean filled) {
        if (r < 0) return;
        if (r == 0) {
            plot(cx, cy, color);
            return;
        }
        int x = 0;
        int y = r;
        int d = 3 - 2 * r;
        while (y >= x) {
            if (filled) {
                drawHorizontalSpan(cx - y, cx + y, cy + x, color);
                drawHorizontalSpan(cx - y, cx + y, cy - x, color);
                drawHorizontalSpan(cx - x, cx + x, cy + y, color);
                drawHorizontalSpan(cx - x, cx + x, cy - y, color);
            } else {
                plot(cx + x, cy + y, color);
                plot(cx - x, cy + y, color);
                plot(cx + x, cy - y, color);
                plot(cx - x, cy - y, color);
                plot(cx + y, cy + x, color);
                plot(cx - y, cy + x, color);
                plot(cx + y, cy - x, color);
                plot(cx - y, cy - x, color);
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

    private void rasterizeTriangle(int x0, int y0, int x1, int y1, int x2, int y2, int color) {
        // Sort vertices by y ascending
        if (y0 > y1) { int t = y0; y0 = y1; y1 = t; t = x0; x0 = x1; x1 = t; }
        if (y1 > y2) { int t = y1; y1 = y2; y2 = t; t = x1; x1 = x2; x2 = t; }
        if (y0 > y1) { int t = y0; y0 = y1; y1 = t; t = x0; x0 = x1; x1 = t; }

        int totalHeight = y2 - y0;
        if (totalHeight == 0) {
            int minX = Math.min(x0, Math.min(x1, x2));
            int maxX = Math.max(x0, Math.max(x1, x2));
            drawHorizontalSpan(minX, maxX, y0, color);
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
            drawHorizontalSpan(ax, bx, y, color);
        }
    }

    private void rasterizeBlitArray(int dx, int dy, int[] src, int baseOffset, int w, int h, boolean alphaBlend) {
        if (w <= 0 || h <= 0) return;
        for (int py = 0; py < h; py++) {
            int targetY = dy + py;
            if (targetY < 0 || targetY >= FB_HEIGHT) continue;
            int rowStart = targetY * FB_WIDTH;
            for (int px = 0; px < w; px++) {
                int targetX = dx + px;
                if (targetX < 0 || targetX >= FB_WIDTH) continue;
                int idx = baseOffset + py * w + px;
                if (idx < 0 || idx >= src.length) continue;
                int c = src[idx];
                if (!alphaBlend) {
                    backBuffer[rowStart + targetX] = normalizeOpaqueColor(c);
                } else {
                    int a = (c >>> 24) & 0xFF;
                    if (a == 0) continue;
                    if (a == 255) {
                        backBuffer[rowStart + targetX] = c;
                    } else {
                        int dst = backBuffer[rowStart + targetX];
                        int invA = 255 - a;
                        int r = (((c >> 16) & 0xFF) * a + ((dst >> 16) & 0xFF) * invA) / 255;
                        int g = (((c >> 8) & 0xFF) * a + ((dst >> 8) & 0xFF) * invA) / 255;
                        int b = ((c & 0xFF) * a + (dst & 0xFF) * invA) / 255;
                        backBuffer[rowStart + targetX] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                }
            }
        }
    }
}
