package games.jvrcruz.siliconcraft.blockentity;

import games.jvrcruz.siliconcraft.data.*;
import games.jvrcruz.siliconcraft.init.ModBlockEntities;
import games.jvrcruz.siliconcraft.init.ModDataComponents;
import games.jvrcruz.siliconcraft.init.ModItems;
import games.jvrcruz.siliconcraft.menu.SocManufacturerMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public class SocManufacturerBlockEntity extends BaseMachineBlockEntity {
    private int progress = 0;
    private int maxProgress = 80;
    private boolean manufacturing = false;

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
                case 6 -> manufacturing ? 1 : 0;
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
                case 6 -> manufacturing = (value != 0);
            }
        }

        @Override
        public int getCount() {
            return 7;
        }
    };

    public SocManufacturerBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.SOC_MANUFACTURER.get(), pos, blockState, 9, 30000);
    }

    public static boolean isFlashStorage(ItemStack stack) {
        if (!stack.is(ModItems.STORAGE.get())) return false;
        StorageData sd = stack.get(ModDataComponents.STORAGE_DATA.get());
        return sd == null || !sd.isNvme();
    }

    @Override
    protected boolean isSlotItemValid(int slot, ItemStack stack) {
        return switch (slot) {
            case 0 -> net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.isFuel(stack);
            case 1 -> stack.is(ItemTags.WOODEN_SLABS);
            case 2 -> stack.is(ModItems.SILICON_POWDER.get());
            case 3 -> stack.is(Items.GOLD_INGOT);
            case 4 -> stack.is(ModItems.ROM.get());
            case 5 -> stack.is(ModItems.CHIP.get());
            case 6 -> stack.is(ModItems.RAM.get());
            case 7 -> isFlashStorage(stack);
            default -> false;
        };
    }

    public ContainerData getDataAccess() {
        return dataAccess;
    }

    public boolean canManufacture() {
        ItemStack wood = itemHandler.getStackInSlot(1);
        ItemStack silicon = itemHandler.getStackInSlot(2);
        ItemStack gold = itemHandler.getStackInSlot(3);
        ItemStack rom = itemHandler.getStackInSlot(4);
        ItemStack chip = itemHandler.getStackInSlot(5);
        ItemStack ram = itemHandler.getStackInSlot(6);
        ItemStack storage = itemHandler.getStackInSlot(7);
        ItemStack output = itemHandler.getStackInSlot(8);

        if (wood.isEmpty() || !wood.is(ItemTags.WOODEN_SLABS)) return false;
        if (silicon.isEmpty() || !silicon.is(ModItems.SILICON_POWDER.get()) || silicon.getCount() < 2) return false;
        if (gold.isEmpty() || !gold.is(Items.GOLD_INGOT) || gold.getCount() < 2) return false;
        if (rom.isEmpty() || !rom.is(ModItems.ROM.get())) return false;
        if (chip.isEmpty() || !chip.is(ModItems.CHIP.get())) return false;
        if (!ram.isEmpty() && !ram.is(ModItems.RAM.get())) return false;
        if (!storage.isEmpty() && !isFlashStorage(storage)) return false;

        if (energyStorage.getEnergyStored() < 1000) return false;

        return output.isEmpty() || output.getCount() < output.getMaxStackSize();
    }

    public void startManufacture() {
        if (canManufacture()) {
            this.progress = 0;
            this.maxProgress = 80;
            this.manufacturing = true;
            setChanged();
        }
    }

    public void tick(Level level, BlockPos pos, BlockState state) {
        tickFuel();

        if (manufacturing) {
            if (!canManufacture()) {
                manufacturing = false;
                progress = 0;
                setChanged();
                return;
            }

            int energyPerTick = Math.max(1, 1000 / maxProgress);
            energyStorage.extractEnergyInternal(energyPerTick, false);
            progress++;

            if (progress >= maxProgress) {
                // Consume materials
                itemHandler.getStackInSlot(1).shrink(1); // Wood slab
                itemHandler.getStackInSlot(2).shrink(2); // Silicon
                itemHandler.getStackInSlot(3).shrink(2); // Gold

                ItemStack romStack = itemHandler.getStackInSlot(4);
                ItemStack chipStack = itemHandler.getStackInSlot(5);
                ItemStack ramStack = itemHandler.getStackInSlot(6);
                ItemStack storageStack = itemHandler.getStackInSlot(7);

                RomData romData = romStack.getOrDefault(ModDataComponents.ROM_DATA.get(), RomData.EMPTY);
                ChipData chipData = chipStack.getOrDefault(ModDataComponents.CHIP_DATA.get(), ChipData.EMPTY);

                int totalRam = 0;
                if (!ramStack.isEmpty() && ramStack.is(ModItems.RAM.get())) {
                    RamData ramData = ramStack.getOrDefault(ModDataComponents.RAM_DATA.get(), RamData.DEFAULT);
                    int ramCount = Math.min(ramStack.getCount(), 4);
                    totalRam = ramCount * ramData.capacity();
                    ramStack.shrink(ramCount);
                }

                int storageCap = 0;
                if (!storageStack.isEmpty() && isFlashStorage(storageStack)) {
                    StorageData sd = storageStack.getOrDefault(ModDataComponents.STORAGE_DATA.get(), StorageData.DEFAULT);
                    storageCap = sd.capacity();
                    storageStack.shrink(1);
                }

                romStack.shrink(1);
                chipStack.shrink(1);

                ItemStack socStack = new ItemStack(ModItems.SOC.get());
                socStack.set(ModDataComponents.SOC_DATA.get(), new SocData(
                        chipData.instructions(),
                        totalRam,
                        romData.lines(),
                        false,
                        storageCap
                ));

                ItemStack out = itemHandler.getStackInSlot(8);
                if (out.isEmpty()) {
                    itemHandler.setStackInSlot(8, socStack);
                } else if (ItemStack.isSameItemSameComponents(out, socStack) && out.getCount() < out.getMaxStackSize()) {
                    out.grow(1);
                }

                progress = 0;
                manufacturing = false;
                setChanged();
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Progress", progress);
        tag.putInt("MaxProgress", maxProgress);
        tag.putBoolean("Manufacturing", manufacturing);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        progress = tag.getInt("Progress");
        maxProgress = tag.getInt("MaxProgress");
        manufacturing = tag.getBoolean("Manufacturing");
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.siliconcraft.soc_manufacturer");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new SocManufacturerMenu(containerId, playerInventory, this, this.dataAccess);
    }
}
