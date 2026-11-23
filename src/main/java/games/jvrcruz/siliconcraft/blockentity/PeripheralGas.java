package games.jvrcruz.siliconcraft.blockentity;

import games.jvrcruz.siliconcraft.init.ModBlockEntities;
import mekanism.api.Action;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

final class PeripheralGas {
    private static final BlockCapability<IChemicalHandler, Direction> CAPABILITY =
            BlockCapability.createSided(ResourceLocation.fromNamespaceAndPath("mekanism", "chemical_handler"), IChemicalHandler.class);

    static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(CAPABILITY, ModBlockEntities.PERIPHERAL_DEVICE.get(), (entity, side) ->
                entity.flow().isGasSide(side) ? handler(entity.flow(), side) : null);
    }

    static double radiation(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
        return mekanism.api.radiation.IRadiationManager.INSTANCE.getRadiationLevel(level, pos);
    }

    static int capacity(PeripheralFlow flow, Direction side) {
        return flow.withTarget(CAPABILITY, side, 0, target -> {
            int total = 0;
            for (int tank = 0; tank < target.getChemicalTanks(); tank++) {
                total += (int) Math.min(Integer.MAX_VALUE - total, target.getChemicalTankCapacity(tank));
            }
            return total;
        });
    }

    static String ready(PeripheralFlow flow, Direction side) {
        return flow.withTarget(CAPABILITY, side, "", target -> id(target.extractChemical(Integer.MAX_VALUE, Action.SIMULATE)));
    }

    private static String id(ChemicalStack stack) {
        return stack.isEmpty() ? "" : stack.getChemicalHolder().unwrapKey().map(key -> key.location().toString()).orElse("");
    }

    private static IChemicalHandler handler(PeripheralFlow flow, Direction side) {
        return new IChemicalHandler() {
            @Override public int getChemicalTanks() { return flow.withTarget(CAPABILITY, side, 0, IChemicalHandler::getChemicalTanks); }
            @Override public ChemicalStack getChemicalInTank(int tank) {
                return flow.withTarget(CAPABILITY, side, ChemicalStack.EMPTY, target ->
                        valid(target, tank) ? target.getChemicalInTank(tank).copy() : ChemicalStack.EMPTY);
            }
            @Override public void setChemicalInTank(int tank, ChemicalStack stack) {
                // An inline meter does not expose direct mutation of remote storage.
            }
            @Override public long getChemicalTankCapacity(int tank) {
                return flow.withTarget(CAPABILITY, side, 0L, target -> valid(target, tank) ? target.getChemicalTankCapacity(tank) : 0L);
            }
            @Override public boolean isValid(int tank, ChemicalStack stack) {
                return flow.withTarget(CAPABILITY, side, false, target -> valid(target, tank) && target.isValid(tank, stack));
            }
            @Override public ChemicalStack insertChemical(int tank, ChemicalStack stack, Action action) {
                if (stack.isEmpty() || flow.allowance() == 0) return stack;
                return flow.withTarget(CAPABILITY, side, stack, target -> {
                    if (!valid(target, tank)) return stack;
                    long amount = Math.min(stack.getAmount(), flow.allowance());
                    var remainder = target.insertChemical(tank, stack.copyWithAmount(amount), action);
                    long moved = amount - remainder.getAmount();
                    if (action.execute()) flow.moved(moved, id(stack));
                    return stack.copyWithAmount(stack.getAmount() - moved);
                });
            }
            @Override public ChemicalStack extractChemical(int tank, long amount, Action action) {
                if (amount <= 0 || flow.allowance() == 0) return ChemicalStack.EMPTY;
                return flow.withTarget(CAPABILITY, side, ChemicalStack.EMPTY, target -> {
                    if (!valid(target, tank)) return ChemicalStack.EMPTY;
                    var extracted = target.extractChemical(tank, Math.min(amount, flow.allowance()), action);
                    if (action.execute()) flow.moved(extracted.getAmount(), id(extracted));
                    return extracted;
                });
            }
        };
    }

    private static boolean valid(IChemicalHandler target, int tank) { return tank >= 0 && tank < target.getChemicalTanks(); }
}
