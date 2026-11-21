package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.Siliconcraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ComputerInputPacket(BlockPos pos, int inputType, int arg1, int arg2, int arg3) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ComputerInputPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "computer_input"));

    public static final StreamCodec<ByteBuf, ComputerInputPacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ComputerInputPacket::pos,
            ByteBufCodecs.VAR_INT, ComputerInputPacket::inputType,
            ByteBufCodecs.VAR_INT, ComputerInputPacket::arg1,
            ByteBufCodecs.VAR_INT, ComputerInputPacket::arg2,
            ByteBufCodecs.VAR_INT, ComputerInputPacket::arg3,
            ComputerInputPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
