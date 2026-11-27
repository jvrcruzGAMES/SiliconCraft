package games.jvrcruz.siliconcraft.blockentity;

import games.jvrcruz.siliconcraft.blockentity.ChipForgeBlockEntity;
import games.jvrcruz.siliconcraft.data.ArchitectureData;
import games.jvrcruz.siliconcraft.init.ModBlockEntities;
import games.jvrcruz.siliconcraft.init.ModDataComponents;
import games.jvrcruz.siliconcraft.init.ModItems;
import games.jvrcruz.siliconcraft.menu.ArchitectureWriterMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;

public class ArchitectureWriterBlockEntity extends BlockEntity implements MenuProvider {
    private final ItemStackHandler itemHandler = new ItemStackHandler(2) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (slot == 0) {
                return stack.is(Items.PAPER) || stack.is(ModItems.ARCHITECTURE_WRITEUP.get());
            }
            return false;
        }

        @Override
        public void setSize(int size) {
            super.setSize(Math.max(size, 2));
        }
    };

    public ArchitectureWriterBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.ARCHITECTURE_WRITER.get(), pos, blockState);
    }

    public ItemStackHandler getItemHandler() {
        return itemHandler;
    }

    public boolean writeArchitecture(String archName, List<String> instructions) {
        return writeArchitecture(archName, instructions, false);
    }

    public boolean writeArchitecture(String archName, List<String> instructions, boolean gpu) {
        if (instructions == null || instructions.isEmpty()) {
            return false;
        }

        ItemStack inputStack = itemHandler.getStackInSlot(0);
        if (inputStack.isEmpty() || (!inputStack.is(Items.PAPER) && !inputStack.is(ModItems.ARCHITECTURE_WRITEUP.get()))) {
            return false;
        }

        ItemStack outputStack = itemHandler.getStackInSlot(1);
        if (!outputStack.isEmpty() && outputStack.getCount() >= outputStack.getMaxStackSize()) {
            return false;
        }

        String defaultName = gpu ? "Custom-GPU" : "Custom-Arch";
        String validName = (archName == null || archName.trim().isEmpty()) ? defaultName : archName.trim();
        List<String> allowedPool = gpu
                ? games.jvrcruz.siliconcraft.compiler.InstructionValidator.GPU_INSTRUCTIONS
                : ChipForgeBlockEntity.ALL_INSTRUCTIONS;
        List<String> validInstructions = new ArrayList<>();
        for (String instr : instructions) {
            if (allowedPool.contains(instr) && !validInstructions.contains(instr)) {
                validInstructions.add(instr);
            }
        }
        if (validInstructions.isEmpty()) {
            return false;
        }

        // Consume 1 paper or blank writeup
        inputStack.shrink(1);

        ItemStack writeup = new ItemStack(ModItems.ARCHITECTURE_WRITEUP.get());
        writeup.set(ModDataComponents.ARCHITECTURE_DATA.get(), new ArchitectureData(validName, validInstructions, gpu));

        if (outputStack.isEmpty()) {
            itemHandler.setStackInSlot(1, writeup);
        } else if (ItemStack.isSameItemSameComponents(outputStack, writeup)) {
            outputStack.grow(1);
        } else {
            // Cannot stack different architecture components
            inputStack.grow(1);
            return false;
        }

        if (level != null) {
            level.playSound(null, worldPosition, SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.BLOCKS, 1.0f, 1.0f);
        }

        setChanged();
        return true;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inventory", itemHandler.serializeNBT(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Inventory")) {
            itemHandler.deserializeNBT(registries, tag.getCompound("Inventory"));
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.siliconcraft.architecture_writer");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new ArchitectureWriterMenu(containerId, playerInventory, this);
    }
}
