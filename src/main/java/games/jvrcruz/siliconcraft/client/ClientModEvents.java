package games.jvrcruz.siliconcraft.client;

import games.jvrcruz.siliconcraft.client.screen.*;
import games.jvrcruz.siliconcraft.init.ModMenuTypes;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

public class ClientModEvents {
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.CHIP_FORGE_MENU.get(), ChipForgeScreen::new);
        event.register(ModMenuTypes.MEMORY_FORGE_MENU.get(), MemoryForgeScreen::new);
        event.register(ModMenuTypes.PROGRAMMER_MENU.get(), ProgrammerScreen::new);
        event.register(ModMenuTypes.SOC_MANUFACTURER_MENU.get(), SocManufacturerScreen::new);
        event.register(ModMenuTypes.COMPUTER_MENU.get(), ComputerScreen::new);
        event.register(ModMenuTypes.ARCHITECTURE_WRITER_MENU.get(), ArchitectureWriterScreen::new);
        event.register(ModMenuTypes.ASSEMBLY_WRITER_MENU.get(), AssemblyWriterScreen::new);
        event.register(ModMenuTypes.CODE_COMPILER_MENU.get(), CodeCompilerScreen::new);
        event.register(ModMenuTypes.PROJECT_COMPILER_MENU.get(), ProjectCompilerScreen::new);
    }
}
