package games.jvrcruz.siliconcraft.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

public record ArchitectureData(String name, List<String> instructions) {
    public static final ArchitectureData EMPTY = new ArchitectureData("", List.of());

    public static final Codec<ArchitectureData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.STRING.optionalFieldOf("name", "").forGetter(ArchitectureData::name),
            Codec.STRING.listOf().optionalFieldOf("instructions", List.of()).forGetter(ArchitectureData::instructions)
        ).apply(instance, ArchitectureData::new)
    );

    public static final StreamCodec<ByteBuf, ArchitectureData> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8, ArchitectureData::name,
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ArchitectureData::instructions,
        ArchitectureData::new
    );
}
