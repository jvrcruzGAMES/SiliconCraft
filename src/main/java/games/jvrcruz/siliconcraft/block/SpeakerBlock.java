package games.jvrcruz.siliconcraft.block;

import games.jvrcruz.siliconcraft.compat.sable.SubLevelCoordinates;
import games.jvrcruz.siliconcraft.network.StopAudioPacket;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.PacketDistributor;

import games.jvrcruz.siliconcraft.init.ModBlocks;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

public class SpeakerBlock extends Block {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public SpeakerBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level instanceof ServerLevel serverLevel) {
            boolean connected = false;
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = pos.relative(dir);
                if (!SubLevelCoordinates.sameSubLevel(level, pos, neighbor)) continue;
                LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(neighbor.getX() >> 4, neighbor.getZ() >> 4);
                if (chunk != null && chunk.getBlockState(neighbor).is(ModBlocks.COMPUTER.get())) {
                    connected = true;
                    player.displayClientMessage(Component.literal("Speaker connected to Personal Computer at [" +
                            neighbor.getX() + ", " + neighbor.getY() + ", " + neighbor.getZ() + "]")
                            .withStyle(ChatFormatting.GREEN), true);
                    break;
                }
            }
            if (!connected) {
                player.displayClientMessage(Component.literal("Speaker active. Place adjacent to a Personal Computer to broadcast audio into the world.")
                        .withStyle(ChatFormatting.YELLOW), true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                StopAudioPacket packet = new StopAudioPacket(pos);
                PacketDistributor.sendToPlayersInDimension(serverLevel, packet);
            }
            super.onRemove(state, level, pos, newState, isMoving);
        }
    }

}
