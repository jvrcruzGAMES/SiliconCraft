package games.jvrcruz.siliconcraft.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

public record SocData(List<String> instructions, int ramCapacity, List<String> bios, boolean hasNvme, int storageCapacity) {
    public static final SocData EMPTY = new SocData(List.of(), 0, List.of(), false, 0);

    public static final Codec<SocData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.STRING.listOf().optionalFieldOf("instructions", List.of()).forGetter(SocData::instructions),
            Codec.INT.optionalFieldOf("ramCapacity", 256).forGetter(SocData::ramCapacity),
            Codec.STRING.listOf().optionalFieldOf("bios", List.of()).forGetter(SocData::bios),
            Codec.BOOL.optionalFieldOf("hasNvme", false).forGetter(SocData::hasNvme),
            Codec.INT.optionalFieldOf("storageCapacity", 0).forGetter(SocData::storageCapacity)
        ).apply(instance, SocData::new)
    );

    public static final StreamCodec<ByteBuf, SocData> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()),
        SocData::instructions,
        ByteBufCodecs.VAR_INT,
        SocData::ramCapacity,
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()),
        SocData::bios,
        ByteBufCodecs.BOOL,
        SocData::hasNvme,
        ByteBufCodecs.VAR_INT,
        SocData::storageCapacity,
        SocData::new
    );
}
