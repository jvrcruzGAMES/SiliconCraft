package games.jvrcruz.siliconcraft.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record RamData(int capacity) {
    public static final int MODULE_CAPACITY = 2048 * 1024;
    public static final RamData DEFAULT = new RamData(MODULE_CAPACITY);

    public RamData {
        if (capacity == 1024 * 1024) capacity = MODULE_CAPACITY;
    }

    public static final Codec<RamData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.INT.optionalFieldOf("capacity", MODULE_CAPACITY).forGetter(RamData::capacity)
        ).apply(instance, RamData::new)
    );

    public static final StreamCodec<ByteBuf, RamData> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT,
        RamData::capacity,
        RamData::new
    );
}
