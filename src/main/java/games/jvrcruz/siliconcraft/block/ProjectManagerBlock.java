package games.jvrcruz.siliconcraft.block;

import com.mojang.serialization.MapCodec;
import games.jvrcruz.siliconcraft.blockentity.ProjectManagerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class ProjectManagerBlock extends BaseMachineBlock {
    public static final MapCodec<ProjectManagerBlock> CODEC = simpleCodec(ProjectManagerBlock::new);

    public ProjectManagerBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ProjectManagerBlockEntity(pos, state);
    }
}
