package games.jvrcruz.siliconcraft.client.screen;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.blockentity.ChipForgeBlockEntity;
import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import games.jvrcruz.siliconcraft.menu.ArchitectureWriterMenu;
import games.jvrcruz.siliconcraft.network.ArchitectureWriterPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

public class ArchitectureWriterScreen extends BaseMachineScreen<ArchitectureWriterMenu> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "textures/gui/architecture_writer.png");

    private final List<String> selectedCpuInstructions = new ArrayList<>(List.of("NOP", "MOV", "HLT", "OUT", "OUTC"));
    private final List<String> selectedGpuInstructions = new ArrayList<>(InstructionValidator.GPU_INSTRUCTIONS);

    private boolean gpuMode = false;
    private EditBox nameInput;
    private Button modeButton;
    private Button selectButton;

    // List bounds inside GUI
    private static final int LIST_X = 56;
    private static final int LIST_Y = 32;
    private static final int LIST_WIDTH = 112;
    private static final int LIST_HEIGHT = 33;
    private static final int ITEM_HEIGHT = 11;
    private static final int SCROLLBAR_WIDTH = 6;

    private float scrollAmount = 0.0f;
    private boolean isDraggingScrollbar = false;

    public ArchitectureWriterScreen(ArchitectureWriterMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    private List<String> getAllInstructions() {
        return gpuMode ? InstructionValidator.GPU_INSTRUCTIONS : ChipForgeBlockEntity.ALL_INSTRUCTIONS;
    }

    private List<String> getSelectedInstructions() {
        return gpuMode ? selectedGpuInstructions : selectedCpuInstructions;
    }

    private void updateModeButton() {
        if (modeButton != null) {
            modeButton.setMessage(Component.literal(gpuMode ? "Mode: GPU" : "Mode: CPU"));
        }
    }

    private void updateSelectButton() {
        if (selectButton != null) {
            boolean allSelected = getSelectedInstructions().size() == getAllInstructions().size();
            selectButton.setMessage(Component.literal(allSelected ? "No Ops" : "All Ops"));
        }
    }

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();

        // Name input box
        nameInput = new EditBox(this.font, this.leftPos + 58, this.topPos + 17, 108, 12, Component.literal("Arch Name"));
        nameInput.setMaxLength(20);
        nameInput.setValue(gpuMode ? "GPU-1" : "RISC-1");
        this.addRenderableWidget(nameInput);

        // CPU / GPU Mode toggle button
        modeButton = Button.builder(
                Component.literal(gpuMode ? "Mode: GPU" : "Mode: CPU"),
                b -> {
                    String currentName = nameInput.getValue().trim();
                    gpuMode = !gpuMode;
                    if (gpuMode && currentName.equals("RISC-1")) {
                        nameInput.setValue("GPU-1");
                    } else if (!gpuMode && currentName.equals("GPU-1")) {
                        nameInput.setValue("RISC-1");
                    }
                    scrollAmount = 0.0f;
                    updateModeButton();
                    updateSelectButton();
                }
        ).bounds(this.leftPos + 7, this.topPos + 67, 54, 14).build();
        this.addRenderableWidget(modeButton);

        // Select All / None button
        boolean allSelected = getSelectedInstructions().size() == getAllInstructions().size();
        selectButton = Button.builder(
                Component.literal(allSelected ? "No Ops" : "All Ops"),
                b -> {
                    List<String> selected = getSelectedInstructions();
                    List<String> all = getAllInstructions();
                    if (selected.size() == all.size()) {
                        selected.clear();
                    } else {
                        selected.clear();
                        selected.addAll(all);
                    }
                    updateSelectButton();
                }
        ).bounds(this.leftPos + 63, this.topPos + 67, 46, 14).build();
        this.addRenderableWidget(selectButton);

        // Write button
        this.addRenderableWidget(Button.builder(Component.literal("Write Arch"), b -> {
            String name = nameInput.getValue().trim();
            if (name.isEmpty()) name = gpuMode ? "Custom-GPU" : "Custom-Arch";
            List<String> selected = getSelectedInstructions();
            if (!selected.isEmpty()) {
                PacketDistributor.sendToServer(new ArchitectureWriterPacket(
                        menu.getBlockEntity().getBlockPos(),
                        name,
                        new ArrayList<>(selected),
                        gpuMode
                ));
            }
        }).bounds(this.leftPos + 111, this.topPos + 67, 58, 14).build());
    }

    private int getMaxScroll() {
        int totalHeight = getAllInstructions().size() * ITEM_HEIGHT;
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
            scrollAmount -= (float) (scrollY * 11.0);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1 && selectButton != null && selectButton.isMouseOver(mouseX, mouseY)) {
            getSelectedInstructions().clear();
            updateSelectButton();
            return true;
        }

        if (button == 0 && isMouseOverList(mouseX, mouseY)) {
            int absX = this.leftPos + LIST_X;
            int absY = this.topPos + LIST_Y;
            int scrollbarX = absX + LIST_WIDTH - SCROLLBAR_WIDTH;

            if (mouseX >= scrollbarX && mouseX <= absX + LIST_WIDTH) {
                isDraggingScrollbar = true;
                updateScrollFromMouse(mouseY);
                return true;
            }

            // Clicked inside instruction list entries
            int relativeY = (int) (mouseY - absY + scrollAmount);
            int clickedIndex = relativeY / ITEM_HEIGHT;
            List<String> all = getAllInstructions();
            List<String> selected = getSelectedInstructions();
            if (clickedIndex >= 0 && clickedIndex < all.size()) {
                String opcode = all.get(clickedIndex);
                if (selected.contains(opcode)) {
                    selected.remove(opcode);
                } else {
                    selected.add(opcode);
                }
                updateSelectButton();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
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

        // Render instruction list entries with scissor clipping
        int listLeft = this.leftPos + LIST_X;
        int listTop = this.topPos + LIST_Y;
        int listRight = listLeft + LIST_WIDTH - SCROLLBAR_WIDTH;
        int listBottom = listTop + LIST_HEIGHT;

        guiGraphics.enableScissor(listLeft, listTop, listRight, listBottom);

        List<String> all = getAllInstructions();
        List<String> selected = getSelectedInstructions();
        for (int i = 0; i < all.size(); i++) {
            String opcode = all.get(i);
            int itemY = (int) (listTop + (i * ITEM_HEIGHT) - scrollAmount);

            if (itemY + ITEM_HEIGHT >= listTop && itemY <= listBottom) {
                boolean isSelected = selected.contains(opcode);
                boolean isHovered = mouseX >= listLeft && mouseX < listRight && mouseY >= itemY && mouseY < itemY + ITEM_HEIGHT;

                if (isHovered) {
                    guiGraphics.fill(listLeft, itemY, listRight, itemY + ITEM_HEIGHT, 0x33FFFFFF);
                }

                String checkbox = isSelected ? "[X] " : "[ ] ";
                int textColor = isSelected ? (gpuMode ? 0x00E5FF : 0x00E676) : 0xAAAAAA;
                guiGraphics.drawString(this.font, checkbox + opcode, listLeft + 2, itemY + 1, textColor, false);
            }
        }

        guiGraphics.disableScissor();

        // Render scrollbar thumb if scrollable
        int maxScroll = getMaxScroll();
        if (maxScroll > 0) {
            int trackHeight = LIST_HEIGHT - 2;
            int thumbHeight = Math.max(6, (LIST_HEIGHT * trackHeight) / (all.size() * ITEM_HEIGHT));
            int thumbY = listTop + 1 + (int) ((scrollAmount / (float) maxScroll) * (trackHeight - thumbHeight));
            int thumbX = this.leftPos + LIST_X + LIST_WIDTH - SCROLLBAR_WIDTH + 1;

            guiGraphics.fill(thumbX, thumbY, thumbX + SCROLLBAR_WIDTH - 2, thumbY + thumbHeight, 0xFF7A8288);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(this.font, this.title, 8, 6, 0x404040, false);
        guiGraphics.drawString(this.font, gpuMode ? "GPU" : "CPU", 148, 6, gpuMode ? 0x007799 : 0x227733, false);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
        renderSlotEmptyTooltip(guiGraphics, mouseX, mouseY);

        if (modeButton != null && modeButton.isMouseOver(mouseX, mouseY)) {
            guiGraphics.renderTooltip(this.font, Component.literal("Toggle between CPU and GPU Architecture Writeup mode"), mouseX, mouseY);
        } else if (selectButton != null && selectButton.isMouseOver(mouseX, mouseY)) {
            guiGraphics.renderTooltip(this.font, Component.literal("Toggle all/none instructions (Right-click: Select None)"), mouseX, mouseY);
        }
    }
}
