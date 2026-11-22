package games.jvrcruz.siliconcraft.client.audio;

import net.minecraft.core.BlockPos;
import org.lwjgl.openal.AL10;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ComputerAudioPlayer {
    private static final ExecutorService AUDIO_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SiliconCraft-Audio");
        t.setDaemon(true);
        return t;
    });

    public static void playScreenAudio(byte[] pcmSamples, int sampleRate) {
        if (pcmSamples == null || pcmSamples.length == 0) return;
        AUDIO_EXECUTOR.submit(() -> {
            try {
                if (playViaOpenAl(pcmSamples, sampleRate, null)) {
                    return;
                }
            } catch (Throwable ignored) {}

            // Fallback to Java Sound
            playViaJavaSound(pcmSamples, sampleRate);
        });
    }

    public static void playWorldAudio(BlockPos pos, byte[] pcmSamples, int sampleRate) {
        if (pcmSamples == null || pcmSamples.length == 0) return;
        AUDIO_EXECUTOR.submit(() -> {
            try {
                if (playViaOpenAl(pcmSamples, sampleRate, pos)) {
                    return;
                }
            } catch (Throwable ignored) {}

            // Fallback to Java Sound
            playViaJavaSound(pcmSamples, sampleRate);
        });
    }

    private static boolean playViaOpenAl(byte[] pcmSamples, int sampleRate, BlockPos worldPos) {
        try {
            int buffer = AL10.alGenBuffers();
            if (buffer == 0) return false;

            ByteBuffer byteBuf = MemoryUtil.memAlloc(pcmSamples.length);
            byteBuf.put(pcmSamples);
            byteBuf.flip();

            AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO8, byteBuf, sampleRate);
            MemoryUtil.memFree(byteBuf);

            int source = AL10.alGenSources();
            if (source == 0) {
                AL10.alDeleteBuffers(buffer);
                return false;
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

            // Wait for sound to finish and cleanup
            long durationMs = (long) ((pcmSamples.length / (double) sampleRate) * 1000) + 100;
            Thread.sleep(Math.min(durationMs, 60000));

            AL10.alSourceStop(source);
            AL10.alDeleteSources(source);
            AL10.alDeleteBuffers(buffer);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static void playViaJavaSound(byte[] pcmSamples, int sampleRate) {
        try {
            AudioFormat format = new AudioFormat((float) sampleRate, 8, 1, false, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) return;

            try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
                line.open(format, Math.max(1024, pcmSamples.length));
                line.start();
                line.write(pcmSamples, 0, pcmSamples.length);
                line.drain();
            }
        } catch (Throwable ignored) {}
    }
}
