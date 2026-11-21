package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.Siliconcraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PlayAudioPacket(BlockPos pos, byte[] pcmSamples, int sampleRate, boolean isWorldAudio) implements CustomPacketPayload {
    public static final Type<PlayAudioPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "play_audio"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayAudioPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    PlayAudioPacket::pos,
                    ByteBufCodecs.BYTE_ARRAY,
                    PlayAudioPacket::pcmSamples,
                    ByteBufCodecs.VAR_INT,
                    PlayAudioPacket::sampleRate,
                    ByteBufCodecs.BOOL,
                    PlayAudioPacket::isWorldAudio,
                    PlayAudioPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
