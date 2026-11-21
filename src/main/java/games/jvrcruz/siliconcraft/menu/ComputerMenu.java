package games.jvrcruz.siliconcraft.menu;

import games.jvrcruz.siliconcraft.blockentity.ComputerBlockEntity;
import games.jvrcruz.siliconcraft.data.StorageData;
import games.jvrcruz.siliconcraft.init.ModBlocks;
import games.jvrcruz.siliconcraft.init.ModDataComponents;
import games.jvrcruz.siliconcraft.init.ModItems;
import games.jvrcruz.siliconcraft.init.ModMenuTypes;
import games.jvrcruz.siliconcraft.menu.slot.FilteredSlot;
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

public class ComputerMenu extends AbstractContainerMenu {
    private final ComputerBlockEntity blockEntity;
    private final ContainerLevelAccess levelAccess;
    private final ContainerData data;

    public ComputerMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        this(containerId, playerInventory, (ComputerBlockEntity) playerInventory.player.level().getBlockEntity(buf.readBlockPos()), new SimpleContainerData(14));
    }

    public ComputerMenu(int containerId, Inventory playerInventory, ComputerBlockEntity blockEntity, ContainerData data) {
        super(ModMenuTypes.COMPUTER_MENU.get(), containerId);
        this.blockEntity = blockEntity;
        this.levelAccess = ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos());
        this.data = data;

        // Machine Slots (0-1: NVMe Storage, 2-5: RAM Modules)
        this.addSlot(new FilteredSlot(blockEntity.getItemHandler(), 0, 162, 138,
                ComputerMenu::isNvmeStorage,
                Component.literal("NVMe Storage Slot 1 (1 Module Max)")));
        this.addSlot(new FilteredSlot(blockEntity.getItemHandler(), 1, 180, 138,
                ComputerMenu::isNvmeStorage,
                Component.literal("NVMe Storage Slot 2 (1 Module Max)")));

        for (int i = 0; i < 4; i++) {
            final int slotNum = i + 1;
            this.addSlot(new FilteredSlot(blockEntity.getItemHandler(), 2 + i, 50 + i * 18, 138,
                    stack -> stack.is(ModItems.RAM.get()),
                    Component.literal("RAM Slot " + slotNum + " (1 Module Max)")));
        }

        // Player Inventory (3 rows x 9 columns)
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 39 + col * 18, 160 + row * 18));
            }
        }

        // Player Hotbar (9 columns)
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(playerInventory, col, 39 + col * 18, 218));
        }

        this.addDataSlots(data);
    }

    public static boolean isNvmeStorage(ItemStack stack) {
        if (!stack.is(ModItems.STORAGE.get())) return false;
        StorageData sd = stack.get(ModDataComponents.STORAGE_DATA.get());
        return sd != null && sd.isNvme();
    }

    public ComputerBlockEntity getBlockEntity() { return blockEntity; }
    public int getCpuState() { return data.get(0); }
    public int getPc() { return data.get(1); }
    public int getRegA() { return data.get(2); }
    public int getRegB() { return data.get(3); }
    public int getRegC() { return data.get(4); }
    public int getRegD() { return data.get(5); }
    public boolean getFlagZ() { return data.get(6) != 0; }
    public boolean getFlagS() { return data.get(7) != 0; }
    public int getCycles() { return data.get(8); }
    public boolean hasSoc() { return data.get(9) != 0; }
    public int getTotalRam() { return data.get(10); }
    public boolean hasNvme() { return data.get(11) != 0; }
    public int getTotalStorage() { return data.get(12); }
    public int getSocRam() { return data.get(13); }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack slotStack = slot.getItem();
            itemstack = slotStack.copy();

            if (index < 6) {
                // Moving from machine slots to player inventory
                if (!this.moveItemStackTo(slotStack, 6, 42, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // Moving from player inventory/hotbar to machine slots
                if (isNvmeStorage(slotStack)) {
                    if (!this.moveItemStackTo(slotStack, 0, 2, false)) return ItemStack.EMPTY;
                } else if (slotStack.is(ModItems.RAM.get())) {
                    if (!this.moveItemStackTo(slotStack, 2, 6, false)) return ItemStack.EMPTY;
                } else if (index < 33) {
                    if (!this.moveItemStackTo(slotStack, 33, 42, false)) return ItemStack.EMPTY;
                } else {
                    if (!this.moveItemStackTo(slotStack, 6, 33, false)) return ItemStack.EMPTY;
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
        return stillValid(levelAccess, player, ModBlocks.COMPUTER.get());
    }
}
