package games.jvrcruz.siliconcraft.menu;

import games.jvrcruz.siliconcraft.blockentity.ComputerBlockEntity;
import games.jvrcruz.siliconcraft.init.ModBlocks;
import games.jvrcruz.siliconcraft.init.ModMenuTypes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

public class ComputerMenu extends AbstractContainerMenu {
    private final ComputerBlockEntity blockEntity;
    private final ContainerLevelAccess levelAccess;
    private final ContainerData data;

    public ComputerMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        this(containerId, playerInventory, (ComputerBlockEntity) playerInventory.player.level().getBlockEntity(buf.readBlockPos()), new SimpleContainerData(10));
    }

    public ComputerMenu(int containerId, Inventory playerInventory, ComputerBlockEntity blockEntity, ContainerData data) {
        super(ModMenuTypes.COMPUTER_MENU.get(), containerId);
        this.blockEntity = blockEntity;
        this.levelAccess = ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos());
        this.data = data;

        this.addDataSlots(data);
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

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(levelAccess, player, ModBlocks.COMPUTER.get());
    }
}
