package games.jvrcruz.siliconcraft;

import com.mojang.logging.LogUtils;
import games.jvrcruz.siliconcraft.client.ClientModEvents;
import games.jvrcruz.siliconcraft.init.*;
import games.jvrcruz.siliconcraft.network.ModMessages;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(Siliconcraft.MODID)
public class Siliconcraft {
    public static final String MODID = "siliconcraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Siliconcraft(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(Config::onLoad);
        modEventBus.addListener(ModMessages::register);
        modEventBus.addListener(ModCapabilities::registerCapabilities);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            modEventBus.addListener(ClientModEvents::registerScreens);
        }

        // Register Deferred Registers
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModMenuTypes.MENUS.register(modEventBus);
        ModDataComponents.REGISTRY.register(modEventBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Silicon Craft initialized successfully.");
    }
}
