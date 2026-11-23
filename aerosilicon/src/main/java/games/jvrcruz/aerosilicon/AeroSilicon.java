package games.jvrcruz.aerosilicon;

import games.jvrcruz.siliconcraft.api.peripheral.ComputerPeripheral;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import games.jvrcruz.siliconcraft.init.ModCreativeTabs;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

@Mod(AeroSilicon.ID)
public final class AeroSilicon {
    public static final String ID = "aerosilicon";
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, ID);

    public static final Supplier<AirshipSensorBlock> SENSOR = BLOCKS.register("airship_sensor",
            () -> new AirshipSensorBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)));
    public static final Supplier<AirshipControllerBlock> CONTROLLER = BLOCKS.register("airship_controller",
            () -> new AirshipControllerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)));
    public static final Supplier<BlockEntityType<AirshipControllerEntity>> CONTROLLER_ENTITY = ENTITIES.register("airship_controller",
            () -> BlockEntityType.Builder.of(AirshipControllerEntity::new, CONTROLLER.get()).build(null));
    public static final Supplier<MenuType<AirshipControllerMenu>> CONTROLLER_MENU = MENUS.register("airship_controller",
            () -> IMenuTypeExtension.create(AirshipControllerMenu::new));

    public AeroSilicon(IEventBus bus) {
        ITEMS.registerSimpleBlockItem("airship_sensor", SENSOR);
        ITEMS.registerSimpleBlockItem("airship_controller", CONTROLLER);
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        MENUS.register(bus);
        bus.addListener(this::registerCapabilities);
        bus.addListener(this::registerPayloads);
        bus.addListener(this::addCreativeItems);
    }

    static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(ID, path);
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlock(ComputerPeripheral.CAPABILITY,
                (level, pos, state, entity, face) -> AirshipSensorBlock.PERIPHERAL, SENSOR.get());
        event.registerBlockEntity(ComputerPeripheral.CAPABILITY, CONTROLLER_ENTITY.get(),
                (entity, face) -> entity);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(RenameChannelPayload.TYPE, RenameChannelPayload.CODEC, RenameChannelPayload::handle);
    }

    private void addCreativeItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(ModCreativeTabs.SILICON_TAB.getKey())) {
            event.accept(SENSOR.get());
            event.accept(CONTROLLER.get());
        }
    }
}
