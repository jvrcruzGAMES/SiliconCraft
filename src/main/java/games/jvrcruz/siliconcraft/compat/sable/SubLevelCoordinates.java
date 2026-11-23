package games.jvrcruz.siliconcraft.compat.sable;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
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

    public static boolean sameSubLevel(Level level, BlockPos first, BlockPos second) {
        return !SABLE_PRESENT || SableIntegration.sameSubLevel(level, first, second);
    }

    private static final class SableIntegration {
        static Vec3 worldPosition(Level level, Vec3 localPosition) {
            var companion = SableCompanion.INSTANCE;
            if (level.isClientSide && companion.getContaining(level, localPosition) instanceof ClientSubLevelAccess client) {
                return client.renderPose().transformPosition(localPosition);
            }
            return companion.projectOutOfSubLevel(level, localPosition);
        }

        static boolean sameSubLevel(Level level, BlockPos first, BlockPos second) {
            var companion = SableCompanion.INSTANCE;
            return companion.getContaining(level, first) == companion.getContaining(level, second);
        }
    }
}
