package games.jvrcruz.siliconcraft.init;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.data.*;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModDataComponents {
    public static final DeferredRegister.DataComponents REGISTRY = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Siliconcraft.MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ChipData>> CHIP_DATA =
            REGISTRY.registerComponentType("chip_data", builder -> builder.persistent(ChipData.CODEC).networkSynchronized(ChipData.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<RamData>> RAM_DATA =
            REGISTRY.registerComponentType("ram_data", builder -> builder.persistent(RamData.CODEC).networkSynchronized(RamData.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<RomData>> ROM_DATA =
            REGISTRY.registerComponentType("rom_data", builder -> builder.persistent(RomData.CODEC).networkSynchronized(RomData.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<StorageData>> STORAGE_DATA =
            REGISTRY.registerComponentType("storage_data", builder -> builder.persistent(StorageData.CODEC).networkSynchronized(StorageData.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<SocData>> SOC_DATA =
            REGISTRY.registerComponentType("soc_data", builder -> builder.persistent(SocData.CODEC).networkSynchronized(SocData.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ArchitectureData>> ARCHITECTURE_DATA =
            REGISTRY.registerComponentType("architecture_data", builder -> builder.persistent(ArchitectureData.CODEC).networkSynchronized(ArchitectureData.STREAM_CODEC));
}
