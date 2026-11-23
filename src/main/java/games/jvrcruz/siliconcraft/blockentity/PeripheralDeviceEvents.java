package games.jvrcruz.siliconcraft.blockentity;

import com.google.gson.Gson;
import games.jvrcruz.siliconcraft.Siliconcraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.WeakHashMap;

@EventBusSubscriber(modid = Siliconcraft.MODID)
public final class PeripheralDeviceEvents {
    private static final Gson JSON = new Gson();
    private static final Map<MinecraftServer, ArrayDeque<Event>> EVENTS = new WeakHashMap<>();
    private static long sequence;

    record Event(long sequence, long tick, ResourceKey<Level> dimension, BlockPos position, boolean chat, String payload) {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void chat(ServerChatEvent event) { record(event.getPlayer(), "chat", event.getRawText()); }
    @SubscribeEvent
    public static void joined(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) record(player, "join", "");
    }
    @SubscribeEvent
    public static void left(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) record(player, "leave", "");
    }
    @SubscribeEvent
    public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) record(player, "dimension", event.getFrom().location().toString());
    }
    @SubscribeEvent
    public static void died(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) record(player, "death", "");
    }

    private static synchronized void record(ServerPlayer player, String type, String message) {
        ServerLevel world = player.serverLevel();
        var events = EVENTS.computeIfAbsent(world.getServer(), server -> new ArrayDeque<>());
        if (events.size() == 256) events.removeFirst();
        events.addLast(new Event(++sequence, world.getServer().getTickCount(), world.dimension(), player.blockPosition(),
                type.equals("chat"), JSON.toJson(Map.of("event", type, "player", player.getGameProfile().getName(),
                "uuid", player.getStringUUID(), "dimension", world.dimension().location().toString(), "message", message))));
    }

    static synchronized Event poll(ServerLevel world, long after, boolean chat, Vec3 pos) {
        var events = EVENTS.get(world.getServer());
        if (events == null) return null;
        for (Event event : events) {
            if (event.sequence() > after && world.getServer().getTickCount() - event.tick() <= 1200 && chat == event.chat()
                    && (!chat || event.dimension().equals(world.dimension()) && pos.distanceToSqr(Vec3.atCenterOf(event.position())) <= 4096)) return event;
        }
        return null;
    }
}
