package games.jvrcruz.siliconcraft;

import com.mojang.logging.LogUtils;
import games.jvrcruz.siliconcraft.client.ClientModEvents;
import games.jvrcruz.siliconcraft.init.*;
import games.jvrcruz.siliconcraft.network.ModMessages;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(Siliconcraft.MODID)
public class Siliconcraft {
    public static final String MODID = "siliconcraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Siliconcraft(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(NetworkConfig::onConfig);
        modEventBus.addListener(ModMessages::register);
        modEventBus.addListener(ModCapabilities::registerCapabilities);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            modEventBus.addListener(ClientModEvents::registerScreens);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ClientModEvents::updateAudio);
        }

        // Register Deferred Registers
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModMenuTypes.MENUS.register(modEventBus);
        ModDataComponents.REGISTRY.register(modEventBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);

        NetworkConfig.register(modContainer);

        // Register Server & Game Shutdown Handlers on NeoForge event bus
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent event) -> {
            shutdownAll();
        });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent event) -> {
            shutdownAll();
        });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.GameShuttingDownEvent event) -> {
            shutdownAll();
        });

        // Client world disconnect listener
        if (FMLEnvironment.dist == Dist.CLIENT) {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) -> {
                games.jvrcruz.siliconcraft.client.audio.ClientAudioShutdownHelper.stopAllAudio();
            });
        }

        // JVM Shutdown Hook for forced terminations, crashes, or SIGTERM
        Runtime.getRuntime().addShutdownHook(new Thread(Siliconcraft::shutdownAll, "SiliconCraft-ShutdownHook"));
    }

    /**
     * Unloads all background worker threads and releases hardware audio resources immediately.
     */
    public static void shutdownAll() {
        try {
            games.jvrcruz.siliconcraft.vm.ComputerThreadManager.shutdown();
        } catch (Throwable ignored) {}

        if (FMLEnvironment.dist == Dist.CLIENT) {
            try {
                games.jvrcruz.siliconcraft.client.audio.ClientAudioShutdownHelper.shutdown();
            } catch (Throwable ignored) {}
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Silicon Craft initialized successfully.");
    }
}
