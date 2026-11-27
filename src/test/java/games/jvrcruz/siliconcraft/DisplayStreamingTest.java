package games.jvrcruz.siliconcraft;

import games.jvrcruz.siliconcraft.client.ClientDisplayCache;
import games.jvrcruz.siliconcraft.network.FramebufferCodec;
import games.jvrcruz.siliconcraft.vm.ComputerHost;
import games.jvrcruz.siliconcraft.vm.ComputerThreadManager;
import games.jvrcruz.siliconcraft.vm.DisplayStreamSession;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class DisplayStreamingTest {
    private static final int FB_WIDTH = VirtualCpu.FB_WIDTH;
    private static final int FB_HEIGHT = VirtualCpu.FB_HEIGHT;
    private static final int FB_PIXELS = FB_WIDTH * FB_HEIGHT;

    @BeforeEach
    @AfterEach
    public void cleanup() {
        ComputerThreadManager.shutdown();
        ClientDisplayCache.clearAll();
    }

    private static int[] solidFrame(int argb) {
        int[] px = new int[FB_PIXELS];
        Arrays.fill(px, argb);
        return px;
    }

    private static void fillRows(int[] px, int startRow, int endRowExclusive, int argb) {
        Arrays.fill(px, startRow * FB_WIDTH, endRowExclusive * FB_WIDTH, argb);
    }

    @Test
    public void testCodecKeyframeDeltaFallbackUnchangedAndChunks() {
        int[] frame1 = solidFrame(0xFF112233);
        fillRows(frame1, 0, 20, 0xFFAABBCC);

        // Initial keyframe
        FramebufferCodec.EncodedFrame kf = FramebufferCodec.encodeStreamFrame(frame1, null, true);
        assertTrue(kf.changed());
        assertTrue(kf.keyframe());
        assertFalse(kf.chunks().isEmpty());

        int[] decoded = new int[FB_PIXELS];
        assertTrue(FramebufferCodec.applyStreamFrame(FramebufferCodec.joinChunks(kf.chunks()), kf.keyframe(), decoded));
        assertArrayEquals(frame1, decoded);

        // Identical frame is skipped
        FramebufferCodec.EncodedFrame unchanged = FramebufferCodec.encodeStreamFrame(frame1, frame1.clone(), false);
        assertFalse(unchanged.changed());
        assertTrue(unchanged.chunks().isEmpty());

        // Sparse row delta (2 small runs)
        int[] frame2 = frame1.clone();
        fillRows(frame2, 10, 16, 0xFF00FF44);
        fillRows(frame2, 220, 228, 0xFFFF5500);
        FramebufferCodec.EncodedFrame delta = FramebufferCodec.encodeStreamFrame(frame2, frame1, false);
        assertTrue(delta.changed());
        assertFalse(delta.keyframe(), "Sparse row changes should emit row-delta instead of keyframe");

        assertTrue(FramebufferCodec.applyStreamFrame(FramebufferCodec.joinChunks(delta.chunks()), delta.keyframe(), decoded));
        assertArrayEquals(frame2, decoded);

        // >75% dirty rows automatically upgrades to keyframe
        int[] frame3 = frame2.clone();
        fillRows(frame3, 0, 450, 0xFF778899); // 450 / 540 > 75%
        FramebufferCodec.EncodedFrame autoKf = FramebufferCodec.encodeStreamFrame(frame3, frame2, false);
        assertTrue(autoKf.changed());
        assertTrue(autoKf.keyframe(), ">75% dirty rows must fall back to keyframe");
        assertTrue(FramebufferCodec.applyStreamFrame(FramebufferCodec.joinChunks(autoKf.chunks()), autoKf.keyframe(), decoded));
        assertArrayEquals(frame3, decoded);

        // Multi-chunk split and reassembly
        byte[] large = new byte[FramebufferCodec.STREAM_CHUNK_BYTES * 2 + 12345];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) (i * 31 + 7);
        }
        List<byte[]> split = FramebufferCodec.splitChunks(large);
        assertEquals(3, split.size());
        assertEquals(FramebufferCodec.STREAM_CHUNK_BYTES, split.get(0).length);
        assertEquals(FramebufferCodec.STREAM_CHUNK_BYTES, split.get(1).length);
        assertEquals(12345, split.get(2).length);
        assertArrayEquals(large, FramebufferCodec.joinChunks(split));
    }

    @Test
    public void testClientDisplayCacheAtomicChunksAndOutOfOrderRejection() {
        BlockPos pos = new BlockPos(10, 64, -20);
        int[] frame1 = solidFrame(0xFF010203);
        int[] frame2 = solidFrame(0xFF0A0B0C);
        int[] frame3 = solidFrame(0xFFAA5500);

        byte[] payload1 = FramebufferCodec.joinChunks(FramebufferCodec.encodeStreamFrame(frame1, null, true).chunks());
        byte[] payload2 = FramebufferCodec.joinChunks(FramebufferCodec.encodeStreamFrame(frame2, null, true).chunks());
        byte[] payload3 = FramebufferCodec.joinChunks(FramebufferCodec.encodeStreamFrame(frame3, null, true).chunks());

        // Split payload1 artificially into 3 parts to simulate multi-chunk streaming
        int p1CutA = Math.max(1, payload1.length / 3);
        int p1CutB = Math.max(p1CutA + 1, (payload1.length * 2) / 3);
        byte[] c0 = Arrays.copyOfRange(payload1, 0, p1CutA);
        byte[] c1 = Arrays.copyOfRange(payload1, p1CutA, p1CutB);
        byte[] c2 = Arrays.copyOfRange(payload1, p1CutB, payload1.length);

        int[] readBuf = new int[FB_PIXELS];

        // Partial chunks must not expose an incomplete frame
        ClientDisplayCache.onDisplayFrame(pos, 1, 1, 2, 3, true, c2); // out-of-order last chunk first
        assertEquals(-1L, ClientDisplayCache.copyFrameIfUpdated(pos, 0L, readBuf));

        ClientDisplayCache.onDisplayFrame(pos, 1, 1, 0, 3, true, c0);
        assertEquals(-1L, ClientDisplayCache.copyFrameIfUpdated(pos, 0L, readBuf));

        // Final missing chunk arrives -> frame 1 becomes visible atomically
        ClientDisplayCache.onDisplayFrame(pos, 1, 1, 1, 3, true, c1);
        long v1 = ClientDisplayCache.copyFrameIfUpdated(pos, 0L, readBuf);
        assertTrue(v1 > 0L);
        assertEquals(0xFF010203, readBuf[0]);
        assertEquals(0xFF010203, readBuf[FB_PIXELS - 1]);
        assertEquals(1, ClientDisplayCache.getDisplayMode(pos, 0));

        // Newer frame 3 supersedes incomplete frame 2 (missing chunk) cleanly
        int half2 = Math.max(1, payload2.length / 2);
        ClientDisplayCache.onDisplayFrame(pos, 1, 2, 0, 2, true, Arrays.copyOfRange(payload2, 0, half2));
        assertEquals(v1, ClientDisplayCache.copyFrameIfUpdated(pos, v1, readBuf), "Incomplete frame 2 must not advance version");

        ClientDisplayCache.onDisplayFrame(pos, 1, 3, 0, 1, true, payload3);
        long v3 = ClientDisplayCache.copyFrameIfUpdated(pos, v1, readBuf);
        assertTrue(v3 > v1);
        assertEquals(0xFFAA5500, readBuf[0]);

        // Stale frame 2 completion packet arriving late is rejected
        ClientDisplayCache.onDisplayFrame(pos, 1, 2, 1, 2, true, Arrays.copyOfRange(payload2, half2, payload2.length));
        assertEquals(v3, ClientDisplayCache.copyFrameIfUpdated(pos, v3, readBuf));
        assertEquals(0xFFAA5500, readBuf[12345]);

        // Terminal & mode switching
        ClientDisplayCache.onTerminalSync(pos, List.of("BIOS OK", "60+ FPS"));
        assertEquals(List.of("BIOS OK", "60+ FPS"), ClientDisplayCache.getTerminalLines(pos));

        ClientDisplayCache.onDisplayFrame(pos, 0, 4, 0, 1, true, new byte[0]);
        assertEquals(0, ClientDisplayCache.getDisplayMode(pos, 1));

        ClientDisplayCache.remove(pos);
        assertEquals(-1L, ClientDisplayCache.copyFrameIfUpdated(pos, 0L, readBuf));
        assertNull(ClientDisplayCache.getTerminalLines(pos));
    }

    @Test
    public void testClientDisplayCacheConcurrentTearFreeReads() throws Exception {
        BlockPos pos = new BlockPos(0, 80, 0);
        int framesToPublish = 24;
        List<byte[]> encodedPayloads = new ArrayList<>(framesToPublish);
        for (int i = 0; i < framesToPublish; i++) {
            int color = 0xFF000000 | ((i + 1) * 0x090503);
            encodedPayloads.add(FramebufferCodec.joinChunks(
                    FramebufferCodec.encodeStreamFrame(solidFrame(color), null, true).chunks()));
        }

        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicBoolean done = new AtomicBoolean(false);
        CountDownLatch readerStarted = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            int[] readBuf = new int[FB_PIXELS];
            long ver = 0L;
            readerStarted.countDown();
            while (!done.get() && !failed.get()) {
                long nextVer = ClientDisplayCache.copyFrameIfUpdated(pos, ver, readBuf);
                if (nextVer > 0 && nextVer != ver) {
                    ver = nextVer;
                    int first = readBuf[0];
                    int mid = readBuf[FB_PIXELS / 2];
                    int last = readBuf[FB_PIXELS - 1];
                    if (first != mid || first != last) {
                        failed.set(true);
                    }
                }
            }
        });
        reader.setDaemon(true);
        reader.start();
        assertTrue(readerStarted.await(1, TimeUnit.SECONDS));

        for (int i = 0; i < framesToPublish; i++) {
            byte[] p = encodedPayloads.get(i);
            int split = p.length / 2;
            ClientDisplayCache.onDisplayFrame(pos, 1, i + 1, 0, 2, true, Arrays.copyOfRange(p, 0, split));
            ClientDisplayCache.onDisplayFrame(pos, 1, i + 1, 1, 2, true, Arrays.copyOfRange(p, split, p.length));
            Thread.sleep(2);
        }
        done.set(true);
        reader.join(1000);
        assertFalse(failed.get(), "Reader must never observe a torn framebuffer");
    }

    private static class StreamingTestHost implements ComputerHost {
        final VirtualCpu cpu = new VirtualCpu();
        final DisplayStreamSession session;
        final UUID viewerId = UUID.randomUUID();
        final BlockPos pos;
        final List<Integer> emittedFrameColors = new CopyOnWriteArrayList<>();
        final List<Long> emitTimestampsNanos = new CopyOnWriteArrayList<>();
        volatile boolean removed = false;

        StreamingTestHost(BlockPos pos, List<String> program, boolean withGpu, long minIntervalNanos) {
            this.pos = pos;
            this.session = new DisplayStreamSession(minIntervalNanos);
            Set<String> isa = Set.of(
                    "MOV", "ADD", "JMP", "HLT",
                    "FBMODE", "FBCLR",
                    "GPUCLR", "GPUSWAP"
            );
            cpu.configure(isa, 4096, program, false, 0);
            if (withGpu) {
                cpu.configureGpu(true, "TestGPU", 2048 * 1024, 4);
            }
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

        @Override
        public void tickAsyncCpu(int cycles) {
            if (cpu.getState() == VirtualCpu.State.RUNNING) {
                cpu.tick(cycles, 1_500_000L);
            }
        }

        @Override
        public boolean hasPendingSyncWork() {
            return cpu.isAnyFbDirty();
        }

        @Override
        public boolean tickAsyncSync(long nowNanos) {
            return session.tickDisplay(cpu, List.of(viewerId), nowNanos, (frame, broadcast) -> {
                int total = frame.chunks().size();
                for (int i = 0; i < total; i++) {
                    ClientDisplayCache.onDisplayFrame(
                            pos,
                            frame.mode(),
                            frame.frameId(),
                            i,
                            total,
                            frame.keyframe(),
                            frame.chunks().get(i)
                    );
                }
                if (frame.mode() == 1) {
                    int[] px = new int[FB_PIXELS];
                    long ver = ClientDisplayCache.copyFrameIfUpdated(pos, 0L, px);
                    if (ver > 0) {
                        emittedFrameColors.add(px[0]);
                        emitTimestampsNanos.add(System.nanoTime());
                    }
                }
            });
        }
    }

    @Test
    public void testOffTickSyncStreamsMultipleFramesPer50MsForCpuAndGpu() throws Exception {
        // Software CPU render loop changing frame color every iteration
        List<String> cpuProgram = List.of(
                "  FBMODE 1",
                "  MOV A, 0xFF010101",
                "loop:",
                "  FBCLR A",
                "  ADD A, 0x00030507",
                "  JMP loop"
        );
        // Hardware GPU render loop clearing & swapping frames continuously
        List<String> gpuProgram = List.of(
                "  FBMODE 1",
                "  MOV A, 0xFF102030",
                "loop:",
                "  GPUCLR A",
                "  GPUSWAP",
                "  ADD A, 0x00020406",
                "  JMP loop"
        );

        BlockPos cpuPos = new BlockPos(1, 64, 1);
        BlockPos gpuPos = new BlockPos(2, 64, 2);

        // Use 5 ms interval cap (~200 Hz ceiling) to prove multiple updates occur within <50 ms (no server tick calls)
        StreamingTestHost cpuHost = new StreamingTestHost(cpuPos, cpuProgram, false, 5_000_000L);
        StreamingTestHost gpuHost = new StreamingTestHost(gpuPos, gpuProgram, true, 5_000_000L);

        ComputerThreadManager.register(cpuHost);
        ComputerThreadManager.register(gpuHost);

        assertTrue(ComputerThreadManager.getActiveSyncWorkerCount() >= 1,
                "SyncWorkerThread must start when computers are registered");

        long deadline = System.currentTimeMillis() + 2000;
        while ((cpuHost.emittedFrameColors.size() < 6 || gpuHost.emittedFrameColors.size() < 6)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }

        assertTrue(cpuHost.emittedFrameColors.size() >= 6,
                "CPU framebuffer should stream >20 updates/sec off-tick, got " + cpuHost.emittedFrameColors.size()
                        + " state=" + cpuHost.cpu.getState() + " err=" + cpuHost.cpu.getErrorMessage()
                        + " mode=" + cpuHost.cpu.getDisplayMode() + " cycles=" + cpuHost.cpu.getCycles());
        assertTrue(gpuHost.emittedFrameColors.size() >= 6,
                "GPU GPUSWAP should stream >20 updates/sec off-tick, got " + gpuHost.emittedFrameColors.size()
                        + " state=" + gpuHost.cpu.getState() + " err=" + gpuHost.cpu.getErrorMessage()
                        + " mode=" + gpuHost.cpu.getDisplayMode() + " cycles=" + gpuHost.cpu.getCycles());

        // Verify distinct frames decoded into ClientDisplayCache
        assertNotEquals(cpuHost.emittedFrameColors.get(0), cpuHost.emittedFrameColors.get(cpuHost.emittedFrameColors.size() - 1));
        assertNotEquals(gpuHost.emittedFrameColors.get(0), gpuHost.emittedFrameColors.get(gpuHost.emittedFrameColors.size() - 1));

        // Verify multiple frames arrived faster than 1 server tick (50 ms) between earliest and subsequent frames
        long cpuSpanFor5FramesMs = TimeUnit.NANOSECONDS.toMillis(
                cpuHost.emitTimestampsNanos.get(cpuHost.emitTimestampsNanos.size() - 1) - cpuHost.emitTimestampsNanos.get(0));
        long gpuSpanFor5FramesMs = TimeUnit.NANOSECONDS.toMillis(
                gpuHost.emitTimestampsNanos.get(gpuHost.emitTimestampsNanos.size() - 1) - gpuHost.emitTimestampsNanos.get(0));
        assertTrue(cpuHost.emittedFrameColors.size() * 1000.0 / Math.max(1L, cpuSpanFor5FramesMs) > 25.0,
                "Effective CPU frame stream rate must exceed 20 FPS (span=" + cpuSpanFor5FramesMs + "ms for "
                        + cpuHost.emittedFrameColors.size() + " frames)");
        assertTrue(gpuHost.emittedFrameColors.size() * 1000.0 / Math.max(1L, gpuSpanFor5FramesMs) > 25.0,
                "Effective GPU frame stream rate must exceed 20 FPS (span=" + gpuSpanFor5FramesMs + "ms for "
                        + gpuHost.emittedFrameColors.size() + " frames)");

        long shutdownStart = System.currentTimeMillis();
        ComputerThreadManager.shutdown();
        long shutdownElapsed = System.currentTimeMillis() - shutdownStart;
        assertEquals(0, ComputerThreadManager.getActiveSyncWorkerCount());
        assertTrue(shutdownElapsed < 250, "Shutdown with sync workers must stay fast (<250ms), took " + shutdownElapsed + "ms");
    }
}
