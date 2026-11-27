package games.jvrcruz.siliconcraft.menu;

import games.jvrcruz.siliconcraft.blockentity.GpuAssemblerBlockEntity;
import games.jvrcruz.siliconcraft.data.ArchitectureData;
import games.jvrcruz.siliconcraft.init.ModBlocks;
import games.jvrcruz.siliconcraft.init.ModItems;
import games.jvrcruz.siliconcraft.init.ModMenuTypes;
import games.jvrcruz.siliconcraft.item.ArchitectureWriteupItem;
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

public class GpuAssemblerMenu extends AbstractContainerMenu {
    private final GpuAssemblerBlockEntity blockEntity;
    private final ContainerLevelAccess levelAccess;
    private final ContainerData data;

    public GpuAssemblerMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        this(containerId, playerInventory,
                (GpuAssemblerBlockEntity) playerInventory.player.level().getBlockEntity(buf.readBlockPos()),
                new SimpleContainerData(13));
    }

    public GpuAssemblerMenu(int containerId, Inventory playerInventory, GpuAssemblerBlockEntity blockEntity, ContainerData data) {
        super(ModMenuTypes.GPU_ASSEMBLER_MENU.get(), containerId);
        this.blockEntity = blockEntity;
        this.levelAccess = ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos());
        this.data = data;

        // Slot 0: Fuel
        this.addSlot(new FilteredSlot(blockEntity.getItemHandler(), GpuAssemblerBlockEntity.SLOT_FUEL, 26, 35,
                AbstractFurnaceBlockEntity::isFuel,
                Component.literal("Fuel (Coal, Charcoal, etc.)")));

        // Slot 1: Silicon Powder
        this.addSlot(new FilteredSlot(blockEntity.getItemHandler(), GpuAssemblerBlockEntity.SLOT_SILICON, 26, 17,
                stack -> stack.is(ModItems.SILICON_POWDER.get()),
                Component.literal("Silicon Powder")));

        // Slot 2: Redstone Dust
        this.addSlot(new FilteredSlot(blockEntity.getItemHandler(), GpuAssemblerBlockEntity.SLOT_REDSTONE, 44, 17,
                stack -> stack.is(Items.REDSTONE),
                Component.literal("Redstone Dust")));

        // Slot 3: GPU Architecture Writeup (Blueprint, NOT consumed)
        this.addSlot(new FilteredSlot(blockEntity.getItemHandler(), GpuAssemblerBlockEntity.SLOT_WRITEUP, 44, 35,
                ArchitectureWriteupItem::isGpuWriteup,
                Component.literal("GPU Architecture Writeup (Blueprint)")));

        // Slots 4..11: Up to 8 RAM Modules (4x2 grid)
        for (int i = 0; i < GpuAssemblerBlockEntity.RAM_SLOT_COUNT; i++) {
            int col = i % 4;
            int row = i / 4;
            int x = 64 + col * 18;
            int y = 17 + row * 18;
            this.addSlot(new FilteredSlot(blockEntity.getItemHandler(), GpuAssemblerBlockEntity.RAM_SLOT_START + i, x, y,
                    stack -> stack.is(ModItems.RAM.get()),
                    Component.literal("VRAM RAM Module " + (i + 1) + " (Optional, Max 8 Total)")));
        }

        // Slot 12: Output GPU
        this.addSlot(new OutputSlot(blockEntity.getItemHandler(), GpuAssemblerBlockEntity.SLOT_OUTPUT, 153, 25,
                Component.literal("Assembled GPU Output")));

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

    public GpuAssemblerBlockEntity getBlockEntity() {
        return blockEntity;
    }

    public ArchitectureData getInstalledArchitecture() {
        return blockEntity.getInstalledArchitecture();
    }

    public int getProgress() { return data.get(0); }
    public int getMaxProgress() { return data.get(1); }
    public int getEnergy() { return data.get(2); }
    public int getMaxEnergy() { return data.get(3); }
    public int getBurnTime() { return data.get(4); }
    public int getMaxBurnTime() { return data.get(5); }
    public boolean isAssembling() { return data.get(6) != 0; }
    public int getRamCount() { return data.get(7); }
    public int getTotalVramKb() { return data.get(8); }
    public int getRequiredEnergy() { return data.get(9); }
    public int getComputeUnits() { return data.get(10); }
    public int getRequiredSilicon() { return data.get(11); }
    public int getRequiredRedstone() { return data.get(12); }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        int machineSlots = GpuAssemblerBlockEntity.TOTAL_SLOTS;
        int playerEnd = machineSlots + 36;
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack slotStack = slot.getItem();
            itemstack = slotStack.copy();

            if (index < machineSlots) {
                if (!this.moveItemStackTo(slotStack, machineSlots, playerEnd, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                if (AbstractFurnaceBlockEntity.isFuel(slotStack)) {
                    if (!this.moveItemStackTo(slotStack, GpuAssemblerBlockEntity.SLOT_FUEL, GpuAssemblerBlockEntity.SLOT_FUEL + 1, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (slotStack.is(ModItems.SILICON_POWDER.get())) {
                    if (!this.moveItemStackTo(slotStack, GpuAssemblerBlockEntity.SLOT_SILICON, GpuAssemblerBlockEntity.SLOT_SILICON + 1, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (slotStack.is(Items.REDSTONE)) {
                    if (!this.moveItemStackTo(slotStack, GpuAssemblerBlockEntity.SLOT_REDSTONE, GpuAssemblerBlockEntity.SLOT_REDSTONE + 1, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (ArchitectureWriteupItem.isGpuWriteup(slotStack)) {
                    if (!this.moveItemStackTo(slotStack, GpuAssemblerBlockEntity.SLOT_WRITEUP, GpuAssemblerBlockEntity.SLOT_WRITEUP + 1, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (slotStack.is(ModItems.RAM.get())) {
                    if (!this.moveItemStackTo(slotStack, GpuAssemblerBlockEntity.RAM_SLOT_START,
                            GpuAssemblerBlockEntity.RAM_SLOT_START + GpuAssemblerBlockEntity.RAM_SLOT_COUNT, false)) {
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
        return stillValid(levelAccess, player, ModBlocks.GPU_ASSEMBLER.get());
    }
}
