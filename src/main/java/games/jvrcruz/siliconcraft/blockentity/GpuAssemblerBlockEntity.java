package games.jvrcruz.siliconcraft.blockentity;

import games.jvrcruz.siliconcraft.block.BaseMachineBlock;
import games.jvrcruz.siliconcraft.data.ArchitectureData;
import games.jvrcruz.siliconcraft.data.GpuData;
import games.jvrcruz.siliconcraft.data.RamData;
import games.jvrcruz.siliconcraft.init.ModBlockEntities;
import games.jvrcruz.siliconcraft.init.ModDataComponents;
import games.jvrcruz.siliconcraft.init.ModItems;
import games.jvrcruz.siliconcraft.item.ArchitectureWriteupItem;
import games.jvrcruz.siliconcraft.menu.GpuAssemblerMenu;
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
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;

public class GpuAssemblerBlockEntity extends BaseMachineBlockEntity {
    public static final int SLOT_FUEL = 0;
    public static final int SLOT_SILICON = 1;
    public static final int SLOT_REDSTONE = 2;
    public static final int SLOT_WRITEUP = 3;
    public static final int RAM_SLOT_START = 4;
    public static final int RAM_SLOT_COUNT = GpuData.MAX_RAM_MODULES; // 8
    public static final int SLOT_OUTPUT = 12;
    public static final int TOTAL_SLOTS = 13;

    private int progress = 0;
    private int maxProgress = 60;
    private boolean assembling = false;

