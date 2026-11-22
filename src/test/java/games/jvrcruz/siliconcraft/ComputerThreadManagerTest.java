package games.jvrcruz.siliconcraft;

import games.jvrcruz.siliconcraft.vm.ComputerHost;
import games.jvrcruz.siliconcraft.vm.ComputerThreadManager;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class ComputerThreadManagerTest {

    private static class TestHost implements ComputerHost {
        private final VirtualCpu cpu = new VirtualCpu();
        private volatile boolean removed = false;

        public TestHost() {}

        public TestHost(List<String> program) {
            Set<String> isa = Set.of("MOV", "ADD", "INC", "CMP", "JNZ", "JMP", "HLT");
            cpu.configure(isa, 256, program, false, 0);
            cpu.powerOn();
        }

        @Override
        public VirtualCpu getCpu() {
            return cpu;
        }

        @Override
        public boolean isRemoved() {
            return removed;
        }

        public void setRemoved(boolean removed) {
            this.removed = removed;
        }

        @Override
        public void tickAsyncCpu(int cycles) {
            cpu.tick(cycles);
        }
    }

    @BeforeEach
    @AfterEach
    public void cleanup() {
        ComputerThreadManager.shutdown();
    }

    @Test
    public void testThreadAllocationAndLimit() {
        assertEquals(0, ComputerThreadManager.getActiveWorkerCount());
        assertEquals(0, ComputerThreadManager.getTotalRegisteredComputers());

        List<TestHost> hosts = new ArrayList<>();

        // Register 8 computers (fills up thread 1)
        for (int i = 0; i < ComputerThreadManager.MAX_COMPUTERS_PER_THREAD; i++) {
            TestHost host = new TestHost();
            hosts.add(host);
            ComputerThreadManager.register(host);
        }

        assertEquals(1, ComputerThreadManager.getActiveWorkerCount(),
                "8 computers should all be hosted on a single worker thread");
        assertEquals(8, ComputerThreadManager.getTotalRegisteredComputers());

        // Register the 9th computer (must spawn thread 2 because limit is 8 per thread)
        TestHost ninth = new TestHost();
        hosts.add(ninth);
        ComputerThreadManager.register(ninth);

        assertEquals(2, ComputerThreadManager.getActiveWorkerCount(),
                "Registering a 9th computer must spawn a 2nd worker thread to enforce the per-thread limit");
        assertEquals(9, ComputerThreadManager.getTotalRegisteredComputers());

        // Unregister the 9th computer
        ComputerThreadManager.unregister(ninth);
        assertEquals(8, ComputerThreadManager.getTotalRegisteredComputers());
    }

    @Test
    public void testAsynchronousExecutionOnSharedThread() throws InterruptedException {
        // Simple counter loop:
        // loop:
        //   INC A
        //   JMP loop
        List<String> program = List.of(
                "loop:",
                "  INC A",
                "  JMP loop"
        );

        TestHost host1 = new TestHost(program);
        TestHost host2 = new TestHost(program);

        assertEquals(0, host1.getCpu().getRegA());
        assertEquals(0, host2.getCpu().getRegA());

        // Register both to background thread execution
        ComputerThreadManager.register(host1);
        ComputerThreadManager.register(host2);

        assertEquals(1, ComputerThreadManager.getActiveWorkerCount());
        assertEquals(2, ComputerThreadManager.getTotalRegisteredComputers());

        // Wait a few milliseconds for background thread to execute slices
        long start = System.currentTimeMillis();
        while (host1.getCpu().getRegA() < 1000 && (System.currentTimeMillis() - start < 2000)) {
            Thread.sleep(10);
        }

        assertTrue(host1.getCpu().getRegA() >= 1000,
                "Host 1 should have executed cycles asynchronously on background thread: A=" + host1.getCpu().getRegA());
        assertTrue(host2.getCpu().getRegA() >= 1000,
                "Host 2 should have executed cycles asynchronously on the same shared thread: A=" + host2.getCpu().getRegA());

        // Power off host 1; host 2 should continue executing
        host1.getCpu().powerOff();
        int pausedA1 = host1.getCpu().getRegA();

        Thread.sleep(30);

        assertEquals(pausedA1, host1.getCpu().getRegA(), "Host 1 must stop incrementing when powered off");
        assertTrue(host2.getCpu().getRegA() > 1000, "Host 2 must continue running");
    }

    @Test
    public void testHostRemovedUnregistration() throws InterruptedException {
        TestHost host = new TestHost();
        ComputerThreadManager.register(host);
        assertEquals(1, ComputerThreadManager.getTotalRegisteredComputers());

        host.setRemoved(true);

        // Background worker loop should automatically purge removed hosts
        long start = System.currentTimeMillis();
        while (ComputerThreadManager.getTotalRegisteredComputers() > 0 && (System.currentTimeMillis() - start < 1000)) {
            Thread.sleep(10);
        }

        assertEquals(0, ComputerThreadManager.getTotalRegisteredComputers(),
                "Removed host should be purged from background worker thread");
    }

    @Test
    public void testFastShutdownUnloading() {
        List<String> infiniteLoop = List.of(
                "loop:",
                "  INC A",
                "  JMP loop"
        );

        // Spawn multiple workers (e.g. 18 computers -> 3 workers)
        List<TestHost> hosts = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            TestHost host = new TestHost(infiniteLoop);
            hosts.add(host);
            ComputerThreadManager.register(host);
        }

        assertTrue(ComputerThreadManager.getActiveWorkerCount() >= 2,
                "Should have at least 2 active worker threads for 18 computers");

        // Measure shutdown time
        long start = System.currentTimeMillis();
        ComputerThreadManager.shutdown();
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(0, ComputerThreadManager.getActiveWorkerCount(),
                "All workers should be terminated and unregistered");
        assertEquals(0, ComputerThreadManager.getTotalRegisteredComputers(),
                "Registered computers should be 0 after shutdown");
        assertTrue(elapsed < 250, "Forced shutdown should complete fast (<250ms), took: " + elapsed + "ms");

        // Verify that after shutdown, new computers can still be registered cleanly
        TestHost postShutdownHost = new TestHost(infiniteLoop);
        ComputerThreadManager.register(postShutdownHost);
        assertEquals(1, ComputerThreadManager.getActiveWorkerCount());
        assertEquals(1, ComputerThreadManager.getTotalRegisteredComputers());
    }
}
