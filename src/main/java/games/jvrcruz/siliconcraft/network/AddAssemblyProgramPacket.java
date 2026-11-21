package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.Siliconcraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record AddAssemblyProgramPacket(BlockPos pos, String name, List<String> lines) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<AddAssemblyProgramPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "add_assembly_program"));

    public static final StreamCodec<ByteBuf, AddAssemblyProgramPacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, AddAssemblyProgramPacket::pos,
            ByteBufCodecs.STRING_UTF8, AddAssemblyProgramPacket::name,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), AddAssemblyProgramPacket::lines,
            AddAssemblyProgramPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
