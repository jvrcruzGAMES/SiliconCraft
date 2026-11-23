package games.jvrcruz.siliconcraft.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record StorageData(boolean isNvme, int capacity) {
    public static final int DRIVE_CAPACITY_KB = 2048 * 1024;
    public static final long MAX_CAPACITY_BYTES = 2048L * 1024 * 1024;
    public static final StorageData DEFAULT = new StorageData(false, DRIVE_CAPACITY_KB);

    public StorageData {
        capacity = upgradedCapacity(capacity);
    }

    public static int upgradedCapacity(int capacity) {
        return capacity == 1024 * 1024 ? DRIVE_CAPACITY_KB : capacity;
    }

    public static final Codec<StorageData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.BOOL.optionalFieldOf("isNvme", false).forGetter(StorageData::isNvme),
            Codec.INT.optionalFieldOf("capacity", DRIVE_CAPACITY_KB).forGetter(StorageData::capacity)
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
