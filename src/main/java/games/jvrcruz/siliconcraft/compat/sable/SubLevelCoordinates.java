package games.jvrcruz.siliconcraft.compat.sable;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.neoforged.fml.ModList;

public final class SubLevelCoordinates {
    private static final boolean SABLE_PRESENT = ModList.get().isLoaded("sable") && ModList.get().isLoaded("sablecompanion");

    private SubLevelCoordinates() {}

    public static Vec3 worldPosition(Level level, Vec3 localPosition) {
        if (SABLE_PRESENT) {
            return SableIntegration.worldPosition(level, localPosition);
        }
        return localPosition;
    }

    public static double distanceSquared(Level level, Vec3 first, Vec3 second) {
        return SABLE_PRESENT ? SableIntegration.distanceSquared(level, first, second) : first.distanceToSqr(second);
    }

    public static boolean sameSubLevel(Level level, BlockPos first, BlockPos second) {
        return !SABLE_PRESENT || SableIntegration.sameSubLevel(level, first, second);
    }

    public static List<Map<String, String>> nearbyStructures(Level level, AABB bounds) {
        return SABLE_PRESENT ? SableIntegration.nearbyStructures(level, bounds) : List.of();
    }

    private static final class SableIntegration {
        static List<Map<String, String>> nearbyStructures(Level level, AABB bounds) {
            var structures = new ArrayList<Map<String, String>>();
            var box = new dev.ryanhcode.sable.companion.math.BoundingBox3d(bounds);
            for (var subLevel : SableCompanion.INSTANCE.getAllIntersecting(level, box)) {
                structures.add(Map.of("uuid", subLevel.getUniqueId().toString(),
                        "name", java.util.Objects.toString(subLevel.getName(), "")));
                if (structures.size() == 24) break;
            }
            return structures;
        }

        static Vec3 worldPosition(Level level, Vec3 localPosition) {
            var companion = SableCompanion.INSTANCE;
            if (level.isClientSide && companion.getContaining(level, localPosition) instanceof ClientSubLevelAccess client) {
                return client.renderPose().transformPosition(localPosition);
            }
            return companion.projectOutOfSubLevel(level, (Position) localPosition);
        }

        static double distanceSquared(Level level, Vec3 first, Vec3 second) {
            return SableCompanion.INSTANCE.distanceSquaredWithSubLevels(level, first, second);
        }

        static boolean sameSubLevel(Level level, BlockPos first, BlockPos second) {
            var companion = SableCompanion.INSTANCE;
            return companion.getContaining(level, first) == companion.getContaining(level, second);
        }
    }
}
