package games.jvrcruz.siliconcraft.init;

import games.jvrcruz.siliconcraft.api.peripheral.ComputerPeripheral;
import games.jvrcruz.siliconcraft.blockentity.PeripheralFlow;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

public class ModCapabilities {
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        BuiltinPeripherals.register(event);
        CompassPeripherals.registerCapabilities(event);
        event.registerBlockEntity(ComputerPeripheral.CAPABILITY,
                ModBlockEntities.PERIPHERAL_DEVICE.get(), (entity, side) -> entity);
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.PERIPHERAL_DEVICE.get(),
                (entity, side) -> entity.flow().energy(side));
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ModBlockEntities.PERIPHERAL_DEVICE.get(),
                (entity, side) -> entity.flow().fluid(side));
        PeripheralFlow.registerGas(event);

        // Chip Forge
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.CHIP_FORGE.get(), (be, side) -> be.getEnergyStorage());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.CHIP_FORGE.get(), (be, side) -> be.getItemHandler());

        // Memory Forge
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.MEMORY_FORGE.get(), (be, side) -> be.getEnergyStorage());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.MEMORY_FORGE.get(), (be, side) -> be.getItemHandler());

        // Programmer
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.PROGRAMMER.get(), (be, side) -> be.getEnergyStorage());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.PROGRAMMER.get(), (be, side) -> be.getItemHandler());

        // SoC Manufacturer
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.SOC_MANUFACTURER.get(), (be, side) -> be.getEnergyStorage());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.SOC_MANUFACTURER.get(), (be, side) -> be.getItemHandler());

        // Computer
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.COMPUTER.get(), (be, side) -> be.getItemHandler());
    }
}
