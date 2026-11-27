package games.jvrcruz.siliconcraft.blockentity;

import games.jvrcruz.siliconcraft.data.RomData;
import games.jvrcruz.siliconcraft.init.ModBlockEntities;
import games.jvrcruz.siliconcraft.init.ModDataComponents;
import games.jvrcruz.siliconcraft.init.ModItems;
import games.jvrcruz.siliconcraft.menu.ProgrammerMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public class ProgrammerBlockEntity extends BaseMachineBlockEntity {
    protected final ContainerData dataAccess = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case 0 -> energyStorage.getEnergyStored();
                case 1 -> energyStorage.getMaxEnergyStored();
                case 2 -> burnTime;
                case 3 -> maxBurnTime;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            switch (index) {
                case 2 -> burnTime = value;
                case 3 -> maxBurnTime = value;
            }
        }

        @Override
        public int getCount() {
            return 4;
        }
    };

    public ProgrammerBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.PROGRAMMER.get(), pos, blockState, 3, 20000);
    }

    @Override
    protected boolean isSlotItemValid(int slot, ItemStack stack) {
        return switch (slot) {
            case 0 -> net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.isFuel(stack);
            case 1 -> stack.is(ModItems.ROM.get()) || stack.is(ModItems.STORAGE.get());
            default -> false;
        };
    }

    public ContainerData getDataAccess() {
        return dataAccess;
    }

    public boolean burnProgram(byte[] bytecode) {
        if (bytecode == null || bytecode.length == 0) {
            return false;
        }
        ItemStack inputStack = itemHandler.getStackInSlot(1);
        if (inputStack.isEmpty() || (!inputStack.is(ModItems.ROM.get()) && !inputStack.is(ModItems.STORAGE.get()))) {
            return false;
        }
        if (energyStorage.getEnergyStored() < 100) {
            return false;
        }
        ItemStack outStack = itemHandler.getStackInSlot(2);
        if (!outStack.isEmpty() && outStack.getCount() >= outStack.getMaxStackSize()) {
            return false;
        }

        // Consume 100 FE
        energyStorage.extractEnergyInternal(100, false);

        ItemStack programmed = inputStack.copyWithCount(1);
        programmed.set(ModDataComponents.ROM_DATA.get(), new RomData(java.util.Arrays.copyOf(bytecode, bytecode.length)));
        inputStack.shrink(1);

        if (outStack.isEmpty()) {
            itemHandler.setStackInSlot(2, programmed);
        } else if (ItemStack.isSameItemSameComponents(outStack, programmed)) {
            outStack.grow(1);
        } else {
            // If cannot stack into slot 2, place back in slot 1 or drop
            itemHandler.setStackInSlot(1, programmed);
        }

        setChanged();
        return true;
    }

    public void tick(Level level, BlockPos pos, BlockState state) {
        tickFuel();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.siliconcraft.programmer");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new ProgrammerMenu(containerId, playerInventory, this, this.dataAccess);
    }
}
