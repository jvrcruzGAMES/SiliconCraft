package games.jvrcruz.siliconcraft.vm;

import games.jvrcruz.siliconcraft.network.FramebufferCodec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pure-Java per-computer display and terminal streaming session used by background sync workers.
 * Produces monotonic, tear-free keyframes and row-deltas independently of the 20 TPS server tick.
 */
public class DisplayStreamSession {
    public static final long DEFAULT_MIN_FRAME_INTERVAL_NANOS = 8_000_000L; // ~125 Hz cap

    public record StreamFrame(int mode, int frameId, boolean keyframe, List<byte[]> chunks) {}

    @FunctionalInterface
    public interface FrameEmitter {
        void emit(StreamFrame frame, boolean broadcastToAllViewers);
    }

    @FunctionalInterface
    public interface TerminalEmitter {
        void emit(List<String> lines);
    }

    private final Object streamLock = new Object();
    private final long minFrameIntervalNanos;
    private final int[] scratchPixels = new int[VirtualCpu.FB_WIDTH * VirtualCpu.FB_HEIGHT];
    private final int[] lastSentPixels = new int[VirtualCpu.FB_WIDTH * VirtualCpu.FB_HEIGHT];
    private final Set<UUID> syncedViewerIds = new HashSet<>();

    private boolean hasSentPixelFrame = false;
    private int lastSentMode = -1;
    private int nextFrameId = 1;
    private long lastDisplayEmitNanos = 0L;

    private long lastSentTerminalVersion = -1L;
    private List<String> lastSentTerminalLines = List.of();

    public DisplayStreamSession() {
        this(DEFAULT_MIN_FRAME_INTERVAL_NANOS);
    }

    public DisplayStreamSession(long minFrameIntervalNanos) {
        this.minFrameIntervalNanos = Math.max(0L, minFrameIntervalNanos);
    }

    public void removeViewer(UUID viewerId) {
        if (viewerId == null) return;
        synchronized (streamLock) {
            syncedViewerIds.remove(viewerId);
        }
    }

    public void resetBaseline() {
        synchronized (streamLock) {
            hasSentPixelFrame = false;
            lastSentMode = -1;
            syncedViewerIds.clear();
            lastDisplayEmitNanos = 0L;
        }
    }

    public boolean sendInitialSnapshot(UUID viewerId, VirtualCpu cpu, FrameEmitter frameEmitter, TerminalEmitter terminalEmitter) {
        if (viewerId == null || cpu == null) return false;
        boolean emitted = false;
        synchronized (streamLock) {
            int mode = cpu.snapshotActiveDisplay(scratchPixels);
            int frameId = nextFrameId++;
            boolean hadOtherViewers = !syncedViewerIds.isEmpty() && (!syncedViewerIds.contains(viewerId) || syncedViewerIds.size() > 1);

            if (mode != 1) {
                lastSentMode = mode;
                lastDisplayEmitNanos = System.nanoTime();
                syncedViewerIds.add(viewerId);
                if (frameEmitter != null) {
                    frameEmitter.emit(new StreamFrame(mode, frameId, true, List.of(new byte[0])), hadOtherViewers);
                    emitted = true;
                }
            } else {
                FramebufferCodec.EncodedFrame encoded = FramebufferCodec.encodeStreamFrame(scratchPixels, null, true);
                System.arraycopy(scratchPixels, 0, lastSentPixels, 0, scratchPixels.length);
                hasSentPixelFrame = true;
                lastSentMode = 1;
                lastDisplayEmitNanos = System.nanoTime();
                syncedViewerIds.add(viewerId);
                if (frameEmitter != null && encoded.changed()) {
                    frameEmitter.emit(new StreamFrame(1, frameId, true, encoded.chunks()), hadOtherViewers);
                    emitted = true;
                }
            }

            if (terminalEmitter != null) {
                long termVer = cpu.getTerminalVersion();
                List<String> lines = cpu.getTerminalOutput();
                lastSentTerminalVersion = termVer;
                lastSentTerminalLines = new ArrayList<>(lines);
                terminalEmitter.emit(lastSentTerminalLines);
            }
        }
        return emitted;
    }

    public boolean tickDisplay(VirtualCpu cpu, Collection<UUID> activeViewerIds, long nowNanos, FrameEmitter emitter) {
        if (cpu == null || activeViewerIds == null) return false;
        synchronized (streamLock) {
            syncedViewerIds.retainAll(activeViewerIds);
            if (activeViewerIds.isEmpty()) {
                return false;
            }

            boolean hasNewViewer = false;
            for (UUID id : activeViewerIds) {
                if (!syncedViewerIds.contains(id)) {
                    hasNewViewer = true;
                    break;
                }
            }

            boolean dirty = cpu.isAnyFbDirty();
            int currentMode = cpu.getDisplayMode();
            boolean modeChanged = lastSentMode != currentMode;

            if (!dirty && !hasNewViewer && !modeChanged) {
                return false;
            }
            if (!hasNewViewer && !modeChanged && (nowNanos - lastDisplayEmitNanos) < minFrameIntervalNanos) {
                return false;
            }

            int mode = cpu.snapshotActiveDisplay(scratchPixels);
            if (mode != 1) {
                boolean emitTextSwitch = hasNewViewer || lastSentMode != mode;
                lastSentMode = mode;
                syncedViewerIds.addAll(activeViewerIds);
                if (emitTextSwitch && emitter != null) {
                    lastDisplayEmitNanos = nowNanos;
                    emitter.emit(new StreamFrame(mode, nextFrameId++, true, List.of(new byte[0])), true);
                    return true;
                }
                return false;
            }

            boolean forceKeyframe = hasNewViewer || !hasSentPixelFrame || lastSentMode != 1;
            FramebufferCodec.EncodedFrame encoded = FramebufferCodec.encodeStreamFrame(
                    scratchPixels,
                    hasSentPixelFrame ? lastSentPixels : null,
                    forceKeyframe
            );

            lastSentMode = 1;
            syncedViewerIds.addAll(activeViewerIds);
            if (!encoded.changed()) {
                return false;
            }

            System.arraycopy(scratchPixels, 0, lastSentPixels, 0, scratchPixels.length);
            hasSentPixelFrame = true;
            lastDisplayEmitNanos = nowNanos;
            if (emitter != null) {
                emitter.emit(new StreamFrame(1, nextFrameId++, encoded.keyframe(), encoded.chunks()), true);
            }
            return true;
        }
    }

    public boolean tickTerminal(VirtualCpu cpu, TerminalEmitter emitter) {
        if (cpu == null) return false;
        long ver = cpu.getTerminalVersion();
        synchronized (streamLock) {
            if (ver == lastSentTerminalVersion) {
                return false;
            }
            List<String> lines = cpu.getTerminalOutput();
            lastSentTerminalVersion = ver;
            if (lines.equals(lastSentTerminalLines)) {
                return false;
            }
            lastSentTerminalLines = new ArrayList<>(lines);
            if (emitter != null) {
                emitter.emit(lastSentTerminalLines);
            }
            return true;
        }
    }
}
