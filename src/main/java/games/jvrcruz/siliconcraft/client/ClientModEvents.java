package games.jvrcruz.siliconcraft.client;

import games.jvrcruz.siliconcraft.client.audio.ComputerAudioPlayer;
import games.jvrcruz.siliconcraft.client.screen.*;
import games.jvrcruz.siliconcraft.compat.controlify.ControlifyComputerScreen;
import games.jvrcruz.siliconcraft.compat.controlify.ExternalControllerManager;
import games.jvrcruz.siliconcraft.init.ModMenuTypes;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

public class ClientModEvents {
    public static void updateAudio(ClientTickEvent.Post event) {
        ComputerAudioPlayer.updateWorldAudio(Minecraft.getInstance().level);
    }

    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.CHIP_FORGE_MENU.get(), ChipForgeScreen::new);
        event.register(ModMenuTypes.MEMORY_FORGE_MENU.get(), MemoryForgeScreen::new);
        event.register(ModMenuTypes.GPU_ASSEMBLER_MENU.get(), GpuAssemblerScreen::new);
        event.register(ModMenuTypes.PROGRAMMER_MENU.get(), ProgrammerScreen::new);
        event.register(ModMenuTypes.SOC_MANUFACTURER_MENU.get(), SocManufacturerScreen::new);
        if (ExternalControllerManager.isControllerSupportEnabled()) {
            event.register(ModMenuTypes.COMPUTER_MENU.get(), ControlifyComputerScreen::new);
        } else {
            event.register(ModMenuTypes.COMPUTER_MENU.get(), ComputerScreen::new);
        }
        event.register(ModMenuTypes.ARCHITECTURE_WRITER_MENU.get(), ArchitectureWriterScreen::new);
        event.register(ModMenuTypes.PROJECT_MANAGER_MENU.get(), ProjectManagerScreen::new);
    }
}
