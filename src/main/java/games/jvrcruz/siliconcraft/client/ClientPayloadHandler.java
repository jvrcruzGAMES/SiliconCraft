package games.jvrcruz.siliconcraft.client;

import games.jvrcruz.siliconcraft.blockentity.ComputerBlockEntity;
import games.jvrcruz.siliconcraft.client.audio.ComputerAudioPlayer;
import games.jvrcruz.siliconcraft.compat.sable.SubLevelCoordinates;
import games.jvrcruz.siliconcraft.network.PlayAudioPacket;
import games.jvrcruz.siliconcraft.network.PeripheralNoticePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import games.jvrcruz.siliconcraft.network.StopAudioPacket;
import games.jvrcruz.siliconcraft.network.SyncFramebufferPacket;
import games.jvrcruz.siliconcraft.network.FramebufferCodec;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class ClientPayloadHandler {

    public static void handleSyncFramebuffer(SyncFramebufferPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player != null && player.level() != null && player.level().isLoaded(packet.pos())) {
                BlockEntity be = player.level().getBlockEntity(packet.pos());
                if (be instanceof ComputerBlockEntity compBe) {
                    compBe.getCpu().setDisplayMode(packet.mode());
                    int[] decompressed = FramebufferCodec.decompress(packet.compressedPixels());
                    int[] framebuffer = compBe.getCpu().getFramebuffer();
                    int offset = packet.offset();
                    if (decompressed.length > 0 && offset >= 0 && offset <= framebuffer.length - decompressed.length) {
                        System.arraycopy(decompressed, 0, framebuffer, offset, decompressed.length);
                        compBe.getCpu().setFbDirty(true);
                    }
                }
            }
        });
    }

    public static void handlePlayAudio(PlayAudioPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player == null) return;
            if (packet.isWorldAudio()) {
                var level = player.level();
                ComputerAudioPlayer.playWorldAudio(level, packet.computerPos(), packet.audioPos(), packet.pcmSamples(), packet.sampleRate());
                Vec3 position = SubLevelCoordinates.worldPosition(level, Vec3.atBottomCenterOf(packet.audioPos()).add(0, 1.2, 0));
                level.addParticle(ParticleTypes.NOTE, position.x, position.y, position.z,
                        level.random.nextDouble(), 0.0, 0.0);
            } else {
                ComputerAudioPlayer.playScreenAudio(packet.computerPos(), packet.pcmSamples(), packet.sampleRate());
            }
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
        context.enqueueWork(() -> {
            ComputerAudioPlayer.stopAudio(packet.computerPos());
        });
    }
}
