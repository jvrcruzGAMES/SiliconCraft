package games.jvrcruz.siliconcraft.compat.controlify;

import dev.isxander.controlify.api.ControlifyApi;
import dev.isxander.controlify.controller.input.ControllerStateView;
import dev.isxander.controlify.controller.input.GamepadInputs;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import games.jvrcruz.siliconcraft.vm.GamepadState;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * Handles communication with Controlify and connected external physical controllers.
 * This class is only referenced when Controlify is verified to be installed.
 */
public class ControlifyCompatHandler {

    public static boolean isAnyControllerConnected() {
        try {
            if (ControlifyApi.get().getCurrentController().isPresent()) {
                return true;
            }
        } catch (Throwable ignored) {}

        for (int j = GLFW.GLFW_JOYSTICK_1; j <= GLFW.GLFW_JOYSTICK_4; j++) {
            if (GLFW.glfwJoystickPresent(j) && GLFW.glfwJoystickIsGamepad(j)) {
                return true;
            }
        }
        return false;
    }

    public static int pollMask() {
        return pollState().buttons();
    }

    public static GamepadState pollState() {
        try {
            var controller = ControlifyApi.get().getCurrentController();
            if (controller.isPresent()) {
                var input = controller.get().input();
                if (input.isPresent()) return fromControlify(input.get().stateNow());
            }
        } catch (Throwable ignored) {}
        return pollGlfwGamepad();
    }

    private static GamepadState fromControlify(ControllerStateView state) {
        int mask = 0;
        if (state.isButtonDown(GamepadInputs.DPAD_UP_BUTTON)) mask |= VirtualCpu.PAD_UP;
        if (state.isButtonDown(GamepadInputs.DPAD_DOWN_BUTTON)) mask |= VirtualCpu.PAD_DOWN;
        if (state.isButtonDown(GamepadInputs.DPAD_LEFT_BUTTON)) mask |= VirtualCpu.PAD_LEFT;
        if (state.isButtonDown(GamepadInputs.DPAD_RIGHT_BUTTON)) mask |= VirtualCpu.PAD_RIGHT;
        if (state.isButtonDown(GamepadInputs.SOUTH_BUTTON)) mask |= VirtualCpu.PAD_B;
        if (state.isButtonDown(GamepadInputs.EAST_BUTTON)) mask |= VirtualCpu.PAD_A;
        if (state.isButtonDown(GamepadInputs.WEST_BUTTON)) mask |= VirtualCpu.PAD_Y;
        if (state.isButtonDown(GamepadInputs.NORTH_BUTTON)) mask |= VirtualCpu.PAD_X;
        if (state.isButtonDown(GamepadInputs.LEFT_SHOULDER_BUTTON)) mask |= VirtualCpu.PAD_L;
        if (state.isButtonDown(GamepadInputs.RIGHT_SHOULDER_BUTTON)) mask |= VirtualCpu.PAD_R;
        if (state.isButtonDown(GamepadInputs.START_BUTTON)) mask |= VirtualCpu.PAD_START;
        if (state.isButtonDown(GamepadInputs.BACK_BUTTON)) mask |= VirtualCpu.PAD_SELECT;
        if (state.isButtonDown(GamepadInputs.LEFT_STICK_BUTTON)) mask |= VirtualCpu.PAD_LEFT_STICK;
        if (state.isButtonDown(GamepadInputs.RIGHT_STICK_BUTTON)) mask |= VirtualCpu.PAD_RIGHT_STICK;
        return GamepadState.fromAnalog(mask,
                state.getAxisState(GamepadInputs.LEFT_STICK_AXIS_RIGHT) - state.getAxisState(GamepadInputs.LEFT_STICK_AXIS_LEFT),
                state.getAxisState(GamepadInputs.LEFT_STICK_AXIS_DOWN) - state.getAxisState(GamepadInputs.LEFT_STICK_AXIS_UP),
                state.getAxisState(GamepadInputs.RIGHT_STICK_AXIS_RIGHT) - state.getAxisState(GamepadInputs.RIGHT_STICK_AXIS_LEFT),
                state.getAxisState(GamepadInputs.RIGHT_STICK_AXIS_DOWN) - state.getAxisState(GamepadInputs.RIGHT_STICK_AXIS_UP),
                state.getAxisState(GamepadInputs.LEFT_TRIGGER_AXIS), state.getAxisState(GamepadInputs.RIGHT_TRIGGER_AXIS));
    }

    private static GamepadState pollGlfwGamepad() {
        for (int joystick = GLFW.GLFW_JOYSTICK_1; joystick <= GLFW.GLFW_JOYSTICK_4; joystick++) {
            if (!GLFW.glfwJoystickPresent(joystick) || !GLFW.glfwJoystickIsGamepad(joystick)) continue;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                GLFWGamepadState state = GLFWGamepadState.malloc(stack);
                if (!GLFW.glfwGetGamepadState(joystick, state)) continue;
                ByteBuffer buttons = state.buttons();
                FloatBuffer axes = state.axes();
                int mask = 0;
                if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_UP) == GLFW.GLFW_PRESS) mask |= VirtualCpu.PAD_UP;
                if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN) == GLFW.GLFW_PRESS) mask |= VirtualCpu.PAD_DOWN;
                if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_LEFT) == GLFW.GLFW_PRESS) mask |= VirtualCpu.PAD_LEFT;
                if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_RIGHT) == GLFW.GLFW_PRESS) mask |= VirtualCpu.PAD_RIGHT;
                if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_A) == GLFW.GLFW_PRESS) mask |= VirtualCpu.PAD_B;
                if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_B) == GLFW.GLFW_PRESS) mask |= VirtualCpu.PAD_A;
                if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_X) == GLFW.GLFW_PRESS) mask |= VirtualCpu.PAD_Y;
                if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_Y) == GLFW.GLFW_PRESS) mask |= VirtualCpu.PAD_X;
                if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_LEFT_BUMPER) == GLFW.GLFW_PRESS) mask |= VirtualCpu.PAD_L;
                if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER) == GLFW.GLFW_PRESS) mask |= VirtualCpu.PAD_R;
                if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_START) == GLFW.GLFW_PRESS) mask |= VirtualCpu.PAD_START;
                if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_BACK) == GLFW.GLFW_PRESS) mask |= VirtualCpu.PAD_SELECT;
                if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_LEFT_THUMB) == GLFW.GLFW_PRESS) mask |= VirtualCpu.PAD_LEFT_STICK;
                if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_THUMB) == GLFW.GLFW_PRESS) mask |= VirtualCpu.PAD_RIGHT_STICK;
                // GLFW reports triggers as -1 at rest and +1 fully pressed.
                return GamepadState.fromAnalog(mask,
                        axes.get(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X), axes.get(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y),
                        axes.get(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_X), axes.get(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_Y),
                        (axes.get(GLFW.GLFW_GAMEPAD_AXIS_LEFT_TRIGGER) + 1f) / 2f,
                        (axes.get(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER) + 1f) / 2f);
            }
        }
        return GamepadState.NEUTRAL;
    }
}
