package games.jvrcruz.siliconcraft.client.screen;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import games.jvrcruz.siliconcraft.compiler.NCompiler;
import games.jvrcruz.siliconcraft.data.ArchitectureData;
import games.jvrcruz.siliconcraft.menu.ProjectCompilerMenu;
import games.jvrcruz.siliconcraft.network.CompileProjectPacket;
import games.jvrcruz.siliconcraft.network.RequestProgramsPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import net.minecraft.client.gui.components.Tooltip;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ProjectCompilerScreen extends BaseMachineScreen<ProjectCompilerMenu> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "textures/gui/project_compiler.png");

    private Map<String, List<String>> projectFiles = new LinkedHashMap<>();
    private String loadedProjectName = "";
    private String statusText = "No project chosen";
    private boolean isChoosing = false;
    private Button compileButton;

    public ProjectCompilerScreen(ProjectCompilerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    protected void init() {
        super.init();

        // Request latest program library from server
        PacketDistributor.sendToServer(new RequestProgramsPacket());

        // Zip selection button
        this.addRenderableWidget(Button.builder(Component.literal("📁 .zip"), b -> {
            chooseFileAsync();
        }).tooltip(Tooltip.create(Component.literal("Select .zip project archive")))
          .bounds(this.leftPos + 72, this.topPos + 16, 47, 16).build());

        // Folder selection button
        this.addRenderableWidget(Button.builder(Component.literal("📂 Folder"), b -> {
            chooseFolderAsync();
        }).tooltip(Tooltip.create(Component.literal("Select root folder containing main.n")))
          .bounds(this.leftPos + 121, this.topPos + 16, 47, 16).build());

        // Compile & Add button
        compileButton = Button.builder(Component.literal("+ Compile Project"), b -> {
            if (projectFiles == null || projectFiles.isEmpty()) {
                statusText = "Load a project first!";
                return;
            }

            ArchitectureData arch = menu.getInstalledArchitecture();
            if (arch == null || arch.instructions().isEmpty()) {
                statusText = "Insert Arch Writeup!";
                return;
            }

            NCompiler.CompileResult result = NCompiler.compileProject(projectFiles, null);
            if (!result.success()) {
                statusText = "Compile error in project!";
                return;
            }

            List<String> missing = InstructionValidator.getMissingInstructions(result.requiredInstructions(), arch.instructions());
            if (!missing.isEmpty()) {
                statusText = "Missing: " + missing;
                return;
            }

            PacketDistributor.sendToServer(new CompileProjectPacket(
                    menu.getBlockEntity().getBlockPos(),
                    loadedProjectName.isEmpty() ? "project" : loadedProjectName,
                    new LinkedHashMap<>(projectFiles)
            ));
            statusText = "Compiled & Added!";
        }).bounds(this.leftPos + 72, this.topPos + 64, 96, 15).build();

        this.addRenderableWidget(compileButton);
    }

    private void chooseFileAsync() {
        if (isChoosing) return;
        isChoosing = true;
        statusText = "Selecting .zip project...";

        CompletableFuture.supplyAsync(() -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filterPatterns = stack.mallocPointer(2);
                filterPatterns.put(stack.UTF8("*.zip"));
                filterPatterns.put(stack.UTF8("*.ZIP"));
                filterPatterns.flip();

                return TinyFileDialogs.tinyfd_openFileDialog(
                        "Select .n Project Zip Archive",
                        "",
                        filterPatterns,
                        "Zip Archive (*.zip)",
                        false
                );
            } catch (Throwable t) {
                return TinyFileDialogs.tinyfd_openFileDialog(
                        "Select .n Project Zip Archive",
                        "",
                        null,
                        null,
                        false
                );
            }
        }).thenAcceptAsync(filePath -> {
            isChoosing = false;
            if (filePath != null && !filePath.trim().isEmpty()) {
                try {
                    File file = new File(filePath.trim());
                    if (file.exists() && file.isFile()) {
                        Map<String, List<String>> extracted = NCompiler.loadProjectFromZip(file);

                        if (extracted.isEmpty()) {
                            this.statusText = "No .n files found in zip!";
                        } else {
                            this.projectFiles = extracted;
                            this.loadedProjectName = file.getName();
                            this.statusText = file.getName() + " (" + extracted.size() + " files)";
                        }
                    } else {
                        this.statusText = "File not found";
                    }
                } catch (Exception e) {
                    this.statusText = "Error reading zip: " + e.getMessage();
                }
            } else {
                this.statusText = projectFiles.isEmpty() ? "No project chosen" : loadedProjectName;
            }
        }, Minecraft.getInstance()::execute);
    }

    private void chooseFolderAsync() {
        if (isChoosing) return;
        isChoosing = true;
        statusText = "Selecting folder...";

        CompletableFuture.supplyAsync(() -> {
            try {
                return TinyFileDialogs.tinyfd_selectFolderDialog(
                        "Select .n Project Folder (root containing main.n)",
                        ""
                );
            } catch (Throwable t) {
                return null;
            }
        }).thenAcceptAsync(folderPath -> {
            isChoosing = false;
            if (folderPath != null && !folderPath.trim().isEmpty()) {
                try {
                    File folder = new File(folderPath.trim());
                    if (folder.exists() && folder.isDirectory()) {
                        Map<String, List<String>> extracted = NCompiler.loadProjectFromFolder(folder);

                        if (extracted.isEmpty()) {
                            this.statusText = "No .n files found in folder!";
                        } else {
                            this.projectFiles = extracted;
                            this.loadedProjectName = folder.getName();
                            this.statusText = folder.getName() + " (" + extracted.size() + " files)";
                        }
                    } else {
                        this.statusText = "Folder not found";
                    }
                } catch (Exception e) {
                    this.statusText = "Error reading folder: " + e.getMessage();
                }
            } else {
                this.statusText = projectFiles.isEmpty() ? "No project chosen" : loadedProjectName;
            }
        }, Minecraft.getInstance()::execute);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);

        // Preview box
        int boxX = this.leftPos + 74;
        int boxY = this.topPos + 36;

        ArchitectureData arch = menu.getInstalledArchitecture();

        if (projectFiles == null || projectFiles.isEmpty()) {
            guiGraphics.drawString(this.font, "No project chosen", boxX, boxY + 2, 0x888888, false);
            guiGraphics.drawString(this.font, "Choose .zip / Folder", boxX, boxY + 12, 0x555555, false);
            compileButton.active = false;
        } else {
            // First check N compiler validity
            NCompiler.CompileResult result = NCompiler.compileProject(projectFiles, null);
            if (!result.success()) {
                String error = result.errors().isEmpty() ? "Syntax error" : result.errors().get(0);
                if (this.font.width(error) > 90) error = error.substring(0, Math.min(error.length(), 14)) + "..";
                guiGraphics.drawString(this.font, "Compile Error!", boxX, boxY + 1, 0xFF4444, false);
                guiGraphics.drawString(this.font, error, boxX, boxY + 12, 0xCC3333, false);
                compileButton.active = false;
            } else {
                String title = loadedProjectName;
                if (this.font.width(title) > 90) {
                    title = title.substring(0, Math.min(title.length(), 11)) + "...";
                }
                guiGraphics.drawString(this.font, title, boxX, boxY + 1, 0x00E676, false);

                if (arch == null || arch.instructions().isEmpty()) {
                    guiGraphics.drawString(this.font, "Insert Writeup!", boxX, boxY + 12, 0xCC5555, false);
                    compileButton.active = false;
                } else {
                    List<String> missing = InstructionValidator.getMissingInstructions(result.requiredInstructions(), arch.instructions());
                    if (!missing.isEmpty()) {
                        String missStr = "Missing: " + missing;
                        if (this.font.width(missStr) > 90) missStr = missStr.substring(0, Math.min(missStr.length(), 14)) + "..";
                        guiGraphics.drawString(this.font, missStr, boxX, boxY + 10, 0xFF4444, false);
                        guiGraphics.drawString(this.font, "Arch rejected!", boxX, boxY + 18, 0xCC3333, false);
                        compileButton.active = false;
                    } else {
                        guiGraphics.drawString(this.font, "Arch: " + arch.name(), boxX, boxY + 10, 0xFFD700, false);
                        guiGraphics.drawString(this.font, "Ready to compile!", boxX, boxY + 18, 0x00FF88, false);
                        compileButton.active = true;
                    }
                }
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(this.font, this.title, 8, 6, 0x404040, false);
        if (projectFiles != null && !projectFiles.isEmpty()) {
            guiGraphics.drawString(this.font, "Files: " + projectFiles.size(), 20, 58, 0x555555, false);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
        renderSlotEmptyTooltip(guiGraphics, mouseX, mouseY);
    }
}
