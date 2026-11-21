package games.jvrcruz.siliconcraft.client;

import games.jvrcruz.siliconcraft.blockentity.ComputerBlockEntity;
import games.jvrcruz.siliconcraft.client.audio.ComputerAudioPlayer;
import games.jvrcruz.siliconcraft.network.PlayAudioPacket;
import games.jvrcruz.siliconcraft.network.SyncFramebufferPacket;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class ClientPayloadHandler {

    public static void handleSyncFramebuffer(SyncFramebufferPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player != null && player.level() != null && player.level().isLoaded(packet.pos())) {
                BlockEntity be = player.level().getBlockEntity(packet.pos());
                if (be instanceof ComputerBlockEntity compBe) {
                    compBe.getCpu().setDisplayMode(packet.mode());
                    int[] decompressed = SyncFramebufferPacket.decompress(packet.compressedPixels());
                    if (decompressed.length > 0) {
                        System.arraycopy(decompressed, 0, compBe.getCpu().getFramebuffer(), 0,
                                Math.min(decompressed.length, compBe.getCpu().getFramebuffer().length));
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
                ComputerAudioPlayer.playWorldAudio(packet.pos(), packet.pcmSamples(), packet.sampleRate());
                if (player.level() != null) {
                    player.level().addParticle(ParticleTypes.NOTE,
                            packet.pos().getX() + 0.5, packet.pos().getY() + 1.2, packet.pos().getZ() + 0.5,
                            (player.level().random.nextDouble() * 24.0) / 24.0, 0.0, 0.0);
                }
            } else {
                ComputerAudioPlayer.playScreenAudio(packet.pcmSamples(), packet.sampleRate());
            }
        });
    }
}
