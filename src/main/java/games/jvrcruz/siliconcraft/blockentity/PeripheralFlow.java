package games.jvrcruz.siliconcraft.blockentity;

import games.jvrcruz.siliconcraft.api.peripheral.ComputerPeripheral.Result;
import games.jvrcruz.siliconcraft.block.PeripheralDeviceBlock.Kind;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.Optional;
import java.util.function.Function;

public final class PeripheralFlow {
    private final PeripheralDeviceBlockEntity device;
    private int limit = Integer.MAX_VALUE;
    private long tick = Long.MIN_VALUE;
    private int transferred;
    private String lastId = "";
    private boolean forwarding;

    PeripheralFlow(PeripheralDeviceBlockEntity device) { this.device = device; }

    public static void registerGas(RegisterCapabilitiesEvent event) {
        if (ModList.get().isLoaded("mekanism")) PeripheralGas.register(event);
    }

    int limit() { return limit; }
    void limit(int value) { limit = value; }
    private long time() { return device.getLevel() == null ? Long.MIN_VALUE : device.getLevel().getGameTime(); }
    int allowance() { return Math.max(0, limit - (tick == time() ? transferred : 0)); }

    void moved(long amount, String id) {
        if (amount <= 0) return;
        long now = time();
        if (tick != now) {
            tick = now;
            transferred = 0;
        }
        transferred += (int) Math.min(amount, Integer.MAX_VALUE - transferred);
        lastId = id;
    }

    <T, R> R withTarget(BlockCapability<T, Direction> capability, Direction face, R empty, Function<T, R> action) {
        if (forwarding || device.isRemoved() || !(device.getLevel() instanceof ServerLevel world)
                || !world.getServer().isSameThread()) return empty;
        Direction side = face.getOpposite();
        var target = device.getBlockPos().relative(side);
        if (!device.accessible(world, target)) return empty;
        forwarding = true;
        try {
            T handler = world.getCapability(capability, target, face);
            return handler == null ? empty : action.apply(handler);
        } finally {
            forwarding = false;
        }
    }

    public boolean isGasSide(Direction side) { return supports(Kind.GAS, side); }

    private boolean supports(Kind kind, Direction side) {
        return device.kind() == kind && side != null && side.getAxis() == device.front().getAxis();
    }

    Optional<Result> call(int method, int arg) {
        return switch (method) {
            case 0x470 -> number(tick >= time() - 1 ? transferred : 0);
            case 0x471 -> number(limit);
            case 0x472 -> {
                limit = Math.max(0, arg);
                device.setChanged();
                yield number(limit);
            }
            case 0x473 -> number(capacity());
            case 0x474 -> Optional.of(Result.text(lastId));
            case 0x475 -> Optional.of(Result.text(readyId()));
            default -> Optional.empty();
        };
    }

    private int capacity() {
        Direction outlet = device.front();
        return switch (device.kind()) {
            case ENERGY -> energy(outlet).getMaxEnergyStored();
            case FLUID -> withTarget(Capabilities.FluidHandler.BLOCK, outlet, 0, handler -> {
                long capacity = 0;
                for (int tank = 0; tank < handler.getTanks(); tank++) capacity += handler.getTankCapacity(tank);
                return (int) Math.min(Integer.MAX_VALUE, capacity);
            });
            case GAS -> ModList.get().isLoaded("mekanism") ? PeripheralGas.capacity(this, outlet) : 0;
            default -> 0;
        };
    }

