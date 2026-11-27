package games.jvrcruz.siliconcraft.init;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.blockentity.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Siliconcraft.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ChipForgeBlockEntity>> CHIP_FORGE =
            BLOCK_ENTITIES.register("chip_forge", () ->
                    BlockEntityType.Builder.of(ChipForgeBlockEntity::new, ModBlocks.CHIP_FORGE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MemoryForgeBlockEntity>> MEMORY_FORGE =
            BLOCK_ENTITIES.register("memory_forge", () ->
                    BlockEntityType.Builder.of(MemoryForgeBlockEntity::new, ModBlocks.MEMORY_FORGE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GpuAssemblerBlockEntity>> GPU_ASSEMBLER =
            BLOCK_ENTITIES.register("gpu_assembler", () ->
                    BlockEntityType.Builder.of(GpuAssemblerBlockEntity::new, ModBlocks.GPU_ASSEMBLER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ProgrammerBlockEntity>> PROGRAMMER =
            BLOCK_ENTITIES.register("programmer", () ->
                    BlockEntityType.Builder.of(ProgrammerBlockEntity::new, ModBlocks.PROGRAMMER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SocManufacturerBlockEntity>> SOC_MANUFACTURER =
            BLOCK_ENTITIES.register("soc_manufacturer", () ->
                    BlockEntityType.Builder.of(SocManufacturerBlockEntity::new, ModBlocks.SOC_MANUFACTURER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ComputerBlockEntity>> COMPUTER =
            BLOCK_ENTITIES.register("computer", () ->
                    BlockEntityType.Builder.of(ComputerBlockEntity::new, ModBlocks.COMPUTER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ArchitectureWriterBlockEntity>> ARCHITECTURE_WRITER =
            BLOCK_ENTITIES.register("architecture_writer", () ->
                    BlockEntityType.Builder.of(ArchitectureWriterBlockEntity::new, ModBlocks.ARCHITECTURE_WRITER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AssemblyWriterBlockEntity>> ASSEMBLY_WRITER =
            BLOCK_ENTITIES.register("assembly_writer", () ->
                    BlockEntityType.Builder.of(AssemblyWriterBlockEntity::new, ModBlocks.ASSEMBLY_WRITER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CodeCompilerBlockEntity>> CODE_COMPILER =
            BLOCK_ENTITIES.register("code_compiler", () ->
                    BlockEntityType.Builder.of(CodeCompilerBlockEntity::new, ModBlocks.CODE_COMPILER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ProjectCompilerBlockEntity>> PROJECT_COMPILER =
            BLOCK_ENTITIES.register("project_compiler", () ->
                    BlockEntityType.Builder.of(ProjectCompilerBlockEntity::new, ModBlocks.PROJECT_COMPILER.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PeripheralDeviceBlockEntity>> PERIPHERAL_DEVICE =
            BLOCK_ENTITIES.register("peripheral_device", () -> BlockEntityType.Builder.of(PeripheralDeviceBlockEntity::new,
                    ModBlocks.CHAT_BOX.get(), ModBlocks.DISTANCE_DETECTOR.get(), ModBlocks.ENVIRONMENT_DETECTOR.get(),
                    ModBlocks.PLAYER_DETECTOR.get(), ModBlocks.ENERGY_DETECTOR.get(), ModBlocks.FLUID_DETECTOR.get(),
                    ModBlocks.GAS_DETECTOR.get(), ModBlocks.BLOCK_READER.get(), ModBlocks.INVENTORY_MANAGER.get(),
                    ModBlocks.REDSTONE_INTEGRATOR.get()).build(null));
}
