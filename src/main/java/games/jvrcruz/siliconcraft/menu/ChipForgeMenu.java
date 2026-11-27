package games.jvrcruz.siliconcraft.menu;

import games.jvrcruz.siliconcraft.blockentity.ChipForgeBlockEntity;
import games.jvrcruz.siliconcraft.data.ArchitectureData;
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
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

public class ChipForgeMenu extends AbstractContainerMenu {
    private final ChipForgeBlockEntity blockEntity;
    private final ContainerLevelAccess levelAccess;
    private final ContainerData data;

    public ChipForgeMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        this(containerId, playerInventory, (ChipForgeBlockEntity) playerInventory.player.level().getBlockEntity(buf.readBlockPos()), new SimpleContainerData(7));
    }

    public ChipForgeMenu(int containerId, Inventory playerInventory, ChipForgeBlockEntity blockEntity, ContainerData data) {
        super(ModMenuTypes.CHIP_FORGE_MENU.get(), containerId);
        this.blockEntity = blockEntity;
        this.levelAccess = ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos());
        this.data = data;

        // Machine slots
        this.addSlot(new FilteredSlot(blockEntity.getItemHandler(), 0, 27, 50,
                AbstractFurnaceBlockEntity::isFuel,
                Component.literal("Fuel (Coal, Charcoal, etc.)")));
        this.addSlot(new FilteredSlot(blockEntity.getItemHandler(), 1, 27, 20,
                stack -> stack.is(ModItems.SILICON_POWDER.get()),
                Component.literal("Silicon Powder")));
        this.addSlot(new FilteredSlot(blockEntity.getItemHandler(), 2, 49, 20,
                stack -> stack.is(Items.REDSTONE),
                Component.literal("Redstone Dust")));
        this.addSlot(new FilteredSlot(blockEntity.getItemHandler(), 3, 49, 50,
                games.jvrcruz.siliconcraft.item.ArchitectureWriteupItem::isCpuWriteup,
                Component.literal("CPU Architecture Writeup (Blueprint)")));
        this.addSlot(new OutputSlot(blockEntity.getItemHandler(), 4, 71, 35,
                Component.literal("Forged Silicon Chip Output")));

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

        this.addDataSlots(data);
    }

    public ChipForgeBlockEntity getBlockEntity() {
        return blockEntity;
    }

    public ArchitectureData getInstalledArchitecture() {
        return blockEntity.getInstalledArchitecture();
    }

    public int getProgress() {
        return data.get(0);
    }

    public int getMaxProgress() {
        return data.get(1);
    }

    public int getEnergy() {
        return data.get(2);
    }

    public int getMaxEnergy() {
        return data.get(3);
    }

    public int getBurnTime() {
        return data.get(4);
    }

    public int getMaxBurnTime() {
        return data.get(5);
    }

    public boolean isForging() {
        return data.get(6) != 0;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack slotStack = slot.getItem();
            itemstack = slotStack.copy();

            if (index < 5) {
                // Moving from machine to player
                if (!this.moveItemStackTo(slotStack, 5, 41, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // Moving from player to machine
                if (AbstractFurnaceBlockEntity.isFuel(slotStack)) {
                    if (!this.moveItemStackTo(slotStack, 0, 1, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (slotStack.is(ModItems.SILICON_POWDER.get())) {
                    if (!this.moveItemStackTo(slotStack, 1, 2, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (slotStack.is(Items.REDSTONE)) {
                    if (!this.moveItemStackTo(slotStack, 2, 3, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (games.jvrcruz.siliconcraft.item.ArchitectureWriteupItem.isCpuWriteup(slotStack)) {
                    if (!this.moveItemStackTo(slotStack, 3, 4, false)) {
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
        return stillValid(levelAccess, player, ModBlocks.CHIP_FORGE.get());
    }
}
