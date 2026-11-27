package games.jvrcruz.siliconcraft.compat.jei;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.compat.jei.category.ChipForgeCategory;
import games.jvrcruz.siliconcraft.compat.jei.category.ComputerAssemblyCategory;
import games.jvrcruz.siliconcraft.compat.jei.category.GpuAssemblerCategory;
import games.jvrcruz.siliconcraft.compat.jei.category.MemoryForgeCategory;
import games.jvrcruz.siliconcraft.compat.jei.category.SocManufacturerCategory;
import games.jvrcruz.siliconcraft.compat.jei.recipe.ChipForgeJeiRecipe;
import games.jvrcruz.siliconcraft.compat.jei.recipe.ComputerAssemblyJeiRecipe;
import games.jvrcruz.siliconcraft.compat.jei.recipe.GpuAssemblerJeiRecipe;
import games.jvrcruz.siliconcraft.compat.jei.recipe.MemoryForgeJeiRecipe;
import games.jvrcruz.siliconcraft.compat.jei.recipe.SocManufacturerJeiRecipe;
import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import games.jvrcruz.siliconcraft.data.*;
import games.jvrcruz.siliconcraft.init.ModBlocks;
import games.jvrcruz.siliconcraft.init.ModDataComponents;
import games.jvrcruz.siliconcraft.init.ModItems;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.List;

@JeiPlugin
public class SiliconCraftJeiPlugin implements IModPlugin {
    public static final ResourceLocation PLUGIN_UID =
            ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_UID;
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        var guiHelper = registration.getJeiHelpers().getGuiHelper();
        registration.addRecipeCategories(
                new ChipForgeCategory(guiHelper),
                new MemoryForgeCategory(guiHelper),
                new GpuAssemblerCategory(guiHelper),
                new SocManufacturerCategory(guiHelper),
                new ComputerAssemblyCategory(guiHelper)
        );
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        // --- Chip Forge Recipes ---
        List<ChipForgeJeiRecipe> chipRecipes = new ArrayList<>();

        // Starter 8-instruction CPU
        List<String> starterArch = List.of("MOV", "ADD", "SUB", "CMP", "JMP", "JZ", "OUT", "HLT");
        ItemStack starterWriteup = new ItemStack(ModItems.ARCHITECTURE_WRITEUP.get());
        starterWriteup.set(ModDataComponents.ARCHITECTURE_DATA.get(), new ArchitectureData("Silicon-8", starterArch));
        ItemStack starterChip = new ItemStack(ModItems.CHIP.get());
        starterChip.set(ModDataComponents.CHIP_DATA.get(), new ChipData("Silicon-8", starterArch));
        chipRecipes.add(new ChipForgeJeiRecipe(
                starterWriteup,
                new ItemStack(ModItems.SILICON_POWDER.get(), Math.min(12, 1 + (8 / 4))),
                new ItemStack(Items.REDSTONE, Math.min(16, 1 + (8 / 3))),
                starterChip,
                200 + (8 * 100),
                38 + (8 * 13),
                "Silicon-8 Starter CPU (8 ops)",
                starterArch
        ));

        // Complete RISC CPU
        List<String> fullArch = InstructionValidator.ALL_INSTRUCTIONS;
        int fullCount = fullArch.size();
        ItemStack fullWriteup = new ItemStack(ModItems.ARCHITECTURE_WRITEUP.get());
        fullWriteup.set(ModDataComponents.ARCHITECTURE_DATA.get(), new ArchitectureData("Silicon-" + fullCount, fullArch));
        ItemStack fullChip = new ItemStack(ModItems.CHIP.get());
        fullChip.set(ModDataComponents.CHIP_DATA.get(), new ChipData("Silicon-" + fullCount, fullArch));
        chipRecipes.add(new ChipForgeJeiRecipe(
                fullWriteup,
                new ItemStack(ModItems.SILICON_POWDER.get(), Math.min(12, 1 + (fullCount / 4))),
                new ItemStack(Items.REDSTONE, Math.min(16, 1 + (fullCount / 3))),
                fullChip,
                200 + (fullCount * 100),
                38 + (fullCount * 13),
                "Silicon-" + fullCount + " Full RISC CPU (" + fullCount + " ops)",
                fullArch
        ));

