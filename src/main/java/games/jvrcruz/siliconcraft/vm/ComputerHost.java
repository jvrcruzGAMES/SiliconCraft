package games.jvrcruz.siliconcraft.vm;

/**
 * Common interface for entities or mock hosts executing a VirtualCpu asynchronously
 * on shared background worker threads.
 */
public interface ComputerHost {

    /**
     * @return The Virtual CPU instance managed by this host.
     */
    VirtualCpu getCpu();

    /**
     * @return True if the host is removed, unloaded, or destroyed.
     */
    boolean isRemoved();

    /**
     * Executes asynchronous CPU cycles on a background worker thread.
     *
     * @param cycles Maximum cycle budget allocated for this execution slice.
     */
    void tickAsyncCpu(int cycles);

    /**
     * @return The Virtual GPU associated with this host, if any.
     */
    default VirtualGpu getGpu() {
        VirtualCpu cpu = getCpu();
        return cpu != null ? cpu.getGpu() : null;
    }

    /**
     * Executes an asynchronous GPU command batch on a dedicated GPU worker thread.
     *
     * @return Number of GPU commands executed in this slice.
     */
    default int tickAsyncGpu() {
        VirtualGpu gpu = getGpu();
        if (gpu != null && gpu.hasPendingWork()) {
            return gpu.tick(gpu.getBatchBudget());
        }
        return 0;
    }

    /**
     * Flushes dirty display frames, terminal snapshots, and audio samples off the server tick.
     *
     * @param nowNanos Current monotonic timestamp from {@link System#nanoTime()}.
     * @return True if any output was emitted or work remains pending (e.g. frame-rate paced).
     */
    default boolean tickAsyncSync(long nowNanos) {
        return false;
    }

    /**
     * @return True if display, terminal, or audio buffers still have unsent data.
     */
    default boolean hasPendingSyncWork() {
        VirtualCpu cpu = getCpu();
        return cpu != null && (cpu.isAnyFbDirty() || cpu.hasPendingAudio());
    }

    default void attachSyncWorker(Thread worker) {
        VirtualCpu cpu = getCpu();
        if (cpu != null) {
            cpu.attachSyncWorker(worker);
        }
    }

    default void detachSyncWorker(Thread worker) {
        VirtualCpu cpu = getCpu();
        if (cpu != null) {
            cpu.detachSyncWorker(worker);
        }
    }
}
