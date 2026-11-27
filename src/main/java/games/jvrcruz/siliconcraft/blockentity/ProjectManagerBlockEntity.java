package games.jvrcruz.siliconcraft.blockentity;

import games.jvrcruz.siliconcraft.init.ModBlockEntities;
import games.jvrcruz.siliconcraft.menu.ProjectManagerMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class ProjectManagerBlockEntity extends BlockEntity implements MenuProvider {
    public ProjectManagerBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.PROJECT_MANAGER.get(), pos, blockState);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.siliconcraft.project_manager");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new ProjectManagerMenu(containerId, playerInventory, this);
    }
}