    private String readyId() {
        Direction outlet = device.front();
        return switch (device.kind()) {
            case ENERGY -> energy(outlet).getEnergyStored() > 0 ? "neoforge:energy" : "";
            case FLUID -> withTarget(Capabilities.FluidHandler.BLOCK, outlet, "", handler -> fluidId(handler.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.SIMULATE)));
            case GAS -> ModList.get().isLoaded("mekanism") ? PeripheralGas.ready(this, outlet) : "";
            default -> "";
        };
    }

    private static Optional<Result> number(int value) { return Optional.of(Result.number(value)); }

    private static String fluidId(FluidStack stack) {
        return stack.isEmpty() ? "" : net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString();
    }

    public IEnergyStorage energy(Direction side) {
        if (!supports(Kind.ENERGY, side)) return null;
        return new IEnergyStorage() {
            @Override public int receiveEnergy(int amount, boolean simulate) {
                return withTarget(Capabilities.EnergyStorage.BLOCK, side, 0, target -> {
                    int result = target.receiveEnergy(Math.max(0, Math.min(allowance(), amount)), simulate);
                    if (!simulate) moved(result, "neoforge:energy");
                    return result;
                });
            }
            @Override public int extractEnergy(int amount, boolean simulate) {
                return withTarget(Capabilities.EnergyStorage.BLOCK, side, 0, target -> {
                    int result = target.extractEnergy(Math.max(0, Math.min(allowance(), amount)), simulate);
                    if (!simulate) moved(result, "neoforge:energy");
                    return result;
                });
            }
            @Override public int getEnergyStored() { return withTarget(Capabilities.EnergyStorage.BLOCK, side, 0, IEnergyStorage::getEnergyStored); }
            @Override public int getMaxEnergyStored() { return withTarget(Capabilities.EnergyStorage.BLOCK, side, 0, IEnergyStorage::getMaxEnergyStored); }
            @Override public boolean canExtract() { return withTarget(Capabilities.EnergyStorage.BLOCK, side, false, IEnergyStorage::canExtract); }
            @Override public boolean canReceive() { return withTarget(Capabilities.EnergyStorage.BLOCK, side, false, IEnergyStorage::canReceive); }
        };
    }

    public IFluidHandler fluid(Direction side) {
        if (!supports(Kind.FLUID, side)) return null;
        return new IFluidHandler() {
            @Override public int getTanks() { return withTarget(Capabilities.FluidHandler.BLOCK, side, 0, IFluidHandler::getTanks); }
            @Override public FluidStack getFluidInTank(int tank) {
                return withTarget(Capabilities.FluidHandler.BLOCK, side, FluidStack.EMPTY, target ->
                        tank >= 0 && tank < target.getTanks() ? target.getFluidInTank(tank).copy() : FluidStack.EMPTY);
            }
            @Override public int getTankCapacity(int tank) {
                return withTarget(Capabilities.FluidHandler.BLOCK, side, 0, target ->
                        tank >= 0 && tank < target.getTanks() ? target.getTankCapacity(tank) : 0);
            }
            @Override public boolean isFluidValid(int tank, FluidStack stack) {
                return withTarget(Capabilities.FluidHandler.BLOCK, side, false, target ->
                        tank >= 0 && tank < target.getTanks() && target.isFluidValid(tank, stack));
            }
            @Override public int fill(FluidStack resource, FluidAction action) {
                if (resource.isEmpty() || allowance() == 0) return 0;
                return withTarget(Capabilities.FluidHandler.BLOCK, side, 0, target -> {
                    int amount = target.fill(resource.copyWithAmount(Math.min(allowance(), resource.getAmount())), action);
                    if (action.execute()) moved(amount, fluidId(resource));
                    return amount;
                });
            }
            @Override public FluidStack drain(FluidStack resource, FluidAction action) {
                if (resource.isEmpty() || allowance() == 0) return FluidStack.EMPTY;
                return withTarget(Capabilities.FluidHandler.BLOCK, side, FluidStack.EMPTY, target ->
                        record(target.drain(resource.copyWithAmount(Math.min(resource.getAmount(), allowance())), action), action));
            }
            @Override public FluidStack drain(int amount, FluidAction action) {
                if (amount <= 0 || allowance() == 0) return FluidStack.EMPTY;
                return withTarget(Capabilities.FluidHandler.BLOCK, side, FluidStack.EMPTY, target ->
                        record(target.drain(Math.min(allowance(), amount), action), action));
            }
            private FluidStack record(FluidStack resource, FluidAction action) {
                if (action.execute()) moved(resource.getAmount(), fluidId(resource));
                return resource;
            }
        };
    }
}
