package games.jvrcruz.siliconcraft.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

public record ChipData(String archName, List<String> instructions) {
    public static final ChipData EMPTY = new ChipData("", List.of());

    public ChipData(List<String> instructions) {
        this("", instructions);
    }

    public static final Codec<ChipData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.STRING.optionalFieldOf("archName", "").forGetter(ChipData::archName),
            Codec.STRING.listOf().optionalFieldOf("instructions", List.of()).forGetter(ChipData::instructions)
        ).apply(instance, ChipData::new)
    );

    public static final StreamCodec<ByteBuf, ChipData> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8, ChipData::archName,
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ChipData::instructions,
        ChipData::new
    );
}
