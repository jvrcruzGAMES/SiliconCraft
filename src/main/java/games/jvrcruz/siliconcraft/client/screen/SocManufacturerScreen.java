package games.jvrcruz.siliconcraft.client.screen;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.menu.SocManufacturerMenu;
import games.jvrcruz.siliconcraft.network.SocManufacturerPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

public class SocManufacturerScreen extends BaseMachineScreen<SocManufacturerMenu> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "textures/gui/soc_manufacturer.png");

    public SocManufacturerScreen(SocManufacturerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    protected void init() {
        super.init();

        this.addRenderableWidget(Button.builder(Component.literal("Assemble SoC"), b -> {
            PacketDistributor.sendToServer(new SocManufacturerPacket(menu.getBlockEntity().getBlockPos()));
        }).bounds(this.leftPos + 90, this.topPos + 48, 48, 20).build());
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);

        // Render Energy Bar
        renderEnergyBar(guiGraphics, menu.getEnergy(), menu.getMaxEnergy());

        // Progress bar (arrow into SoC slot)
        if (menu.isManufacturing() && menu.getMaxProgress() > 0) {
            int progressWidth = (menu.getProgress() * 24) / menu.getMaxProgress();
            guiGraphics.fill(this.leftPos + 115, this.topPos + 34, this.leftPos + 115 + progressWidth, this.topPos + 38, 0xFF00E676);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(this.font, this.title, 8, 4, 0x404040, false);
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
