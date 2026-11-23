package games.jvrcruz.siliconcraft.api.peripheral;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.BlockCapability;

import java.util.OptionalInt;

/**
 * An API exposed by a block to a directly connected or wired SiliconCraft computer. Register a provider
 * on the mod bus with RegisterCapabilitiesEvent. The capability context is the
 * face of the peripheral that touches the computer, a serial cable, or an expansion.
 */
@FunctionalInterface
public interface ComputerPeripheral {
    BlockCapability<ComputerPeripheral, Direction> CAPABILITY = BlockCapability.createSided(
            ResourceLocation.fromNamespaceAndPath("siliconcraft", "computer_peripheral"), ComputerPeripheral.class);

    /** Automatic peripherals can be called without a PWRAP instruction. */
    default boolean autoWrap() {
        return false;
    }

    /**
     * Called on the server thread. Return an empty result for an unsupported method.
     * Methods should finish within a server tick and avoid loading chunks.
     * Providers for manual peripherals should return a stable instance while connected.
     */
    OptionalInt call(Context context, int method, int arg0, int arg1);

    /** Called for a PCALLS request with a string copied out of guest RAM. */
    default OptionalInt callString(Context context, int method, String text, int argument) {
        return OptionalInt.empty();
    }

    record Context(ServerLevel level, BlockPos computerPos, BlockPos peripheralPos, Direction face) {}
}
