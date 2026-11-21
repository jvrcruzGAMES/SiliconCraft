package games.jvrcruz.siliconcraft.client.screen;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import games.jvrcruz.siliconcraft.compiler.NCompiler;
import games.jvrcruz.siliconcraft.data.ArchitectureData;
import games.jvrcruz.siliconcraft.menu.CodeCompilerMenu;
import games.jvrcruz.siliconcraft.network.CompileProgramPacket;
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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class CodeCompilerScreen extends BaseMachineScreen<CodeCompilerMenu> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "textures/gui/code_compiler.png");

    private List<String> loadedLines = new ArrayList<>();
    private String loadedFileName = "";
    private String statusText = "No .n file chosen";
    private boolean isChoosing = false;
    private Button compileButton;

    public CodeCompilerScreen(CodeCompilerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    protected void init() {
        super.init();

        // Request latest program library from server
        PacketDistributor.sendToServer(new RequestProgramsPacket());

        // File selection button
        this.addRenderableWidget(Button.builder(Component.literal("📁 Choose .n File"), b -> {
            chooseFileAsync();
        }).bounds(this.leftPos + 72, this.topPos + 16, 96, 16).build());

        // Compile & Add button
        compileButton = Button.builder(Component.literal("+ Compile & Add"), b -> {
            if (loadedLines == null || loadedLines.isEmpty()) {
                statusText = "Load a .n file first!";
                return;
            }

            ArchitectureData arch = menu.getInstalledArchitecture();
            if (arch == null || arch.instructions().isEmpty()) {
                statusText = "Insert Arch Writeup!";
                return;
            }

            NCompiler.CompileResult result = NCompiler.compile(loadedLines);
            if (!result.success()) {
                statusText = "Compile error in code!";
                return;
            }

            List<String> missing = InstructionValidator.getMissingInstructions(result.requiredInstructions(), arch.instructions());
            if (!missing.isEmpty()) {
                statusText = "Missing: " + missing;
                return;
            }

            PacketDistributor.sendToServer(new CompileProgramPacket(
                    menu.getBlockEntity().getBlockPos(),
                    loadedFileName.isEmpty() ? "program.n" : loadedFileName,
                    new ArrayList<>(loadedLines)
            ));
            statusText = "Compiled & Added!";
        }).bounds(this.leftPos + 72, this.topPos + 64, 96, 15).build();

        this.addRenderableWidget(compileButton);
    }

    private void chooseFileAsync() {
        if (isChoosing) return;
        isChoosing = true;
        statusText = "Selecting .n file...";

        CompletableFuture.supplyAsync(() -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filterPatterns = stack.mallocPointer(2);
                filterPatterns.put(stack.UTF8("*.n"));
                filterPatterns.put(stack.UTF8("*.N"));
                filterPatterns.flip();

                return TinyFileDialogs.tinyfd_openFileDialog(
                        "Select .n Language File",
                        "",
                        filterPatterns,
                        ".n Source files (*.n)",
                        false
                );
            } catch (Throwable t) {
                return TinyFileDialogs.tinyfd_openFileDialog(
                        "Select .n Language File",
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
                        List<String> rawLines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                        List<String> cleaned = new ArrayList<>();
                        for (String line : rawLines) {
                            String trimmed = line.trim();
                            if (!trimmed.isEmpty()) {
                                cleaned.add(trimmed);
                            }
                        }
                        this.loadedLines = cleaned;
                        this.loadedFileName = file.getName();
                        this.statusText = file.getName() + " (" + cleaned.size() + " lines)";
                    } else {
                        this.statusText = "File not found";
                    }
                } catch (Exception e) {
                    this.statusText = "Error reading file";
                }
            } else {
                this.statusText = loadedLines.isEmpty() ? "No .n file chosen" : loadedFileName;
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

        if (loadedLines == null || loadedLines.isEmpty()) {
            guiGraphics.drawString(this.font, "No .n file chosen", boxX, boxY + 2, 0x888888, false);
            guiGraphics.drawString(this.font, "Click Choose File", boxX, boxY + 12, 0x555555, false);
            compileButton.active = false;
        } else {
            // First check N compiler validity
            NCompiler.CompileResult result = NCompiler.compile(loadedLines);
            if (!result.success()) {
                String error = result.errors().isEmpty() ? "Syntax error" : result.errors().get(0);
                if (this.font.width(error) > 90) error = error.substring(0, Math.min(error.length(), 14)) + "..";
                guiGraphics.drawString(this.font, "Compile Error!", boxX, boxY + 1, 0xFF4444, false);
                guiGraphics.drawString(this.font, error, boxX, boxY + 12, 0xCC3333, false);
                compileButton.active = false;
            } else {
                String title = loadedFileName;
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
        if (loadedLines != null && !loadedLines.isEmpty()) {
            guiGraphics.drawString(this.font, "Lines: " + loadedLines.size(), 20, 58, 0x555555, false);
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
