package games.jvrcruz.siliconcraft.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record RamData(int capacity) {
    public static final RamData DEFAULT = new RamData(256);

    public static final Codec<RamData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.INT.optionalFieldOf("capacity", 256).forGetter(RamData::capacity)
        ).apply(instance, RamData::new)
    );

    public static final StreamCodec<ByteBuf, RamData> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT,
        RamData::capacity,
        RamData::new
    );
}
