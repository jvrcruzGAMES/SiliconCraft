package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.Siliconcraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record ArchitectureWriterPacket(BlockPos pos, String archName, List<String> instructions, boolean gpu) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ArchitectureWriterPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "architecture_writer"));

    public static final StreamCodec<ByteBuf, ArchitectureWriterPacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ArchitectureWriterPacket::pos,
            ByteBufCodecs.STRING_UTF8, ArchitectureWriterPacket::archName,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ArchitectureWriterPacket::instructions,
            ByteBufCodecs.BOOL, ArchitectureWriterPacket::gpu,
            ArchitectureWriterPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
