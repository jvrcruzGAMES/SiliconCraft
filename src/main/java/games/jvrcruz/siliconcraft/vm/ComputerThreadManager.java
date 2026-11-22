package games.jvrcruz.siliconcraft.vm;

import games.jvrcruz.siliconcraft.blockentity.ComputerBlockEntity;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Manages background execution threads for Virtual CPUs in SiliconCraft.
 *
 * Each background worker thread runs a cluster of computers (up to MAX_COMPUTERS_PER_THREAD)
 * in cooperative slices, removing execution latency and TPS burden from the Minecraft
 * server tick loop.
 */
public class ComputerThreadManager {

    /**
     * Maximum number of computers that can be assigned to a single worker thread.
     * Prevents any single thread from becoming overburdened and slowing down individual machines.
     */
    public static final int MAX_COMPUTERS_PER_THREAD = 8;

    /**
     * Number of cycles executed per computer per time slice.
     */
    private static final int CYCLES_PER_SLICE = 2500;

    /**
     * Park duration between execution slices (1 ms = 1000 slices/sec, yielding ~2.5 MHz per computer).
     */
    private static final long SLICE_PARK_NANOS = 1_000_000L;

    private static final List<WorkerThread> WORKERS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);
    private static final Object ALLOCATION_LOCK = new Object();

    /**
     * Registers a computer for background execution. If already registered, this is a no-op.
     * Assigns the computer to an existing worker thread with available capacity (< MAX_COMPUTERS_PER_THREAD),
     * or spawns a new worker thread if all existing threads are full.
     */
    public static void register(ComputerHost computer) {
        if (computer == null || computer.isRemoved()) return;

        synchronized (ALLOCATION_LOCK) {
            // Check if already registered in any worker
            for (WorkerThread worker : WORKERS) {
                if (worker.contains(computer)) {
                    return;
                }
            }

            // Find an existing worker with capacity
            for (WorkerThread worker : WORKERS) {
                if (worker.computerCount() < MAX_COMPUTERS_PER_THREAD && worker.isAlive()) {
                    worker.add(computer);
                    return;
                }
            }

            // No available worker; spawn a new one
            WorkerThread newWorker = new WorkerThread(THREAD_COUNTER.getAndIncrement());
            newWorker.add(computer);
            WORKERS.add(newWorker);
            newWorker.start();
        }
    }

    /**
     * Unregisters a computer from background execution across all worker threads.
     */
    public static void unregister(ComputerHost computer) {
        if (computer == null) return;

        synchronized (ALLOCATION_LOCK) {
            for (WorkerThread worker : WORKERS) {
                worker.remove(computer);
            }
        }
    }

    /**
     * Shuts down all worker threads cleanly and quickly (e.g. on server stop, game exit, or JVM shutdown).
     */
    public static void shutdown() {
        List<WorkerThread> workersToStop;
        synchronized (ALLOCATION_LOCK) {
            workersToStop = new java.util.ArrayList<>(WORKERS);
            for (WorkerThread worker : workersToStop) {
                worker.stopWorker();
            }
            WORKERS.clear();
        }
        for (WorkerThread worker : workersToStop) {
            try {
                worker.join(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public static int getActiveWorkerCount() {
        return WORKERS.size();
    }

    public static int getTotalRegisteredComputers() {
        int total = 0;
        for (WorkerThread worker : WORKERS) {
            total += worker.computerCount();
        }
        return total;
    }

    private static class WorkerThread extends Thread {
        private final Set<ComputerHost> computers = ConcurrentHashMap.newKeySet();
        private volatile boolean running = true;
        private int idleTicks = 0;

        public WorkerThread(int id) {
            super("SiliconCraft-CpuWorker-" + id);
            setDaemon(true);
        }

        public void add(ComputerHost computer) {
            computers.add(computer);
        }

        public void remove(ComputerHost computer) {
            computers.remove(computer);
        }

        public boolean contains(ComputerHost computer) {
            return computers.contains(computer);
        }

        public int computerCount() {
            return computers.size();
        }

        public void stopWorker() {
            this.running = false;
            interrupt();
            this.computers.clear();
        }

        @Override
        public void run() {
            while (running && !isInterrupted()) {
                if (computers.isEmpty()) {
                    idleTicks++;
                    // If idle for over 5 seconds (500 iterations of 10ms), retire worker
                    if (idleTicks > 500) {
                        synchronized (ALLOCATION_LOCK) {
                            if (computers.isEmpty()) {
                                WORKERS.remove(this);
                                break;
                            }
                        }
                    }
                    LockSupport.parkNanos(10_000_000L); // 10 ms idle park
                    continue;
                }

                idleTicks = 0;
                long sliceStart = System.nanoTime();

                for (ComputerHost comp : computers) {
                    if (!running || isInterrupted()) break;

                    if (comp.isRemoved()) {
                        computers.remove(comp);
                        continue;
                    }

                    VirtualCpu cpu = comp.getCpu();
                    if (cpu != null && cpu.getState() == VirtualCpu.State.RUNNING) {
                        comp.tickAsyncCpu(CYCLES_PER_SLICE);
                    }
                }

                if (!running || isInterrupted()) break;

                long elapsed = System.nanoTime() - sliceStart;
                if (elapsed < SLICE_PARK_NANOS) {
                    LockSupport.parkNanos(SLICE_PARK_NANOS - elapsed);
                } else {
                    Thread.yield();
                }
            }

            synchronized (ALLOCATION_LOCK) {
                WORKERS.remove(this);
            }
        }
    }
}
