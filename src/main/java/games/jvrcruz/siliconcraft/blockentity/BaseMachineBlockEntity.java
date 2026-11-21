package games.jvrcruz.siliconcraft.blockentity;

import games.jvrcruz.siliconcraft.energy.CustomEnergyStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

public abstract class BaseMachineBlockEntity extends BlockEntity implements MenuProvider {
    protected final ItemStackHandler itemHandler;
    protected final CustomEnergyStorage energyStorage;
    protected int burnTime = 0;
    protected int maxBurnTime = 0;

    public BaseMachineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState blockState, int slotCount, int energyCapacity) {
        super(type, pos, blockState);
        this.itemHandler = new ItemStackHandler(slotCount) {
            @Override
            protected void onContentsChanged(int slot) {
                setChanged();
            }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return BaseMachineBlockEntity.this.isSlotItemValid(slot, stack);
            }
        };
        this.energyStorage = new CustomEnergyStorage(energyCapacity, 1000, 0);
    }

    protected boolean isSlotItemValid(int slot, ItemStack stack) {
        if (slot == 0) {
            return AbstractFurnaceBlockEntity.isFuel(stack);
        }
        return true;
    }

    public ItemStackHandler getItemHandler() {
        return itemHandler;
    }

    public CustomEnergyStorage getEnergyStorage() {
        return energyStorage;
    }

    public int getBurnTime() {
        return burnTime;
    }

    public int getMaxBurnTime() {
        return maxBurnTime;
    }

    protected void tickFuel() {
        if (burnTime > 0) {
            burnTime--;
            energyStorage.receiveEnergyInternal(20, false);
            setChanged();
        }

        if (burnTime <= 0 && energyStorage.getEnergyStored() < energyStorage.getMaxEnergyStored()) {
            ItemStack fuelStack = itemHandler.getStackInSlot(0);
            if (!fuelStack.isEmpty()) {
                int fuelDuration = AbstractFurnaceBlockEntity.getFuel().getOrDefault(fuelStack.getItem(), 0);
                if (fuelDuration > 0) {
                    this.burnTime = fuelDuration;
                    this.maxBurnTime = fuelDuration;
                    fuelStack.shrink(1);
                    setChanged();
                }
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inventory", itemHandler.serializeNBT(registries));
        tag.put("Energy", energyStorage.serializeNBT(registries));
        tag.putInt("BurnTime", burnTime);
        tag.putInt("MaxBurnTime", maxBurnTime);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Inventory")) {
            itemHandler.deserializeNBT(registries, tag.getCompound("Inventory"));
        }
        if (tag.contains("Energy")) {
            energyStorage.deserializeNBT(registries, tag.get("Energy"));
        }
        burnTime = tag.getInt("BurnTime");
        maxBurnTime = tag.getInt("MaxBurnTime");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
