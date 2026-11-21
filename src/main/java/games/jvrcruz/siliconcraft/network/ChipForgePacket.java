package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.Siliconcraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ChipForgePacket(BlockPos pos, boolean startForge) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ChipForgePacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "chip_forge"));

    public static final StreamCodec<ByteBuf, ChipForgePacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ChipForgePacket::pos,
            ByteBufCodecs.BOOL, ChipForgePacket::startForge,
            ChipForgePacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
