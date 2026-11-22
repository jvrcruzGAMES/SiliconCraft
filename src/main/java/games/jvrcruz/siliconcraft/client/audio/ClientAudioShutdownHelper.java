package games.jvrcruz.siliconcraft.client.audio;

/**
 * Dist-safe helper for invoking client-side audio shutdown and cleanup routines.
 * Ensures server environments do not load OpenAL or client audio classes directly.
 */
public class ClientAudioShutdownHelper {

    public static void shutdown() {
        try {
            ComputerAudioPlayer.shutdown();
        } catch (Throwable ignored) {}
    }

    public static void stopAllAudio() {
        try {
            ComputerAudioPlayer.stopAllAudio();
        } catch (Throwable ignored) {}
    }
}
