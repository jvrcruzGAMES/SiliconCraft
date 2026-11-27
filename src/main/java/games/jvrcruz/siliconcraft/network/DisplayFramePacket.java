package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.Siliconcraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record DisplayFramePacket(
        BlockPos pos,
        int mode,
        int frameId,
        int chunkIndex,
        int totalChunks,
        boolean keyframe,
        byte[] payload
) implements CustomPacketPayload {
    public static final Type<DisplayFramePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "display_frame"));

    private static final StreamCodec<io.netty.buffer.ByteBuf, byte[]> PAYLOAD_CODEC =
            ByteBufCodecs.byteArray(FramebufferCodec.PACKET_BYTES);

    public static final StreamCodec<RegistryFriendlyByteBuf, DisplayFramePacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public DisplayFramePacket decode(RegistryFriendlyByteBuf buf) {
                    BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                    int mode = ByteBufCodecs.VAR_INT.decode(buf);
                    int frameId = ByteBufCodecs.VAR_INT.decode(buf);
                    int chunkIndex = ByteBufCodecs.VAR_INT.decode(buf);
                    int totalChunks = ByteBufCodecs.VAR_INT.decode(buf);
                    boolean keyframe = buf.readBoolean();
                    byte[] payload = PAYLOAD_CODEC.decode(buf);
                    return new DisplayFramePacket(pos, mode, frameId, chunkIndex, totalChunks, keyframe, payload);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, DisplayFramePacket value) {
                    BlockPos.STREAM_CODEC.encode(buf, value.pos());
                    ByteBufCodecs.VAR_INT.encode(buf, value.mode());
                    ByteBufCodecs.VAR_INT.encode(buf, value.frameId());
                    ByteBufCodecs.VAR_INT.encode(buf, value.chunkIndex());
                    ByteBufCodecs.VAR_INT.encode(buf, value.totalChunks());
                    buf.writeBoolean(value.keyframe());
                    PAYLOAD_CODEC.encode(buf, value.payload());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
