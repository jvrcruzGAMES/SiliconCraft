package games.jvrcruz.siliconcraft.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import games.jvrcruz.siliconcraft.network.LargePayloadCodec;
import games.jvrcruz.siliconcraft.vm.BinaryBytecode;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.nio.ByteBuffer;
import java.util.Arrays;

public record RomData(byte[] bytecode) {
    public static final Codec<byte[]> BYTE_ARRAY_CODEC = Codec.BYTE_BUFFER.xmap(
            buf -> {
                ByteBuffer dup = buf.duplicate();
                byte[] bytes = new byte[dup.remaining()];
                dup.get(bytes);
                return bytes;
            },
            ByteBuffer::wrap
    );

    public static final RomData EMPTY = new RomData(new byte[0]);

    public RomData {
        bytecode = bytecode == null ? new byte[0] : bytecode;
    }

    public int getInstructionCount() {
        return BinaryBytecode.getInstructionCount(bytecode);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RomData other)) return false;
        return Arrays.equals(bytecode, other.bytecode);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytecode);
    }

    @Override
    public String toString() {
        return "RomData[bytes=" + bytecode.length + ", instructions=" + getInstructionCount() + "]";
    }

    public static final Codec<RomData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            BYTE_ARRAY_CODEC.optionalFieldOf("bytecode", new byte[0]).forGetter(RomData::bytecode)
        ).apply(instance, RomData::new)
    );

    public static final StreamCodec<ByteBuf, RomData> STREAM_CODEC = StreamCodec.composite(
        LargePayloadCodec.BYTE_ARRAY_STREAM_CODEC,
        RomData::bytecode,
        RomData::new
    );
}
