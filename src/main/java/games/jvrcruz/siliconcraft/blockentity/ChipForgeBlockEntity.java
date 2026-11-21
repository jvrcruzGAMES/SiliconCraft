package games.jvrcruz.siliconcraft.blockentity;

import games.jvrcruz.siliconcraft.block.BaseMachineBlock;
import games.jvrcruz.siliconcraft.data.ArchitectureData;
import games.jvrcruz.siliconcraft.data.ChipData;
import games.jvrcruz.siliconcraft.init.ModBlockEntities;
import games.jvrcruz.siliconcraft.init.ModDataComponents;
import games.jvrcruz.siliconcraft.init.ModItems;
import games.jvrcruz.siliconcraft.menu.ChipForgeMenu;
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

import java.util.ArrayList;
import java.util.List;

public class ChipForgeBlockEntity extends BaseMachineBlockEntity {
    public static final List<String> ALL_INSTRUCTIONS = games.jvrcruz.siliconcraft.compiler.InstructionValidator.ALL_INSTRUCTIONS;

    private int progress = 0;
    private int maxProgress = 100;
    private boolean forging = false;

    // Container data for syncing progress and energy:
    // 0: progress, 1: maxProgress, 2: energy, 3: maxEnergy, 4: burnTime, 5: maxBurnTime, 6: forging (0/1)
    protected final ContainerData dataAccess = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case 0 -> progress;
                case 1 -> maxProgress;
                case 2 -> energyStorage.getEnergyStored();
                case 3 -> energyStorage.getMaxEnergyStored();
                case 4 -> burnTime;
                case 5 -> maxBurnTime;
                case 6 -> forging ? 1 : 0;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            switch (index) {
                case 0 -> progress = value;
                case 1 -> maxProgress = value;
                case 4 -> burnTime = value;
                case 5 -> maxBurnTime = value;
                case 6 -> forging = (value != 0);
            }
        }

        @Override
        public int getCount() {
            return 7;
        }
    };

    public ChipForgeBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.CHIP_FORGE.get(), pos, blockState, 5, 30000);
    }

    @Override
    protected boolean isSlotItemValid(int slot, ItemStack stack) {
        return switch (slot) {
            case 0 -> net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.isFuel(stack);
            case 1 -> stack.is(ModItems.SILICON_POWDER.get());
            case 2 -> stack.is(Items.REDSTONE);
            case 3 -> stack.is(ModItems.ARCHITECTURE_WRITEUP.get());
            default -> false;
        };
    }

    public ContainerData getDataAccess() {
        return dataAccess;
    }

    public ArchitectureData getInstalledArchitecture() {
        ItemStack writeup = itemHandler.getStackInSlot(3);
        if (!writeup.isEmpty() && writeup.is(ModItems.ARCHITECTURE_WRITEUP.get())) {
            ArchitectureData data = writeup.get(ModDataComponents.ARCHITECTURE_DATA.get());
            if (data != null && !data.instructions().isEmpty()) {
                return data;
            }
        }
        return null;
    }

    public int getRequiredSilicon() {
        ArchitectureData arch = getInstalledArchitecture();
        int count = (arch != null) ? arch.instructions().size() : 3;
        return 1 + (count / 3);
    }

    public int getRequiredRedstone() {
        ArchitectureData arch = getInstalledArchitecture();
        int count = (arch != null) ? arch.instructions().size() : 3;
        return 1 + (count / 2);
    }

    public int getRequiredEnergy() {
        ArchitectureData arch = getInstalledArchitecture();
        int count = (arch != null) ? arch.instructions().size() : 3;
        return 400 + (count * 200);
    }

    public int calculateMaxProgress() {
        ArchitectureData arch = getInstalledArchitecture();
        int count = (arch != null) ? arch.instructions().size() : 3;
        return 60 + (count * 20);
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
        ArchitectureData arch = getInstalledArchitecture();
        if (arch == null || arch.instructions().isEmpty()) {
            return false;
        }

        ItemStack silicon = itemHandler.getStackInSlot(1);
        ItemStack redstone = itemHandler.getStackInSlot(2);
        ItemStack output = itemHandler.getStackInSlot(4);

        if (silicon.isEmpty() || !silicon.is(ModItems.SILICON_POWDER.get()) || silicon.getCount() < getRequiredSilicon()) {
            return false;
        }
        if (redstone.isEmpty() || !redstone.is(Items.REDSTONE) || redstone.getCount() < getRequiredRedstone()) {
            return false;
        }
        if (energyStorage.getEnergyStored() < getRequiredEnergy()) {
            return false;
        }
        return output.isEmpty() || (output.is(ModItems.CHIP.get()) && output.getCount() < output.getMaxStackSize());
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
                // Complete forge
                ArchitectureData arch = getInstalledArchitecture();
                String archName = (arch != null && !arch.name().isEmpty()) ? arch.name() : "Custom-Arch";
                List<String> instructions = (arch != null) ? arch.instructions() : List.of("NOP");

                itemHandler.getStackInSlot(1).shrink(getRequiredSilicon());
                itemHandler.getStackInSlot(2).shrink(getRequiredRedstone());
                // NOTE: Slot 3 (Architecture Writeup) is NOT consumed!

                ItemStack chipStack = new ItemStack(ModItems.CHIP.get());
                chipStack.set(ModDataComponents.CHIP_DATA.get(), new ChipData(archName, new ArrayList<>(instructions)));

                ItemStack out = itemHandler.getStackInSlot(4);
                if (out.isEmpty()) {
                    itemHandler.setStackInSlot(4, chipStack);
                } else if (out.is(ModItems.CHIP.get()) && ItemStack.isSameItemSameComponents(out, chipStack)) {
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
        tag.putInt("Progress", progress);
        tag.putInt("MaxProgress", maxProgress);
        tag.putBoolean("Forging", forging);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        progress = tag.getInt("Progress");
        maxProgress = tag.getInt("MaxProgress");
        forging = tag.getBoolean("Forging");
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.siliconcraft.chip_forge");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new ChipForgeMenu(containerId, playerInventory, this, this.dataAccess);
    }
}
