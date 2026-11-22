package games.jvrcruz.siliconcraft.client.screen;

import games.jvrcruz.siliconcraft.client.renderer.ComputerDisplayRenderer;
import games.jvrcruz.siliconcraft.menu.ComputerMenu;
import games.jvrcruz.siliconcraft.menu.slot.FilteredSlot;
import games.jvrcruz.siliconcraft.network.ComputerActionPacket;
import games.jvrcruz.siliconcraft.network.ComputerInputPacket;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

public class ComputerScreen extends AbstractContainerScreen<ComputerMenu> {
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
        // Monitor outer chassis bezel
        guiGraphics.fill(this.leftPos, this.topPos, this.leftPos + this.imageWidth, this.topPos + this.imageHeight, 0xFF1C1E22);
        guiGraphics.fill(this.leftPos + 2, this.topPos + 2, this.leftPos + this.imageWidth - 2, this.topPos + this.imageHeight - 2, 0xFF2B2E36);

        // CRT phosphor screen (240x135 - 16:9 aspect ratio)
        int screenLeft = this.leftPos + 8;
        int screenTop = this.topPos + 7;
        int screenRight = this.leftPos + 248;
        int screenBottom = this.topPos + 142;
        guiGraphics.fill(screenLeft, screenTop, screenRight, screenBottom, 0xFF051009);

        // Direct OpenGL Framebuffer rendering (bypassing Minecraft UI engine)
        if (state != VirtualCpu.State.OFF.ordinal() && menu.getBlockEntity().getCpu().getDisplayMode() == 1) {
            displayRenderer.updatePixels(menu.getBlockEntity().getCpu().getFramebuffer());
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

        // Player Inventory Panel (Left side)
        guiGraphics.fill(this.leftPos + 8, this.topPos + 174, this.leftPos + 174, this.topPos + 232, 0xFF181A20);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                renderSlotFrame(guiGraphics, this.leftPos + 10 + col * 18, this.topPos + 176 + row * 18);
            }
        }

        // Player Hotbar Panel
        guiGraphics.fill(this.leftPos + 8, this.topPos + 232, this.leftPos + 174, this.topPos + 252, 0xFF181A20);
        for (int col = 0; col < 9; col++) {
            renderSlotFrame(guiGraphics, this.leftPos + 10 + col * 18, this.topPos + 234);
        }

        // Expansion Slots Panels (Right side, oriented vertically)
        // RAM panel (4 vertical slots: 3 matching main inv rows + 1 matching hotbar row)
        guiGraphics.fill(this.leftPos + 182, this.topPos + 174, this.leftPos + 208, this.topPos + 252, 0xFF16181D);
        for (int i = 0; i < 3; i++) {
            renderSlotFrame(guiGraphics, this.leftPos + 186, this.topPos + 176 + i * 18);
        }
        renderSlotFrame(guiGraphics, this.leftPos + 186, this.topPos + 234);

        // NVMe panel (2 vertical slots)
        guiGraphics.fill(this.leftPos + 216, this.topPos + 174, this.leftPos + 242, this.topPos + 214, 0xFF16181D);
        for (int i = 0; i < 2; i++) {
            renderSlotFrame(guiGraphics, this.leftPos + 220, this.topPos + 176 + i * 18);
        }
    }

    private void renderSlotFrame(GuiGraphics guiGraphics, int x, int y) {
        guiGraphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF353A45);
        guiGraphics.fill(x, y, x + 16, y + 16, 0xFF0D0F12);
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
        String ramStr = totalRam + "B" + (slotsRam > 0 ? " (+" + slotsRam + "B)" : "");

        int storage = menu.getTotalStorage();
        boolean hasNvme = menu.hasNvme();
        String storageStr = hasNvme ? storage + "KB" : (storage > 0 ? storage + "KB" : "None");

        String statusText = "RAM: " + ramStr + " | " + (hasNvme ? "NVMe: " : "Drive: ") + storageStr;
        if (this.font.width(statusText) > 165) {
            statusText = "RAM: " + totalRam + "B | " + (hasNvme ? "NVMe: " : "Drive: ") + storageStr;
        }
        guiGraphics.drawString(this.font, statusText, 10, 165, 0x88CCFF, false);

        // Dedicated slot column header labels (at x=188 and x=220, completely free of any text collision)
        guiGraphics.drawString(this.font, "RAM", 188, 165, 0x88CC88, false);
        guiGraphics.drawString(this.font, "NVMe", 220, 165, 0x88CCFF, false);
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
        if (menu.getCpuState() == VirtualCpu.State.RUNNING.ordinal()) {
            int crtX = (int) (mouseX - (this.leftPos + 8));
            int crtY = (int) (mouseY - (this.topPos + 7));
            if (crtX >= 0 && crtX < VirtualCpu.FB_WIDTH && crtY >= 0 && crtY < VirtualCpu.FB_HEIGHT) {
                PacketDistributor.sendToServer(new ComputerInputPacket(menu.getBlockEntity().getBlockPos(), 1, crtX, crtY, 0));
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        if (menu.getCpuState() == VirtualCpu.State.RUNNING.ordinal()) {
            int crtX = (int) (mouseX - (this.leftPos + 8));
            int crtY = (int) (mouseY - (this.topPos + 7));
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
    private int lastSentPadMask = 0;

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
        if (mask != lastSentPadMask) {
            lastSentPadMask = mask;
            PacketDistributor.sendToServer(new ComputerInputPacket(menu.getBlockEntity().getBlockPos(), 3, 0, mask, 0));
        }
    }

    @Override
    public void onClose() {
        super.onClose();
        heldKeyCodes.clear();
        updateAndSendPadState();
        displayRenderer.close();
    }
}
