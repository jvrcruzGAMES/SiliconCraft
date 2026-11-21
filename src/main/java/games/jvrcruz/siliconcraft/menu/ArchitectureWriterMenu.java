package games.jvrcruz.siliconcraft.menu;

import games.jvrcruz.siliconcraft.blockentity.ArchitectureWriterBlockEntity;
import games.jvrcruz.siliconcraft.init.ModBlocks;
import games.jvrcruz.siliconcraft.init.ModItems;
import games.jvrcruz.siliconcraft.init.ModMenuTypes;
import games.jvrcruz.siliconcraft.menu.slot.FilteredSlot;
import games.jvrcruz.siliconcraft.menu.slot.OutputSlot;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class ArchitectureWriterMenu extends AbstractContainerMenu {
    private final ArchitectureWriterBlockEntity blockEntity;
    private final ContainerLevelAccess levelAccess;

    public ArchitectureWriterMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        this(containerId, playerInventory, (ArchitectureWriterBlockEntity) playerInventory.player.level().getBlockEntity(buf.readBlockPos()));
    }

    public ArchitectureWriterMenu(int containerId, Inventory playerInventory, ArchitectureWriterBlockEntity blockEntity) {
        super(ModMenuTypes.ARCHITECTURE_WRITER_MENU.get(), containerId);
        this.blockEntity = blockEntity;
        this.levelAccess = ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos());

        // Machine slots
        this.addSlot(new FilteredSlot(blockEntity.getItemHandler(), 0, 27, 20,
                stack -> stack.is(Items.PAPER) || stack.is(ModItems.ARCHITECTURE_WRITEUP.get()),
                Component.literal("Blank Paper or Architecture Writeup")));
        this.addSlot(new OutputSlot(blockEntity.getItemHandler(), 1, 27, 50,
                Component.literal("Written Architecture Writeup Output")));

        // Player Inventory
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        // Player Hotbar
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }
    }

    public ArchitectureWriterBlockEntity getBlockEntity() {
        return blockEntity;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack slotStack = slot.getItem();
            itemstack = slotStack.copy();

            if (index < 2) {
                // Moving from machine to player inventory
                if (!this.moveItemStackTo(slotStack, 2, 38, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // Moving from player inventory to machine
                if (slotStack.is(Items.PAPER) || slotStack.is(ModItems.ARCHITECTURE_WRITEUP.get())) {
                    if (!this.moveItemStackTo(slotStack, 0, 1, false)) {
                        return ItemStack.EMPTY;
                    }
                } else {
                    return ItemStack.EMPTY;
                }
            }

            if (slotStack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }

            if (slotStack.getCount() == itemstack.getCount()) {
                return ItemStack.EMPTY;
            }

            slot.onTake(player, slotStack);
        }

        return itemstack;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(levelAccess, player, ModBlocks.ARCHITECTURE_WRITER.get());
    }
}
