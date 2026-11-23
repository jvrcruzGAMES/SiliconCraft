package games.jvrcruz.siliconcraft.vm;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.BitSet;

/** Non-moving, conservative mark-and-sweep heap. Its bookkeeping is saved with guest RAM. */
final class GuestHeap {
    private static final int CONTROL_WORDS = 3;
    private static final int HEADER_WORDS = 2;
    private static final int MIN_BUDGET = 2048;

    private GuestHeap() {}

    static void initialize(int[] ram, int base) {
        if (base < 0 || base > ram.length - CONTROL_WORDS) {
            throw new IndexOutOfBoundsException("N2 heap does not fit in RAM");
        }
        ram[base] = base + CONTROL_WORDS;
        ram[base + 1] = 0;
        ram[base + 2] = MIN_BUDGET;
    }

    static int allocate(int[] ram, int base, int words, int regA, int regB, int regC, int regD,
                        Iterable<Integer> stack,
                        Iterable<VirtualCpu.CpuContext> interrupts) {
        if (words < 1 || base < 0 || base > ram.length - CONTROL_WORDS
                || words > ram.length - base - CONTROL_WORDS - HEADER_WORDS) {
            throw new IndexOutOfBoundsException("N2 heap allocation exceeds installed RAM");
        }
        int start = base + CONTROL_WORDS;
        int top = ram[base];
        if (top < start || top > ram.length) {
            throw new IndexOutOfBoundsException("N2 heap bounds are invalid");
        }
        if (Thread.currentThread().isInterrupted()) return 0;
        int size = words + HEADER_WORDS;
        boolean collected = ram[base + 2] < size;
        if (collected && !collect(ram, base, regA, regB, regC, regD, stack, interrupts)) return 0;

        int block = reserve(ram, base, size);
        if (block == 0 && !collected) {
            if (!collect(ram, base, regA, regB, regC, regD, stack, interrupts)) return 0;
            block = reserve(ram, base, size);
        }
        if (block == 0) {
            if (Thread.currentThread().isInterrupted()) return 0;
            throw new IndexOutOfBoundsException("N2 heap is out of memory");
        }

        ram[base + 2] -= ram[block];
        Arrays.fill(ram, block + HEADER_WORDS, block + ram[block], 0);
        return block + HEADER_WORDS;
    }

    private static int reserve(int[] ram, int base, int size) {
        int top = ram[base];
        int link = base + 1;
        for (int block = ram[link]; block != 0; block = ram[link]) {
            if (Thread.currentThread().isInterrupted()) return 0;
            if (block < base + CONTROL_WORDS || block >= top || ram[block] >= 0) {
                throw new IndexOutOfBoundsException("Invalid N2 free block");
            }
            int available = -blockSize(ram, block, top);
            int next = ram[block + 1];
            if (next != 0 && next < block + available) {
                throw new IndexOutOfBoundsException("Invalid N2 free list");
            }
            if (available >= size) {
                if (available - size > HEADER_WORDS) {
                    int remainder = block + size;
                    ram[remainder] = size - available;
                    ram[remainder + 1] = next;
                    ram[link] = remainder;
                    ram[block] = size;
                } else {
                    ram[link] = next;
                    ram[block] = available;
                }
                return block;
            }
            link = block + 1;
        }
        if (size > ram.length - top) return 0;
        ram[top] = size;
        ram[base] = top + size;
        return top;
    }

    private static int blockSize(int[] ram, int block, int top) {
        int size = ram[block];
        int length = Math.abs(size);
        if (length <= HEADER_WORDS || length > top - block) {
            throw new IndexOutOfBoundsException("Invalid N2 heap block size");
        }
        return size;
    }

    private static boolean collect(int[] ram, int base, int regA, int regB, int regC, int regD,
                                   Iterable<Integer> stack,
                                   Iterable<VirtualCpu.CpuContext> interrupts) {
        int start = base + CONTROL_WORDS;
        int top = ram[base];
        BitSet allocated = new BitSet();
        for (int block = start; block < top; ) {
            if (Thread.currentThread().isInterrupted()) return false;
            int size = blockSize(ram, block, top);
            if (size > 0) allocated.set(block - start);
            block += Math.abs(size);
        }
        var mark = new Marker(ram, start, top, allocated);
        if (!mark.scan(0, base)) return false;
        mark.reference(regA);
        mark.reference(regB);
        mark.reference(regC);
        mark.reference(regD);
        for (int value : stack) mark.reference(value);
        for (VirtualCpu.CpuContext context : interrupts) {
            if (context.restoreRegs()) {
                mark.reference(context.regA());
                mark.reference(context.regB());
                mark.reference(context.regC());
                mark.reference(context.regD());
            }
        }
        while (!mark.pending.isEmpty()) {
            int block = mark.pending.removeLast();
            if (!mark.scan(block + HEADER_WORDS, block + ram[block])) return false;
        }
        return sweep(ram, base, start, top, mark.live);
    }

    private static boolean sweep(int[] ram, int base, int start, int top, BitSet live) {
        int freeLink = base + 1;
        ram[freeLink] = 0;
        // An interrupted sweep leaves a valid block chain and can rebuild the free list on retry.
        ram[base + 2] = 0;
        int liveWords = 0;
        int freeStart = start;
        for (int block = start; block < top; ) {
            if (Thread.currentThread().isInterrupted()) return false;
            int size = Math.abs(ram[block]);
            if (live.get(block - start)) {
                if (freeStart < block) {
                    ram[freeStart] = freeStart - block;
                    ram[freeStart + 1] = 0;
                    ram[freeLink] = freeStart;
                    freeLink = freeStart + 1;
                }
                liveWords += size;
                freeStart = block + size;
            }
            block += size;
        }
        ram[base] = freeStart;
        // Lua-like pause: wait for roughly another live heap's worth of allocations.
        ram[base + 2] = Math.max(MIN_BUDGET, liveWords);
        return true;
    }

    private static final class Marker {
        private final int[] ram;
        private final int start;
        private final int top;
        private final BitSet allocated;
        private final BitSet live = new BitSet();
        private final ArrayDeque<Integer> pending = new ArrayDeque<>();

        private Marker(int[] ram, int start, int top, BitSet allocated) {
            this.ram = ram;
            this.start = start;
            this.top = top;
            this.allocated = allocated;
        }

        private boolean scan(int from, int to) {
            for (int index = from; index < to; index++) {
                if ((index - from) % 1024 == 0 && Thread.currentThread().isInterrupted()) return false;
                reference(ram[index]);
            }
            return true;
        }

        private void reference(int value) {
            if (value < start + HEADER_WORDS || value >= top) return;
            int offset = allocated.previousSetBit(value - start - HEADER_WORDS);
            if (offset < 0 || live.get(offset)) return;
            int block = start + offset;
            if (value >= block + ram[block]) return;
            live.set(offset);
            pending.add(block);
        }
    }
}
