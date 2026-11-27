package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.Siliconcraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;
import java.util.Objects;

public record ProgrammerPacket(BlockPos pos, byte[] bytecode) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ProgrammerPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "programmer"));

    public ProgrammerPacket {
        bytecode = bytecode == null ? new byte[0] : bytecode;
    }

    public static final StreamCodec<ByteBuf, ProgrammerPacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ProgrammerPacket::pos,
            LargePayloadCodec.BYTE_ARRAY_STREAM_CODEC, ProgrammerPacket::bytecode,
            ProgrammerPacket::new
    );

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProgrammerPacket other)) return false;
        return Objects.equals(pos, other.pos) && Arrays.equals(bytecode, other.bytecode);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hashCode(pos) + Arrays.hashCode(bytecode);
    }

    @Override
    public String toString() {
        return "ProgrammerPacket[pos=" + pos + ", bytes=" + bytecode.length + "]";
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
