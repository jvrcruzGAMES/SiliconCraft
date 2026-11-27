package games.jvrcruz.siliconcraft.init;

import games.jvrcruz.siliconcraft.compat.create.CreatePeripherals;
import games.jvrcruz.siliconcraft.Siliconcraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Siliconcraft.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> SILICON_TAB =
            CREATIVE_MODE_TABS.register("silicon_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.siliconcraft"))
                    .icon(() -> ModItems.SOC.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        // Materials
                        output.accept(ModItems.SILICON_POWDER.get());
                        output.accept(ModItems.CHIP.get());
                        output.accept(ModItems.ARCHITECTURE_WRITEUP.get());
                        output.accept(ModItems.RAM.get());
                        output.accept(ModItems.ROM.get());
                        output.accept(ModItems.STORAGE.get());
                        output.accept(ModItems.GPU.get());
                        output.accept(ModItems.SOC.get());

                        // Blocks
                        output.accept(ModItems.SILICON_ORE_ITEM.get());
                        output.accept(ModItems.DEEPSLATE_SILICON_ORE_ITEM.get());
                        output.accept(ModItems.ARCHITECTURE_WRITER_ITEM.get());
                        output.accept(ModItems.ASSEMBLY_WRITER_ITEM.get());
                        output.accept(ModItems.CODE_COMPILER_ITEM.get());
                        output.accept(ModItems.PROJECT_COMPILER_ITEM.get());
                        output.accept(ModItems.CHIP_FORGE_ITEM.get());
                        output.accept(ModItems.MEMORY_FORGE_ITEM.get());
                        output.accept(ModItems.GPU_ASSEMBLER_ITEM.get());
                        output.accept(ModItems.PROGRAMMER_ITEM.get());
                        output.accept(ModItems.SOC_MANUFACTURER_ITEM.get());
                        output.accept(ModItems.COMPUTER_CASE_ITEM.get());
                        output.accept(ModItems.COMPUTER_ITEM.get());
                        output.accept(ModItems.SPEAKER_ITEM.get());
                        output.accept(ModItems.SERIAL_CABLE_ITEM.get());
                        output.accept(ModItems.SERIAL_EXPANSION_ITEM.get());
                        output.accept(ModItems.CHAT_BOX_ITEM.get());
                        output.accept(ModItems.DISTANCE_DETECTOR_ITEM.get());
                        output.accept(ModItems.ENVIRONMENT_DETECTOR_ITEM.get());
                        output.accept(ModItems.PLAYER_DETECTOR_ITEM.get());
                        output.accept(ModItems.ENERGY_DETECTOR_ITEM.get());
                        output.accept(ModItems.FLUID_DETECTOR_ITEM.get());
                        output.accept(ModItems.GAS_DETECTOR_ITEM.get());
                        output.accept(ModItems.BLOCK_READER_ITEM.get());
                        output.accept(ModItems.INVENTORY_MANAGER_ITEM.get());
                        output.accept(ModItems.REDSTONE_INTEGRATOR_ITEM.get());
                        output.accept(ModItems.MEMORY_CARD.get());
                        CompassPeripherals.fillTab(output);
                        CreatePeripherals.fillTab(output);
                    }).build());
}
