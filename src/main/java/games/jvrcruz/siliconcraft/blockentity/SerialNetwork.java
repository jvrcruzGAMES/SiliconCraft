package games.jvrcruz.siliconcraft.blockentity;

import games.jvrcruz.siliconcraft.api.peripheral.ComputerPeripheral;
import games.jvrcruz.siliconcraft.block.SerialCableBlock;
import games.jvrcruz.siliconcraft.compat.sable.SubLevelCoordinates;
import games.jvrcruz.siliconcraft.init.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/** Resolves wired ports on the server thread without force-loading chunks. */
final class SerialNetwork {
    static final int FIRST_WIRED_PORT = 6;
    private static final int CONNECTIONS_PER_TICK = 128;
    private static final Direction[] DIRECTIONS = Direction.values();

    record Endpoint(BlockPos pos, Direction face) {}
    private record Step(BlockPos pos, Direction face, boolean wired) {}

    private final BlockPos computer;
    private Map<Endpoint, Integer> addresses = Map.of();
    private NavigableMap<Integer, Endpoint> endpoints = new TreeMap<>();
    private int nextAddress = FIRST_WIRED_PORT;
    private @Nullable Scan scan;

    SerialNetwork(BlockPos computer) {
        this.computer = computer;
    }

    void reset() {
        cancelScan();
        addresses = Map.of();
        endpoints = new TreeMap<>();
        nextAddress = FIRST_WIRED_PORT;
    }

    void cancelScan() {
        scan = null;
    }

    @Nullable Endpoint endpoint(int port) {
        if (port >= FIRST_WIRED_PORT) return endpoints.get(port);
        Direction direction = Direction.from3DDataValue(port);
        return new Endpoint(computer.relative(direction), direction.getOpposite());
    }

    int nextPort(int after) {
        Integer next = endpoints.higherKey(after);
        return next == null ? -1 : next;
    }

    boolean advance(ServerLevel level) {
        if (scan == null) scan = new Scan();
        if (!scan.advance(level)) return false;

        // Revalidate the walked connectors before publishing the scan. A cable
        // can be cut, rotated, unloaded or moved between server ticks.
        if (!scan.validate(level)) return false;
        addresses = scan.discoveredAddresses;
        endpoints = scan.discoveredEndpoints;
        nextAddress = scan.nextAddress;
        scan = null;
        return true;
    }

    static @Nullable LevelChunk loadedChunk(ServerLevel level, BlockPos computer, BlockPos pos) {
        if (!SubLevelCoordinates.sameSubLevel(level, computer, pos)) return null;
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
    }

    @Nullable ComputerPeripheral resolve(ServerLevel level, Endpoint endpoint) {
        LevelChunk chunk = loadedChunk(level, computer, endpoint.pos);
        if (chunk == null) return null;
        BlockState state = chunk.getBlockState(endpoint.pos);
        return level.getCapability(ComputerPeripheral.CAPABILITY, endpoint.pos, state, null, endpoint.face);
    }

    private static boolean isConnector(BlockState state) {
        return state.is(ModBlocks.SERIAL_CABLE.get()) || state.is(ModBlocks.SERIAL_EXPANSION.get());
    }

    private static boolean connects(BlockState state, Direction face) {
        return state.is(ModBlocks.SERIAL_EXPANSION.get()) || state.getValue(SerialCableBlock.AXIS) == face.getAxis();
    }

    private final class Scan {
        private final ArrayDeque<Step> work = new ArrayDeque<>();
        private final Set<BlockPos> connectors = new HashSet<>();
        private final Set<BlockPos> discoveredDevices = new HashSet<>();
        private final Map<Endpoint, Integer> discoveredAddresses = new HashMap<>();
        private final NavigableMap<Integer, Endpoint> discoveredEndpoints = new TreeMap<>();
        private final Map<BlockPos, BlockState> walked = new HashMap<>();
        private final Map<ChunkPos, LevelChunk> chunks = new HashMap<>();
        private int nextAddress = SerialNetwork.this.nextAddress;

        Scan() {
            for (Direction direction : DIRECTIONS) {
                work.add(new Step(computer.relative(direction), direction.getOpposite(), false));
            }
        }

        boolean advance(ServerLevel level) {
            for (int i = 0; i < CONNECTIONS_PER_TICK && !work.isEmpty(); i++) {
                Step step = work.removeFirst();
                if (step.pos.equals(computer)) continue;
                LevelChunk chunk = loadedChunk(level, computer, step.pos);
                if (chunk == null) continue;
                BlockState state = chunk.getBlockState(step.pos);
                if (isConnector(state)) {
                    if (!connects(state, step.face) || !connectors.add(step.pos)) continue;
                    walked.put(step.pos, state);
                    chunks.put(chunk.getPos(), chunk);
                    for (Direction direction : DIRECTIONS) {
                        if (direction != step.face && connects(state, direction)) {
                            work.addLast(new Step(step.pos.relative(direction), direction.getOpposite(), true));
                        }
                    }
                } else if (!discoveredDevices.contains(step.pos)) {
                    Endpoint endpoint = new Endpoint(step.pos, step.face);
                    if (level.getCapability(ComputerPeripheral.CAPABILITY, step.pos, state, null, step.face) != null) {
                        discoveredDevices.add(step.pos);
                        if (step.wired) {
                            Integer previous = addresses.get(endpoint);
                            int address = previous != null ? previous : nextAddress;
                            if (previous == null) nextAddress = Math.incrementExact(nextAddress);
                            discoveredAddresses.put(endpoint, address);
                            discoveredEndpoints.put(address, endpoint);
                        }
                    }
                }
            }
            return work.isEmpty();
        }

        boolean validate(ServerLevel level) {
            for (var entry : chunks.entrySet()) {
                ChunkPos pos = entry.getKey();
                if (level.getChunkSource().getChunkNow(pos.x, pos.z) != entry.getValue()) {
                    scan = null;
                    return false;
                }
            }
            for (var entry : walked.entrySet()) {
                BlockPos pos = entry.getKey();
                if (chunks.get(new ChunkPos(pos)).getBlockState(pos) != entry.getValue()) {
                    scan = null;
                    return false;
                }
            }
            return true;
        }
    }
}
