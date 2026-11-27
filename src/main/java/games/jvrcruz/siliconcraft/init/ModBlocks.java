package games.jvrcruz.siliconcraft.init;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.block.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Siliconcraft.MODID);

    public static final DeferredBlock<SiliconOreBlock> SILICON_ORE = BLOCKS.register("silicon_ore",
            () -> new SiliconOreBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .requiresCorrectToolForDrops()
                    .strength(3.0F, 3.0F)
                    .sound(SoundType.STONE)));

    public static final DeferredBlock<SiliconOreBlock> DEEPSLATE_SILICON_ORE = BLOCKS.register("deepslate_silicon_ore",
            () -> new SiliconOreBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.DEEPSLATE)
                    .requiresCorrectToolForDrops()
                    .strength(4.5F, 3.0F)
                    .sound(SoundType.DEEPSLATE)));

    public static final DeferredBlock<ChipForgeBlock> CHIP_FORGE = BLOCKS.register("chip_forge",
            () -> new ChipForgeBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .requiresCorrectToolForDrops()
                    .strength(3.5F, 4.0F)
                    .sound(SoundType.METAL)));

    public static final DeferredBlock<MemoryForgeBlock> MEMORY_FORGE = BLOCKS.register("memory_forge",
            () -> new MemoryForgeBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .requiresCorrectToolForDrops()
                    .strength(3.5F, 4.0F)
                    .sound(SoundType.METAL)));

    public static final DeferredBlock<GpuAssemblerBlock> GPU_ASSEMBLER = BLOCKS.register("gpu_assembler",
            () -> new GpuAssemblerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .requiresCorrectToolForDrops()
                    .strength(3.5F, 4.0F)
                    .sound(SoundType.METAL)));

    public static final DeferredBlock<ProgrammerBlock> PROGRAMMER = BLOCKS.register("programmer",
            () -> new ProgrammerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .requiresCorrectToolForDrops()
                    .strength(3.5F, 4.0F)
                    .sound(SoundType.METAL)));

    public static final DeferredBlock<SocManufacturerBlock> SOC_MANUFACTURER = BLOCKS.register("soc_manufacturer",
            () -> new SocManufacturerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .requiresCorrectToolForDrops()
                    .strength(3.0F, 3.5F)
                    .sound(SoundType.WOOD)));

    public static final DeferredBlock<ComputerCaseBlock> COMPUTER_CASE = BLOCKS.register("computer_case",
            () -> new ComputerCaseBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .requiresCorrectToolForDrops()
                    .strength(3.0F, 3.5F)
                    .sound(SoundType.METAL)));

    public static final DeferredBlock<ComputerBlock> COMPUTER = BLOCKS.register("computer",
            () -> new ComputerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .requiresCorrectToolForDrops()
                    .strength(3.5F, 4.0F)
                    .sound(SoundType.METAL)));

    public static final DeferredBlock<ArchitectureWriterBlock> ARCHITECTURE_WRITER = BLOCKS.register("architecture_writer",
            () -> new ArchitectureWriterBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.5F, 3.0F)
                    .sound(SoundType.WOOD)));

    public static final DeferredBlock<AssemblyWriterBlock> ASSEMBLY_WRITER = BLOCKS.register("assembly_writer",
            () -> new AssemblyWriterBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .requiresCorrectToolForDrops()
                    .strength(3.5F, 4.0F)
                    .sound(SoundType.METAL)));

    public static final DeferredBlock<CodeCompilerBlock> CODE_COMPILER = BLOCKS.register("code_compiler",
            () -> new CodeCompilerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .requiresCorrectToolForDrops()
                    .strength(3.5F, 4.0F)
                    .sound(SoundType.METAL)));

    public static final DeferredBlock<ProjectCompilerBlock> PROJECT_COMPILER = BLOCKS.register("project_compiler",
            () -> new ProjectCompilerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .requiresCorrectToolForDrops()
                    .strength(3.5F, 4.0F)
                    .sound(SoundType.METAL)));

    public static final DeferredBlock<SpeakerBlock> SPEAKER = BLOCKS.register("speaker",
            () -> new SpeakerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .requiresCorrectToolForDrops()
                    .strength(2.5F, 3.0F)
                    .sound(SoundType.WOOD)));

    public static final DeferredBlock<SerialCableBlock> SERIAL_CABLE = BLOCKS.register("serial_cable",
            () -> new SerialCableBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE)
                    .strength(0.8F).noOcclusion().sound(SoundType.COPPER)));

    public static final DeferredBlock<Block> SERIAL_EXPANSION = BLOCKS.register("serial_expansion",
            () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.METAL)
                    .strength(2.0F).sound(SoundType.METAL)));
    public static final DeferredBlock<PeripheralDeviceBlock> CHAT_BOX = peripheral("chat_box", PeripheralDeviceBlock.Kind.CHAT);
    public static final DeferredBlock<PeripheralDeviceBlock> DISTANCE_DETECTOR = peripheral("distance_detector", PeripheralDeviceBlock.Kind.DISTANCE);
    public static final DeferredBlock<PeripheralDeviceBlock> ENVIRONMENT_DETECTOR = peripheral("environment_detector", PeripheralDeviceBlock.Kind.ENVIRONMENT);
    public static final DeferredBlock<PeripheralDeviceBlock> PLAYER_DETECTOR = peripheral("player_detector", PeripheralDeviceBlock.Kind.PLAYER);
    public static final DeferredBlock<PeripheralDeviceBlock> ENERGY_DETECTOR = peripheral("energy_detector", PeripheralDeviceBlock.Kind.ENERGY);
    public static final DeferredBlock<PeripheralDeviceBlock> FLUID_DETECTOR = peripheral("fluid_detector", PeripheralDeviceBlock.Kind.FLUID);
    public static final DeferredBlock<PeripheralDeviceBlock> GAS_DETECTOR = peripheral("gas_detector", PeripheralDeviceBlock.Kind.GAS);
    public static final DeferredBlock<PeripheralDeviceBlock> BLOCK_READER = peripheral("block_reader", PeripheralDeviceBlock.Kind.BLOCK);
    public static final DeferredBlock<PeripheralDeviceBlock> INVENTORY_MANAGER = peripheral("inventory_manager", PeripheralDeviceBlock.Kind.INVENTORY);
    public static final DeferredBlock<PeripheralDeviceBlock> REDSTONE_INTEGRATOR = peripheral("redstone_integrator", PeripheralDeviceBlock.Kind.REDSTONE);

    private static DeferredBlock<PeripheralDeviceBlock> peripheral(String name, PeripheralDeviceBlock.Kind kind) {
        return BLOCKS.register(name, () -> new PeripheralDeviceBlock(kind, BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL).strength(2.5F, 3.0F).requiresCorrectToolForDrops().sound(SoundType.METAL)));
    }
}