    // ContainerData:
    // 0: progress, 1: maxProgress, 2: energy, 3: maxEnergy, 4: burnTime, 5: maxBurnTime,
    // 6: assembling (0/1), 7: ramCount (0..8), 8: totalVramKb, 9: requiredEnergy,
    // 10: computeUnits, 11: requiredSilicon, 12: requiredRedstone
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
                case 6 -> assembling ? 1 : 0;
                case 7 -> getInstalledRamCount();
                case 8 -> getTotalVramBytes() / 1024;
                case 9 -> getRequiredEnergy();
                case 10 -> getPreviewComputeUnits();
                case 11 -> getRequiredSilicon();
                case 12 -> getRequiredRedstone();
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
                case 6 -> assembling = (value != 0);
            }
        }

        @Override
        public int getCount() {
            return 13;
        }
    };

    public GpuAssemblerBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.GPU_ASSEMBLER.get(), pos, blockState, TOTAL_SLOTS, 30000);
    }

    private final ItemStackHandler gpuItemHandler = new ItemStackHandler(TOTAL_SLOTS) {
        @Override
        public int getSlotLimit(int slot) {
            if (slot == SLOT_WRITEUP || slot == SLOT_OUTPUT) {
                return 1;
            }
            if (slot >= RAM_SLOT_START && slot < RAM_SLOT_START + RAM_SLOT_COUNT) {
                return 1;
            }
            return 64;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return isSlotItemValid(slot, stack);
        }

        @Override
        public void setSize(int size) {
            super.setSize(Math.max(size, TOTAL_SLOTS));
        }

        @Override
        protected void onContentsChanged(int slot) {
            if (assembling && slot >= SLOT_SILICON && slot <= SLOT_OUTPUT) {
                assembling = false;
                progress = 0;
            }
            setChanged();
        }
    };

    @Override
    public ItemStackHandler getItemHandler() {
        return gpuItemHandler;
    }

    @Override
    protected boolean isSlotItemValid(int slot, ItemStack stack) {
        if (slot == SLOT_FUEL) {
            return net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.isFuel(stack);
        }
        if (slot == SLOT_SILICON) {
            return stack.is(ModItems.SILICON_POWDER.get());
        }
        if (slot == SLOT_REDSTONE) {
            return stack.is(Items.REDSTONE);
        }
        if (slot == SLOT_WRITEUP) {
            return ArchitectureWriteupItem.isGpuWriteup(stack);
        }
        if (slot >= RAM_SLOT_START && slot < RAM_SLOT_START + RAM_SLOT_COUNT) {
            return stack.is(ModItems.RAM.get());
        }
        return false;
    }

    public ContainerData getDataAccess() {
        return dataAccess;
    }

    public ArchitectureData getInstalledArchitecture() {
        ItemStack writeup = gpuItemHandler.getStackInSlot(SLOT_WRITEUP);
        if (!writeup.isEmpty() && writeup.is(ModItems.ARCHITECTURE_WRITEUP.get())) {
            ArchitectureData data = writeup.get(ModDataComponents.ARCHITECTURE_DATA.get());
            if (data != null && data.isGpu() && !data.instructions().isEmpty()) {
                return data;
            }
        }
        return null;
    }

    public int getInstalledRamCount() {
        int count = 0;
        for (int i = RAM_SLOT_START; i < RAM_SLOT_START + RAM_SLOT_COUNT; i++) {
            ItemStack stack = gpuItemHandler.getStackInSlot(i);
            if (!stack.isEmpty() && stack.is(ModItems.RAM.get())) {
                count++;
            }
        }
        return count;
    }

    public int getTotalVramBytes() {
        int total = 0;
        for (int i = RAM_SLOT_START; i < RAM_SLOT_START + RAM_SLOT_COUNT; i++) {
            ItemStack stack = gpuItemHandler.getStackInSlot(i);
            if (!stack.isEmpty() && stack.is(ModItems.RAM.get())) {
                RamData rd = stack.getOrDefault(ModDataComponents.RAM_DATA.get(), RamData.DEFAULT);
                total += Math.max(0, rd.capacity());
            }
        }
        return total;
    }

    public int getRequiredSilicon() {
        ArchitectureData arch = getInstalledArchitecture();
        int opCount = arch != null ? arch.instructions().size() : 1;
        return GpuData.calculateRequiredSilicon(opCount);
    }

    public int getRequiredRedstone() {
        ArchitectureData arch = getInstalledArchitecture();
        int opCount = arch != null ? arch.instructions().size() : 1;
        return GpuData.calculateRequiredRedstone(opCount);
    }

    public int getPreviewComputeUnits() {
        ArchitectureData arch = getInstalledArchitecture();
        int opCount = arch != null ? arch.instructions().size() : 0;
        return Math.max(1, opCount / 4 + getInstalledRamCount());
    }

    public int getRequiredEnergy() {
        ArchitectureData arch = getInstalledArchitecture();
        int opCount = arch != null ? arch.instructions().size() : 0;
        return GpuData.calculateRequiredEnergy(opCount, getInstalledRamCount());
    }

    public static int calculateRequiredEnergy(int ramModules) {
        return GpuData.calculateRequiredEnergy(ramModules);
    }

    public int calculateMaxProgress() {
        ArchitectureData arch = getInstalledArchitecture();
        int opCount = arch != null ? arch.instructions().size() : 0;
        return GpuData.calculateMaxProgress(opCount, getInstalledRamCount());
    }

    public static int calculateMaxProgress(int ramModules) {
        return GpuData.calculateMaxProgress(ramModules);
    }

    public boolean hasValidInputsAndSpace() {
        ArchitectureData arch = getInstalledArchitecture();
        if (arch == null || !arch.isGpu() || arch.instructions().isEmpty()) {
            return false;
        }
        ItemStack silicon = gpuItemHandler.getStackInSlot(SLOT_SILICON);
        if (silicon.isEmpty() || !silicon.is(ModItems.SILICON_POWDER.get()) || silicon.getCount() < getRequiredSilicon()) {
            return false;
        }
        ItemStack redstone = gpuItemHandler.getStackInSlot(SLOT_REDSTONE);
        if (redstone.isEmpty() || !redstone.is(Items.REDSTONE) || redstone.getCount() < getRequiredRedstone()) {
            return false;
        }
        for (int i = RAM_SLOT_START; i < RAM_SLOT_START + RAM_SLOT_COUNT; i++) {
            ItemStack ram = gpuItemHandler.getStackInSlot(i);
            if (!ram.isEmpty() && !ram.is(ModItems.RAM.get())) {
                return false;
            }
        }
        ItemStack output = gpuItemHandler.getStackInSlot(SLOT_OUTPUT);
        return output.isEmpty();
    }

    public boolean canAssemble() {
        return hasValidInputsAndSpace() && energyStorage.getEnergyStored() >= getRequiredEnergy();
    }

    public void startAssembling() {
        if (canAssemble()) {
            this.maxProgress = calculateMaxProgress();
            this.progress = 0;
            this.assembling = true;
            setChanged();
        }
    }

    public void tick(Level level, BlockPos pos, BlockState state) {
        tickFuel();

        if (assembling) {
            if (!hasValidInputsAndSpace()) {
                assembling = false;
                progress = 0;
                if (state.getValue(BaseMachineBlock.LIT)) {
                    level.setBlock(pos, state.setValue(BaseMachineBlock.LIT, false), 3);
                }
                setChanged();
                return;
            }

            int energyPerTick = Math.max(1, getRequiredEnergy() / Math.max(1, maxProgress));
            if (energyStorage.getEnergyStored() < energyPerTick) {
                if (state.getValue(BaseMachineBlock.LIT)) {
                    level.setBlock(pos, state.setValue(BaseMachineBlock.LIT, false), 3);
                }
                return;
            }

            if (!state.getValue(BaseMachineBlock.LIT)) {
                level.setBlock(pos, state.setValue(BaseMachineBlock.LIT, true), 3);
            }

            energyStorage.extractEnergyInternal(energyPerTick, false);
            progress++;

            if (progress >= maxProgress) {
                ArchitectureData arch = getInstalledArchitecture();
                if (arch == null) {
                    assembling = false;
                    progress = 0;
                    setChanged();
                    return;
                }

                int totalVram = getTotalVramBytes();
                int ramCount = getInstalledRamCount();
                int computeUnits = Math.max(1, arch.instructions().size() / 4 + ramCount);
                String archName = arch.name().isEmpty() ? "Custom-GPU" : arch.name();

                gpuItemHandler.getStackInSlot(SLOT_SILICON).shrink(getRequiredSilicon());
                gpuItemHandler.getStackInSlot(SLOT_REDSTONE).shrink(getRequiredRedstone());
                // NOTE: SLOT_WRITEUP (GPU Architecture Writeup) is NOT consumed!
                for (int i = RAM_SLOT_START; i < RAM_SLOT_START + RAM_SLOT_COUNT; i++) {
                    ItemStack ram = gpuItemHandler.getStackInSlot(i);
                    if (!ram.isEmpty() && ram.is(ModItems.RAM.get())) {
                        ram.shrink(1);
                    }
                }

                ItemStack gpuStack = new ItemStack(ModItems.GPU.get());
                gpuStack.set(ModDataComponents.GPU_DATA.get(), new GpuData(
                        true,
                        archName,
                        totalVram,
                        computeUnits,
                        new ArrayList<>(arch.instructions())
                ));
                gpuItemHandler.setStackInSlot(SLOT_OUTPUT, gpuStack);

                progress = 0;
                assembling = false;
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
    protected void tickFuel() {
        if (burnTime > 0) {
            burnTime--;
            energyStorage.receiveEnergyInternal(20, false);
            setChanged();
        }

        if (burnTime <= 0 && energyStorage.getEnergyStored() < energyStorage.getMaxEnergyStored()) {
            ItemStack fuelStack = gpuItemHandler.getStackInSlot(SLOT_FUEL);
            if (!fuelStack.isEmpty()) {
                int fuelDuration = net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.getFuel().getOrDefault(fuelStack.getItem(), 0);
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
        tag.put("Inventory", gpuItemHandler.serializeNBT(registries));
        tag.putInt("Progress", progress);
        tag.putInt("MaxProgress", maxProgress);
        tag.putBoolean("Assembling", assembling);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Inventory")) {
            gpuItemHandler.deserializeNBT(registries, tag.getCompound("Inventory"));
            if (gpuItemHandler.getSlots() < TOTAL_SLOTS) {
                gpuItemHandler.setSize(TOTAL_SLOTS);
            }
        }
        progress = tag.getInt("Progress");
        maxProgress = Math.max(1, tag.getInt("MaxProgress"));
        assembling = tag.getBoolean("Assembling");
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.siliconcraft.gpu_assembler");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new GpuAssemblerMenu(containerId, playerInventory, this, this.dataAccess);
    }
}
