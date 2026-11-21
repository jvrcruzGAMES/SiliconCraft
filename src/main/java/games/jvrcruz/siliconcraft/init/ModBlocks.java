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
}
