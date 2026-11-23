package games.jvrcruz.siliconcraft.compat.create;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.api.peripheral.ComputerPeripheral;
import games.jvrcruz.siliconcraft.init.ModBlockEntities;
import games.jvrcruz.siliconcraft.init.ModBlocks;
import games.jvrcruz.siliconcraft.init.ModItems;
import games.jvrcruz.siliconcraft.init.ModMenuTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredItem;

import java.util.function.Supplier;

public final class CreatePeripherals {
    private static final String SENSOR = "airship_sensor";
    private static final String LINK = "digital_redstone_link";
    static Supplier<BlockEntityType<DigitalRedstoneLinkEntity>> LINK_ENTITY;
    static Supplier<MenuType<DigitalRedstoneLinkMenu>> LINK_MENU;
    private static Supplier<AirshipSensorBlock> sensor;
    private static DeferredItem<BlockItem> sensorItem;
    private static DeferredItem<BlockItem> linkItem;

    private CreatePeripherals() {}

    public static void register() {
        if (ModList.get().isLoaded("aeronautics") && ModList.get().isLoaded("sable")) {
            var block = ModBlocks.BLOCKS.register(SENSOR, () -> new AirshipSensorBlock(ironProperties()));
            sensor = block;
            sensorItem = ModItems.ITEMS.registerSimpleBlockItem(SENSOR, block);
            aliasBlockAndItem(SENSOR, SENSOR);
        }
        if (ModList.get().isLoaded("create")) {
            var block = ModBlocks.BLOCKS.register(LINK, () -> new DigitalRedstoneLinkBlock(ironProperties()));
            linkItem = ModItems.ITEMS.registerSimpleBlockItem(LINK, block);
            LINK_ENTITY = ModBlockEntities.BLOCK_ENTITIES.register(LINK,
                    () -> BlockEntityType.Builder.of(DigitalRedstoneLinkEntity::new, block.get()).build(null));
            LINK_MENU = ModMenuTypes.MENUS.register(LINK, () -> IMenuTypeExtension.create(DigitalRedstoneLinkMenu::new));
            aliasBlockAndItem("airship_controller", LINK);
            ModBlockEntities.BLOCK_ENTITIES.addAlias(legacy("airship_controller"), id(LINK));
        }
    }

    private static BlockBehaviour.Properties ironProperties() {
        return BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK);
    }

    private static void aliasBlockAndItem(String oldName, String newName) {
        ModBlocks.BLOCKS.addAlias(legacy(oldName), id(newName));
        ModItems.ITEMS.addAlias(legacy(oldName), id(newName));
    }

    static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, path);
    }

    private static ResourceLocation legacy(String path) {
        return ResourceLocation.fromNamespaceAndPath("aerosilicon", path);
    }

    public static boolean hasRedstoneLink() {
        return LINK_MENU != null;
    }

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        if (sensor != null) {
            event.registerBlock(ComputerPeripheral.CAPABILITY,
                    (level, pos, state, entity, face) -> AirshipSensorBlock.PERIPHERAL, sensor.get());
        }
        if (hasRedstoneLink()) {
            event.registerBlockEntity(ComputerPeripheral.CAPABILITY, LINK_ENTITY.get(), (entity, face) -> entity);
        }
    }

    public static void registerPayloads(PayloadRegistrar registrar) {
        if (hasRedstoneLink()) {
            registrar.playToServer(RenameChannelPayload.TYPE, RenameChannelPayload.CODEC, RenameChannelPayload::handle);
        }
    }

    public static void fillTab(CreativeModeTab.Output output) {
        if (sensorItem != null) output.accept(sensorItem.get());
        if (linkItem != null) output.accept(linkItem.get());
    }
}
