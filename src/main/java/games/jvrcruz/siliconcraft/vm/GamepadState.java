package games.jvrcruz.siliconcraft.vm;

/** Immutable input sample; safe to publish from the server thread to a CPU worker. */
public record GamepadState(int buttons, int leftX, int leftY, int rightX, int rightY,
                           int leftTrigger, int rightTrigger) {
    public static final int LEFT_TRIGGER_BUTTON = 1 << 12;
    public static final int RIGHT_TRIGGER_BUTTON = 1 << 13;
    public static final int LEFT_STICK_BUTTON = 1 << 14;
    public static final int RIGHT_STICK_BUTTON = 1 << 15;
    public static final int AXIS_LEFT_X = 0, AXIS_LEFT_Y = 1, AXIS_RIGHT_X = 2, AXIS_RIGHT_Y = 3;
    public static final int AXIS_LEFT_TRIGGER = 4, AXIS_RIGHT_TRIGGER = 5;
    public static final GamepadState NEUTRAL = new GamepadState(0, 0, 0, 0, 0, 0, 0);

    public GamepadState {
        buttons &= 0xffff;
        leftX = Math.clamp(leftX, -1000, 1000);
        leftY = Math.clamp(leftY, -1000, 1000);
        rightX = Math.clamp(rightX, -1000, 1000);
        rightY = Math.clamp(rightY, -1000, 1000);
        leftTrigger = Math.clamp(leftTrigger, 0, 1000);
        rightTrigger = Math.clamp(rightTrigger, 0, 1000);
    }

    /** X points right; Y points down. Triggers must already be normalized to 0..1. */
    public static GamepadState fromAnalog(int buttons, float leftX, float leftY, float rightX, float rightY,
                                         float leftTrigger, float rightTrigger) {
        int lx = stick(leftX), ly = stick(leftY), rx = stick(rightX), ry = stick(rightY);
        int lt = trigger(leftTrigger), rt = trigger(rightTrigger);
        // Preserve the pre-0.1.2 digital stick and trigger aliases.
        if (lx > 500) buttons |= 1;
        if (lx < -500) buttons |= 2;
        if (ly > 500) buttons |= 4;
        if (ly < -500) buttons |= 8;
        if (lt > 500) buttons |= LEFT_TRIGGER_BUTTON | (1 << 10);
        if (rt > 500) buttons |= RIGHT_TRIGGER_BUTTON | (1 << 11);
        return new GamepadState(buttons, lx, ly, rx, ry, lt, rt);
    }

    private static int stick(float value) {
        return !Float.isFinite(value) || Math.abs(value) <= 0.15f ? 0 : Math.round(Math.clamp(value, -1f, 1f) * 1000);
    }

    private static int trigger(float value) {
        return !Float.isFinite(value) ? 0 : Math.round(Math.clamp(value, 0f, 1f) * 1000);
    }

    public GamepadState withButtons(int mask) {
        return new GamepadState(mask, leftX, leftY, rightX, rightY, leftTrigger, rightTrigger);
    }

    public int axis(int axis) {
        return switch (axis) {
            case AXIS_LEFT_X -> leftX;
            case AXIS_LEFT_Y -> leftY;
            case AXIS_RIGHT_X -> rightX;
            case AXIS_RIGHT_Y -> rightY;
            case AXIS_LEFT_TRIGGER -> leftTrigger;
            case AXIS_RIGHT_TRIGGER -> rightTrigger;
            default -> 0;
        };
    }
}
