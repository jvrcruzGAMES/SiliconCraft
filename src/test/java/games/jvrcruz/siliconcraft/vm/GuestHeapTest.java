package games.jvrcruz.siliconcraft.vm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GuestHeapTest {
    @Test
    void reusesUnreachableCyclesAndPreservesRegistersStackAndSavedInterrupts() {
        int[] ram = new int[64];
        GuestHeap.initialize(ram, 4);
        int garbage = allocate(ram, 10);
        int otherGarbage = allocate(ram, 10);
        int live = allocate(ram, 5);
        int child = allocate(ram, 5);
        int stackRoot = allocate(ram, 5);
        int interruptRoot = allocate(ram, 5);
        ram[garbage] = otherGarbage;
        ram[otherGarbage] = garbage;
        ram[live] = child;
        ram[child] = 101;
        ram[stackRoot] = 102;
        ram[interruptRoot] = 103;

        // Simulate loading a saved RAM image and force a collection on allocation.
        int[] restored = ram.clone();
        var context = new VirtualCpu.CpuContext(0, interruptRoot, 0, 0, 0, false, false, true);
        int reused = GuestHeap.allocate(restored, 4, 18, live + 1, 0, 0, 0,
                List.of(stackRoot), List.of(context));

        assertEquals(garbage, reused);
        assertEquals(0, restored[reused]);
        assertEquals(child, restored[live]);
        assertEquals(101, restored[child]);
        assertEquals(102, restored[stackRoot]);
        assertEquals(103, restored[interruptRoot]);
    }

    @Test
    void reportsOutOfMemoryWhenEverythingIsLive() {
        int[] ram = new int[32];
        GuestHeap.initialize(ram, 4);
        ram[0] = allocate(ram, 20);
        assertThrows(IndexOutOfBoundsException.class, () -> allocate(ram, 4));
        assertEquals(9, ram[0]);
    }

    private static int allocate(int[] ram, int words) {
        return GuestHeap.allocate(ram, 4, words, 0, 0, 0, 0, List.of(), List.of());
    }
}
