package games.jvrcruz.siliconcraft.client.screen;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.blockentity.GpuAssemblerBlockEntity;
import games.jvrcruz.siliconcraft.data.ArchitectureData;
import games.jvrcruz.siliconcraft.menu.GpuAssemblerMenu;
import games.jvrcruz.siliconcraft.network.GpuAssemblerPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

public class GpuAssemblerScreen extends BaseMachineScreen<GpuAssemblerMenu> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "textures/gui/gpu_assembler.png");

    private Button assembleButton;

    public GpuAssemblerScreen(GpuAssemblerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();

        assembleButton = Button.builder(Component.literal("Assemble"), b ->
                PacketDistributor.sendToServer(new GpuAssemblerPacket(menu.getBlockEntity().getBlockPos(), true))
        ).bounds(this.leftPos + 25, this.topPos + 57, 52, 16).build();

        this.addRenderableWidget(assembleButton);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);

        renderEnergyBar(guiGraphics, menu.getEnergy(), menu.getMaxEnergy());

        // Progress bar between RAM grid and Output GPU slot (138,31 max width 11, height 4)
        if (menu.isAssembling() && menu.getMaxProgress() > 0) {
            int progressWidth = (menu.getProgress() * 11) / menu.getMaxProgress();
            guiGraphics.fill(this.leftPos + 138, this.topPos + 31, this.leftPos + 138 + progressWidth, this.topPos + 35, 0xFF00E676);
        }

        ArchitectureData arch = menu.getInstalledArchitecture();
        int reqSilicon = menu.getRequiredSilicon();
        int reqRedstone = menu.getRequiredRedstone();
        boolean hasWriteup = arch != null && arch.isGpu() && !arch.instructions().isEmpty();
        boolean hasSilicon = menu.slots.get(GpuAssemblerBlockEntity.SLOT_SILICON).getItem().getCount() >= reqSilicon;
        boolean hasRedstone = menu.slots.get(GpuAssemblerBlockEntity.SLOT_REDSTONE).getItem().getCount() >= reqRedstone;
        boolean outputEmpty = !menu.slots.get(GpuAssemblerBlockEntity.SLOT_OUTPUT).hasItem();
        boolean enoughEnergy = menu.getEnergy() >= menu.getRequiredEnergy();

        if (assembleButton != null) {
            assembleButton.active = hasWriteup && hasSilicon && hasRedstone && outputEmpty && enoughEnergy && !menu.isAssembling();
        }

        // Info text in dark readout panel (x=79..170, y=53..80, interior x=80..168, y=54..78)
        int infoX = this.leftPos + 82;
        int infoY = this.topPos + 55;
        if (hasWriteup) {
            String archName = arch.name().isEmpty() ? "Custom-GPU" : arch.name();
            while (this.font.width(archName) > 84 && archName.length() > 3) {
                archName = archName.substring(0, archName.length() - 1);
            }
            guiGraphics.drawString(this.font, archName, infoX, infoY, 0xFFD700, false);

            int vramKb = menu.getTotalVramKb();
            String vramText = vramKb >= 1024 ? (vramKb / 1024) + "MB" : vramKb + "KB";
            guiGraphics.drawString(this.font, vramText + " " + menu.getRamCount() + "/8 RAM", infoX, infoY + 8, vramKb > 0 ? 0x00E676 : 0xFFAA00, false);

            String costLine = reqSilicon + "Si " + reqRedstone + "Rs " + menu.getRequiredEnergy() + "FE";
            int costColor = (hasSilicon && hasRedstone && enoughEnergy) ? 0xCCCCCC : 0xFF5555;
            guiGraphics.drawString(this.font, costLine, infoX, infoY + 16, costColor, false);
        } else {
            guiGraphics.drawString(this.font, "No GPU Writeup", infoX, infoY, 0xCC5555, false);
            int vramKb = menu.getTotalVramKb();
            String vramText = vramKb >= 1024 ? (vramKb / 1024) + "MB" : vramKb + "KB";
            guiGraphics.drawString(this.font, vramText + " " + menu.getRamCount() + "/8 RAM", infoX, infoY + 8, 0xAAAAAA, false);
            guiGraphics.drawString(this.font, "Insert GPU Arch", infoX, infoY + 16, 0x777777, false);
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

        renderEnergyTooltip(guiGraphics, menu.getEnergy(), menu.getMaxEnergy(), menu.getBurnTime(), mouseX, mouseY);
        renderSlotEmptyTooltip(guiGraphics, mouseX, mouseY);

        if (mouseX >= this.leftPos + 79 && mouseX < this.leftPos + 170
                && mouseY >= this.topPos + 53 && mouseY < this.topPos + 80) {
            ArchitectureData arch = menu.getInstalledArchitecture();
            int vramKb = menu.getTotalVramKb();
            String vramDisplay = vramKb >= 1024 ? (vramKb / 1024) + " MB (" + vramKb + " KB)" : vramKb + " KB";
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(Component.literal("Target GPU Specs").withStyle(ChatFormatting.GOLD));
            if (arch != null && arch.isGpu()) {
                tooltip.add(Component.literal("Arch: " + (arch.name().isEmpty() ? "Custom-GPU" : arch.name())
                        + " (" + arch.instructions().size() + " GPU ops)").withStyle(ChatFormatting.YELLOW));
                tooltip.add(Component.literal("Cost: " + menu.getRequiredSilicon() + " Silicon, "
                        + menu.getRequiredRedstone() + " Redstone").withStyle(ChatFormatting.GRAY));
            } else {
                tooltip.add(Component.literal("Requires a GPU Architecture Writeup").withStyle(ChatFormatting.RED));
            }
            tooltip.add(Component.literal("VRAM: " + vramDisplay).withStyle(ChatFormatting.AQUA));
            tooltip.add(Component.literal("Compute Units: " + menu.getComputeUnits()).withStyle(ChatFormatting.GREEN));
            tooltip.add(Component.literal("Energy Cost: " + menu.getRequiredEnergy() + " FE").withStyle(ChatFormatting.RED));
            guiGraphics.renderComponentTooltip(this.font, tooltip, mouseX, mouseY);
        }
    }
}
