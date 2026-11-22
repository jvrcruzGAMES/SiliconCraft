package games.jvrcruz.siliconcraft.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record StorageData(boolean isNvme, int capacity) {
    public static final StorageData DEFAULT = new StorageData(false, 1024 * 1024);

    public static final Codec<StorageData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.BOOL.optionalFieldOf("isNvme", false).forGetter(StorageData::isNvme),
            Codec.INT.optionalFieldOf("capacity", 1024 * 1024).forGetter(StorageData::capacity)
        ).apply(instance, StorageData::new)
    );

    public static final StreamCodec<ByteBuf, StorageData> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.BOOL,
        StorageData::isNvme,
        ByteBufCodecs.VAR_INT,
        StorageData::capacity,
        StorageData::new
    );
}
