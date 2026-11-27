package games.jvrcruz.siliconcraft.client.screen;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.client.ClientProgramManager;
import games.jvrcruz.siliconcraft.data.ProgramEntry;
import games.jvrcruz.siliconcraft.data.ScProjectFile;
import games.jvrcruz.siliconcraft.menu.ProjectManagerMenu;
import games.jvrcruz.siliconcraft.vm.BinaryBytecode;
import games.jvrcruz.siliconcraft.network.ChunkedTransferStore;
import games.jvrcruz.siliconcraft.network.DeleteProgramPacket;
import games.jvrcruz.siliconcraft.network.PayloadCompression;
import games.jvrcruz.siliconcraft.network.RenameProgramPacket;
import games.jvrcruz.siliconcraft.network.RequestProgramsPacket;
import games.jvrcruz.siliconcraft.network.UploadProjectChunkPacket;
import games.jvrcruz.siliconcraft.network.UploadProjectStartPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPOutputStream;

public class ProjectManagerScreen extends BaseMachineScreen<ProjectManagerMenu> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "textures/gui/project_manager.png");

    private static final int LIST_X = 8;
    private static final int LIST_Y = 56;
    private static final int LIST_WIDTH = 160;
    private static final int LIST_HEIGHT = 90;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int ITEM_HEIGHT = 15;

    private ProgramEntry selectedProgram = null;
    private String statusText = "Select or add a .sc project";
    private int statusColor = 0x404040;
    private float scrollAmount = 0.0f;
    private boolean isDraggingScrollbar = false;
    private boolean isChoosing = false;

    private Button addButton;
    private Button renameButton;
    private Button removeButton;
    private EditBox renameInput;

    public ProjectManagerScreen(ProjectManagerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    protected void init() {
        super.init();
        PacketDistributor.sendToServer(new RequestProgramsPacket());

        addButton = Button.builder(Component.literal("+ Add .sc"), b -> chooseScFileAsync())
                .bounds(this.leftPos + 8, this.topPos + 18, 52, 16)
                .tooltip(Tooltip.create(Component.literal("Import a compiled .sc file built by the SiliconCraft N2 compiler")))
                .build();
        this.addRenderableWidget(addButton);

        renameButton = Button.builder(Component.literal("Rename"), b -> submitRename())
                .bounds(this.leftPos + 63, this.topPos + 18, 52, 16)
                .tooltip(Tooltip.create(Component.literal("Rename the selected project")))
                .build();
        this.addRenderableWidget(renameButton);

        removeButton = Button.builder(Component.literal("Remove").withStyle(ChatFormatting.RED), b -> removeSelectedProgram())
                .bounds(this.leftPos + 118, this.topPos + 18, 50, 16)
                .tooltip(Tooltip.create(Component.literal("Remove the selected project from the library")))
                .build();
        this.addRenderableWidget(removeButton);

        String previousText = renameInput != null ? renameInput.getValue() : "";
        renameInput = new EditBox(this.font, this.leftPos + 8, this.topPos + 38, 160, 14, Component.literal("Project Name"));
        renameInput.setMaxLength(ScProjectFile.MAX_NAME_LENGTH);
        renameInput.setValue(previousText);
        renameInput.setHint(Component.literal("Select project to rename...").withStyle(ChatFormatting.DARK_GRAY));
        this.addRenderableWidget(renameInput);

        updateActionState();
    }

    private void setStatus(String text, int color) {
        this.statusText = text;
        this.statusColor = color;
    }

    private void updateActionState() {
        boolean hasSelection = selectedProgram != null;
        if (removeButton != null) {
            removeButton.active = hasSelection;
        }
        if (renameInput != null) {
            renameInput.setEditable(hasSelection);
        }
        if (renameButton != null) {
            String candidate = renameInput != null ? renameInput.getValue().trim() : "";
            renameButton.active = hasSelection
                    && !candidate.isEmpty()
                    && !candidate.equals(selectedProgram.name());
        }
    }

    private void selectProgram(ProgramEntry entry) {
        this.selectedProgram = entry;
        if (renameInput != null) {
            renameInput.setValue(entry != null ? entry.name() : "");
        }
        if (entry != null) {
            setStatus("Selected: " + entry.name(), 0x226644);
        }
        updateActionState();
    }

    private void submitRename() {
        if (selectedProgram == null || renameInput == null) {
            return;
        }
        String newName = renameInput.getValue().trim();
        if (newName.isEmpty()) {
            setStatus("Name cannot be empty", 0xAA2222);
            return;
        }
        if (newName.contains("/") || newName.contains("\\")) {
            setStatus("Name cannot contain slashes", 0xAA2222);
            return;
        }
        if (newName.equals(selectedProgram.name())) {
            return;
        }
        for (ProgramEntry entry : getPrograms()) {
            if (!entry.id().equals(selectedProgram.id()) && entry.name().equalsIgnoreCase(newName)) {
                setStatus("Name already exists", 0xAA2222);
                return;
            }
        }
        PacketDistributor.sendToServer(new RenameProgramPacket(
                menu.getBlockEntity().getBlockPos(),
                selectedProgram.id(),
                newName
        ));
        setStatus("Renamed to " + newName, 0x226644);
        updateActionState();
    }

    private void removeSelectedProgram() {
        if (selectedProgram == null) {
            return;
        }
        String name = selectedProgram.name();
        PacketDistributor.sendToServer(new DeleteProgramPacket(selectedProgram.id()));
        selectProgram(null);
        setStatus("Removed " + name, 0x996600);
    }

    private void chooseScFileAsync() {
        if (isChoosing) return;
        isChoosing = true;
        setStatus("Selecting .sc file...", 0x404040);

        CompletableFuture.supplyAsync(() -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filterPatterns = stack.mallocPointer(2);
                filterPatterns.put(stack.UTF8("*.sc"));
                filterPatterns.put(stack.UTF8("*.SC"));
                filterPatterns.flip();

                return TinyFileDialogs.tinyfd_openFileDialog(
                        "Select Compiled SiliconCraft Project (.sc)",
                        "",
                        filterPatterns,
                        "SiliconCraft Project (*.sc)",
                        false
                );
            } catch (Throwable t) {
                return TinyFileDialogs.tinyfd_openFileDialog(
                        "Select Compiled SiliconCraft Project (.sc)",
                        "",
                        null,
                        null,
                        false
                );
            }
        }).thenAcceptAsync(filePath -> {
            isChoosing = false;
            if (filePath == null || filePath.isBlank()) {
                setStatus(selectedProgram != null ? "Selected: " + selectedProgram.name() : "Selection cancelled", 0x404040);
                return;
            }
            importScFile(new File(filePath.trim()));
        }, Minecraft.getInstance()::execute);
    }

    private void importScFile(File file) {
        if (!file.exists() || !file.isFile()) {
            setStatus("File not found", 0xAA2222);
            return;
        }
        try {
            long fileLen = file.length();
            if (fileLen <= 0) {
                setStatus("Selected .sc file is empty", 0xAA2222);
                return;
            }
            if (fileLen > PayloadCompression.MAX_UNCOMPRESSED_BYTES) {
                setStatus(".sc file exceeds 64 MB limit", 0xAA2222);
                return;
            }

            byte[] rawBytes = Files.readAllBytes(file.toPath());
            ScProjectFile.ParsedProject parsed = ScProjectFile.parseBytes(rawBytes, file.getName());

            byte[] wireBytes = ScProjectFile.isGzip(rawBytes) ? rawBytes : gzipBytes(rawBytes);
            if (wireBytes.length > ChunkedTransferStore.MAX_UPLOAD_COMPRESSED_BYTES) {
                setStatus("Project too large to upload", 0xAA2222);
                return;
            }

            List<byte[]> chunks = ChunkedTransferStore.splitIntoChunks(wireBytes);
            if (chunks.isEmpty()) {
                setStatus("Selected .sc file is empty", 0xAA2222);
                return;
            }

            UUID transferId = UUID.randomUUID();
            PacketDistributor.sendToServer(new UploadProjectStartPacket(
                    transferId,
                    menu.getBlockEntity().getBlockPos(),
                    parsed.name(),
                    chunks.size(),
                    wireBytes.length
            ));
            for (int i = 0; i < chunks.size(); i++) {
                PacketDistributor.sendToServer(new UploadProjectChunkPacket(
                        transferId,
                        i,
                        chunks.get(i)
                ));
            }

            setStatus("Added " + parsed.name() + " (" + parsed.getInstructionCount() + "I)", 0x226644);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Failed to read .sc file";
            setStatus(msg, 0xAA2222);
        }
    }

    private static byte[] gzipBytes(byte[] raw) throws java.io.IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(raw.length, 65536));
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(raw);
        }
        return baos.toByteArray();
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

            List<ProgramEntry> progs = getPrograms();
            int relativeY = (int) (mouseY - absY + scrollAmount);
            int clickedIndex = relativeY / ITEM_HEIGHT;
            if (clickedIndex >= 0 && clickedIndex < progs.size()) {
                if (renameInput != null) {
                    renameInput.setFocused(false);
                }
                selectProgram(progs.get(clickedIndex));
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

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (renameInput != null && renameInput.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                submitRename();
                return true;
            }
            if (keyCode != GLFW.GLFW_KEY_ESCAPE && keyCode != GLFW.GLFW_KEY_TAB) {
                renameInput.keyPressed(keyCode, scanCode, modifiers);
                return true;
            }
        } else if (selectedProgram != null && keyCode == GLFW.GLFW_KEY_DELETE) {
            removeSelectedProgram();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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

        int listLeft = this.leftPos + LIST_X;
        int listTop = this.topPos + LIST_Y;
        int listRight = listLeft + LIST_WIDTH - SCROLLBAR_WIDTH;
        int listBottom = listTop + LIST_HEIGHT;

        List<ProgramEntry> progs = getPrograms();
        if (selectedProgram != null) {
            ProgramEntry refreshed = null;
            for (ProgramEntry p : progs) {
                if (p.id().equals(selectedProgram.id())) {
                    refreshed = p;
                    break;
                }
            }
            if (refreshed == null) {
                selectedProgram = null;
                if (renameInput != null && !renameInput.isFocused()) {
                    renameInput.setValue("");
                }
            } else {
                boolean nameChangedOnServer = !refreshed.name().equals(selectedProgram.name());
                selectedProgram = refreshed;
                if (nameChangedOnServer && renameInput != null && !renameInput.isFocused()) {
                    renameInput.setValue(refreshed.name());
                }
            }
        }
        clampScroll();
        updateActionState();

        if (progs.isEmpty()) {
            guiGraphics.drawString(this.font, "No .sc projects!", listLeft + 6, listTop + 12, 0x888888, false);
            guiGraphics.drawString(this.font, "Compile externally &", listLeft + 6, listTop + 26, 0x666666, false);
            guiGraphics.drawString(this.font, "click + Add .sc above", listLeft + 6, listTop + 36, 0x666666, false);
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

                    String displayName = this.font.plainSubstrByWidth(entry.name(), 102);
                    if (!displayName.equals(entry.name())) {
                        displayName = this.font.plainSubstrByWidth(entry.name(), 94) + "..";
                    }

                    int titleColor = isSelected ? 0x00FF88 : 0xEEEEEE;
                    guiGraphics.drawString(this.font, displayName, listLeft + 3, itemY + 3, titleColor, false);

                    String lineBadge = entry.getInstructionCount() + "I";
                    int badgeWidth = this.font.width(lineBadge);
                    guiGraphics.drawString(this.font, lineBadge, listRight - badgeWidth - 3, itemY + 3, 0x888888, false);
                }
            }

            guiGraphics.disableScissor();

            int maxScroll = getMaxScroll();
            if (maxScroll > 0) {
                int trackHeight = LIST_HEIGHT - 2;
                int thumbHeight = Math.max(8, (LIST_HEIGHT * trackHeight) / (progs.size() * ITEM_HEIGHT));
                int thumbY = listTop + 1 + (int) ((scrollAmount / (float) maxScroll) * (trackHeight - thumbHeight));
                int thumbX = this.leftPos + LIST_X + LIST_WIDTH - SCROLLBAR_WIDTH + 1;
                guiGraphics.fill(thumbX, thumbY, thumbX + SCROLLBAR_WIDTH - 2, thumbY + thumbHeight, 0xFF7A8288);
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(this.font, this.title, 8, 6, 0x404040, false);
        String renderedStatus = this.font.plainSubstrByWidth(statusText, 160);
        guiGraphics.drawString(this.font, renderedStatus, 8, 151, statusColor, false);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
        renderProgramTooltip(guiGraphics, mouseX, mouseY);
    }

    private static String formatByteSize(int bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        if (bytes >= 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    private void renderProgramTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (isDraggingScrollbar) return;

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
                tooltip.add(Component.literal(entry.name()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

                String arch = (entry.archName() != null && !entry.archName().isBlank()) ? entry.archName() : "Any";
                tooltip.add(Component.literal("Target: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(arch).withStyle(ChatFormatting.AQUA)));

                int instrCount = entry.getInstructionCount();
                int byteCount = entry.bytecode().length;
                tooltip.add(Component.literal("Size: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(instrCount + " instructions (" + formatByteSize(byteCount) + ")").withStyle(ChatFormatting.YELLOW)));

                List<String> req = entry.requiredInstructions();
                if ((req == null || req.isEmpty()) && byteCount > 0) {
                    req = new ArrayList<>(BinaryBytecode.getRequiredInstructions(entry.bytecode()));
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
                                tooltip.add(Component.literal("  " + sb).withStyle(ChatFormatting.DARK_AQUA));
                                sb.setLength(0);
                            }
                        }
                    }
                }

                guiGraphics.renderComponentTooltip(this.font, tooltip, mouseX, mouseY);
            }
        }
    }
}
