package games.jvrcruz.siliconcraft.menu;

import games.jvrcruz.siliconcraft.blockentity.CodeCompilerBlockEntity;
import games.jvrcruz.siliconcraft.data.ArchitectureData;
import games.jvrcruz.siliconcraft.init.ModBlocks;
import games.jvrcruz.siliconcraft.init.ModItems;
import games.jvrcruz.siliconcraft.init.ModMenuTypes;
import games.jvrcruz.siliconcraft.menu.slot.FilteredSlot;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class CodeCompilerMenu extends AbstractContainerMenu {
    private final CodeCompilerBlockEntity blockEntity;
    private final ContainerLevelAccess levelAccess;

    public CodeCompilerMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        this(containerId, playerInventory, (CodeCompilerBlockEntity) playerInventory.player.level().getBlockEntity(buf.readBlockPos()));
    }

    public CodeCompilerMenu(int containerId, Inventory playerInventory, CodeCompilerBlockEntity blockEntity) {
        super(ModMenuTypes.CODE_COMPILER_MENU.get(), containerId);
        this.blockEntity = blockEntity;
        this.levelAccess = ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos());

        // Slot 0: Architecture Writeup input slot
        this.addSlot(new FilteredSlot(blockEntity.getItemHandler(), 0, 27, 35,
                stack -> stack.is(ModItems.ARCHITECTURE_WRITEUP.get()),
                Component.literal("Architecture Writeup (Blueprint)")));

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

    public CodeCompilerBlockEntity getBlockEntity() {
        return blockEntity;
    }

    public ArchitectureData getInstalledArchitecture() {
        return blockEntity.getInstalledArchitecture();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack slotStack = slot.getItem();
            itemstack = slotStack.copy();

            if (index < 1) {
                // Moving from machine to player
                if (!this.moveItemStackTo(slotStack, 1, 37, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // Moving from player to machine
                if (slotStack.is(ModItems.ARCHITECTURE_WRITEUP.get())) {
                    if (!this.moveItemStackTo(slotStack, 0, 1, false)) {
                        return ItemStack.EMPTY;
                    }
                } else {
                    return ItemStack.EMPTY;
                }
            }

            if (slotStack.isEmpty()) {
                slot.set(ItemStack.EMPTY);
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
        return stillValid(levelAccess, player, ModBlocks.CODE_COMPILER.get());
    }
}
