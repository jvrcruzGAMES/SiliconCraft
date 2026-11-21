package games.jvrcruz.siliconcraft.client.screen;

import games.jvrcruz.siliconcraft.menu.ComputerMenu;
import games.jvrcruz.siliconcraft.network.ComputerActionPacket;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

public class ComputerScreen extends AbstractContainerScreen<ComputerMenu> {
    public ComputerScreen(ComputerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 240;
        this.imageHeight = 200;
    }

    @Override
    protected void init() {
        super.init();
        rebuildButtons();
    }

    private void rebuildButtons() {
        this.clearWidgets();

        boolean isRunning = (menu.getCpuState() == VirtualCpu.State.RUNNING.ordinal());

        // Power button
        this.addRenderableWidget(Button.builder(
                Component.literal(isRunning ? "⏻ Turn OFF" : "⏻ Turn ON"),
                b -> {
                    PacketDistributor.sendToServer(new ComputerActionPacket(menu.getBlockEntity().getBlockPos(), 0));
                    b.setMessage(Component.literal(!isRunning ? "⏻ Turn OFF" : "⏻ Turn ON"));
                }
        ).bounds(this.leftPos + 8, this.topPos + 172, 70, 20).build());

        // Reset button
        this.addRenderableWidget(Button.builder(
                Component.literal("↺ Reset"),
                b -> PacketDistributor.sendToServer(new ComputerActionPacket(menu.getBlockEntity().getBlockPos(), 1))
        ).bounds(this.leftPos + 82, this.topPos + 172, 50, 20).build());

        // Step button
        this.addRenderableWidget(Button.builder(
                Component.literal("⏯ Step"),
                b -> PacketDistributor.sendToServer(new ComputerActionPacket(menu.getBlockEntity().getBlockPos(), 2))
        ).bounds(this.leftPos + 136, this.topPos + 172, 46, 20).build());
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        // Monitor bezel
        guiGraphics.fill(this.leftPos, this.topPos, this.leftPos + this.imageWidth, this.topPos + this.imageHeight, 0xFF1C1E22);
        guiGraphics.fill(this.leftPos + 2, this.topPos + 2, this.leftPos + this.imageWidth - 2, this.topPos + this.imageHeight - 2, 0xFF2B2E36);

        // CRT phosphor screen
        int screenLeft = this.leftPos + 6;
        int screenTop = this.topPos + 6;
        int screenRight = this.leftPos + this.imageWidth - 6;
        int screenBottom = this.topPos + 140;
        guiGraphics.fill(screenLeft, screenTop, screenRight, screenBottom, 0xFF051009);

        // Scanlines effect / inner border
        guiGraphics.fill(screenLeft + 1, screenTop + 1, screenRight - 1, screenTop + 2, 0x33103318);

        // Hardware registers bar background
        guiGraphics.fill(this.leftPos + 6, this.topPos + 144, this.leftPos + this.imageWidth - 6, this.topPos + 168, 0xFF14171C);

        // Status LED
        int ledX = this.leftPos + this.imageWidth - 18;
        int ledY = this.topPos + 178;
        int state = menu.getCpuState();
        int ledColor = switch (state) {
            case 1 -> 0xFF00E676; // RUNNING (Green)
            case 3 -> 0xFFFFB300; // HALTED (Amber)
            case 4 -> 0xFFFF1744; // ERROR (Red)
            default -> 0xFF424242; // OFF (Dark Gray)
        };
        guiGraphics.fill(ledX, ledY, ledX + 8, ledY + 8, ledColor);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int state = menu.getCpuState();
        boolean isOff = (state == VirtualCpu.State.OFF.ordinal());

        // Render CRT Terminal Text
        if (isOff) {
            guiGraphics.drawString(this.font, "[PERSONAL COMPUTER POWERED OFF]", 12, 12, 0x447755, false);
            guiGraphics.drawString(this.font, "Press the Power Button below to boot.", 12, 24, 0x336644, false);
        } else {
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
                guiGraphics.drawString(this.font, line, 10, y, color, false);
                y += 10;
            }
        }

        // Render Registers & Hardware info
        String stateStr = switch (state) {
            case 1 -> "RUNNING";
            case 2 -> "PAUSED";
            case 3 -> "HALTED";
            case 4 -> "ERROR";
            default -> "POWER OFF";
        };

        guiGraphics.drawString(this.font, "CPU: " + stateStr + " | PC: " + menu.getPc() + " | CYCLES: " + menu.getCycles(), 10, 147, 0xDDDDDD, false);
        guiGraphics.drawString(this.font, "A:" + menu.getRegA() + " B:" + menu.getRegB() + " C:" + menu.getRegC() + " D:" + menu.getRegD()
                + " [Z:" + (menu.getFlagZ() ? 1 : 0) + " S:" + (menu.getFlagS() ? 1 : 0) + "]", 10, 157, 0x88CCFF, false);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }
}
