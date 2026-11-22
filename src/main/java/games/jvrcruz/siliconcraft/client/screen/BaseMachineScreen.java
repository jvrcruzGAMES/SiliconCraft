package games.jvrcruz.siliconcraft.client.screen;

import games.jvrcruz.siliconcraft.menu.slot.FilteredSlot;
import games.jvrcruz.siliconcraft.menu.slot.OutputSlot;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.ArrayList;
import java.util.List;

public abstract class BaseMachineScreen<M extends AbstractContainerMenu> extends AbstractContainerScreen<M> {
    protected double lastMouseX;
    protected double lastMouseY;

    public BaseMachineScreen(M menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
    }

    protected void clearUnhoveredButtonFocus(double mouseX, double mouseY) {
        for (GuiEventListener child : this.children()) {
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
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        clearUnhoveredButtonFocus(mouseX, mouseY);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        clearUnhoveredButtonFocus(mouseX, mouseY);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        super.mouseMoved(mouseX, mouseY);
        clearUnhoveredButtonFocus(mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        clearUnhoveredButtonFocus(mouseX, mouseY);
        return handled;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        boolean handled = super.mouseReleased(mouseX, mouseY, button);
        clearUnhoveredButtonFocus(mouseX, mouseY);
        return handled;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        clearUnhoveredButtonFocus(this.lastMouseX, this.lastMouseY);
        boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
        clearUnhoveredButtonFocus(this.lastMouseX, this.lastMouseY);
        return handled;
    }

    protected void renderEnergyBar(GuiGraphics guiGraphics, int energy, int maxEnergy) {
        int x = this.leftPos + 9;
        int y = this.topPos + 18;
        int innerX = x + 1;
        int innerY = y + 1;
        int innerW = 10;
        int innerH = 48;

        if (maxEnergy > 0 && energy > 0) {
            int filledH = Math.min(innerH, (energy * innerH) / maxEnergy);
            int startY = innerY + innerH - filledH;
            guiGraphics.fillGradient(innerX, startY, innerX + innerW, innerY + innerH, 0xFFFF3B30, 0xFFB71C1C);
        }

        // Horizontal tick lines for gauge look
        for (int ty = innerY + 10; ty < innerY + innerH; ty += 10) {
            guiGraphics.fill(innerX, ty, innerX + innerW, ty + 1, 0x40000000);
        }
    }

    protected boolean isHoveringEnergyBar(int mouseX, int mouseY) {
        int x = this.leftPos + 9;
        int y = this.topPos + 18;
        return mouseX >= x && mouseX < x + 12 && mouseY >= y && mouseY < y + 50;
    }

    protected void renderEnergyTooltip(GuiGraphics guiGraphics, int energy, int maxEnergy, int burnTime, int mouseX, int mouseY) {
        if (isHoveringEnergyBar(mouseX, mouseY)) {
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(Component.literal("Energy: " + String.format("%,d", energy) + " / " + String.format("%,d", maxEnergy) + " FE").withStyle(ChatFormatting.RED));
            if (burnTime > 0) {
                tooltip.add(Component.literal("Solid Fuel Burning (" + burnTime + " ticks)").withStyle(ChatFormatting.GOLD));
            } else {
                tooltip.add(Component.literal("Accepts Coal, Charcoal, or FE via Cables").withStyle(ChatFormatting.GRAY));
            }
            guiGraphics.renderComponentTooltip(this.font, tooltip, mouseX, mouseY);
        }
    }

    protected void renderSlotEmptyTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (this.hoveredSlot != null && !this.hoveredSlot.hasItem() && this.menu.getCarried().isEmpty()) {
            if (this.hoveredSlot instanceof FilteredSlot fs) {
                guiGraphics.renderTooltip(this.font, fs.getEmptyTooltip(), mouseX, mouseY);
            } else if (this.hoveredSlot instanceof OutputSlot os) {
                guiGraphics.renderTooltip(this.font, os.getEmptyTooltip(), mouseX, mouseY);
            }
        }
    }
}
