package games.jvrcruz.siliconcraft.client.screen;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.menu.MemoryForgeMenu;
import games.jvrcruz.siliconcraft.network.MemoryForgePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

public class MemoryForgeScreen extends BaseMachineScreen<MemoryForgeMenu> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "textures/gui/memory_forge.png");

    private int localMode = 0;
    private boolean localNvme = false;

    public MemoryForgeScreen(MemoryForgeMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.localMode = menu.getMode();
        this.localNvme = menu.isNvmeEnabled();
    }

    @Override
    protected void init() {
        super.init();
        rebuildModeButtons();
    }

    private void rebuildModeButtons() {
        this.clearWidgets();

        // Mode buttons in middle column (x = 70..116, width = 46)
        this.addRenderableWidget(Button.builder(Component.literal(localMode == 0 ? "> RAM" : "RAM"), b -> {
            localMode = 0;
            sync(false);
            rebuildModeButtons();
        }).bounds(this.leftPos + 70, this.topPos + 18, 46, 14).build());

        this.addRenderableWidget(Button.builder(Component.literal(localMode == 1 ? "> ROM" : "ROM"), b -> {
            localMode = 1;
            sync(false);
            rebuildModeButtons();
        }).bounds(this.leftPos + 70, this.topPos + 34, 46, 14).build());

        this.addRenderableWidget(Button.builder(Component.literal(localMode == 2 ? "> Drive" : "Drive"), b -> {
            localMode = 2;
            sync(false);
            rebuildModeButtons();
        }).bounds(this.leftPos + 70, this.topPos + 50, 46, 14).build());

        // NVMe switch button (shown when in Drive mode, below Drive button)
        if (localMode == 2) {
            this.addRenderableWidget(Button.builder(Component.literal(localNvme ? "NVMe: ON" : "NVMe: OFF"), b -> {
                localNvme = !localNvme;
                sync(false);
                rebuildModeButtons();
            }).bounds(this.leftPos + 70, this.topPos + 66, 46, 14).build());
        }

        // Forge button (placed in right column below the output slot)
        this.addRenderableWidget(Button.builder(Component.literal("Forge"), b -> {
            sync(true);
        }).bounds(this.leftPos + 124, this.topPos + 50, 44, 20).build());
    }

    private void sync(boolean startForge) {
        PacketDistributor.sendToServer(new MemoryForgePacket(
                menu.getBlockEntity().getBlockPos(),
                localMode,
                localNvme,
                startForge
        ));
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);

        // Render Energy Bar
        renderEnergyBar(guiGraphics, menu.getEnergy(), menu.getMaxEnergy());

        // Progress bar filling arrow pointing to output slot
        if (menu.isForging() && menu.getMaxProgress() > 0) {
            int progressWidth = (menu.getProgress() * 20) / menu.getMaxProgress();
            guiGraphics.fill(this.leftPos + 124, this.topPos + 32, this.leftPos + 124 + progressWidth, this.topPos + 35, 0xFF00E676);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(this.font, this.title, 8, 6, 0x404040, false);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);

        // Tooltip for Energy Bar and Empty Slots
        renderEnergyTooltip(guiGraphics, menu.getEnergy(), menu.getMaxEnergy(), menu.getBurnTime(), mouseX, mouseY);
        renderSlotEmptyTooltip(guiGraphics, mouseX, mouseY);
    }
}
