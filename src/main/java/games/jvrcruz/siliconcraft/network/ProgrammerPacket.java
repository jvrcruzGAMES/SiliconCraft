package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.Siliconcraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record ProgrammerPacket(BlockPos pos, List<String> lines) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ProgrammerPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "programmer"));

    public static final StreamCodec<ByteBuf, ProgrammerPacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ProgrammerPacket::pos,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ProgrammerPacket::lines,
            ProgrammerPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
