package games.jvrcruz.siliconcraft.client.screen;

import games.jvrcruz.siliconcraft.client.audio.ComputerAudioPlayer;

import games.jvrcruz.siliconcraft.client.renderer.ComputerDisplayRenderer;
import games.jvrcruz.siliconcraft.menu.ComputerMenu;
import games.jvrcruz.siliconcraft.menu.slot.FilteredSlot;
import games.jvrcruz.siliconcraft.network.ComputerActionPacket;
import games.jvrcruz.siliconcraft.network.ComputerInputPacket;
import games.jvrcruz.siliconcraft.network.GamepadInputPacket;
import games.jvrcruz.siliconcraft.vm.GamepadState;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import games.jvrcruz.siliconcraft.compat.controlify.ExternalControllerManager;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

public class ComputerScreen extends AbstractContainerScreen<ComputerMenu> {
    private static final ResourceLocation BACKGROUND = ResourceLocation.fromNamespaceAndPath(
            "siliconcraft", "textures/gui/computer.png");
    private boolean displayInitialized;
    private int lastCpuState = -1;
    private final ComputerDisplayRenderer displayRenderer = new ComputerDisplayRenderer();

    public ComputerScreen(ComputerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 256;
        this.imageHeight = 256;
    }

    @Override
    protected void init() {
        super.init();
        rebuildButtons();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (menu.getCpuState() != lastCpuState) {
            lastCpuState = menu.getCpuState();
            rebuildButtons();
        }
        if (menu.getCpuState() == VirtualCpu.State.RUNNING.ordinal()) {
            padSyncTicks++;
            updateAndSendPadState();
        } else {
            heldKeyCodes.clear();
            releasePadState();
        }
    }

