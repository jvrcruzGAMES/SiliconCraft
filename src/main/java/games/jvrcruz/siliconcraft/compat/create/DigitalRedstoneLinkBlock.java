package games.jvrcruz.siliconcraft.compat.create;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

final class DigitalRedstoneLinkBlock extends BaseEntityBlock {
    private static final MapCodec<DigitalRedstoneLinkBlock> CODEC = simpleCodec(DigitalRedstoneLinkBlock::new);

    DigitalRedstoneLinkBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<DigitalRedstoneLinkBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DigitalRedstoneLinkEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof DigitalRedstoneLinkEntity entity) {
            serverPlayer.openMenu(entity, entity::writeMenuData);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, CreatePeripherals.LINK_ENTITY.get(),
                (world, pos, block, entity) -> entity.refreshLinks());
    }
}
