package games.jvrcruz.siliconcraft.menu;

import games.jvrcruz.siliconcraft.blockentity.SocManufacturerBlockEntity;
import games.jvrcruz.siliconcraft.init.ModBlocks;
import games.jvrcruz.siliconcraft.init.ModItems;
import games.jvrcruz.siliconcraft.init.ModMenuTypes;
import games.jvrcruz.siliconcraft.menu.slot.FilteredSlot;
import games.jvrcruz.siliconcraft.menu.slot.OutputSlot;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
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

public class SocManufacturerMenu extends AbstractContainerMenu {
    private final SocManufacturerBlockEntity blockEntity;
    private final ContainerLevelAccess levelAccess;
    private final ContainerData data;

    public SocManufacturerMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        this(containerId, playerInventory, (SocManufacturerBlockEntity) playerInventory.player.level().getBlockEntity(buf.readBlockPos()), new SimpleContainerData(7));
    }

    public SocManufacturerMenu(int containerId, Inventory playerInventory, SocManufacturerBlockEntity blockEntity, ContainerData data) {
        super(ModMenuTypes.SOC_MANUFACTURER_MENU.get(), containerId);
        this.blockEntity = blockEntity;
        this.levelAccess = ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos());
        this.data = data;

        // Machine slots
        this.addSlot(new FilteredSlot(blockEntity.getItemHandler(), 0, 27, 52,
                AbstractFurnaceBlockEntity::isFuel,
                Component.literal("Fuel (Coal, Charcoal, etc.)")));
        this.addSlot(new FilteredSlot(blockEntity.getItemHandler(), 1, 27, 14,
                stack -> stack.is(ItemTags.WOODEN_SLABS),
                Component.literal("Wooden Slab (Substrate Base)")));
        this.addSlot(new FilteredSlot(blockEntity.getItemHandler(), 2, 27, 33,
                stack -> stack.is(ModItems.SILICON_POWDER.get()),
                Component.literal("Silicon Powder (Die Interconnect)")));
        this.addSlot(new FilteredSlot(blockEntity.getItemHandler(), 3, 47, 14,
                stack -> stack.is(Items.GOLD_INGOT),
                Component.literal("Gold Ingot (Wire Bonding)")));
        this.addSlot(new FilteredSlot(blockEntity.getItemHandler(), 4, 47, 33,
                stack -> stack.is(ModItems.ROM.get()),
                Component.literal("ROM Chip (BIOS Firmware)")));
        this.addSlot(new FilteredSlot(blockEntity.getItemHandler(), 5, 47, 52,
                stack -> stack.is(ModItems.CHIP.get()),
                Component.literal("Silicon Chip (CPU Core)")));
        this.addSlot(new FilteredSlot(blockEntity.getItemHandler(), 6, 67, 23,
                stack -> stack.is(ModItems.RAM.get()),
                Component.literal("RAM Module (Optional, Up to 4 Modules)")));
        this.addSlot(new FilteredSlot(blockEntity.getItemHandler(), 7, 67, 43,
                SocManufacturerBlockEntity::isFlashStorage,
                Component.literal("Storage Drive (Optional Flash Storage Only)")));
        this.addSlot(new OutputSlot(blockEntity.getItemHandler(), 8, 143, 33,
                Component.literal("Completed SoC Output")));

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

    public SocManufacturerBlockEntity getBlockEntity() { return blockEntity; }
    public int getProgress() { return data.get(0); }
    public int getMaxProgress() { return data.get(1); }
    public int getEnergy() { return data.get(2); }
    public int getMaxEnergy() { return data.get(3); }
    public int getBurnTime() { return data.get(4); }
    public int getMaxBurnTime() { return data.get(5); }
    public boolean isManufacturing() { return data.get(6) != 0; }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack slotStack = slot.getItem();
            itemstack = slotStack.copy();

            if (index < 9) {
                if (!this.moveItemStackTo(slotStack, 9, 45, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                if (AbstractFurnaceBlockEntity.isFuel(slotStack)) {
                    if (!this.moveItemStackTo(slotStack, 0, 1, false)) return ItemStack.EMPTY;
                } else if (slotStack.is(ItemTags.WOODEN_SLABS)) {
                    if (!this.moveItemStackTo(slotStack, 1, 2, false)) return ItemStack.EMPTY;
                } else if (slotStack.is(ModItems.SILICON_POWDER.get())) {
                    if (!this.moveItemStackTo(slotStack, 2, 3, false)) return ItemStack.EMPTY;
                } else if (slotStack.is(Items.GOLD_INGOT)) {
                    if (!this.moveItemStackTo(slotStack, 3, 4, false)) return ItemStack.EMPTY;
                } else if (slotStack.is(ModItems.ROM.get())) {
                    if (!this.moveItemStackTo(slotStack, 4, 5, false)) return ItemStack.EMPTY;
                } else if (slotStack.is(ModItems.CHIP.get())) {
                    if (!this.moveItemStackTo(slotStack, 5, 6, false)) return ItemStack.EMPTY;
                } else if (slotStack.is(ModItems.RAM.get())) {
                    if (!this.moveItemStackTo(slotStack, 6, 7, false)) return ItemStack.EMPTY;
                } else if (slotStack.is(ModItems.STORAGE.get()) && SocManufacturerBlockEntity.isFlashStorage(slotStack)) {
                    if (!this.moveItemStackTo(slotStack, 7, 8, false)) return ItemStack.EMPTY;
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
        return stillValid(levelAccess, player, ModBlocks.SOC_MANUFACTURER.get());
    }
}