    private void rebuildButtons() {
        this.clearWidgets();

        boolean isRunning = (menu.getCpuState() == VirtualCpu.State.RUNNING.ordinal());

        // Power button
        this.addRenderableWidget(Button.builder(
                Component.literal(isRunning ? "⏻ OFF" : "⏻ ON"),
                b -> {
                    PacketDistributor.sendToServer(new ComputerActionPacket(menu.getBlockEntity().getBlockPos(), 0));
                    b.setMessage(Component.literal(!isRunning ? "⏻ OFF" : "⏻ ON"));
                }
        ).bounds(this.leftPos + 8, this.topPos + 145, 48, 16).build());

        // Reset button
        this.addRenderableWidget(Button.builder(
                Component.literal("↺ RST"),
                b -> PacketDistributor.sendToServer(new ComputerActionPacket(menu.getBlockEntity().getBlockPos(), 1))
        ).bounds(this.leftPos + 58, this.topPos + 145, 36, 16).build());

        // Step button
        this.addRenderableWidget(Button.builder(
                Component.literal("⏯ STEP"),
                b -> PacketDistributor.sendToServer(new ComputerActionPacket(menu.getBlockEntity().getBlockPos(), 2))
        ).bounds(this.leftPos + 96, this.topPos + 145, 38, 16).build());
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int state = menu.getCpuState();
        guiGraphics.blit(BACKGROUND, leftPos, topPos, 0, 0, imageWidth, imageHeight);

        // The CRT keeps its 240 × 135 GUI area.
        int screenLeft = leftPos + 8;
        int screenTop = topPos + 7;
        int screenRight = leftPos + 248;
        int screenBottom = topPos + 142;

        // Direct OpenGL Framebuffer rendering (bypassing Minecraft UI engine)
        if (state != VirtualCpu.State.OFF.ordinal() && menu.getBlockEntity().getCpu().getDisplayMode() == 1) {
            VirtualCpu cpu = menu.getBlockEntity().getCpu();
            if (!displayInitialized || cpu.isFbDirty()) {
                displayRenderer.updatePixels(cpu.getFramebuffer());
                cpu.setFbDirty(false);
                displayInitialized = true;
            }
            displayRenderer.renderDirect(guiGraphics.pose().last().pose(), screenLeft, screenTop, screenRight, screenBottom);
        }

        // Scanlines effect / inner border
        guiGraphics.fill(screenLeft + 1, screenTop + 1, screenRight - 1, screenTop + 2, 0x33103318);

        // Status LED
        int ledX = this.leftPos + 242;
        int ledY = this.topPos + 149;
        int ledColor = switch (state) {
            case 1 -> 0xFF00E676; // RUNNING (Green)
            case 3 -> 0xFFFFB300; // HALTED (Amber)
            case 4 -> 0xFFFF1744; // ERROR (Red)
            default -> 0xFF424242; // OFF (Dark Gray)
        };
        guiGraphics.fill(ledX - 1, ledY - 1, ledX + 9, ledY + 9, 0xFF000000);
        guiGraphics.fill(ledX, ledY, ledX + 8, ledY + 8, ledColor);

    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int state = menu.getCpuState();
        boolean isOff = (state == VirtualCpu.State.OFF.ordinal());

        // Render CRT Terminal Text (only when in text console mode)
        if (isOff) {
            guiGraphics.drawString(this.font, "[PERSONAL COMPUTER POWERED OFF]", 14, 14, 0x447755, false);
            guiGraphics.drawString(this.font, "Press the Power Button below to boot.", 14, 26, 0x336644, false);
        } else if (menu.getBlockEntity().getCpu().getDisplayMode() == 0) {
            List<String> output = menu.getBlockEntity().getCpu().getTerminalOutput();
            int maxLines = 13;
            int start = Math.max(0, output.size() - maxLines);
            int y = 10;
            for (int i = start; i < output.size(); i++) {
                String line = output.get(i);
                int color = 0x33FF66;
                if (line.contains("EXCEPTION") || line.contains("ERROR")) {
                    color = 0xFF5555;
                } else if (line.contains("BIOS") || line.contains("OK")) {
                    color = 0x66DDFF;
                }
                guiGraphics.drawString(this.font, line, 12, y, color, false);
                y += 10;
            }
        }

        // Row 1: CPU State (next to buttons, clearly separated from status LED)
        String stateStr = switch (state) {
            case 1 -> "RUNNING";
            case 2 -> "PAUSED";
            case 3 -> "HALTED";
            case 4 -> "ERROR";
            default -> "OFF";
        };
        guiGraphics.drawString(this.font, "CPU: " + stateStr, 140, 149, 0xDDDDDD, false);

        // Row 2: Runtime RAM & Storage capacities (above inventory, capped to avoid ever touching RAM column)
        int totalRam = menu.getTotalRam();
        int socRam = menu.getSocRam();
        int slotsRam = Math.max(0, totalRam - socRam);
        String totalRamStr = totalRam >= 1024 * 1024 ? (totalRam / 1024) + "KB" : (totalRam >= 1024 ? (totalRam / 1024) + "KB" : totalRam + "B");
        String slotsRamStr = slotsRam >= 1024 * 1024 ? (slotsRam / 1024) + "KB" : (slotsRam >= 1024 ? (slotsRam / 1024) + "KB" : slotsRam + "B");
        String ramStr = totalRamStr + (slotsRam > 0 ? " (+" + slotsRamStr + ")" : "");

        int storage = menu.getTotalStorage();
        boolean hasNvme = menu.hasNvme();
        String storageDisplay = storage >= 1024 ? (storage / 1024) + "MB" : (storage > 0 ? storage + "KB" : "None");
        String storageStr = (storage > 0 ? storageDisplay : "None");

        String statusText = "RAM: " + ramStr + " | " + (hasNvme ? "NVMe: " : "Drive: ") + storageStr;
        if (this.font.width(statusText) > 165) {
            statusText = "RAM: " + totalRamStr + " | " + (hasNvme ? "NVMe: " : "Drive: ") + storageStr;
        }
        guiGraphics.drawString(this.font, statusText, 10, 165, 0x88CCFF, false);

        // Dedicated slot column header labels (at x=188 and x=220, completely free of any text collision)
        guiGraphics.drawString(this.font, "RAM", 186, 165, 0x88CC88, false);
        guiGraphics.drawString(this.font, "Drive", 222, 165, 0x88CCFF, false);
    }

