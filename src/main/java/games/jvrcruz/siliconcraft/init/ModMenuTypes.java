package games.jvrcruz.siliconcraft.init;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.menu.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, Siliconcraft.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<ChipForgeMenu>> CHIP_FORGE_MENU =
            MENUS.register("chip_forge_menu", () -> IMenuTypeExtension.create(ChipForgeMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<MemoryForgeMenu>> MEMORY_FORGE_MENU =
            MENUS.register("memory_forge_menu", () -> IMenuTypeExtension.create(MemoryForgeMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<GpuAssemblerMenu>> GPU_ASSEMBLER_MENU =
            MENUS.register("gpu_assembler_menu", () -> IMenuTypeExtension.create(GpuAssemblerMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<ProgrammerMenu>> PROGRAMMER_MENU =
            MENUS.register("programmer_menu", () -> IMenuTypeExtension.create(ProgrammerMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<SocManufacturerMenu>> SOC_MANUFACTURER_MENU =
            MENUS.register("soc_manufacturer_menu", () -> IMenuTypeExtension.create(SocManufacturerMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<ComputerMenu>> COMPUTER_MENU =
            MENUS.register("computer_menu", () -> IMenuTypeExtension.create(ComputerMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<ArchitectureWriterMenu>> ARCHITECTURE_WRITER_MENU =
            MENUS.register("architecture_writer_menu", () -> IMenuTypeExtension.create(ArchitectureWriterMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<AssemblyWriterMenu>> ASSEMBLY_WRITER_MENU =
            MENUS.register("assembly_writer_menu", () -> IMenuTypeExtension.create(AssemblyWriterMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<CodeCompilerMenu>> CODE_COMPILER_MENU =
            MENUS.register("code_compiler_menu", () -> IMenuTypeExtension.create(CodeCompilerMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<ProjectCompilerMenu>> PROJECT_COMPILER_MENU =
            MENUS.register("project_compiler_menu", () -> IMenuTypeExtension.create(ProjectCompilerMenu::new));
}
