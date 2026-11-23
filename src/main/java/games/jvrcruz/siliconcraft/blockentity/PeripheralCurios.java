package games.jvrcruz.siliconcraft.blockentity;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.Map;
import java.util.TreeMap;

final class PeripheralCurios {
    private PeripheralCurios() {}

    static Map<String, Integer> sizes(ServerPlayer player) {
        Map<String, Integer> sizes = new TreeMap<>();
        CuriosApi.getCuriosInventory(player).ifPresent(inventory ->
                inventory.getCurios().forEach((name, slots) -> sizes.put(name, slots.getSlots())));
        return sizes;
    }

    static IItemHandlerModifiable slots(ServerPlayer player, String type) {
        return CuriosApi.getCuriosInventory(player).flatMap(inventory -> inventory.getStacksHandler(type))
                .map(handler -> handler.getStacks()).orElse(null);
    }
}