        // Minimal 3-instruction Microcontroller
        List<String> minArch = List.of("MOV", "OUT", "HLT");
        ItemStack minWriteup = new ItemStack(ModItems.ARCHITECTURE_WRITEUP.get());
        minWriteup.set(ModDataComponents.ARCHITECTURE_DATA.get(), new ArchitectureData("Micro-3", minArch));
        ItemStack minChip = new ItemStack(ModItems.CHIP.get());
        minChip.set(ModDataComponents.CHIP_DATA.get(), new ChipData("Micro-3", minArch));
        chipRecipes.add(new ChipForgeJeiRecipe(
                minWriteup,
                new ItemStack(ModItems.SILICON_POWDER.get(), Math.min(12, 1 + (3 / 4))),
                new ItemStack(Items.REDSTONE, Math.min(16, 1 + (3 / 3))),
                minChip,
                200 + (3 * 100),
                38 + (3 * 13),
                "Silicon-3 Micro-Controller (3 ops)",
                minArch
        ));

        registration.addRecipes(ChipForgeCategory.RECIPE_TYPE, chipRecipes);

        // --- Memory Forge Recipes ---
        List<MemoryForgeJeiRecipe> memoryRecipes = new ArrayList<>();

        // RAM
        ItemStack ramStack = new ItemStack(ModItems.RAM.get());
        ramStack.set(ModDataComponents.RAM_DATA.get(), RamData.DEFAULT);
        memoryRecipes.add(new MemoryForgeJeiRecipe(
                "Mode: RAM (2048 KB)",
                List.of(new ItemStack(ModItems.SILICON_POWDER.get(), 2), new ItemStack(Items.GOLD_INGOT, 1)),
                ramStack,
                400,
                60
        ));

        // ROM (Blank)
        ItemStack romStack = new ItemStack(ModItems.ROM.get());
        romStack.set(ModDataComponents.ROM_DATA.get(), new RomData(List.of()));
        memoryRecipes.add(new MemoryForgeJeiRecipe(
                "Mode: ROM (Blank Cartridge)",
                List.of(new ItemStack(ModItems.SILICON_POWDER.get(), 2), new ItemStack(Items.GOLD_INGOT, 1)),
                romStack,
                400,
                60
        ));

        // Storage (Standard SATA SSD)
        ItemStack storageStack = new ItemStack(ModItems.STORAGE.get());
        storageStack.set(ModDataComponents.STORAGE_DATA.get(), StorageData.DEFAULT);
        memoryRecipes.add(new MemoryForgeJeiRecipe(
                "Mode: Storage (Standard Flash 2048 MB)",
                List.of(new ItemStack(ModItems.SILICON_POWDER.get(), 4), new ItemStack(Items.GOLD_INGOT, 2)),
                storageStack,
                800,
                80
        ));

        // NVMe Storage
        ItemStack nvmeStack = new ItemStack(ModItems.STORAGE.get());
        nvmeStack.set(ModDataComponents.STORAGE_DATA.get(), new StorageData(true, StorageData.DRIVE_CAPACITY_KB));
        memoryRecipes.add(new MemoryForgeJeiRecipe(
                "Mode: Storage (NVMe M.2 2048 MB)",
                List.of(new ItemStack(ModItems.SILICON_POWDER.get(), 4), new ItemStack(Items.GOLD_INGOT, 2), new ItemStack(Items.DIAMOND, 1)),
                nvmeStack,
                1200,
                100
        ));

        registration.addRecipes(MemoryForgeCategory.RECIPE_TYPE, memoryRecipes);

        // --- GPU Assembler Recipes ---
        List<GpuAssemblerJeiRecipe> gpuRecipes = new ArrayList<>();
        List<String> gpuOps = InstructionValidator.GPU_INSTRUCTIONS;
        int gpuOpCount = gpuOps.size();
        ItemStack gpuWriteup = new ItemStack(ModItems.ARCHITECTURE_WRITEUP.get());
        gpuWriteup.set(ModDataComponents.ARCHITECTURE_DATA.get(), new ArchitectureData("GPU-1", gpuOps, true));
        int gpuSilicon = GpuData.calculateRequiredSilicon(gpuOpCount);
        int gpuRedstone = GpuData.calculateRequiredRedstone(gpuOpCount);

