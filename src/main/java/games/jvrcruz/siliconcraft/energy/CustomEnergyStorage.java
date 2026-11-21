package games.jvrcruz.siliconcraft.energy;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.energy.EnergyStorage;

public class CustomEnergyStorage extends EnergyStorage {
    public CustomEnergyStorage(int capacity, int maxReceive, int maxExtract) {
        super(capacity, maxReceive, maxExtract);
    }

    public void setEnergy(int energy) {
        this.energy = Math.max(0, Math.min(this.capacity, energy));
    }

    public int extractEnergyInternal(int maxExtract, boolean simulate) {
        int energyExtracted = Math.min(this.energy, maxExtract);
        if (!simulate) {
            this.energy -= energyExtracted;
        }
        return energyExtracted;
    }

    public int receiveEnergyInternal(int maxReceive, boolean simulate) {
        int energyReceived = Math.min(this.capacity - this.energy, maxReceive);
        if (!simulate) {
            this.energy += energyReceived;
        }
        return energyReceived;
    }

    @Override
    public Tag serializeNBT(HolderLookup.Provider lookup) {
        return IntTag.valueOf(this.getEnergyStored());
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider lookup, Tag nbt) {
        if (nbt instanceof IntTag intTag) {
            this.setEnergy(intTag.getAsInt());
        }
    }
}
