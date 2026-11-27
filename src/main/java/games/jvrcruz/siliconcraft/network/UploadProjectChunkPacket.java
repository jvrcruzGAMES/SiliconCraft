package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.Siliconcraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record UploadProjectChunkPacket(
        UUID transferId,
        int chunkIndex,
        byte[] data
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<UploadProjectChunkPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "upload_project_chunk"));

    public static final StreamCodec<ByteBuf, UploadProjectChunkPacket> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, UploadProjectChunkPacket::transferId,
            ByteBufCodecs.VAR_INT, UploadProjectChunkPacket::chunkIndex,
            ByteBufCodecs.byteArray(ChunkedTransferStore.CHUNK_SIZE), UploadProjectChunkPacket::data,
            UploadProjectChunkPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
