package games.jvrcruz.siliconcraft.blockentity;

import games.jvrcruz.siliconcraft.block.BaseMachineBlock;
import games.jvrcruz.siliconcraft.data.RamData;
import games.jvrcruz.siliconcraft.data.RomData;
import games.jvrcruz.siliconcraft.data.StorageData;
import games.jvrcruz.siliconcraft.init.ModBlockEntities;
import games.jvrcruz.siliconcraft.init.ModDataComponents;
import games.jvrcruz.siliconcraft.init.ModItems;
import games.jvrcruz.siliconcraft.menu.MemoryForgeMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public class MemoryForgeBlockEntity extends BaseMachineBlockEntity {
    private int mode = 0; // 0: RAM, 1: ROM, 2: Storage
    private boolean nvmeEnabled = false;
    private int progress = 0;
    private int maxProgress = 60;
    private boolean forging = false;

    protected final ContainerData dataAccess = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case 0 -> mode;
                case 1 -> nvmeEnabled ? 1 : 0;
                case 2 -> progress;
                case 3 -> maxProgress;
                case 4 -> energyStorage.getEnergyStored();
                case 5 -> energyStorage.getMaxEnergyStored();
                case 6 -> burnTime;
                case 7 -> maxBurnTime;
                case 8 -> forging ? 1 : 0;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            switch (index) {
                case 0 -> mode = value;
                case 1 -> nvmeEnabled = (value != 0);
                case 2 -> progress = value;
                case 3 -> maxProgress = value;
                case 6 -> burnTime = value;
                case 7 -> maxBurnTime = value;
                case 8 -> forging = (value != 0);
            }
        }

        @Override
        public int getCount() {
            return 9;
        }
    };

    public MemoryForgeBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.MEMORY_FORGE.get(), pos, blockState, 5, 30000);
    }

    @Override
    protected boolean isSlotItemValid(int slot, ItemStack stack) {
        return switch (slot) {
            case 0 -> net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.isFuel(stack);
            case 1 -> stack.is(ModItems.SILICON_POWDER.get());
            case 2 -> stack.is(Items.GOLD_INGOT);
            case 3 -> stack.is(Items.DIAMOND);
            default -> false;
        };
    }

    public int getMode() { return mode; }
    public void setMode(int mode) { this.mode = mode; setChanged(); }
    public boolean isNvmeEnabled() { return nvmeEnabled; }
    public void setNvmeEnabled(boolean nvme) { this.nvmeEnabled = nvme; setChanged(); }
    public ContainerData getDataAccess() { return dataAccess; }

    public int getRequiredSilicon() {
        return (mode == 2) ? 4 : 2;
    }

    public int getRequiredGold() {
        return (mode == 2) ? 2 : 1;
    }

    public int getRequiredEnergy() {
        if (mode == 2) return nvmeEnabled ? 1200 : 800;
        return 400;
    }

    public int calculateMaxProgress() {
        if (mode == 2) return nvmeEnabled ? 100 : 80;
        return 60;
    }

    public void startForging() {
        if (canForge()) {
            this.maxProgress = calculateMaxProgress();
            this.progress = 0;
            this.forging = true;
            setChanged();
        }
    }

    public boolean canForge() {
        ItemStack silicon = itemHandler.getStackInSlot(1);
        ItemStack gold = itemHandler.getStackInSlot(2);
        ItemStack controller = itemHandler.getStackInSlot(3);
        ItemStack output = itemHandler.getStackInSlot(4);

        if (silicon.isEmpty() || !silicon.is(ModItems.SILICON_POWDER.get()) || silicon.getCount() < getRequiredSilicon()) return false;
        if (gold.isEmpty() || !gold.is(Items.GOLD_INGOT) || gold.getCount() < getRequiredGold()) return false;
        if (mode == 2 && nvmeEnabled) {
            if (controller.isEmpty() || !controller.is(Items.DIAMOND)) return false;
        }
        if (energyStorage.getEnergyStored() < getRequiredEnergy()) return false;

        return output.isEmpty() || output.getCount() < output.getMaxStackSize();
    }

    public void tick(Level level, BlockPos pos, BlockState state) {
        tickFuel();

        if (forging) {
            if (!canForge()) {
                forging = false;
                progress = 0;
                if (state.getValue(BaseMachineBlock.LIT)) {
                    level.setBlock(pos, state.setValue(BaseMachineBlock.LIT, false), 3);
                }
                setChanged();
                return;
            }

            if (!state.getValue(BaseMachineBlock.LIT)) {
                level.setBlock(pos, state.setValue(BaseMachineBlock.LIT, true), 3);
            }

            int energyPerTick = Math.max(1, getRequiredEnergy() / maxProgress);
            energyStorage.extractEnergyInternal(energyPerTick, false);
            progress++;

            if (progress >= maxProgress) {
                // Consume materials
                itemHandler.getStackInSlot(1).shrink(getRequiredSilicon());
                itemHandler.getStackInSlot(2).shrink(getRequiredGold());
                if (mode == 2 && nvmeEnabled) {
                    itemHandler.getStackInSlot(3).shrink(1);
                }

                ItemStack resultStack;
                if (mode == 0) {
                    resultStack = new ItemStack(ModItems.RAM.get());
                    resultStack.set(ModDataComponents.RAM_DATA.get(), RamData.DEFAULT);
                } else if (mode == 1) {
                    resultStack = new ItemStack(ModItems.ROM.get());
                    resultStack.set(ModDataComponents.ROM_DATA.get(), RomData.EMPTY);
                } else {
                    resultStack = new ItemStack(ModItems.STORAGE.get());
                    resultStack.set(ModDataComponents.STORAGE_DATA.get(), new StorageData(nvmeEnabled, StorageData.DRIVE_CAPACITY_KB));
                }

                ItemStack out = itemHandler.getStackInSlot(4);
                if (out.isEmpty()) {
                    itemHandler.setStackInSlot(4, resultStack);
                } else if (ItemStack.isSameItemSameComponents(out, resultStack) && out.getCount() < out.getMaxStackSize()) {
                    out.grow(1);
                }

                progress = 0;
                forging = false;
                if (state.getValue(BaseMachineBlock.LIT)) {
                    level.setBlock(pos, state.setValue(BaseMachineBlock.LIT, false), 3);
                }
                setChanged();
            }
        } else {
            if (state.getValue(BaseMachineBlock.LIT)) {
                level.setBlock(pos, state.setValue(BaseMachineBlock.LIT, false), 3);
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Mode", mode);
        tag.putBoolean("NvmeEnabled", nvmeEnabled);
        tag.putInt("Progress", progress);
        tag.putInt("MaxProgress", maxProgress);
        tag.putBoolean("Forging", forging);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        mode = tag.getInt("Mode");
        nvmeEnabled = tag.getBoolean("NvmeEnabled");
        progress = tag.getInt("Progress");
        maxProgress = tag.getInt("MaxProgress");
        forging = tag.getBoolean("Forging");
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.siliconcraft.memory_forge");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new MemoryForgeMenu(containerId, playerInventory, this, this.dataAccess);
    }
}
