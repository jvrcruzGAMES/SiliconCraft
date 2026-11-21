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
        this.imageWidth = 240;
        this.imageHeight = 242;
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
        ).bounds(this.leftPos + 8, this.topPos + 116, 54, 18).build());

        // Reset button
        this.addRenderableWidget(Button.builder(
                Component.literal("↺ RST"),
                b -> PacketDistributor.sendToServer(new ComputerActionPacket(menu.getBlockEntity().getBlockPos(), 1))
        ).bounds(this.leftPos + 64, this.topPos + 116, 42, 18).build());

        // Step button
        this.addRenderableWidget(Button.builder(
                Component.literal("⏯ STEP"),
                b -> PacketDistributor.sendToServer(new ComputerActionPacket(menu.getBlockEntity().getBlockPos(), 2))
        ).bounds(this.leftPos + 108, this.topPos + 116, 42, 18).build());
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int state = menu.getCpuState();
        // Monitor outer chassis bezel
        guiGraphics.fill(this.leftPos, this.topPos, this.leftPos + this.imageWidth, this.topPos + this.imageHeight, 0xFF1C1E22);
        guiGraphics.fill(this.leftPos + 2, this.topPos + 2, this.leftPos + this.imageWidth - 2, this.topPos + this.imageHeight - 2, 0xFF2B2E36);

        // CRT phosphor screen
        int screenLeft = this.leftPos + 6;
        int screenTop = this.topPos + 6;
        int screenRight = this.leftPos + this.imageWidth - 6;
        int screenBottom = this.topPos + 88;
        guiGraphics.fill(screenLeft, screenTop, screenRight, screenBottom, 0xFF051009);

        // Direct OpenGL Framebuffer rendering (bypassing Minecraft UI engine)
        if (state != VirtualCpu.State.OFF.ordinal() && menu.getBlockEntity().getCpu().getDisplayMode() == 1) {
            displayRenderer.updatePixels(menu.getBlockEntity().getCpu().getFramebuffer());
            displayRenderer.renderDirect(guiGraphics.pose().last().pose(), screenLeft, screenTop, screenRight, screenBottom);
        }

        // Scanlines effect / inner border
        guiGraphics.fill(screenLeft + 1, screenTop + 1, screenRight - 1, screenTop + 2, 0x33103318);

        // Hardware registers bar background
        guiGraphics.fill(this.leftPos + 6, this.topPos + 91, this.leftPos + this.imageWidth - 6, this.topPos + 113, 0xFF14171C);

        // Status LED
        int ledX = this.leftPos + this.imageWidth - 18;
        int ledY = this.topPos + 120;
        int ledColor = switch (state) {
            case 1 -> 0xFF00E676; // RUNNING (Green)
            case 3 -> 0xFFFFB300; // HALTED (Amber)
            case 4 -> 0xFFFF1744; // ERROR (Red)
            default -> 0xFF424242; // OFF (Dark Gray)
        };
        guiGraphics.fill(ledX - 1, ledY - 1, ledX + 9, ledY + 9, 0xFF000000);
        guiGraphics.fill(ledX, ledY, ledX + 8, ledY + 8, ledColor);

        // Expansion Slots Panels
        // RAM panel
        guiGraphics.fill(this.leftPos + 48, this.topPos + 136, this.leftPos + 124, this.topPos + 158, 0xFF16181D);
        for (int i = 0; i < 4; i++) {
            renderSlotFrame(guiGraphics, this.leftPos + 50 + i * 18, this.topPos + 138);
        }

        // NVMe panel
        guiGraphics.fill(this.leftPos + 160, this.topPos + 136, this.leftPos + 200, this.topPos + 158, 0xFF16181D);
        for (int i = 0; i < 2; i++) {
            renderSlotFrame(guiGraphics, this.leftPos + 162 + i * 18, this.topPos + 138);
        }

        // Player Inventory Panel
        guiGraphics.fill(this.leftPos + 37, this.topPos + 158, this.leftPos + 203, this.topPos + 214, 0xFF181A20);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                renderSlotFrame(guiGraphics, this.leftPos + 39 + col * 18, this.topPos + 160 + row * 18);
            }
        }

        // Player Hotbar Panel
        guiGraphics.fill(this.leftPos + 37, this.topPos + 216, this.leftPos + 203, this.topPos + 238, 0xFF181A20);
        for (int col = 0; col < 9; col++) {
            renderSlotFrame(guiGraphics, this.leftPos + 39 + col * 18, this.topPos + 218);
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
            guiGraphics.drawString(this.font, "[PERSONAL COMPUTER POWERED OFF]", 12, 12, 0x447755, false);
            guiGraphics.drawString(this.font, "Press the Power Button below to boot.", 12, 24, 0x336644, false);
        } else if (menu.getBlockEntity().getCpu().getDisplayMode() == 0) {
            List<String> output = menu.getBlockEntity().getCpu().getTerminalOutput();
            int maxLines = 8;
            int start = Math.max(0, output.size() - maxLines);
            int y = 9;
            for (int i = start; i < output.size(); i++) {
                String line = output.get(i);
                int color = 0x33FF66;
                if (line.contains("EXCEPTION") || line.contains("ERROR")) {
                    color = 0xFF5555;
                } else if (line.contains("BIOS") || line.contains("OK")) {
                    color = 0x66DDFF;
                }
                guiGraphics.drawString(this.font, line, 10, y, color, false);
                y += 10;
            }
        }

        // Render Registers Bar
        String stateStr = switch (state) {
            case 1 -> "RUNNING";
            case 2 -> "PAUSED";
            case 3 -> "HALTED";
            case 4 -> "ERROR";
            default -> "OFF";
        };

        guiGraphics.drawString(this.font, "CPU: " + stateStr + " | PC: " + menu.getPc() + " | CYCLES: " + menu.getCycles(), 10, 94, 0xDDDDDD, false);
        guiGraphics.drawString(this.font, "A:" + menu.getRegA() + " B:" + menu.getRegB() + " C:" + menu.getRegC() + " D:" + menu.getRegD()
                + " [Z:" + (menu.getFlagZ() ? 1 : 0) + " S:" + (menu.getFlagS() ? 1 : 0) + "]", 10, 103, 0x88CCFF, false);

        // Hardware Controls Info (RAM and NVMe status)
        int totalRam = menu.getTotalRam();
        int socRam = menu.getSocRam();
        int slotsRam = Math.max(0, totalRam - socRam);
        String ramStr = "RAM: " + totalRam + "B" + (slotsRam > 0 ? " (+" + slotsRam + "B)" : "");
        guiGraphics.drawString(this.font, ramStr, 153, 117, 0x66EE88, false);

        int storage = menu.getTotalStorage();
        boolean hasNvme = menu.hasNvme();
        String storageStr = hasNvme ? "NVMe: " + storage + "KB" : (storage > 0 ? "Flash: " + storage + "KB" : "NVMe: None");
        guiGraphics.drawString(this.font, storageStr, 153, 126, 0x66DDFF, false);

        // Slot Labels
        guiGraphics.drawString(this.font, "RAM", 26, 143, 0x88CC88, false);
        guiGraphics.drawString(this.font, "NVMe", 132, 143, 0x88CCFF, false);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);

        // Empty slot tooltip
        if (this.hoveredSlot != null && !this.hoveredSlot.hasItem() && this.menu.getCarried().isEmpty()) {
            if (this.hoveredSlot instanceof FilteredSlot fs) {
                guiGraphics.renderTooltip(this.font, fs.getEmptyTooltip(), mouseX, mouseY);
            }
        }
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(mouseX, mouseY);
        if (menu.getCpuState() == VirtualCpu.State.RUNNING.ordinal()) {
            int crtX = (int) (mouseX - (this.leftPos + 6));
            int crtY = (int) (mouseY - (this.topPos + 6));
            if (crtX >= 0 && crtX < VirtualCpu.FB_WIDTH && crtY >= 0 && crtY < VirtualCpu.FB_HEIGHT) {
                PacketDistributor.sendToServer(new ComputerInputPacket(menu.getBlockEntity().getBlockPos(), 1, crtX, crtY, 0));
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (menu.getCpuState() == VirtualCpu.State.RUNNING.ordinal()) {
            int crtX = (int) (mouseX - (this.leftPos + 6));
            int crtY = (int) (mouseY - (this.topPos + 6));
            if (crtX >= 0 && crtX < VirtualCpu.FB_WIDTH && crtY >= 0 && crtY < VirtualCpu.FB_HEIGHT) {
                PacketDistributor.sendToServer(new ComputerInputPacket(menu.getBlockEntity().getBlockPos(), 2, crtX, crtY, button));
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (menu.getCpuState() == VirtualCpu.State.RUNNING.ordinal()) {
            PacketDistributor.sendToServer(new ComputerInputPacket(menu.getBlockEntity().getBlockPos(), 0, (int) codePoint, 0, modifiers));
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // Escape
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (menu.getCpuState() == VirtualCpu.State.RUNNING.ordinal()) {
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
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        super.onClose();
        displayRenderer.close();
    }
}
