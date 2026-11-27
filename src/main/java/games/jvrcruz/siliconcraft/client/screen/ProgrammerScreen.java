package games.jvrcruz.siliconcraft.client.screen;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.client.ClientProgramManager;
import games.jvrcruz.siliconcraft.data.ProgramEntry;
import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import games.jvrcruz.siliconcraft.menu.ProgrammerMenu;
import games.jvrcruz.siliconcraft.network.DeleteProgramPacket;
import games.jvrcruz.siliconcraft.network.ProgrammerPacket;
import games.jvrcruz.siliconcraft.network.RequestProgramsPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ProgrammerScreen extends BaseMachineScreen<ProgrammerMenu> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "textures/gui/programmer.png");

    private static final int LIST_X = 72;
    private static final int LIST_Y = 16;
    private static final int LIST_WIDTH = 96;
    private static final int LIST_HEIGHT = 45;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int ITEM_HEIGHT = 15;

    private ProgramEntry selectedProgram = null;
    private String statusText = "Select a program";
    private float scrollAmount = 0.0f;
    private boolean isDraggingScrollbar = false;
    private Button burnButton;
    private Button deleteButton;

    public ProgrammerScreen(ProgrammerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    protected void init() {
        super.init();

        // Request programs library from server
        PacketDistributor.sendToServer(new RequestProgramsPacket());

        // Burn button
        burnButton = Button.builder(Component.literal("⚡ Write"), b -> {
            if (selectedProgram != null && selectedProgram.lines() != null && !selectedProgram.lines().isEmpty()) {
                if (games.jvrcruz.siliconcraft.network.LargePayloadCodec.compressStringList(selectedProgram.lines()).length
                        > games.jvrcruz.siliconcraft.network.LargePayloadCodec.MAX_WIRE_BYTES - 1024) {
                    statusText = "Program too large!";
                    return;
                }
                PacketDistributor.sendToServer(new ProgrammerPacket(
                        menu.getBlockEntity().getBlockPos(),
                        new ArrayList<>(selectedProgram.lines())
                ));
                statusText = "Programmed " + selectedProgram.name();
            }
        }).bounds(this.leftPos + 72, this.topPos + 63, 68, 16)
          .tooltip(Tooltip.create(Component.literal("Write selected program to ROM or Storage (Costs 100 FE)")))
          .build();

        burnButton.active = false;
        this.addRenderableWidget(burnButton);

        // Delete button
        deleteButton = Button.builder(Component.literal("Del").withStyle(ChatFormatting.RED), b -> {
            deleteSelectedProgram();
        }).bounds(this.leftPos + 142, this.topPos + 63, 26, 16)
          .tooltip(Tooltip.create(Component.literal("Delete program from library")))
          .build();

        deleteButton.active = false;
        this.addRenderableWidget(deleteButton);
    }

    private void deleteSelectedProgram() {
        if (selectedProgram != null) {
            String name = selectedProgram.name();
            PacketDistributor.sendToServer(new DeleteProgramPacket(selectedProgram.id()));
            statusText = "Deleted " + name;
            selectedProgram = null;
            if (burnButton != null) burnButton.active = false;
            if (deleteButton != null) deleteButton.active = false;
        }
    }

    private List<ProgramEntry> getPrograms() {
        return ClientProgramManager.PROGRAMS;
    }

    private int getMaxScroll() {
        int totalHeight = getPrograms().size() * ITEM_HEIGHT;
        return Math.max(0, totalHeight - (LIST_HEIGHT - 2));
    }

    private void clampScroll() {
        scrollAmount = Mth.clamp(scrollAmount, 0.0f, (float) getMaxScroll());
    }

    private boolean isMouseOverList(double mouseX, double mouseY) {
        int absX = this.leftPos + LIST_X;
        int absY = this.topPos + LIST_Y;
        return mouseX >= absX && mouseX <= absX + LIST_WIDTH && mouseY >= absY && mouseY <= absY + LIST_HEIGHT;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isMouseOverList(mouseX, mouseY) && getMaxScroll() > 0) {
            scrollAmount -= (float) (scrollY * 12.0);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isMouseOverList(mouseX, mouseY)) {
            int absX = this.leftPos + LIST_X;
            int absY = this.topPos + LIST_Y;
            int scrollbarX = absX + LIST_WIDTH - SCROLLBAR_WIDTH;

            if (mouseX >= scrollbarX && mouseX <= absX + LIST_WIDTH) {
                isDraggingScrollbar = true;
                updateScrollFromMouse(mouseY);
                return true;
            }

            // Clicked inside program list entries
            List<ProgramEntry> progs = getPrograms();
            int relativeY = (int) (mouseY - absY + scrollAmount);
            int clickedIndex = relativeY / ITEM_HEIGHT;
            if (clickedIndex >= 0 && clickedIndex < progs.size()) {
                selectedProgram = progs.get(clickedIndex);
                if (burnButton != null) burnButton.active = true;
                if (deleteButton != null) deleteButton.active = true;
                statusText = "Selected: " + selectedProgram.name();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (selectedProgram != null && (keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE)) {
            deleteSelectedProgram();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (isDraggingScrollbar) {
            updateScrollFromMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            isDraggingScrollbar = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void updateScrollFromMouse(double mouseY) {
        int absY = this.topPos + LIST_Y;
        float progress = (float) (mouseY - absY) / (float) LIST_HEIGHT;
        scrollAmount = progress * (float) getMaxScroll();
        clampScroll();
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);

        // Render Energy Bar
        renderEnergyBar(guiGraphics, menu.getEnergy(), menu.getMaxEnergy());

        // Render Program List
        int listLeft = this.leftPos + LIST_X;
        int listTop = this.topPos + LIST_Y;
        int listRight = listLeft + LIST_WIDTH - SCROLLBAR_WIDTH;
        int listBottom = listTop + LIST_HEIGHT;

        List<ProgramEntry> progs = getPrograms();

        if (selectedProgram != null) {
            boolean exists = false;
            for (ProgramEntry p : progs) {
                if (p.id().equals(selectedProgram.id())) {
                    selectedProgram = p;
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                selectedProgram = null;
                if (burnButton != null) burnButton.active = false;
                if (deleteButton != null) deleteButton.active = false;
            }
        }

        if (progs.isEmpty()) {
            guiGraphics.drawString(this.font, "No programs!", listLeft + 4, listTop + 6, 0x888888, false);
            guiGraphics.drawString(this.font, "Use Project", listLeft + 4, listTop + 16, 0x666666, false);
            guiGraphics.drawString(this.font, "Manager (.sc)", listLeft + 4, listTop + 26, 0x666666, false);
            if (burnButton != null) burnButton.active = false;
            if (deleteButton != null) deleteButton.active = false;
        } else {
            guiGraphics.enableScissor(listLeft, listTop, listRight, listBottom);

            for (int i = 0; i < progs.size(); i++) {
                ProgramEntry entry = progs.get(i);
                int itemY = (int) (listTop + (i * ITEM_HEIGHT) - scrollAmount);

                if (itemY + ITEM_HEIGHT >= listTop && itemY <= listBottom) {
                    boolean isSelected = (selectedProgram != null && selectedProgram.id().equals(entry.id()));
                    boolean isHovered = mouseX >= listLeft && mouseX < listRight && mouseY >= itemY && mouseY < itemY + ITEM_HEIGHT;

                    if (isSelected) {
                        guiGraphics.fill(listLeft, itemY, listRight, itemY + ITEM_HEIGHT, 0x4000E676);
                    } else if (isHovered) {
                        guiGraphics.fill(listLeft, itemY, listRight, itemY + ITEM_HEIGHT, 0x22FFFFFF);
                    }

                    String displayName = entry.name();
                    if (this.font.width(displayName) > 78) {
                        displayName = displayName.substring(0, Math.min(displayName.length(), 10)) + "..";
                    }

                    int titleColor = isSelected ? 0x00FF88 : 0xEEEEEE;
                    guiGraphics.drawString(this.font, displayName, listLeft + 2, itemY + 1, titleColor, false);

                    String subtext = entry.lines().size() + "L (" + (entry.archName().isEmpty() ? "any" : entry.archName()) + ")";
                    if (this.font.width(subtext) > 78) {
                        subtext = entry.lines().size() + "L";
                    }
                    guiGraphics.drawString(this.font, subtext, listLeft + 2, itemY + 8, 0x888888, false);
                }
            }

            guiGraphics.disableScissor();

            // Render scrollbar thumb if scrollable
            int maxScroll = getMaxScroll();
            if (maxScroll > 0) {
                int trackHeight = LIST_HEIGHT - 2;
                int thumbHeight = Math.max(6, (LIST_HEIGHT * trackHeight) / (progs.size() * ITEM_HEIGHT));
                int thumbY = listTop + 1 + (int) ((scrollAmount / (float) maxScroll) * (trackHeight - thumbHeight));
                int thumbX = this.leftPos + LIST_X + LIST_WIDTH - SCROLLBAR_WIDTH + 1;

                guiGraphics.fill(thumbX, thumbY, thumbX + SCROLLBAR_WIDTH - 2, thumbY + thumbHeight, 0xFF7A8288);
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(this.font, this.title, 8, 6, 0x404040, false);
        boolean hasSelected = (selectedProgram != null);
        if (burnButton != null) burnButton.active = hasSelected;
        if (deleteButton != null) deleteButton.active = hasSelected;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);

        renderEnergyTooltip(guiGraphics, menu.getEnergy(), menu.getMaxEnergy(), menu.getBurnTime(), mouseX, mouseY);
        renderSlotEmptyTooltip(guiGraphics, mouseX, mouseY);
        renderProgramTooltip(guiGraphics, mouseX, mouseY);
    }

    private void renderProgramTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (isDraggingScrollbar || !menu.getCarried().isEmpty()) return;

        int listLeft = this.leftPos + LIST_X;
        int listTop = this.topPos + LIST_Y;
        int listRight = listLeft + LIST_WIDTH - SCROLLBAR_WIDTH;
        int listBottom = listTop + LIST_HEIGHT;

        if (mouseX >= listLeft && mouseX < listRight && mouseY >= listTop && mouseY < listBottom) {
            List<ProgramEntry> progs = getPrograms();
            if (progs.isEmpty()) return;

            int relativeY = (int) (mouseY - listTop + scrollAmount);
            if (relativeY < 0) return;
            int hoveredIndex = relativeY / ITEM_HEIGHT;

            if (hoveredIndex >= 0 && hoveredIndex < progs.size()) {
                ProgramEntry entry = progs.get(hoveredIndex);
                List<Component> tooltip = new ArrayList<>();

                // Program Name (untruncated)
                tooltip.add(Component.literal(entry.name()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

                // Target Architecture
                String arch = (entry.archName() != null && !entry.archName().isBlank()) ? entry.archName() : "Any";
                tooltip.add(Component.literal("Architecture: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(arch).withStyle(ChatFormatting.AQUA)));

                // Instructions / Line count
                int lineCount = entry.lines() != null ? entry.lines().size() : 0;
                tooltip.add(Component.literal("Size: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(lineCount + " instructions").withStyle(ChatFormatting.YELLOW)));

                // Required Instructions
                List<String> req = entry.requiredInstructions();
                if ((req == null || req.isEmpty()) && entry.lines() != null && !entry.lines().isEmpty()) {
                    req = new ArrayList<>(InstructionValidator.getRequiredInstructions(entry.lines()));
                }

                if (req != null && !req.isEmpty()) {
                    List<String> sortedReq = new ArrayList<>(req);
                    Collections.sort(sortedReq);
                    tooltip.add(Component.literal("Required Instructions (" + sortedReq.size() + "):").withStyle(ChatFormatting.GRAY));

                    if (!hasShiftDown() && sortedReq.size() > 4) {
                        List<String> preview = sortedReq.subList(0, 4);
                        int remaining = sortedReq.size() - 4;
                        tooltip.add(Component.literal("  " + String.join(", ", preview) + ", ... (+" + remaining + " more)").withStyle(ChatFormatting.DARK_AQUA));
                        tooltip.add(Component.literal("  Hold [SHIFT] to view all").withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC));
                    } else {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < sortedReq.size(); i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(sortedReq.get(i));
                            if ((i + 1) % 6 == 0 || i == sortedReq.size() - 1) {
                                tooltip.add(Component.literal("  " + sb.toString()).withStyle(ChatFormatting.DARK_AQUA));
                                sb.setLength(0);
                            }
                        }
                    }
                } else {
                    tooltip.add(Component.literal("Required Instructions: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal("None").withStyle(ChatFormatting.DARK_GRAY)));
                }

                // Selection hint
                tooltip.add(Component.empty());
                boolean isSelected = (selectedProgram != null && selectedProgram.id().equals(entry.id()));
                if (isSelected) {
                    tooltip.add(Component.literal("✔ Selected for writing").withStyle(ChatFormatting.GREEN));
                } else {
                    tooltip.add(Component.literal("Click to select for writing").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
                }

                guiGraphics.renderComponentTooltip(this.font, tooltip, mouseX, mouseY);
            }
        }
    }
}