        ItemStack baseGpu = new ItemStack(ModItems.GPU.get());
        baseGpu.set(ModDataComponents.GPU_DATA.get(), new GpuData(true, "GPU-1", 0, Math.max(1, gpuOpCount / 4), gpuOps));
        gpuRecipes.add(new GpuAssemblerJeiRecipe(
                "Rasterizer GPU (0 RAM, 0 KB VRAM)",
                gpuWriteup,
                new ItemStack(ModItems.SILICON_POWDER.get(), gpuSilicon),
                new ItemStack(Items.REDSTONE, gpuRedstone),
                ItemStack.EMPTY,
                baseGpu,
                GpuData.calculateRequiredEnergy(gpuOpCount, 0),
                GpuData.calculateMaxProgress(gpuOpCount, 0)
        ));

        ItemStack stdGpu = new ItemStack(ModItems.GPU.get());
        stdGpu.set(ModDataComponents.GPU_DATA.get(), new GpuData(true, "GPU-1", RamData.MODULE_CAPACITY, Math.max(1, gpuOpCount / 4 + 1), gpuOps));
        gpuRecipes.add(new GpuAssemblerJeiRecipe(
                "Standard GPU (1x RAM = 2048 KB VRAM)",
                gpuWriteup,
                new ItemStack(ModItems.SILICON_POWDER.get(), gpuSilicon),
                new ItemStack(Items.REDSTONE, gpuRedstone),
                ramStack.copyWithCount(1),
                stdGpu,
                GpuData.calculateRequiredEnergy(gpuOpCount, 1),
                GpuData.calculateMaxProgress(gpuOpCount, 1)
        ));

        ItemStack maxGpu = new ItemStack(ModItems.GPU.get());
        maxGpu.set(ModDataComponents.GPU_DATA.get(), new GpuData(true, "GPU-1", RamData.MODULE_CAPACITY * 8, Math.max(1, gpuOpCount / 4 + 8), gpuOps));
        gpuRecipes.add(new GpuAssemblerJeiRecipe(
                "Max VRAM GPU (8x RAM = 16 MB VRAM)",
                gpuWriteup,
                new ItemStack(ModItems.SILICON_POWDER.get(), gpuSilicon),
                new ItemStack(Items.REDSTONE, gpuRedstone),
                ramStack.copyWithCount(8),
                maxGpu,
                GpuData.calculateRequiredEnergy(gpuOpCount, 8),
                GpuData.calculateMaxProgress(gpuOpCount, 8)
        ));

        registration.addRecipes(GpuAssemblerCategory.RECIPE_TYPE, gpuRecipes);

        // --- SoC Manufacturer Recipes ---
        List<SocManufacturerJeiRecipe> socRecipes = new ArrayList<>();

        // Sample BIOS ROM
        ItemStack biosRom = new ItemStack(ModItems.ROM.get());
        biosRom.set(ModDataComponents.ROM_DATA.get(), new RomData(List.of("MOV A, 1", "OUT A", "HLT")));

        // Minimal SoC (No RAM, No Storage)
        ItemStack minSoc = new ItemStack(ModItems.SOC.get());
        minSoc.set(ModDataComponents.SOC_DATA.get(), new SocData(starterArch, 0, List.of("MOV A, 1", "OUT A", "HLT"), false, 0));
        socRecipes.add(new SocManufacturerJeiRecipe(
                Ingredient.of(ItemTags.WOODEN_SLABS),
                new ItemStack(ModItems.SILICON_POWDER.get(), 2),
                new ItemStack(Items.GOLD_INGOT, 2),
                biosRom,
                starterChip,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                minSoc,
                1000,
                80
        ));

        // Standard SoC (1x RAM, No Storage)
        ItemStack standardSoc = new ItemStack(ModItems.SOC.get());
        standardSoc.set(ModDataComponents.SOC_DATA.get(), new SocData(starterArch, 256, List.of("MOV A, 1", "OUT A", "HLT"), false, 0));
        socRecipes.add(new SocManufacturerJeiRecipe(
                Ingredient.of(ItemTags.WOODEN_SLABS),
                new ItemStack(ModItems.SILICON_POWDER.get(), 2),
                new ItemStack(Items.GOLD_INGOT, 2),
                biosRom,
                starterChip,
                ramStack,
                ItemStack.EMPTY,
                standardSoc,
                1000,
                80
        ));

