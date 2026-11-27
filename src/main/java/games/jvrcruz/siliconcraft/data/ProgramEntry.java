package games.jvrcruz.siliconcraft.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import games.jvrcruz.siliconcraft.network.LargePayloadCodec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

public record ProgramEntry(String id, String name, String archName, List<String> lines, List<String> requiredInstructions) {
    public static final Codec<ProgramEntry> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.STRING.fieldOf("id").forGetter(ProgramEntry::id),
            Codec.STRING.fieldOf("name").forGetter(ProgramEntry::name),
            Codec.STRING.optionalFieldOf("archName", "").forGetter(ProgramEntry::archName),
            Codec.STRING.listOf().optionalFieldOf("lines", List.of()).forGetter(ProgramEntry::lines),
            Codec.STRING.listOf().optionalFieldOf("requiredInstructions", List.of()).forGetter(ProgramEntry::requiredInstructions)
        ).apply(instance, ProgramEntry::new)
    );

    public static final StreamCodec<ByteBuf, ProgramEntry> STREAM_CODEC = StreamCodec.composite(
        LargePayloadCodec.LARGE_STRING, ProgramEntry::id,
        LargePayloadCodec.LARGE_STRING, ProgramEntry::name,
        LargePayloadCodec.LARGE_STRING, ProgramEntry::archName,
        LargePayloadCodec.STRING_LIST_STREAM_CODEC, ProgramEntry::lines,
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ProgramEntry::requiredInstructions,
        ProgramEntry::new
    );
}
