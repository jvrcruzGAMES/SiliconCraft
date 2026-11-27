package games.jvrcruz.siliconcraft.client;

import games.jvrcruz.siliconcraft.client.audio.ComputerAudioPlayer;
import games.jvrcruz.siliconcraft.compat.sable.SubLevelCoordinates;
import games.jvrcruz.siliconcraft.network.DisplayFramePacket;
import games.jvrcruz.siliconcraft.network.PeripheralNoticePacket;
import games.jvrcruz.siliconcraft.network.PlayAudioPacket;
import games.jvrcruz.siliconcraft.network.StopAudioPacket;
import games.jvrcruz.siliconcraft.network.SyncTerminalPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class ClientPayloadHandler {

    public static void handleDisplayFrame(DisplayFramePacket packet, IPayloadContext context) {
        ClientDisplayCache.onDisplayFrame(packet);
    }

    public static void handleSyncTerminal(SyncTerminalPacket packet, IPayloadContext context) {
        ClientDisplayCache.onTerminalSync(packet.pos(), packet.lines());
    }

    public static void handlePlayAudio(PlayAudioPacket packet, IPayloadContext context) {
        if (!packet.isWorldAudio()) {
            ComputerAudioPlayer.playScreenAudio(packet.computerPos(), packet.pcmSamples(), packet.sampleRate());
            return;
        }
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player == null) return;
            var level = player.level();
            ComputerAudioPlayer.playWorldAudio(level, packet.computerPos(), packet.audioPos(), packet.pcmSamples(), packet.sampleRate());
            Vec3 position = SubLevelCoordinates.worldPosition(level, Vec3.atBottomCenterOf(packet.audioPos()).add(0, 1.2, 0));
            level.addParticle(ParticleTypes.NOTE, position.x, position.y, position.z,
                    level.random.nextDouble(), 0.0, 0.0);
        });
    }

    public static void handlePeripheralNotice(PeripheralNoticePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft client = Minecraft.getInstance();
            Component title = Component.translatable("block.siliconcraft.chat_box");
            Component message = Component.literal(packet.message());
            if (packet.narration()) {
                client.getNarrator().say(title.copy().append(": ").append(message));
            } else {
                SystemToast.add(client.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, message);
            }
        });
    }

    public static void handleStopAudio(StopAudioPacket packet, IPayloadContext context) {
        ComputerAudioPlayer.stopAudio(packet.computerPos());
    }
}