        // Advanced SoC (with Flash Storage + Optional GPU)
        GpuData sampleGpuData = new GpuData(true, "Silicon-GPU", RamData.MODULE_CAPACITY, Math.max(1, fullCount / 8 + 1), fullArch);
        ItemStack advancedSoc = new ItemStack(ModItems.SOC.get());
        advancedSoc.set(ModDataComponents.SOC_DATA.get(), new SocData(fullArch, 256, List.of("MOV A, 1", "OUT A", "HLT"), false, 512, sampleGpuData));
        socRecipes.add(new SocManufacturerJeiRecipe(
                Ingredient.of(ItemTags.WOODEN_SLABS),
                new ItemStack(ModItems.SILICON_POWDER.get(), 2),
                new ItemStack(Items.GOLD_INGOT, 2),
                biosRom,
                fullChip,
                ramStack,
                storageStack,
                stdGpu,
                advancedSoc,
                1000,
                80
        ));

        registration.addRecipes(SocManufacturerCategory.RECIPE_TYPE, socRecipes);

        // --- Computer Assembly Recipes ---
        List<ComputerAssemblyJeiRecipe> assemblyRecipes = List.of(
                new ComputerAssemblyJeiRecipe(
                        new ItemStack(ModBlocks.COMPUTER_CASE.get()),
                        new ItemStack(ModItems.SOC.get()),
                        new ItemStack(ModBlocks.COMPUTER.get())
                )
        );
        registration.addRecipes(ComputerAssemblyCategory.RECIPE_TYPE, assemblyRecipes);

        // --- Informational Pages for Mod Items & Blocks ---
        registration.addIngredientInfo(ModBlocks.SILICON_ORE.get(),
                Component.translatable("jei.siliconcraft.info.silicon_ore"));
        registration.addIngredientInfo(ModBlocks.DEEPSLATE_SILICON_ORE.get(),
                Component.translatable("jei.siliconcraft.info.silicon_ore"));
        registration.addIngredientInfo(ModItems.SILICON_POWDER.get(),
                Component.translatable("jei.siliconcraft.info.silicon_powder"));
        registration.addIngredientInfo(ModItems.ARCHITECTURE_WRITEUP.get(),
                Component.translatable("jei.siliconcraft.info.architecture_writeup"));
        registration.addIngredientInfo(ModBlocks.ARCHITECTURE_WRITER.get(),
                Component.translatable("jei.siliconcraft.info.architecture_writer"));
        registration.addIngredientInfo(ModBlocks.PROGRAMMER.get(),
                Component.translatable("jei.siliconcraft.info.programmer"));
        registration.addIngredientInfo(ModBlocks.GPU_ASSEMBLER.get(),
                Component.translatable("jei.siliconcraft.info.gpu_assembler"));
        registration.addIngredientInfo(ModItems.GPU.get(),
                Component.translatable("jei.siliconcraft.info.gpu"));
        registration.addIngredientInfo(ModBlocks.CODE_COMPILER.get(),
                Component.translatable("jei.siliconcraft.info.code_compiler"));
        registration.addIngredientInfo(ModBlocks.PROJECT_COMPILER.get(),
                Component.translatable("jei.siliconcraft.info.project_compiler"));
        registration.addIngredientInfo(ModBlocks.ASSEMBLY_WRITER.get(),
                Component.translatable("jei.siliconcraft.info.assembly_writer"));
        registration.addIngredientInfo(ModBlocks.COMPUTER_CASE.get(),
                Component.translatable("jei.siliconcraft.info.computer_case"));
        registration.addIngredientInfo(ModBlocks.COMPUTER.get(),
                Component.translatable("jei.siliconcraft.info.computer"));
        registration.addIngredientInfo(ModBlocks.SPEAKER.get(),
                Component.translatable("jei.siliconcraft.info.speaker"));
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(ModBlocks.CHIP_FORGE.get(), ChipForgeCategory.RECIPE_TYPE);
        registration.addRecipeCatalyst(ModBlocks.MEMORY_FORGE.get(), MemoryForgeCategory.RECIPE_TYPE);
        registration.addRecipeCatalyst(ModBlocks.GPU_ASSEMBLER.get(), GpuAssemblerCategory.RECIPE_TYPE);
        registration.addRecipeCatalyst(ModBlocks.SOC_MANUFACTURER.get(), SocManufacturerCategory.RECIPE_TYPE);
        registration.addRecipeCatalyst(ModBlocks.COMPUTER_CASE.get(), ComputerAssemblyCategory.RECIPE_TYPE);
        registration.addRecipeCatalyst(ModBlocks.COMPUTER.get(), ComputerAssemblyCategory.RECIPE_TYPE);
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        // Do not add recipe click areas over machine processing buttons ("Forge", "Assemble SoC", "Assemble")
        // to prevent JEI from intercepting button clicks with "Show all recipes".
    }
}
