package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.Siliconcraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SocManufacturerPacket(BlockPos pos) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SocManufacturerPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "soc_manufacturer"));

    public static final StreamCodec<ByteBuf, SocManufacturerPacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SocManufacturerPacket::pos,
            SocManufacturerPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
