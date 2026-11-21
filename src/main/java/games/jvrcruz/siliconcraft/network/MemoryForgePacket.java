package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.Siliconcraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record MemoryForgePacket(BlockPos pos, int mode, boolean nvme, boolean startForge) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<MemoryForgePacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "memory_forge"));

    public static final StreamCodec<ByteBuf, MemoryForgePacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, MemoryForgePacket::pos,
            ByteBufCodecs.VAR_INT, MemoryForgePacket::mode,
            ByteBufCodecs.BOOL, MemoryForgePacket::nvme,
            ByteBufCodecs.BOOL, MemoryForgePacket::startForge,
            MemoryForgePacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
