package games.jvrcruz.siliconcraft.client;

import games.jvrcruz.siliconcraft.network.DisplayFramePacket;
import games.jvrcruz.siliconcraft.network.FramebufferCodec;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import net.minecraft.core.BlockPos;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side lock-light, double-buffered display and terminal cache updated directly
 * on {@code HandlerThread.NETWORK} and read by {@code ComputerScreen} at uncapped render FPS.
 */
public final class ClientDisplayCache {
    private static final int FB_PIXELS = VirtualCpu.FB_WIDTH * VirtualCpu.FB_HEIGHT;
    private static final ConcurrentHashMap<BlockPos, DisplayState> STATES = new ConcurrentHashMap<>();

    private ClientDisplayCache() {}

    public static void onDisplayFrame(DisplayFramePacket packet) {
        if (packet == null) return;
        onDisplayFrame(
                packet.pos(),
                packet.mode(),
                packet.frameId(),
                packet.chunkIndex(),
                packet.totalChunks(),
                packet.keyframe(),
                packet.payload()
        );
    }

    public static void onDisplayFrame(
            BlockPos pos,
            int mode,
            int frameId,
            int chunkIndex,
            int totalChunks,
            boolean keyframe,
            byte[] payload
    ) {
        if (pos == null) return;
        STATES.computeIfAbsent(pos.immutable(), p -> new DisplayState())
                .acceptChunk(mode, frameId, chunkIndex, totalChunks, keyframe, payload);
    }

    public static void onTerminalSync(BlockPos pos, List<String> lines) {
        if (pos == null || lines == null) return;
        STATES.computeIfAbsent(pos.immutable(), p -> new DisplayState())
                .acceptTerminal(lines);
    }

    public static int getDisplayMode(BlockPos pos, int fallbackMode) {
        if (pos == null) return fallbackMode;
        DisplayState state = STATES.get(pos);
        if (state == null) return fallbackMode;
        int mode = state.displayMode;
        return mode >= 0 ? mode : fallbackMode;
    }

    /**
     * Copies the latest completed frame into {@code destPixels} if newer than {@code lastVersion}.
     *
     * @return Updated frame version (&gt; 0) when {@code destPixels} was updated, {@code lastVersion}
     *         if unchanged, or {@code -1L} if no streamed pixel frame has arrived yet.
     */
    public static long copyFrameIfUpdated(BlockPos pos, long lastVersion, int[] destPixels) {
        if (pos == null) return -1L;
        DisplayState state = STATES.get(pos);
        if (state == null) return -1L;
        return state.copyFrameIfUpdated(lastVersion, destPixels);
    }

    public static List<String> getTerminalLines(BlockPos pos) {
        if (pos == null) return null;
        DisplayState state = STATES.get(pos);
        return state == null ? null : state.terminalLines;
    }

    public static long getFrameVersion(BlockPos pos) {
        if (pos == null) return 0L;
        DisplayState state = STATES.get(pos);
        return state == null ? 0L : state.frameVersion;
    }

    public static void remove(BlockPos pos) {
        if (pos != null) {
            STATES.remove(pos);
        }
    }

    public static void clearAll() {
        STATES.clear();
    }

    private static final class DisplayState {
        private final Object frontLock = new Object();
        private final int[] stagingPixels = new int[FB_PIXELS];
        private final int[] frontPixels = new int[FB_PIXELS];

        private volatile int displayMode = -1;
        private volatile boolean hasPixelFrame = false;
        private volatile long frameVersion = 0L;
        private volatile List<String> terminalLines = null;

        private int highestCompletedFrameId = 0;
        private int pendingFrameId = -1;
        private int pendingMode = 0;
        private boolean pendingKeyframe = false;
        private byte[][] pendingChunks = null;
        private int pendingReceivedCount = 0;

        DisplayState() {
            Arrays.fill(stagingPixels, 0xFF051009);
            Arrays.fill(frontPixels, 0xFF051009);
        }

        synchronized void acceptChunk(
                int mode,
                int frameId,
                int chunkIndex,
                int totalChunks,
                boolean keyframe,
                byte[] payload
        ) {
            if (frameId <= 0 || totalChunks <= 0 || chunkIndex < 0 || chunkIndex >= totalChunks) {
                return;
            }
            if (frameId <= highestCompletedFrameId) {
                return;
            }
            if (mode == 1 && !keyframe && !hasPixelFrame) {
                return;
            }

            if (frameId > pendingFrameId) {
                pendingFrameId = frameId;
                pendingMode = mode;
                pendingKeyframe = keyframe;
                pendingChunks = new byte[totalChunks][];
                pendingReceivedCount = 0;
            } else if (frameId < pendingFrameId) {
                return;
            } else if (pendingChunks == null || pendingChunks.length != totalChunks) {
                return;
            }

            if (pendingChunks[chunkIndex] == null) {
                pendingChunks[chunkIndex] = payload != null ? payload : new byte[0];
                pendingReceivedCount++;
            }

            if (pendingReceivedCount < totalChunks) {
                return;
            }

            // All chunks for pendingFrameId received; finalize frame atomically
            byte[] combined = totalChunks == 1
                    ? pendingChunks[0]
                    : FramebufferCodec.joinChunks(Arrays.asList(pendingChunks));
            int completedId = pendingFrameId;
            int completedMode = pendingMode;
            boolean completedKeyframe = pendingKeyframe;
            pendingFrameId = -1;
            pendingChunks = null;
            pendingReceivedCount = 0;

            if (completedMode != 1) {
                synchronized (frontLock) {
                    this.displayMode = completedMode;
                    this.frameVersion++;
                }
                highestCompletedFrameId = completedId;
                return;
            }

            if (!FramebufferCodec.applyStreamFrame(combined, completedKeyframe, stagingPixels)) {
                return;
            }

            synchronized (frontLock) {
                System.arraycopy(stagingPixels, 0, frontPixels, 0, FB_PIXELS);
                this.displayMode = 1;
                this.hasPixelFrame = true;
                this.frameVersion++;
            }
            highestCompletedFrameId = completedId;
        }

        void acceptTerminal(List<String> lines) {
            this.terminalLines = List.copyOf(lines);
        }

        long copyFrameIfUpdated(long lastVersion, int[] destPixels) {
            if (!hasPixelFrame) {
                return -1L;
            }
            long currentVer = frameVersion;
            if (currentVer == lastVersion) {
                return lastVersion;
            }
            synchronized (frontLock) {
                currentVer = frameVersion;
                if (destPixels != null && destPixels.length >= FB_PIXELS) {
                    System.arraycopy(frontPixels, 0, destPixels, 0, FB_PIXELS);
                }
                return currentVer;
            }
        }
    }
}
