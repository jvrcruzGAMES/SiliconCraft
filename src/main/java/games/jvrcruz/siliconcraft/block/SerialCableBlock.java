package games.jvrcruz.siliconcraft.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class SerialCableBlock extends RotatedPillarBlock {
    private static final VoxelShape X_SHAPE = Block.box(0, 6, 6, 16, 10, 10);
    private static final VoxelShape Y_SHAPE = Block.box(6, 0, 6, 10, 16, 10);
    private static final VoxelShape Z_SHAPE = Block.box(6, 6, 0, 10, 10, 16);

    public SerialCableBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(AXIS)) {
            case X -> X_SHAPE;
            case Y -> Y_SHAPE;
            case Z -> Z_SHAPE;
        };
    }
}
