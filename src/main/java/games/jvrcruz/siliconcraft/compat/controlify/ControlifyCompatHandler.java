package games.jvrcruz.siliconcraft.compat.controlify;

import dev.isxander.controlify.api.ControlifyApi;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.isxander.controlify.controller.input.ControllerStateView;
import dev.isxander.controlify.controller.input.GamepadInputs;
import dev.isxander.controlify.controller.input.InputComponent;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Optional;

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
        int mask = 0;
        try {
            Optional<ControllerEntity> opt = ControlifyApi.get().getCurrentController();
            if (opt.isPresent()) {
                ControllerEntity controller = opt.get();
                Optional<InputComponent> inputOpt = controller.input();
                if (inputOpt.isPresent()) {
                    ControllerStateView state = inputOpt.get().stateNow();

                    // Directional inputs: D-Pad and Left Analog Stick
                    if (state.isButtonDown(GamepadInputs.DPAD_UP_BUTTON) || state.getAxisState(GamepadInputs.LEFT_STICK_AXIS_UP) > 0.5f) {
                        mask |= VirtualCpu.PAD_UP;
                    }
                    if (state.isButtonDown(GamepadInputs.DPAD_DOWN_BUTTON) || state.getAxisState(GamepadInputs.LEFT_STICK_AXIS_DOWN) > 0.5f) {
                        mask |= VirtualCpu.PAD_DOWN;
                    }
                    if (state.isButtonDown(GamepadInputs.DPAD_LEFT_BUTTON) || state.getAxisState(GamepadInputs.LEFT_STICK_AXIS_LEFT) > 0.5f) {
                        mask |= VirtualCpu.PAD_LEFT;
                    }
                    if (state.isButtonDown(GamepadInputs.DPAD_RIGHT_BUTTON) || state.getAxisState(GamepadInputs.LEFT_STICK_AXIS_RIGHT) > 0.5f) {
                        mask |= VirtualCpu.PAD_RIGHT;
                    }

                    // Face Buttons (SNES / Retro Layout: South = B, East = A, West = Y, North = X)
                    if (state.isButtonDown(GamepadInputs.SOUTH_BUTTON)) mask |= VirtualCpu.PAD_B;
                    if (state.isButtonDown(GamepadInputs.EAST_BUTTON))  mask |= VirtualCpu.PAD_A;
                    if (state.isButtonDown(GamepadInputs.WEST_BUTTON))  mask |= VirtualCpu.PAD_Y;
                    if (state.isButtonDown(GamepadInputs.NORTH_BUTTON)) mask |= VirtualCpu.PAD_X;

                    // Shoulders and Triggers
                    if (state.isButtonDown(GamepadInputs.LEFT_SHOULDER_BUTTON) || state.getAxisState(GamepadInputs.LEFT_TRIGGER_AXIS) > 0.5f) {
                        mask |= VirtualCpu.PAD_L;
                    }
                    if (state.isButtonDown(GamepadInputs.RIGHT_SHOULDER_BUTTON) || state.getAxisState(GamepadInputs.RIGHT_TRIGGER_AXIS) > 0.5f) {
                        mask |= VirtualCpu.PAD_R;
                    }

                    // Start and Select / Back
                    if (state.isButtonDown(GamepadInputs.START_BUTTON)) mask |= VirtualCpu.PAD_START;
                    if (state.isButtonDown(GamepadInputs.BACK_BUTTON))  mask |= VirtualCpu.PAD_SELECT;
                }
            }
        } catch (Throwable ignored) {}

        // Supplementary / Fallback GLFW Gamepad check
        if (mask == 0) {
            mask = pollGlfwGamepad();
        }

        return mask;
    }

    private static int pollGlfwGamepad() {
        for (int j = GLFW.GLFW_JOYSTICK_1; j <= GLFW.GLFW_JOYSTICK_4; j++) {
            if (GLFW.glfwJoystickPresent(j) && GLFW.glfwJoystickIsGamepad(j)) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    GLFWGamepadState state = GLFWGamepadState.malloc(stack);
                    if (GLFW.glfwGetGamepadState(j, state)) {
                        ByteBuffer buttons = state.buttons();
                        FloatBuffer axes = state.axes();
                        int mask = 0;

                        if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_UP) == GLFW.GLFW_PRESS || axes.get(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y) < -0.5f) {
                            mask |= VirtualCpu.PAD_UP;
                        }
                        if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN) == GLFW.GLFW_PRESS || axes.get(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y) > 0.5f) {
                            mask |= VirtualCpu.PAD_DOWN;
                        }
                        if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_LEFT) == GLFW.GLFW_PRESS || axes.get(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X) < -0.5f) {
                            mask |= VirtualCpu.PAD_LEFT;
                        }
                        if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_RIGHT) == GLFW.GLFW_PRESS || axes.get(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X) > 0.5f) {
                            mask |= VirtualCpu.PAD_RIGHT;
                        }

                        if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_A) == GLFW.GLFW_PRESS) mask |= VirtualCpu.PAD_B;
                        if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_B) == GLFW.GLFW_PRESS) mask |= VirtualCpu.PAD_A;
                        if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_X) == GLFW.GLFW_PRESS) mask |= VirtualCpu.PAD_Y;
                        if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_Y) == GLFW.GLFW_PRESS) mask |= VirtualCpu.PAD_X;

                        if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_LEFT_BUMPER) == GLFW.GLFW_PRESS || axes.get(GLFW.GLFW_GAMEPAD_AXIS_LEFT_TRIGGER) > 0.5f) {
                            mask |= VirtualCpu.PAD_L;
                        }
                        if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER) == GLFW.GLFW_PRESS || axes.get(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER) > 0.5f) {
                            mask |= VirtualCpu.PAD_R;
                        }

                        if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_START) == GLFW.GLFW_PRESS) mask |= VirtualCpu.PAD_START;
                        if (buttons.get(GLFW.GLFW_GAMEPAD_BUTTON_BACK) == GLFW.GLFW_PRESS) mask |= VirtualCpu.PAD_SELECT;

                        if (mask != 0) {
                            return mask;
                        }
                    }
                }
            }
        }
        return 0;
    }
}
