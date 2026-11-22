package games.jvrcruz.siliconcraft.compat.controlify;

import net.neoforged.fml.ModList;

public class ExternalControllerManager {
    private static Boolean controlifyInstalled = null;

    /**
     * Checks if external controller support is enabled.
     * External controller support is enabled if and only if Controlify is installed.
     */
    public static boolean isControllerSupportEnabled() {
        if (controlifyInstalled == null) {
            try {
                controlifyInstalled = ModList.get().isLoaded("controlify");
            } catch (Throwable ignored) {
                controlifyInstalled = false;
            }
        }
        return controlifyInstalled;
    }

    /**
     * Polls the external controller state mask.
     * Returns 0 if external controller support is disabled (Controlify not installed)
     * or if no controller buttons are currently pressed.
     */
    public static int pollControllerMask() {
        if (!isControllerSupportEnabled()) {
            return 0;
        }
        try {
            return ControlifyCompatHandler.pollMask();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * Returns true if a controller is currently connected and detected.
     */
    public static boolean isControllerConnected() {
        if (!isControllerSupportEnabled()) {
            return false;
        }
        try {
            return ControlifyCompatHandler.isAnyControllerConnected();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
