package games.jvrcruz.siliconcraft.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import games.jvrcruz.siliconcraft.network.LargePayloadCodec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

public record RomData(List<String> lines) {
    public static final RomData EMPTY = new RomData(List.of());

    public static final Codec<RomData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.STRING.listOf().optionalFieldOf("lines", List.of()).forGetter(RomData::lines)
        ).apply(instance, RomData::new)
    );

    public static final StreamCodec<ByteBuf, RomData> STREAM_CODEC = StreamCodec.composite(
        LargePayloadCodec.STRING_LIST_STREAM_CODEC,
        RomData::lines,
        RomData::new
    );
}
