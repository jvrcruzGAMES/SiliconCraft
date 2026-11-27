package games.jvrcruz.siliconcraft.menu;

import games.jvrcruz.siliconcraft.blockentity.ProjectManagerBlockEntity;
import games.jvrcruz.siliconcraft.init.ModBlocks;
import games.jvrcruz.siliconcraft.init.ModMenuTypes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;

public class ProjectManagerMenu extends AbstractContainerMenu {
    private final ProjectManagerBlockEntity blockEntity;
    private final ContainerLevelAccess levelAccess;

    public ProjectManagerMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        this(containerId, playerInventory, (ProjectManagerBlockEntity) playerInventory.player.level().getBlockEntity(buf.readBlockPos()));
    }

    public ProjectManagerMenu(int containerId, Inventory playerInventory, ProjectManagerBlockEntity blockEntity) {
        super(ModMenuTypes.PROJECT_MANAGER_MENU.get(), containerId);
        this.blockEntity = blockEntity;
        this.levelAccess = ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos());
    }

    public ProjectManagerBlockEntity getBlockEntity() {
        return blockEntity;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(levelAccess, player, ModBlocks.PROJECT_MANAGER.get());
    }
}
