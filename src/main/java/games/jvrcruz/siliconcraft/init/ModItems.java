package games.jvrcruz.siliconcraft.init;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.item.*;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Siliconcraft.MODID);

    // Raw Materials & Components
    public static final DeferredItem<Item> SILICON_POWDER = ITEMS.register("silicon_powder",
            () -> new Item(new Item.Properties()));

    public static final DeferredItem<ChipItem> CHIP = ITEMS.register("chip",
            () -> new ChipItem(new Item.Properties().stacksTo(16)));

    public static final DeferredItem<RamItem> RAM = ITEMS.register("ram",
            () -> new RamItem(new Item.Properties().stacksTo(16)));

    public static final DeferredItem<RomItem> ROM = ITEMS.register("rom",
            () -> new RomItem(new Item.Properties().stacksTo(16)));

    public static final DeferredItem<StorageItem> STORAGE = ITEMS.register("storage",
            () -> new StorageItem(new Item.Properties().stacksTo(16)));

    public static final DeferredItem<SocItem> SOC = ITEMS.register("soc",
            () -> new SocItem(new Item.Properties().stacksTo(16)));

    public static final DeferredItem<ArchitectureWriteupItem> ARCHITECTURE_WRITEUP = ITEMS.register("architecture_writeup",
            () -> new ArchitectureWriteupItem(new Item.Properties().stacksTo(16)));

    // Block Items
    public static final DeferredItem<BlockItem> SILICON_ORE_ITEM = ITEMS.registerSimpleBlockItem("silicon_ore", ModBlocks.SILICON_ORE);
    public static final DeferredItem<BlockItem> DEEPSLATE_SILICON_ORE_ITEM = ITEMS.registerSimpleBlockItem("deepslate_silicon_ore", ModBlocks.DEEPSLATE_SILICON_ORE);
    public static final DeferredItem<BlockItem> CHIP_FORGE_ITEM = ITEMS.registerSimpleBlockItem("chip_forge", ModBlocks.CHIP_FORGE);
    public static final DeferredItem<BlockItem> MEMORY_FORGE_ITEM = ITEMS.registerSimpleBlockItem("memory_forge", ModBlocks.MEMORY_FORGE);
    public static final DeferredItem<BlockItem> PROGRAMMER_ITEM = ITEMS.registerSimpleBlockItem("programmer", ModBlocks.PROGRAMMER);
    public static final DeferredItem<BlockItem> SOC_MANUFACTURER_ITEM = ITEMS.registerSimpleBlockItem("soc_manufacturer", ModBlocks.SOC_MANUFACTURER);
    public static final DeferredItem<BlockItem> COMPUTER_CASE_ITEM = ITEMS.registerSimpleBlockItem("computer_case", ModBlocks.COMPUTER_CASE);
    public static final DeferredItem<BlockItem> COMPUTER_ITEM = ITEMS.registerSimpleBlockItem("computer", ModBlocks.COMPUTER);
    public static final DeferredItem<BlockItem> ARCHITECTURE_WRITER_ITEM = ITEMS.registerSimpleBlockItem("architecture_writer", ModBlocks.ARCHITECTURE_WRITER);
    public static final DeferredItem<BlockItem> ASSEMBLY_WRITER_ITEM = ITEMS.registerSimpleBlockItem("assembly_writer", ModBlocks.ASSEMBLY_WRITER);
    public static final DeferredItem<BlockItem> CODE_COMPILER_ITEM = ITEMS.registerSimpleBlockItem("code_compiler", ModBlocks.CODE_COMPILER);
    public static final DeferredItem<BlockItem> PROJECT_COMPILER_ITEM = ITEMS.registerSimpleBlockItem("project_compiler", ModBlocks.PROJECT_COMPILER);
    public static final DeferredItem<BlockItem> SERIAL_CABLE_ITEM = ITEMS.registerSimpleBlockItem("serial_cable", ModBlocks.SERIAL_CABLE);
    public static final DeferredItem<BlockItem> SERIAL_EXPANSION_ITEM = ITEMS.registerSimpleBlockItem("serial_expansion", ModBlocks.SERIAL_EXPANSION);
    public static final DeferredItem<BlockItem> SPEAKER_ITEM = ITEMS.registerSimpleBlockItem("speaker", ModBlocks.SPEAKER);
    public static final DeferredItem<BlockItem> CHAT_BOX_ITEM = ITEMS.registerSimpleBlockItem("chat_box", ModBlocks.CHAT_BOX);
    public static final DeferredItem<BlockItem> DISTANCE_DETECTOR_ITEM = ITEMS.registerSimpleBlockItem("distance_detector", ModBlocks.DISTANCE_DETECTOR);
    public static final DeferredItem<BlockItem> ENVIRONMENT_DETECTOR_ITEM = ITEMS.registerSimpleBlockItem("environment_detector", ModBlocks.ENVIRONMENT_DETECTOR);
    public static final DeferredItem<BlockItem> PLAYER_DETECTOR_ITEM = ITEMS.registerSimpleBlockItem("player_detector", ModBlocks.PLAYER_DETECTOR);
    public static final DeferredItem<BlockItem> ENERGY_DETECTOR_ITEM = ITEMS.registerSimpleBlockItem("energy_detector", ModBlocks.ENERGY_DETECTOR);
    public static final DeferredItem<BlockItem> FLUID_DETECTOR_ITEM = ITEMS.registerSimpleBlockItem("fluid_detector", ModBlocks.FLUID_DETECTOR);
    public static final DeferredItem<BlockItem> GAS_DETECTOR_ITEM = ITEMS.registerSimpleBlockItem("gas_detector", ModBlocks.GAS_DETECTOR);
    public static final DeferredItem<BlockItem> BLOCK_READER_ITEM = ITEMS.registerSimpleBlockItem("block_reader", ModBlocks.BLOCK_READER);
    public static final DeferredItem<BlockItem> INVENTORY_MANAGER_ITEM = ITEMS.registerSimpleBlockItem("inventory_manager", ModBlocks.INVENTORY_MANAGER);
    public static final DeferredItem<BlockItem> REDSTONE_INTEGRATOR_ITEM = ITEMS.registerSimpleBlockItem("redstone_integrator", ModBlocks.REDSTONE_INTEGRATOR);

    public static final DeferredItem<games.jvrcruz.siliconcraft.item.MemoryCardItem> MEMORY_CARD =
            ITEMS.register("memory_card", () -> new games.jvrcruz.siliconcraft.item.MemoryCardItem(new Item.Properties().stacksTo(1)));
}
