package games.jvrcruz.siliconcraft.client.audio;

import games.jvrcruz.siliconcraft.compat.sable.SubLevelCoordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public class ComputerAudioPlayer {
    private static final int MAX_AUDIO_THREADS = 16;
    private static final int MAX_AUDIO_QUEUE = 64;

    private static ExecutorService audioExecutor = createExecutor();

    private static ExecutorService createExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2,
                MAX_AUDIO_THREADS,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_AUDIO_QUEUE),
                r -> {
                    Thread t = new Thread(r, "SiliconCraft-Audio");
                    t.setDaemon(true);
                    return t;
                },
                (rejected, pool) -> {
                    Runnable oldest = pool.getQueue().poll();
                    if (oldest instanceof StreamTask task) {
                        ACTIVE_STREAMS.remove(task.stream);
                        task.stream.stop();
                    }
                    if (!pool.isShutdown()) {
                        pool.execute(rejected);
                    }
                }
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static ExecutorService getExecutor() {
        if (audioExecutor.isShutdown()) {
            audioExecutor = createExecutor();
        }
        return audioExecutor;
    }

    private static class ActiveAudioStream {
        final BlockPos computerPos;
        final BlockPos worldPos;
        final ResourceKey<Level> dimension;
        volatile Vec3 sourcePosition;
        volatile boolean stopped = false;
        private Thread workerThread;

        ActiveAudioStream(BlockPos computerPos, BlockPos worldPos, Level level) {
            this.computerPos = computerPos;
            this.worldPos = worldPos;
            this.dimension = level == null ? null : level.dimension();
            if (level != null) {
                this.sourcePosition = SubLevelCoordinates.worldPosition(level, Vec3.atCenterOf(worldPos));
            }
        }

        synchronized boolean begin() {
            if (stopped) return false;
            workerThread = Thread.currentThread();
            return true;
        }

        synchronized void finish() {
            workerThread = null;
        }

        synchronized void stop() {
            stopped = true;
            if (workerThread != null) workerThread.interrupt();
        }
    }

    private static final Set<ActiveAudioStream> ACTIVE_STREAMS = ConcurrentHashMap.newKeySet();

    private static void stopMatching(Predicate<ActiveAudioStream> matches) {
        for (ActiveAudioStream stream : ACTIVE_STREAMS) {
            if (matches.test(stream) && ACTIVE_STREAMS.remove(stream)) {
                stream.stop();
            }
        }
    }

    public static void stopAudio(BlockPos pos) {
        if (pos != null) stopMatching(stream -> pos.equals(stream.computerPos) || pos.equals(stream.worldPos));
    }

    public static void stopScreenAudio(BlockPos computerPos) {
        if (computerPos != null) stopMatching(stream -> stream.worldPos == null && computerPos.equals(stream.computerPos));
    }

    public static synchronized void stopAllAudio() {
        stopMatching(stream -> true);
    }

    public static void updateWorldAudio(Level level) {
        for (ActiveAudioStream stream : ACTIVE_STREAMS) {
            if (stream.worldPos == null) continue;
            if (level == null || !level.dimension().equals(stream.dimension) || !level.isLoaded(stream.worldPos)) {
                if (ACTIVE_STREAMS.remove(stream)) stream.stop();
            } else {
                stream.sourcePosition = SubLevelCoordinates.worldPosition(level, Vec3.atCenterOf(stream.worldPos));
            }
        }
    }

    private static void updateSourcePosition(int source, Vec3 position) {
        AL10.alSource3f(source, AL10.AL_POSITION, (float) position.x, (float) position.y, (float) position.z);
    }

    public static synchronized void shutdown() {
        stopAllAudio();
        audioExecutor.shutdownNow();
    }

    public static void playScreenAudio(BlockPos computerPos, byte[] pcmSamples, int sampleRate) {
        play(new ActiveAudioStream(computerPos, null, null), pcmSamples, sampleRate);
    }

    public static void playWorldAudio(Level level, BlockPos computerPos, BlockPos worldPos, byte[] pcmSamples, int sampleRate) {
        play(new ActiveAudioStream(computerPos, worldPos, level), pcmSamples, sampleRate);
    }

    private static final class StreamTask implements Runnable {
        final ActiveAudioStream stream;
        final byte[] pcmSamples;
        final int sampleRate;

        StreamTask(ActiveAudioStream stream, byte[] pcmSamples, int sampleRate) {
            this.stream = stream;
            this.pcmSamples = pcmSamples;
            this.sampleRate = sampleRate;
        }

        @Override
        public void run() {
            try {
                if (!stream.begin()) return;
                if (!playViaOpenAl(stream, pcmSamples, sampleRate, stream.worldPos)) {
                    playViaJavaSound(stream, pcmSamples, sampleRate);
                }
            } finally {
                stream.finish();
                ACTIVE_STREAMS.remove(stream);
            }
        }
    }

    private static synchronized void play(ActiveAudioStream stream, byte[] pcmSamples, int sampleRate) {
        if (pcmSamples == null || pcmSamples.length == 0) return;
        ACTIVE_STREAMS.add(stream);
        try {
            getExecutor().execute(new StreamTask(stream, pcmSamples, sampleRate));
        } catch (Throwable t) {
            ACTIVE_STREAMS.remove(stream);
            stream.stop();
        }
    }

    private static boolean playViaOpenAl(ActiveAudioStream stream, byte[] pcmSamples, int sampleRate, BlockPos worldPos) {
        if (stream.stopped) {
            return false;
        }
        int buffer = 0;
        int source = 0;
        try {
            buffer = AL10.alGenBuffers();
            if (buffer == 0) {
                return false;
            }

            ByteBuffer byteBuf = MemoryUtil.memAlloc(pcmSamples.length);
            try {
                byteBuf.put(pcmSamples);
                byteBuf.flip();
                AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO8, byteBuf, sampleRate);
            } finally {
                MemoryUtil.memFree(byteBuf);
            }

            source = AL10.alGenSources();
            if (source == 0) {
                AL10.alDeleteBuffers(buffer);
                return false;
            }

            if (stream.stopped) {
                AL10.alDeleteSources(source);
                AL10.alDeleteBuffers(buffer);
                return true;
            }

            AL10.alSourcei(source, AL10.AL_BUFFER, buffer);

            Vec3 lastPosition = stream.sourcePosition;
            if (worldPos == null) {
                // UI / Screen Audio: relative to listener
                AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
                AL10.alSource3f(source, AL10.AL_POSITION, 0.0f, 0.0f, 0.0f);
            } else {
                // 3D World Audio at Speaker Position
                AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
                updateSourcePosition(source, lastPosition);
                AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 1.0f);
                AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 4.0f);
                AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, 32.0f);
            }

            AL10.alSourcePlay(source);

            long durationMs = (long) ((pcmSamples.length / (double) sampleRate) * 1000) + 50;
            long maxWait = Math.min(durationMs, 60000);
            long elapsed = 0;

            while (elapsed < maxWait && !stream.stopped) {
                Vec3 position = stream.sourcePosition;
                if (position != null && !position.equals(lastPosition)) {
                    updateSourcePosition(source, position);
                    lastPosition = position;
                }
                int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
                if (state != AL10.AL_PLAYING) {
                    break;
                }
                long step = Math.min(50, maxWait - elapsed);
                Thread.sleep(step);
                elapsed += step;
            }

            AL10.alSourceStop(source);
            AL10.alDeleteSources(source);
            AL10.alDeleteBuffers(buffer);
            return true;
        } catch (InterruptedException e) {
            if (source != 0) {
                try {
                    AL10.alSourceStop(source);
                    AL10.alDeleteSources(source);
                } catch (Throwable ignored) {}
            }
            if (buffer != 0) {
                try {
                    AL10.alDeleteBuffers(buffer);
                } catch (Throwable ignored) {}
            }
            return true;
        } catch (Throwable e) {
            if (source != 0) {
                try {
                    AL10.alDeleteSources(source);
                } catch (Throwable ignored) {}
            }
            if (buffer != 0) {
                try {
                    AL10.alDeleteBuffers(buffer);
                } catch (Throwable ignored) {}
            }
            return false;
        }
    }

    private static void playViaJavaSound(ActiveAudioStream stream, byte[] pcmSamples, int sampleRate) {
        if (stream.stopped) {
            return;
        }
        try {
            AudioFormat format = new AudioFormat((float) sampleRate, 8, 1, false, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                return;
            }

            try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
                line.open(format, Math.max(1024, pcmSamples.length));
                if (stream.stopped) {
                    return;
                }
                line.start();

                int offset = 0;
                while (offset < pcmSamples.length && !stream.stopped) {
                    int chunk = Math.min(line.available(), Math.min(512, pcmSamples.length - offset));
                    if (chunk > 0) {
                        offset += line.write(pcmSamples, offset, chunk);
                    } else {
                        Thread.sleep(10);
                    }
                }

                // Wait for playback without blocking cancellation in SourceDataLine.drain().
                while (!stream.stopped && line.getLongFramePosition() < offset && line.isRunning()) {
                    Thread.sleep(10);
                }
                if (stream.stopped) {
                    line.stop();
                    line.flush();
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
