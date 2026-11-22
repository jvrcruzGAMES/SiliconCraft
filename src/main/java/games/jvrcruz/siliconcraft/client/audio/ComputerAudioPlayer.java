package games.jvrcruz.siliconcraft.client.audio;

import net.minecraft.core.BlockPos;
import org.lwjgl.openal.AL10;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ComputerAudioPlayer {
    private static volatile ExecutorService AUDIO_EXECUTOR = createExecutor();

    private static ExecutorService createExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "SiliconCraft-Audio");
            t.setDaemon(true);
            return t;
        });
    }

    private static synchronized ExecutorService getExecutor() {
        if (AUDIO_EXECUTOR == null || AUDIO_EXECUTOR.isShutdown()) {
            AUDIO_EXECUTOR = createExecutor();
        }
        return AUDIO_EXECUTOR;
    }

    private static class ActiveAudioStream {
        final BlockPos computerPos;
        final BlockPos worldPos;
        volatile int source = 0;
        volatile int buffer = 0;
        volatile SourceDataLine javaSoundLine = null;
        volatile boolean stopped = false;
        volatile Thread workerThread = null;

        ActiveAudioStream(BlockPos computerPos, BlockPos worldPos) {
            this.computerPos = computerPos;
            this.worldPos = worldPos;
        }

        void stop() {
            stopped = true;
            int src = source;
            if (src != 0) {
                try {
                    AL10.alSourceStop(src);
                } catch (Throwable ignored) {}
            }
            SourceDataLine line = javaSoundLine;
            if (line != null) {
                try {
                    line.stop();
                    line.flush();
                } catch (Throwable ignored) {}
            }
            Thread t = workerThread;
            if (t != null) {
                t.interrupt();
            }
        }
    }

    private static final Map<BlockPos, Set<ActiveAudioStream>> ACTIVE_STREAMS = new ConcurrentHashMap<>();

    private static void registerStream(ActiveAudioStream stream) {
        if (stream.computerPos != null) {
            ACTIVE_STREAMS.computeIfAbsent(stream.computerPos, k -> Collections.synchronizedSet(new HashSet<>())).add(stream);
        }
        if (stream.worldPos != null && !stream.worldPos.equals(stream.computerPos)) {
            ACTIVE_STREAMS.computeIfAbsent(stream.worldPos, k -> Collections.synchronizedSet(new HashSet<>())).add(stream);
        }
    }

    private static void unregisterStream(ActiveAudioStream stream) {
        if (stream.computerPos != null) {
            Set<ActiveAudioStream> set = ACTIVE_STREAMS.get(stream.computerPos);
            if (set != null) {
                set.remove(stream);
                if (set.isEmpty()) ACTIVE_STREAMS.remove(stream.computerPos);
            }
        }
        if (stream.worldPos != null && !stream.worldPos.equals(stream.computerPos)) {
            Set<ActiveAudioStream> set = ACTIVE_STREAMS.get(stream.worldPos);
            if (set != null) {
                set.remove(stream);
                if (set.isEmpty()) ACTIVE_STREAMS.remove(stream.worldPos);
            }
        }
    }

    public static void stopAudio(BlockPos pos) {
        if (pos == null) return;
        Set<ActiveAudioStream> directStreams = ACTIVE_STREAMS.remove(pos);
        if (directStreams != null) {
            synchronized (directStreams) {
                for (ActiveAudioStream s : directStreams) {
                    s.stop();
                }
            }
        }
        for (Map.Entry<BlockPos, Set<ActiveAudioStream>> entry : ACTIVE_STREAMS.entrySet()) {
            Set<ActiveAudioStream> set = entry.getValue();
            synchronized (set) {
                Iterator<ActiveAudioStream> it = set.iterator();
                while (it.hasNext()) {
                    ActiveAudioStream s = it.next();
                    if (pos.equals(s.worldPos) || pos.equals(s.computerPos)) {
                        s.stop();
                        it.remove();
                    }
                }
            }
        }
    }

    public static void stopScreenAudio(BlockPos computerPos) {
        if (computerPos == null) return;
        Set<ActiveAudioStream> set = ACTIVE_STREAMS.get(computerPos);
        if (set != null) {
            synchronized (set) {
                Iterator<ActiveAudioStream> it = set.iterator();
                while (it.hasNext()) {
                    ActiveAudioStream s = it.next();
                    if (s.worldPos == null) {
                        s.stop();
                        it.remove();
                    }
                }
            }
        }
    }

    public static void stopAllAudio() {
        for (Set<ActiveAudioStream> set : ACTIVE_STREAMS.values()) {
            synchronized (set) {
                for (ActiveAudioStream s : set) {
                    s.stop();
                }
            }
        }
        ACTIVE_STREAMS.clear();
    }

    public static void shutdown() {
        stopAllAudio();
        synchronized (ComputerAudioPlayer.class) {
            if (AUDIO_EXECUTOR != null && !AUDIO_EXECUTOR.isShutdown()) {
                AUDIO_EXECUTOR.shutdownNow();
            }
        }
    }

    public static void playScreenAudio(BlockPos computerPos, byte[] pcmSamples, int sampleRate) {
        if (pcmSamples == null || pcmSamples.length == 0) return;
        ActiveAudioStream stream = new ActiveAudioStream(computerPos, null);
        registerStream(stream);

        getExecutor().submit(() -> {
            stream.workerThread = Thread.currentThread();
            try {
                if (playViaOpenAl(stream, pcmSamples, sampleRate, null)) {
                    return;
                }
            } catch (Throwable ignored) {}

            // Fallback to Java Sound
            playViaJavaSound(stream, pcmSamples, sampleRate);
        });
    }

    public static void playWorldAudio(BlockPos computerPos, BlockPos worldPos, byte[] pcmSamples, int sampleRate) {
        if (pcmSamples == null || pcmSamples.length == 0) return;
        ActiveAudioStream stream = new ActiveAudioStream(computerPos, worldPos);
        registerStream(stream);

        getExecutor().submit(() -> {
            stream.workerThread = Thread.currentThread();
            try {
                if (playViaOpenAl(stream, pcmSamples, sampleRate, worldPos)) {
                    return;
                }
            } catch (Throwable ignored) {}

            // Fallback to Java Sound
            playViaJavaSound(stream, pcmSamples, sampleRate);
        });
    }

    private static boolean playViaOpenAl(ActiveAudioStream stream, byte[] pcmSamples, int sampleRate, BlockPos worldPos) {
        if (stream.stopped) {
            unregisterStream(stream);
            return false;
        }
        int buffer = 0;
        int source = 0;
        try {
            buffer = AL10.alGenBuffers();
            if (buffer == 0) {
                unregisterStream(stream);
                return false;
            }

            ByteBuffer byteBuf = MemoryUtil.memAlloc(pcmSamples.length);
            byteBuf.put(pcmSamples);
            byteBuf.flip();

            AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO8, byteBuf, sampleRate);
            MemoryUtil.memFree(byteBuf);

            source = AL10.alGenSources();
            if (source == 0) {
                AL10.alDeleteBuffers(buffer);
                unregisterStream(stream);
                return false;
            }

            stream.buffer = buffer;
            stream.source = source;

            if (stream.stopped) {
                AL10.alDeleteSources(source);
                AL10.alDeleteBuffers(buffer);
                unregisterStream(stream);
                return true;
            }

            AL10.alSourcei(source, AL10.AL_BUFFER, buffer);

            if (worldPos == null) {
                // UI / Screen Audio: relative to listener
                AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
                AL10.alSource3f(source, AL10.AL_POSITION, 0.0f, 0.0f, 0.0f);
            } else {
                // 3D World Audio at Speaker Position
                AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
                AL10.alSource3f(source, AL10.AL_POSITION, worldPos.getX() + 0.5f, worldPos.getY() + 0.5f, worldPos.getZ() + 0.5f);
                AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 1.0f);
                AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 4.0f);
                AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, 32.0f);
            }

            AL10.alSourcePlay(source);

            long durationMs = (long) ((pcmSamples.length / (double) sampleRate) * 1000) + 50;
            long maxWait = Math.min(durationMs, 60000);
            long elapsed = 0;

            while (elapsed < maxWait && !stream.stopped) {
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
            unregisterStream(stream);
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
            unregisterStream(stream);
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
            unregisterStream(stream);
            return false;
        }
    }

    private static void playViaJavaSound(ActiveAudioStream stream, byte[] pcmSamples, int sampleRate) {
        if (stream.stopped) {
            unregisterStream(stream);
            return;
        }
        try {
            AudioFormat format = new AudioFormat((float) sampleRate, 8, 1, false, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                unregisterStream(stream);
                return;
            }

            try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
                stream.javaSoundLine = line;
                line.open(format, Math.max(1024, pcmSamples.length));
                line.start();

                int offset = 0;
                while (offset < pcmSamples.length && !stream.stopped) {
                    int chunk = Math.min(512, pcmSamples.length - offset);
                    line.write(pcmSamples, offset, chunk);
                    offset += chunk;
                }

                if (!stream.stopped) {
                    line.drain();
                } else {
                    line.stop();
                    line.flush();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            unregisterStream(stream);
        }
    }
}
