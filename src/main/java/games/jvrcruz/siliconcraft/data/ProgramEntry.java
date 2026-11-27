package games.jvrcruz.siliconcraft.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import games.jvrcruz.siliconcraft.network.LargePayloadCodec;
import games.jvrcruz.siliconcraft.vm.BinaryBytecode;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public record ProgramEntry(String id, String name, String archName, byte[] bytecode, List<String> requiredInstructions) {
    public ProgramEntry {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        archName = archName == null ? "" : archName;
        bytecode = bytecode == null ? new byte[0] : bytecode;
        requiredInstructions = requiredInstructions == null ? List.of() : List.copyOf(requiredInstructions);
    }

    public int getInstructionCount() {
        return BinaryBytecode.getInstructionCount(bytecode);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProgramEntry other)) return false;
        return Objects.equals(id, other.id)
                && Objects.equals(name, other.name)
                && Objects.equals(archName, other.archName)
                && Arrays.equals(bytecode, other.bytecode)
                && Objects.equals(requiredInstructions, other.requiredInstructions);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(id, name, archName, requiredInstructions);
        result = 31 * result + Arrays.hashCode(bytecode);
        return result;
    }

    @Override
    public String toString() {
        return "ProgramEntry[id=" + id + ", name=" + name + ", archName=" + archName
                + ", bytes=" + bytecode.length + ", instructions=" + getInstructionCount() + "]";
    }

    public static final Codec<ProgramEntry> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.STRING.fieldOf("id").forGetter(ProgramEntry::id),
            Codec.STRING.fieldOf("name").forGetter(ProgramEntry::name),
            Codec.STRING.optionalFieldOf("archName", "").forGetter(ProgramEntry::archName),
            RomData.BYTE_ARRAY_CODEC.optionalFieldOf("bytecode", new byte[0]).forGetter(ProgramEntry::bytecode),
            Codec.STRING.listOf().optionalFieldOf("requiredInstructions", List.of()).forGetter(ProgramEntry::requiredInstructions)
        ).apply(instance, ProgramEntry::new)
    );

    public static final StreamCodec<ByteBuf, ProgramEntry> STREAM_CODEC = StreamCodec.composite(
        LargePayloadCodec.LARGE_STRING, ProgramEntry::id,
        LargePayloadCodec.LARGE_STRING, ProgramEntry::name,
        LargePayloadCodec.LARGE_STRING, ProgramEntry::archName,
        LargePayloadCodec.BYTE_ARRAY_STREAM_CODEC, ProgramEntry::bytecode,
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ProgramEntry::requiredInstructions,
        ProgramEntry::new
    );
}
