package games.jvrcruz.siliconcraft.vm;

import games.jvrcruz.siliconcraft.Siliconcraft;
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
     * Maximum number of GPUs assigned to a single GPU worker thread.
     */
    public static final int MAX_GPUS_PER_THREAD = 8;

    /**
     * Maximum number of hosts assigned to a single async display/audio sync worker thread.
     */
    public static final int MAX_SYNCS_PER_THREAD = 8;

    /**
     * Number of cycles executed per computer per time slice (485 MHz GameCube Gekko target at 1,000 slices/sec).
     */
    public static final int CYCLES_PER_SLICE = 485_000;

    /**
     * Park duration between CPU execution slices (1 ms = 1000 slices/sec, yielding ~485 MHz per JIT computer).
     */
    private static final long SLICE_PARK_NANOS = 1_000_000L;

    /**
     * Park duration between active GPU execution slices (0.2 ms = 5000 slices/sec, 5x CPU slice cadence).
     * Idle GPU workers park longer and are woken immediately via LockSupport.unpark on command enqueue.
     */
    public static final long GPU_SLICE_PARK_NANOS = 200_000L;
    private static final long GPU_IDLE_PARK_NANOS = 5_000_000L;

    /**
     * Park durations for the non-tick-bound display/terminal/audio sync worker threads.
     * Active sync parks briefly (~4 ms) so rate-limited frames (8 ms = 125 Hz) drain promptly.
     */
    public static final long SYNC_ACTIVE_PARK_NANOS = 4_000_000L;
    private static final long SYNC_IDLE_PARK_NANOS = 10_000_000L;

    private static final List<WorkerThread> WORKERS = new CopyOnWriteArrayList<>();
    private static final List<GpuWorkerThread> GPU_WORKERS = new CopyOnWriteArrayList<>();
    private static final List<SyncWorkerThread> SYNC_WORKERS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger GPU_THREAD_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger SYNC_THREAD_COUNTER = new AtomicInteger(1);
    private static final Object ALLOCATION_LOCK = new Object();

    /**
     * Registers a computer for background execution (CPU, GPU, and display/audio sync).
     * If already registered, this is a no-op.
     */
    public static void register(ComputerHost computer) {
        if (computer == null || computer.isRemoved()) return;

        synchronized (ALLOCATION_LOCK) {
            boolean cpuRegistered = false;
            for (WorkerThread worker : WORKERS) {
                if (!worker.isAlive()) {
                    worker.remove(computer);
                    WORKERS.remove(worker);
                    continue;
                }
                if (worker.contains(computer)) {
                    cpuRegistered = true;
                    break;
                }
            }
            if (!cpuRegistered) {
                boolean added = false;
                for (WorkerThread worker : WORKERS) {
                    if (worker.computerCount() < MAX_COMPUTERS_PER_THREAD && worker.isAlive()) {
                        worker.add(computer);
                        added = true;
                        break;
                    }
                }
                if (!added) {
                    WorkerThread newWorker = new WorkerThread(THREAD_COUNTER.getAndIncrement());
                    newWorker.add(computer);
                    WORKERS.add(newWorker);
                    newWorker.start();
                }
            }

            boolean gpuRegistered = false;
            for (GpuWorkerThread gpuWorker : GPU_WORKERS) {
                if (!gpuWorker.isAlive()) {
                    gpuWorker.remove(computer);
                    GPU_WORKERS.remove(gpuWorker);
                    continue;
                }
                if (gpuWorker.contains(computer)) {
                    gpuRegistered = true;
                    break;
                }
            }
            if (!gpuRegistered) {
                boolean added = false;
                for (GpuWorkerThread gpuWorker : GPU_WORKERS) {
                    if (gpuWorker.computerCount() < MAX_GPUS_PER_THREAD && gpuWorker.isAlive()) {
                        gpuWorker.add(computer);
                        added = true;
                        break;
                    }
                }
                if (!added) {
                    GpuWorkerThread newGpuWorker = new GpuWorkerThread(GPU_THREAD_COUNTER.getAndIncrement());
                    newGpuWorker.add(computer);
                    GPU_WORKERS.add(newGpuWorker);
                    newGpuWorker.start();
                }
            }

            registerSyncLocked(computer);
        }
    }

    /**
     * Registers a computer on the background sync worker cluster (e.g. while a screen GUI is open on a paused/halted host).
     */
    public static void registerSync(ComputerHost computer) {
        if (computer == null || computer.isRemoved()) return;
        synchronized (ALLOCATION_LOCK) {
            registerSyncLocked(computer);
        }
    }

    private static void registerSyncLocked(ComputerHost computer) {
        for (SyncWorkerThread syncWorker : SYNC_WORKERS) {
            if (!syncWorker.isAlive()) {
                syncWorker.remove(computer);
                SYNC_WORKERS.remove(syncWorker);
                continue;
            }
            if (syncWorker.contains(computer)) {
                LockSupport.unpark(syncWorker);
                return;
            }
        }
        for (SyncWorkerThread syncWorker : SYNC_WORKERS) {
            if (syncWorker.computerCount() < MAX_SYNCS_PER_THREAD && syncWorker.isAlive()) {
                syncWorker.add(computer);
                return;
            }
        }
        SyncWorkerThread newSyncWorker = new SyncWorkerThread(SYNC_THREAD_COUNTER.getAndIncrement());
        newSyncWorker.add(computer);
        SYNC_WORKERS.add(newSyncWorker);
        newSyncWorker.start();
    }

    /**
     * Unregisters a computer from CPU and GPU execution threads while optionally leaving sync active for open viewers.
     */
    public static void unregisterExecution(ComputerHost computer) {
        if (computer == null) return;
        synchronized (ALLOCATION_LOCK) {
            for (WorkerThread worker : WORKERS) {
                worker.remove(computer);
            }
            for (GpuWorkerThread gpuWorker : GPU_WORKERS) {
                gpuWorker.remove(computer);
            }
        }
    }

    /**
     * Unregisters a computer from background execution across CPU, GPU, and sync worker threads.
     */
    public static void unregister(ComputerHost computer) {
        if (computer == null) return;

        synchronized (ALLOCATION_LOCK) {
            for (WorkerThread worker : WORKERS) {
                worker.remove(computer);
            }
            for (GpuWorkerThread gpuWorker : GPU_WORKERS) {
                gpuWorker.remove(computer);
            }
            for (SyncWorkerThread syncWorker : SYNC_WORKERS) {
                syncWorker.remove(computer);
            }
        }
    }

    /**
     * Wakes the sync worker attached to this computer immediately.
     */
    public static void wakeSync(ComputerHost computer) {
        if (computer == null) return;
        VirtualCpu cpu = computer.getCpu();
        if (cpu != null) {
            cpu.wakeSyncWorker();
        }
    }

    /**
     * Shuts down all CPU, GPU, and sync worker threads cleanly and quickly.
     */
    public static void shutdown() {
        List<WorkerThread> workersToStop;
        List<GpuWorkerThread> gpuWorkersToStop;
        List<SyncWorkerThread> syncWorkersToStop;
        synchronized (ALLOCATION_LOCK) {
            workersToStop = new java.util.ArrayList<>(WORKERS);
            for (WorkerThread worker : workersToStop) {
                worker.stopWorker();
            }
            WORKERS.clear();

            gpuWorkersToStop = new java.util.ArrayList<>(GPU_WORKERS);
            for (GpuWorkerThread gpuWorker : gpuWorkersToStop) {
                gpuWorker.stopWorker();
            }
            GPU_WORKERS.clear();

            syncWorkersToStop = new java.util.ArrayList<>(SYNC_WORKERS);
            for (SyncWorkerThread syncWorker : syncWorkersToStop) {
                syncWorker.stopWorker();
            }
            SYNC_WORKERS.clear();
        }
        for (WorkerThread worker : workersToStop) {
            try {
                worker.join(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (GpuWorkerThread gpuWorker : gpuWorkersToStop) {
            try {
                gpuWorker.join(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (SyncWorkerThread syncWorker : syncWorkersToStop) {
            try {
                syncWorker.join(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public static int getActiveWorkerCount() {
        return WORKERS.size();
    }

    public static int getActiveGpuWorkerCount() {
        return GPU_WORKERS.size();
    }

    public static int getActiveSyncWorkerCount() {
        return SYNC_WORKERS.size();
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
                        try {
                            comp.tickAsyncCpu(CYCLES_PER_SLICE);
                        } catch (Throwable t) {
                            if (t instanceof InterruptedException || !running || isInterrupted()) {
                                break;
                            }
                            Siliconcraft.LOGGER.error("Unhandled exception on CPU worker thread", t);
                        }
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

    private static class GpuWorkerThread extends Thread {
        private final Set<ComputerHost> computers = ConcurrentHashMap.newKeySet();
        private volatile boolean running = true;
        private int idleTicks = 0;

        public GpuWorkerThread(int id) {
            super("SiliconCraft-GpuWorker-" + id);
            setDaemon(true);
            setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 1));
        }

        public void add(ComputerHost computer) {
            VirtualGpu gpu = computer.getGpu();
            if (gpu != null) {
                gpu.attachWorkerThread(this);
            }
            computers.add(computer);
            LockSupport.unpark(this);
        }

        public void remove(ComputerHost computer) {
            computers.remove(computer);
            VirtualGpu gpu = computer.getGpu();
            if (gpu != null) {
                gpu.detachWorkerThread(this);
            }
        }

        public boolean contains(ComputerHost computer) {
            return computers.contains(computer);
        }

        public int computerCount() {
            return computers.size();
        }

        public void stopWorker() {
            this.running = false;
            for (ComputerHost comp : computers) {
                VirtualGpu gpu = comp.getGpu();
                if (gpu != null) {
                    gpu.detachWorkerThread(this);
                }
            }
            interrupt();
            this.computers.clear();
        }

        @Override
        public void run() {
            while (running && !isInterrupted()) {
                if (computers.isEmpty()) {
                    idleTicks++;
                    if (idleTicks > 500) {
                        synchronized (ALLOCATION_LOCK) {
                            if (computers.isEmpty()) {
                                GPU_WORKERS.remove(this);
                                break;
                            }
                        }
                    }
                    LockSupport.parkNanos(10_000_000L);
                    continue;
                }

                idleTicks = 0;
                long sliceStart = System.nanoTime();
                boolean hasMoreWork = false;

                for (ComputerHost comp : computers) {
                    if (!running || isInterrupted()) break;

                    if (comp.isRemoved()) {
                        remove(comp);
                        continue;
                    }

                    VirtualGpu gpu = comp.getGpu();
                    if (gpu != null && gpu.hasPendingWork()) {
                        gpu.attachWorkerThread(this);
                        try {
                            comp.tickAsyncGpu();
                            if (gpu.hasPendingWork()) {
                                hasMoreWork = true;
                            }
                        } catch (Throwable t) {
                            if (t instanceof InterruptedException || !running || isInterrupted()) {
                                break;
                            }
                            Siliconcraft.LOGGER.error("Unhandled exception on GPU worker thread", t);
                        }
                    }
                }

                if (!running || isInterrupted()) break;

                if (hasMoreWork) {
                    long elapsed = System.nanoTime() - sliceStart;
                    if (elapsed < GPU_SLICE_PARK_NANOS) {
                        LockSupport.parkNanos(GPU_SLICE_PARK_NANOS - elapsed);
                    } else {
                        Thread.yield();
                    }
                } else {
                    LockSupport.parkNanos(GPU_IDLE_PARK_NANOS);
                }
            }

            synchronized (ALLOCATION_LOCK) {
                GPU_WORKERS.remove(this);
            }
        }
    }

    private static class SyncWorkerThread extends Thread {
        private final Set<ComputerHost> computers = ConcurrentHashMap.newKeySet();
        private volatile boolean running = true;
        private int idleTicks = 0;

        public SyncWorkerThread(int id) {
            super("SiliconCraft-SyncWorker-" + id);
            setDaemon(true);
        }

        public void add(ComputerHost computer) {
            computer.attachSyncWorker(this);
            computers.add(computer);
            LockSupport.unpark(this);
        }

        public void remove(ComputerHost computer) {
            computers.remove(computer);
            computer.detachSyncWorker(this);
        }

        public boolean contains(ComputerHost computer) {
            return computers.contains(computer);
        }

        public int computerCount() {
            return computers.size();
        }

        public void stopWorker() {
            this.running = false;
            for (ComputerHost comp : computers) {
                comp.detachSyncWorker(this);
            }
            interrupt();
            this.computers.clear();
        }

        @Override
        public void run() {
            while (running && !isInterrupted()) {
                if (computers.isEmpty()) {
                    idleTicks++;
                    if (idleTicks > 500) {
                        synchronized (ALLOCATION_LOCK) {
                            if (computers.isEmpty()) {
                                SYNC_WORKERS.remove(this);
                                break;
                            }
                        }
                    }
                    LockSupport.parkNanos(SYNC_IDLE_PARK_NANOS);
                    continue;
                }

                idleTicks = 0;
                long sliceStart = System.nanoTime();
                boolean hasMoreWork = false;

                for (ComputerHost comp : computers) {
                    if (!running || isInterrupted()) break;

                    if (comp.isRemoved()) {
                        remove(comp);
                        continue;
                    }

                    comp.attachSyncWorker(this);
                    try {
                        if (comp.tickAsyncSync(sliceStart) || comp.hasPendingSyncWork()) {
                            hasMoreWork = true;
                        }
                    } catch (Throwable ignored) {
                    }
                }

                if (!running || isInterrupted()) break;

                if (hasMoreWork) {
                    long elapsed = System.nanoTime() - sliceStart;
                    if (elapsed < SYNC_ACTIVE_PARK_NANOS) {
                        LockSupport.parkNanos(SYNC_ACTIVE_PARK_NANOS - elapsed);
                    } else {
                        Thread.yield();
                    }
                } else {
                    LockSupport.parkNanos(SYNC_IDLE_PARK_NANOS);
                }
            }

            synchronized (ALLOCATION_LOCK) {
                SYNC_WORKERS.remove(this);
            }
        }
    }
}