    private double lastMouseX;
    private double lastMouseY;

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        clearUnhoveredButtonFocus(mouseX, mouseY);
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        clearUnhoveredButtonFocus(mouseX, mouseY);
        this.renderTooltip(guiGraphics, mouseX, mouseY);

        // Empty slot tooltip
        if (this.hoveredSlot != null && !this.hoveredSlot.hasItem() && this.menu.getCarried().isEmpty()) {
            if (this.hoveredSlot instanceof FilteredSlot fs) {
                guiGraphics.renderTooltip(this.font, fs.getEmptyTooltip(), mouseX, mouseY);
            }
        }
    }

    private void clearUnhoveredButtonFocus(double mouseX, double mouseY) {
        for (net.minecraft.client.gui.components.events.GuiEventListener child : this.children()) {
            if (child instanceof Button button) {
                if (button.isFocused() && !button.isMouseOver(mouseX, mouseY)) {
                    button.setFocused(false);
                }
            }
        }
        if (this.getFocused() instanceof Button button && !button.isMouseOver(mouseX, mouseY)) {
            this.setFocused(null);
        }
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        super.mouseMoved(mouseX, mouseY);
        clearUnhoveredButtonFocus(mouseX, mouseY);
        if (!hasControllerCursor() && menu.getCpuState() == VirtualCpu.State.RUNNING.ordinal()) {
            double relX = (mouseX - (this.leftPos + 8)) / 240.0;
            double relY = (mouseY - (this.topPos + 7)) / 135.0;
            int crtX = (int) Math.floor(relX * VirtualCpu.FB_WIDTH);
            int crtY = (int) Math.floor(relY * VirtualCpu.FB_HEIGHT);
            if (crtX >= 0 && crtX < VirtualCpu.FB_WIDTH && crtY >= 0 && crtY < VirtualCpu.FB_HEIGHT) {
                PacketDistributor.sendToServer(new ComputerInputPacket(menu.getBlockEntity().getBlockPos(), 1, crtX, crtY, 0));
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        if (!hasControllerCursor() && menu.getCpuState() == VirtualCpu.State.RUNNING.ordinal()) {
            double relX = (mouseX - (this.leftPos + 8)) / 240.0;
            double relY = (mouseY - (this.topPos + 7)) / 135.0;
            int crtX = (int) Math.floor(relX * VirtualCpu.FB_WIDTH);
            int crtY = (int) Math.floor(relY * VirtualCpu.FB_HEIGHT);
            if (crtX >= 0 && crtX < VirtualCpu.FB_WIDTH && crtY >= 0 && crtY < VirtualCpu.FB_HEIGHT) {
                PacketDistributor.sendToServer(new ComputerInputPacket(menu.getBlockEntity().getBlockPos(), 2, crtX, crtY, button));
            }
        }
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        clearUnhoveredButtonFocus(mouseX, mouseY);
        // Clear focus immediately from computer chassis buttons so they never hold selection or intercept keystrokes
        if (this.getFocused() instanceof Button b) {
            b.setFocused(false);
            this.setFocused(null);
        }
        return handled;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (menu.getCpuState() == VirtualCpu.State.RUNNING.ordinal()) {
            PacketDistributor.sendToServer(new ComputerInputPacket(menu.getBlockEntity().getBlockPos(), 0, (int) codePoint, 0, modifiers));
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    private final java.util.Set<Integer> heldKeyCodes = new java.util.HashSet<>();
    private GamepadState lastSentPadState = GamepadState.NEUTRAL;
    private int padSyncTicks = 20;

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // Escape: closes the screen
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (menu.getCpuState() == VirtualCpu.State.RUNNING.ordinal()) {
            heldKeyCodes.add(keyCode);
            updateAndSendPadState();

            if (keyCode == 257 || keyCode == 335) { // Enter / Numpad Enter -> '\n' (10)
                PacketDistributor.sendToServer(new ComputerInputPacket(menu.getBlockEntity().getBlockPos(), 0, 10, scanCode, modifiers));
                return true;
            } else if (keyCode == 259) { // Backspace -> 8
                PacketDistributor.sendToServer(new ComputerInputPacket(menu.getBlockEntity().getBlockPos(), 0, 8, scanCode, modifiers));
                return true;
            } else if (keyCode == 258) { // Tab -> 9
                PacketDistributor.sendToServer(new ComputerInputPacket(menu.getBlockEntity().getBlockPos(), 0, 9, scanCode, modifiers));
                return true;
            }
            // Consume all other keystrokes while running so they are sent to the running computer
            // and NEVER interact with UI elements (buttons, hotbar swap, inventory close, etc.)
            return true;
        }
        clearUnhoveredButtonFocus(this.lastMouseX, this.lastMouseY);
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (menu.getCpuState() == VirtualCpu.State.RUNNING.ordinal()) {
            if (heldKeyCodes.remove(keyCode)) {
                updateAndSendPadState();
            }
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    private void updateAndSendPadState() {
        int mask = 0;
        for (int k : heldKeyCodes) {
            // Right: Arrow Right (262) or D (68)
            if (k == 262 || k == 68) mask |= VirtualCpu.PAD_RIGHT;
            // Left: Arrow Left (263) or A (65)
            if (k == 263 || k == 65) mask |= VirtualCpu.PAD_LEFT;
            // Down: Arrow Down (264) or S (83)
            if (k == 264 || k == 83) mask |= VirtualCpu.PAD_DOWN;
            // Up: Arrow Up (265) or W (87)
            if (k == 265 || k == 87) mask |= VirtualCpu.PAD_UP;
            // Start: Enter (257) or Keypad Enter (335)
            if (k == 257 || k == 335) mask |= VirtualCpu.PAD_START;
            // Select: Shift (340 / 344)
            if (k == 340 || k == 344) mask |= VirtualCpu.PAD_SELECT;
            // B (Jump): Space (32) or Z (90)
            if (k == 32 || k == 90) mask |= VirtualCpu.PAD_B;
            // A (Spin jump): X (88)
            if (k == 88) mask |= VirtualCpu.PAD_A;
            // Y (Run / dash): C (67) or J (74)
            if (k == 67 || k == 74) mask |= VirtualCpu.PAD_Y;
            // X: V (86) or K (75)
            if (k == 86 || k == 75) mask |= VirtualCpu.PAD_X;
            // L: Q (81) or U (85)
            if (k == 81 || k == 85) mask |= VirtualCpu.PAD_L;
            // R: E (69) or I (73)
            if (k == 69 || k == 73) mask |= VirtualCpu.PAD_R;
        }

        GamepadState controller = computerControllerState();
        GamepadState input = controller.withButtons(controller.buttons() | mask);
        if (!input.equals(lastSentPadState) || padSyncTicks >= 20) {
            lastSentPadState = input;
            padSyncTicks = 0;
            PacketDistributor.sendToServer(new GamepadInputPacket(menu.getBlockEntity().getBlockPos(), input));
        }
    }

    protected boolean hasControllerCursor() {
        return false;
    }

    protected GamepadState computerControllerState() {
        return ExternalControllerManager.pollControllerState();
    }

    private void releasePadState() {
        if (!lastSentPadState.equals(GamepadState.NEUTRAL)) {
            PacketDistributor.sendToServer(new GamepadInputPacket(menu.getBlockEntity().getBlockPos(), GamepadState.NEUTRAL));
            lastSentPadState = GamepadState.NEUTRAL;
        }
        padSyncTicks = 20;
    }

    @Override
    public void onClose() {
        releasePadState();
        super.onClose();
        displayRenderer.close();
        if (menu != null && menu.getBlockEntity() != null) {
            ComputerAudioPlayer.stopScreenAudio(menu.getBlockEntity().getBlockPos());
        }
    }
}
