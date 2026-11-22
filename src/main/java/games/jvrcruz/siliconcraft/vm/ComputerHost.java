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
}
