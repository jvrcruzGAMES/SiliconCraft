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

public record SocData(List<String> instructions, int ramCapacity, byte[] bios, boolean hasNvme, int storageCapacity, GpuData gpu) {
    public static final SocData EMPTY = new SocData(List.of(), 0, new byte[0], false, 0, GpuData.EMPTY);

    public SocData(List<String> instructions, int ramCapacity, byte[] bios, boolean hasNvme, int storageCapacity) {
        this(instructions, ramCapacity, bios, hasNvme, storageCapacity, GpuData.EMPTY);
    }

    public SocData {
        instructions = instructions == null ? List.of() : List.copyOf(instructions);
        bios = bios == null ? new byte[0] : bios;
        gpu = gpu == null ? GpuData.EMPTY : gpu;
    }

    public int getBiosInstructionCount() {
        return BinaryBytecode.getInstructionCount(bios);
    }

    public boolean hasGpu() {
        return gpu != null && gpu.isPresent();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SocData other)) return false;
        return ramCapacity == other.ramCapacity
                && hasNvme == other.hasNvme
                && storageCapacity == other.storageCapacity
                && Objects.equals(instructions, other.instructions)
                && Arrays.equals(bios, other.bios)
                && Objects.equals(gpu, other.gpu);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(instructions, ramCapacity, hasNvme, storageCapacity, gpu);
        result = 31 * result + Arrays.hashCode(bios);
        return result;
    }

    @Override
    public String toString() {
        return "SocData[instructions=" + instructions.size() + ", ram=" + ramCapacity
                + ", biosBytes=" + bios.length + ", hasNvme=" + hasNvme
                + ", storage=" + storageCapacity + ", gpu=" + gpu + "]";
    }

    public static final Codec<SocData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.STRING.listOf().optionalFieldOf("instructions", List.of()).forGetter(SocData::instructions),
            Codec.INT.optionalFieldOf("ramCapacity", 256).forGetter(SocData::ramCapacity),
            RomData.BYTE_ARRAY_CODEC.optionalFieldOf("bios", new byte[0]).forGetter(SocData::bios),
            Codec.BOOL.optionalFieldOf("hasNvme", false).forGetter(SocData::hasNvme),
            Codec.INT.optionalFieldOf("storageCapacity", 0).forGetter(SocData::storageCapacity),
            GpuData.CODEC.optionalFieldOf("gpu", GpuData.EMPTY).forGetter(SocData::gpu)
        ).apply(instance, SocData::new)
    );

    public static final StreamCodec<ByteBuf, SocData> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()),
        SocData::instructions,
        ByteBufCodecs.VAR_INT,
        SocData::ramCapacity,
        LargePayloadCodec.BYTE_ARRAY_STREAM_CODEC,
        SocData::bios,
        ByteBufCodecs.BOOL,
        SocData::hasNvme,
        ByteBufCodecs.VAR_INT,
        SocData::storageCapacity,
        GpuData.STREAM_CODEC,
        SocData::gpu,
        SocData::new
    );
}
