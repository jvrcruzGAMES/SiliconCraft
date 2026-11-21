package games.jvrcruz.siliconcraft.client.screen;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.data.ArchitectureData;
import games.jvrcruz.siliconcraft.menu.ChipForgeMenu;
import games.jvrcruz.siliconcraft.network.ChipForgePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

public class ChipForgeScreen extends BaseMachineScreen<ChipForgeMenu> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "textures/gui/chip_forge.png");

    private Button forgeButton;

    public ChipForgeScreen(ChipForgeMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();

        // Forge button
        forgeButton = Button.builder(Component.literal("Forge"), b -> {
            PacketDistributor.sendToServer(new ChipForgePacket(
                    menu.getBlockEntity().getBlockPos(),
                    true
            ));
        }).bounds(this.leftPos + 96, this.topPos + 60, 70, 16).build();

        this.addRenderableWidget(forgeButton);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);

        // Render Energy Bar
        renderEnergyBar(guiGraphics, menu.getEnergy(), menu.getMaxEnergy());

        // Progress bar (fills across bottom of architecture preview)
        if (menu.isForging() && menu.getMaxProgress() > 0) {
            int progressWidth = (menu.getProgress() * 68) / menu.getMaxProgress();
            guiGraphics.fill(this.leftPos + 97, this.topPos + 54, this.leftPos + 97 + progressWidth, this.topPos + 57, 0xFF00E676);
        }

        // Architecture info preview
        ArchitectureData arch = menu.getInstalledArchitecture();
        int panelX = this.leftPos + 96;
        int panelY = this.topPos + 18;

        if (arch != null && !arch.instructions().isEmpty()) {
            forgeButton.active = true;

            String name = arch.name().isEmpty() ? "Custom-Arch" : arch.name();
            if (this.font.width(name) > 68) {
                name = name.substring(0, Math.min(name.length(), 9)) + "...";
            }
            guiGraphics.drawString(this.font, name, panelX, panelY, 0xFFD700, false);
            guiGraphics.drawString(this.font, arch.instructions().size() + " opcodes", panelX, panelY + 10, 0x00E676, false);

            // Preview first 2 instructions
            List<String> inst = arch.instructions();
            String row1 = "";
            for (int i = 0; i < Math.min(3, inst.size()); i++) {
                row1 += (i > 0 ? " " : "") + inst.get(i);
            }
            guiGraphics.drawString(this.font, row1, panelX, panelY + 22, 0xAAAAAA, false);

            if (inst.size() > 3) {
                String row2 = "";
                for (int i = 3; i < Math.min(6, inst.size()); i++) {
                    row2 += (i > 3 ? " " : "") + inst.get(i);
                }
                if (inst.size() > 6) row2 += "...";
                guiGraphics.drawString(this.font, row2, panelX, panelY + 31, 0x888888, false);
            }
        } else {
            forgeButton.active = false;
            guiGraphics.drawString(this.font, "No Writeup", panelX, panelY + 2, 0xCC5555, false);
            guiGraphics.drawString(this.font, "Insert an Arch", panelX, panelY + 14, 0x777777, false);
            guiGraphics.drawString(this.font, "Writeup below", panelX, panelY + 24, 0x777777, false);
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
