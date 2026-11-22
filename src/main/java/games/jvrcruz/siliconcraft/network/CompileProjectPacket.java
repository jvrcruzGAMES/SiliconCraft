package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.Siliconcraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record CompileProjectPacket(BlockPos pos, String projectName, Map<String, List<String>> files, int standard) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CompileProjectPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "compile_project"));

    public static final StreamCodec<ByteBuf, CompileProjectPacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, CompileProjectPacket::pos,
            ByteBufCodecs.STRING_UTF8, CompileProjectPacket::projectName,
            ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list())), CompileProjectPacket::files,
            ByteBufCodecs.VAR_INT, CompileProjectPacket::standard,
            CompileProjectPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
