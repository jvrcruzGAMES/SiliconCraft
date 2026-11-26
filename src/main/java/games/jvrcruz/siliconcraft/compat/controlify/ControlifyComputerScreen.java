package games.jvrcruz.siliconcraft.compat.controlify;

import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.InputMode;
import dev.isxander.controlify.api.ControlifyApi;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.isxander.controlify.controller.input.GamepadInputs;
import dev.isxander.controlify.screenop.ScreenProcessor;
import dev.isxander.controlify.screenop.ScreenProcessorProvider;
import dev.isxander.controlify.screenop.compat.vanilla.AbstractContainerScreenProcessor;
import dev.isxander.controlify.virtualmouse.VirtualMouseBehaviour;
import games.jvrcruz.siliconcraft.client.screen.ComputerScreen;
import games.jvrcruz.siliconcraft.menu.ComputerMenu;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import games.jvrcruz.siliconcraft.vm.GamepadState;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.Locale;

public final class ControlifyComputerScreen extends ComputerScreen implements ScreenProcessorProvider {
    private static final int TOGGLE_BUTTONS = VirtualCpu.PAD_START | VirtualCpu.PAD_SELECT;
    private ControllerEntity cursorController;
    private boolean toggleHeld;

    private final ScreenProcessor<ControlifyComputerScreen> processor = new AbstractContainerScreenProcessor<>(
            this, () -> hoveredSlot, this::slotClicked, controller -> false) {
        @Override
        public VirtualMouseBehaviour virtualMouseBehaviour() {
            return hasControllerCursor() ? VirtualMouseBehaviour.CURSOR_ONLY : VirtualMouseBehaviour.DISABLED;
        }

        @Override
        public void onControllerUpdate(ControllerEntity controller) {
            var input = controller.input().orElseThrow().stateNow();
            boolean start = input.isButtonDown(GamepadInputs.START_BUTTON);
            boolean select = input.isButtonDown(GamepadInputs.BACK_BUTTON);

            if (start && select && !toggleHeld) {
                toggleHeld = true;
                var mouse = Controlify.instance().virtualMouseHandler();
                if (hasControllerCursor()) {
                    cursorController = null;
                    mouse.disableVirtualMouse();
                    super.onInputModeChanged(InputMode.KEYBOARD_MOUSE);
                } else {
                    cursorController = controller;
                    mouse.enableVirtualMouse();
                    super.onInputModeChanged(Controlify.instance().currentInputMode());
                }
            }
            if (toggleHeld) {
                toggleHeld = start || select;
                return;
            }

            if (hasControllerCursor()) {
                super.onControllerUpdate(controller);
            }
        }

        @Override
        public void onWidgetRebuild() {
            if (hasControllerCursor()) {
                super.onWidgetRebuild();
            }
        }

        @Override
        public void onInputModeChanged(InputMode mode) {
            super.onInputModeChanged(hasControllerCursor() ? mode : InputMode.KEYBOARD_MOUSE);
        }
    };

    public ControlifyComputerScreen(ComputerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    public ScreenProcessor<?> screenProcessor() {
        return processor;
    }

    @Override
    protected boolean hasControllerCursor() {
        return cursorController != null && ControlifyApi.get().getCurrentController().orElse(null) == cursorController;
    }

    @Override
    protected GamepadState computerControllerState() {
        if (hasControllerCursor()) {
            return GamepadState.NEUTRAL;
        }
        GamepadState input = super.computerControllerState();
        return toggleHeld || (input.buttons() & TOGGLE_BUTTONS) == TOGGLE_BUTTONS
                ? input.withButtons(input.buttons() & ~TOGGLE_BUTTONS) : input;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        ControlifyApi.get().getCurrentController().filter(controller -> controller.input().isPresent()).ifPresent(controller -> {
            String hint = hasControllerCursor()
                    ? "screen.siliconcraft.computer.controller.computer"
                    : "screen.siliconcraft.computer.controller.cursor";
            graphics.drawCenteredString(font, Component.translatable(hint, toggleLabel(controller)),
                    width / 2, Math.max(2, topPos - 13), 0xFFFFFFFF);
        });
    }

    private static String toggleLabel(ControllerEntity controller) {
        String type = controller.info().type().namespace().getPath();
        if (type.equals("default")) {
            String name = controller.driverName().toLowerCase(Locale.ROOT);
            if (name.contains("switch")) {
                return "+ / −";
            }
        }
        return switch (type) {
            case "switch" -> "+ / −";
            case "xbox_one", "steam_deck" -> "Menu + View";
            case "dualshock4" -> "Options + Share";
            case "dualsense" -> "Options + Create";
            case "stadia" -> "Menu + Options";
            default -> "Start + Select";
        };
    }
}
