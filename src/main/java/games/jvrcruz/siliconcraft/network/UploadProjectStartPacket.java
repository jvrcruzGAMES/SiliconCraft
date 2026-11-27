package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.Siliconcraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record UploadProjectStartPacket(
        UUID transferId,
        BlockPos pos,
        String fileName,
        int totalChunks,
        int totalBytes
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<UploadProjectStartPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "upload_project_start"));

    public static final StreamCodec<ByteBuf, UploadProjectStartPacket> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, UploadProjectStartPacket::transferId,
            BlockPos.STREAM_CODEC, UploadProjectStartPacket::pos,
            LargePayloadCodec.LARGE_STRING, UploadProjectStartPacket::fileName,
            ByteBufCodecs.VAR_INT, UploadProjectStartPacket::totalChunks,
            ByteBufCodecs.VAR_INT, UploadProjectStartPacket::totalBytes,
            UploadProjectStartPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
