package games.jvrcruz.siliconcraft.compat.create;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import games.jvrcruz.siliconcraft.api.peripheral.ComputerPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import org.joml.Vector3d;

import java.util.OptionalInt;

final class AirshipSensorBlock extends Block {
    private static final DirectionProperty FACING = BlockStateProperties.FACING;
    static final ComputerPeripheral PERIPHERAL = AirshipSensorBlock::readTelemetry;

    AirshipSensorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());
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
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    private static OptionalInt readTelemetry(ComputerPeripheral.Context context, int method, int arg0, int arg1) {
        var level = context.level();
        BlockPos pos = context.peripheralPos();
        Vector3d localCenter = new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        var subLevel = Sable.HELPER.getContaining(level, pos);
        double value;
        switch (method) {
            case 0x300 -> value = Sable.HELPER.projectOutOfSubLevel(level, localCenter).y;
            case 0x301 -> value = DimensionPhysicsData.getAirPressure(level, Sable.HELPER.projectOutOfSubLevel(level, localCenter));
            case 0x302, 0x303 -> {
                if (subLevel == null) return OptionalInt.of(0);
                Vector3d down = subLevel.logicalPose().orientation().transformInverse(new Vector3d(0, -1, 0));
                double axis = method == 0x302 ? down.z : down.x;
                value = down.y < 0 && axis * axis <= 0.001 ? 0 : Math.toDegrees(Math.atan2(axis, -down.y));
            }
            case 0x304, 0x305, 0x306, 0x307, 0x308 -> {
                if (subLevel == null) return OptionalInt.of(0);
                Vector3d currentPosition = subLevel.logicalPose().transformPosition(localCenter, new Vector3d());
                Vector3d previousPosition = subLevel.lastPose().transformPosition(localCenter, new Vector3d());
                Vector3d velocity = currentPosition.sub(previousPosition).mul(20);
                subLevel.logicalPose().orientation().transformInverse(velocity);
                value = switch (method) {
                    case 0x304 -> {
                        var normal = level.getBlockState(pos).getValue(FACING).getNormal();
                        double speed = velocity.dot(normal.getX(), normal.getY(), normal.getZ());
                        yield Math.abs(speed) > 0.05 ? speed : 0;
                    }
                    case 0x305 -> velocity.x;
                    case 0x306 -> velocity.y;
                    case 0x307 -> velocity.z;
                    default -> velocity.length();
                };
            }
            case 0x309 -> value = Sable.HELPER.projectOutOfSubLevel(level, localCenter).x;
            case 0x30A -> value = Sable.HELPER.projectOutOfSubLevel(level, localCenter).z;
            case 0x30B -> {
                var normal = level.getBlockState(pos).getValue(FACING).getNormal();
                Vector3d facing = new Vector3d(normal.getX(), normal.getY(), normal.getZ());
                if (subLevel != null) {
                    subLevel.logicalPose().orientation().transform(facing);
                }
                if (facing.x * facing.x + facing.z * facing.z <= 1e-6) {
                    var horizontal = new Vector3d(0, 0, normal.getY() > 0 ? -1 : 1);
                    if (subLevel != null) {
                        subLevel.logicalPose().orientation().transform(horizontal);
                    }
                    facing = horizontal;
                }
                value = facing.x * facing.x + facing.z * facing.z <= 1e-6 ? 0 : Math.toDegrees(Math.atan2(-facing.x, facing.z));
            }
            default -> { return OptionalInt.empty(); }
        }
        return OptionalInt.of((int) Math.clamp(Math.round(value * 1000), Integer.MIN_VALUE, Integer.MAX_VALUE));
    }
}
