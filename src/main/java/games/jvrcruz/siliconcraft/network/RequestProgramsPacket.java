package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.Siliconcraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RequestProgramsPacket() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RequestProgramsPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "request_programs"));

    public static final StreamCodec<ByteBuf, RequestProgramsPacket> STREAM_CODEC = StreamCodec.unit(new RequestProgramsPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
