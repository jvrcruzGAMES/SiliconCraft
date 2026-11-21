package games.jvrcruz.siliconcraft.compat.jei;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.compat.jei.category.ChipForgeCategory;
import games.jvrcruz.siliconcraft.compat.jei.category.ComputerAssemblyCategory;
import games.jvrcruz.siliconcraft.compat.jei.category.MemoryForgeCategory;
import games.jvrcruz.siliconcraft.compat.jei.category.SocManufacturerCategory;
import games.jvrcruz.siliconcraft.compat.jei.recipe.ChipForgeJeiRecipe;
import games.jvrcruz.siliconcraft.compat.jei.recipe.ComputerAssemblyJeiRecipe;
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
        starterChip.set(ModDataComponents.CHIP_DATA.get(), new ChipData(starterArch));
        chipRecipes.add(new ChipForgeJeiRecipe(
                starterWriteup,
                new ItemStack(ModItems.SILICON_POWDER.get(), 3),
                new ItemStack(Items.REDSTONE, 5),
                starterChip,
                2000,
                198,
                "Silicon-8 Starter CPU (8 ops)",
                starterArch
        ));

        // Complete 61-instruction RISC CPU
        List<String> fullArch = InstructionValidator.ALL_INSTRUCTIONS;
        int fullCount = fullArch.size();
        ItemStack fullWriteup = new ItemStack(ModItems.ARCHITECTURE_WRITEUP.get());
        fullWriteup.set(ModDataComponents.ARCHITECTURE_DATA.get(), new ArchitectureData("Silicon-" + fullCount, fullArch));
        ItemStack fullChip = new ItemStack(ModItems.CHIP.get());
        fullChip.set(ModDataComponents.CHIP_DATA.get(), new ChipData(fullArch));
        chipRecipes.add(new ChipForgeJeiRecipe(
                fullWriteup,
                new ItemStack(ModItems.SILICON_POWDER.get(), 1 + (fullCount / 3)),
                new ItemStack(Items.REDSTONE, 1 + (fullCount / 2)),
                fullChip,
                400 + (fullCount * 200),
                54 + (fullCount * 18),
                "Silicon-" + fullCount + " Full RISC CPU (" + fullCount + " ops)",
                fullArch
        ));

        // Minimal 3-instruction Microcontroller
        List<String> minArch = List.of("MOV", "OUT", "HLT");
        ItemStack minWriteup = new ItemStack(ModItems.ARCHITECTURE_WRITEUP.get());
        minWriteup.set(ModDataComponents.ARCHITECTURE_DATA.get(), new ArchitectureData("Micro-3", minArch));
        ItemStack minChip = new ItemStack(ModItems.CHIP.get());
        minChip.set(ModDataComponents.CHIP_DATA.get(), new ChipData(minArch));
        chipRecipes.add(new ChipForgeJeiRecipe(
                minWriteup,
                new ItemStack(ModItems.SILICON_POWDER.get(), 2),
                new ItemStack(Items.REDSTONE, 2),
                minChip,
                1000,
                108,
                "Silicon-3 Micro-Controller (3 ops)",
                minArch
        ));

        registration.addRecipes(ChipForgeCategory.RECIPE_TYPE, chipRecipes);

        // --- Memory Forge Recipes ---
        List<MemoryForgeJeiRecipe> memoryRecipes = new ArrayList<>();

        // RAM
        ItemStack ramStack = new ItemStack(ModItems.RAM.get());
        ramStack.set(ModDataComponents.RAM_DATA.get(), new RamData(256));
        memoryRecipes.add(new MemoryForgeJeiRecipe(
                "Mode: RAM (256 Bytes)",
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
        storageStack.set(ModDataComponents.STORAGE_DATA.get(), new StorageData(false, 512));
        memoryRecipes.add(new MemoryForgeJeiRecipe(
                "Mode: Storage (Standard Flash 512B)",
                List.of(new ItemStack(ModItems.SILICON_POWDER.get(), 4), new ItemStack(Items.GOLD_INGOT, 2)),
                storageStack,
                800,
                80
        ));

        // NVMe Storage
        ItemStack nvmeStack = new ItemStack(ModItems.STORAGE.get());
        nvmeStack.set(ModDataComponents.STORAGE_DATA.get(), new StorageData(true, 1024));
        memoryRecipes.add(new MemoryForgeJeiRecipe(
                "Mode: Storage (NVMe M.2 1024B)",
                List.of(new ItemStack(ModItems.SILICON_POWDER.get(), 4), new ItemStack(Items.GOLD_INGOT, 2), new ItemStack(Items.DIAMOND, 1)),
                nvmeStack,
                1200,
                100
        ));

        registration.addRecipes(MemoryForgeCategory.RECIPE_TYPE, memoryRecipes);

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

        // Standard SoC (1x RAM 256B, No Storage)
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

        // Advanced SoC (with 512B Flash Storage)
        ItemStack advancedSoc = new ItemStack(ModItems.SOC.get());
        advancedSoc.set(ModDataComponents.SOC_DATA.get(), new SocData(fullArch, 256, List.of("MOV A, 1", "OUT A", "HLT"), false, 512));
        socRecipes.add(new SocManufacturerJeiRecipe(
                Ingredient.of(ItemTags.WOODEN_SLABS),
                new ItemStack(ModItems.SILICON_POWDER.get(), 2),
                new ItemStack(Items.GOLD_INGOT, 2),
                biosRom,
                fullChip,
                ramStack,
                storageStack,
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
        registration.addRecipeCatalyst(ModBlocks.SOC_MANUFACTURER.get(), SocManufacturerCategory.RECIPE_TYPE);
        registration.addRecipeCatalyst(ModBlocks.COMPUTER_CASE.get(), ComputerAssemblyCategory.RECIPE_TYPE);
        registration.addRecipeCatalyst(ModBlocks.COMPUTER.get(), ComputerAssemblyCategory.RECIPE_TYPE);
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        // Do not add recipe click areas over machine processing buttons ("Forge", "Assemble SoC")
        // to prevent JEI from intercepting button clicks with "Show all recipes".
    }
}
