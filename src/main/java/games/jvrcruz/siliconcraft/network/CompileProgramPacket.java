package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.Siliconcraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record CompileProgramPacket(BlockPos pos, String name, List<String> sourceLines) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CompileProgramPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "compile_program"));

    public static final StreamCodec<ByteBuf, CompileProgramPacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, CompileProgramPacket::pos,
            ByteBufCodecs.STRING_UTF8, CompileProgramPacket::name,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), CompileProgramPacket::sourceLines,
            CompileProgramPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
