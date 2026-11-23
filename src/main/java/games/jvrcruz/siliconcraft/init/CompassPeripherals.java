package games.jvrcruz.siliconcraft.init;

import games.jvrcruz.siliconcraft.api.peripheral.ComputerPeripheral;
import games.jvrcruz.siliconcraft.api.peripheral.ComputerPeripheral.Context;
import games.jvrcruz.siliconcraft.api.peripheral.ComputerPeripheral.Result;
import games.jvrcruz.siliconcraft.compat.sable.SubLevelCoordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

public final class CompassPeripherals {
    private record Entry(DeferredBlock<Block> block, DeferredItem<BlockItem> item, ComputerPeripheral peripheral) {}

    private static final int LOCATE = 0x490;
    private static final List<Entry> ENTRIES = new ArrayList<>();

    private CompassPeripherals() {}

    public static void register() {
        if (ModList.get().isLoaded("naturescompass")) register("digital_natures_compass", Nature::locate);
        if (ModList.get().isLoaded("explorerscompass")) register("digital_explorers_compass", Explorer::locate);
    }

    private static void register(String id, Locator locator) {
        var block = ModBlocks.BLOCKS.registerSimpleBlock(id, BlockBehaviour.Properties.of().mapColor(MapColor.METAL)
                .strength(2.5F, 3.0F).requiresCorrectToolForDrops().sound(SoundType.METAL));
        var item = ModItems.ITEMS.registerSimpleBlockItem(id, block);
        ENTRIES.add(new Entry(block, item, new ComputerPeripheral() {
            @Override
            public OptionalInt call(Context context, int method, int arg0, int arg1) {
                return OptionalInt.empty();
            }

            @Override
            public Optional<Result> invokeString(Context context, int method, String text, int radius) {
                if (method != LOCATE) return Optional.empty();
                var target = ResourceLocation.tryParse(text);
                if (target == null) return Optional.of(Result.number(-1));
                BlockPos origin = BlockPos.containing(SubLevelCoordinates.worldPosition(context.level(),
                        Vec3.atCenterOf(context.peripheralPos())));
                try {
                    return Optional.of(locator.locate(context, origin, target, radius));
                } catch (LinkageError incompatibleCompassVersion) {
                    return Optional.of(Result.number(-2));
                }
            }
        }));
    }

    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        for (Entry entry : ENTRIES) {
            event.registerBlock(ComputerPeripheral.CAPABILITY, (world, pos, state, blockEntity, face) -> entry.peripheral(),
                    entry.block().get());
        }
    }

    static void fillTab(CreativeModeTab.Output output) {
        for (Entry entry : ENTRIES) output.accept(entry.item().get());
    }

    @FunctionalInterface
    private interface Locator {
        Result locate(Context context, BlockPos origin, ResourceLocation target, int radius);
    }

    private static Result found(BlockPos pos) {
        return Result.text("{\"x\":" + pos.getX() + ",\"y\":" + pos.getY() + ",\"z\":" + pos.getZ() + "}");
    }

    private static int distance(int requested, int maximum) {
        return requested <= 0 ? maximum : Math.min(requested, maximum);
    }

    private static final class Nature {
        static Result locate(Context context, BlockPos origin, ResourceLocation target, int requestedRadius) {
            var world = context.level();
            var config = com.chaosthedude.naturescompass.config.ConfigHandler.GENERAL;
            if (!config.displayCoordinates.get()
                    || com.chaosthedude.naturescompass.util.BiomeUtils.biomeKeyIsBlacklisted(world, target)) {
                return Result.number(-1);
            }
            var registry = world.registryAccess().registryOrThrow(Registries.BIOME);
            var key = net.minecraft.resources.ResourceKey.create(Registries.BIOME, target);
            if (!registry.containsKey(key)) return Result.number(-1);
            var cache = world.getChunkSource();
            var source = cache.getGenerator().getBiomeSource();
            if (source.possibleBiomes().stream().noneMatch(holder -> holder.is(key))) return Result.number(0);
            var sampler = cache.randomState().sampler();
            int radius = distance(requestedRadius, 512);
            int budget = Math.min(1089, config.maxSamples.get());
            // Sample closest rings first, without asking the world for any chunks.
            for (int ring = 0; ring * 32 <= radius; ring++) {
                BlockPos nearest = null;
                int best = Integer.MAX_VALUE;
                for (int x = -ring; x <= ring; x++) {
                    for (int z = -ring; z <= ring; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) != ring) continue;
                        int distance = (x * x + z * z) * 1024;
                        if (distance > radius * radius || distance >= best) continue;
                        if (budget-- <= 0) return nearest == null ? Result.number(0) : found(nearest);
                        BlockPos pos = origin.offset(x * 32, 0, z * 32);
                        if (source.getNoiseBiome(QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()),
                                QuartPos.fromBlock(pos.getZ()), sampler).is(key)) {
                            nearest = pos;
                            best = distance;
                        }
                    }
                }
                if (nearest != null) return found(nearest);
            }
            return Result.number(0);
        }
    }

    private static final class Explorer {
        static Result locate(Context context, BlockPos origin, ResourceLocation target, int requestedRadius) {
            var world = context.level();
            var registry = world.registryAccess().registryOrThrow(Registries.STRUCTURE);
            var structure = registry.get(target);
            var config = com.chaosthedude.explorerscompass.config.ConfigHandler.GENERAL;
            if (structure == null || !config.displayCoordinates.get()
                    || com.chaosthedude.explorerscompass.util.StructureUtils.structureIsBlacklisted(world, structure)) {
                return Result.number(-1);
            }
            int radius = distance(requestedRadius, Math.min(256, config.maxRadius.get()));
            int budget = Math.min(1089, config.maxSamples.get());
            int chunkRadius = (radius + 15) / 16;
            int cx = origin.getX() >> 4;
            int cz = origin.getZ() >> 4;
            double best = Double.POSITIVE_INFINITY;
            BlockPos nearest = null;
            var chunks = world.getChunkSource();
            for (int ring = 0; ring <= chunkRadius && budget > 0; ring++) {
                for (int dx = -ring; dx <= ring && budget > 0; dx++) {
                    for (int dz = -ring; dz <= ring && budget > 0; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        budget--;
                        var chunk = chunks.getChunkNow(cx + dx, cz + dz);
                        if (chunk == null) continue;
                        var start = chunk.getStartForStructure(structure);
                        if (start == null || !start.isValid()) continue;
                        BlockPos pos = start.getBoundingBox().getCenter();
                        double distance = Math.hypot(pos.getX() - origin.getX(), pos.getZ() - origin.getZ());
                        if (distance <= radius && distance < best) {
                            nearest = pos;
                            best = distance;
                        }
                    }
                }
            }
            return nearest == null ? Result.number(0) : found(nearest);
        }
    }
}
