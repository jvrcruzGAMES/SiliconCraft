package games.jvrcruz.siliconcraft.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

public record GpuData(
        boolean present,
        String archName,
        int vramCapacity,
        int computeUnits,
        List<String> features
) {
    public static final int MAX_RAM_MODULES = 8;
    public static final GpuData EMPTY = new GpuData(false, "", 0, 0, List.of());
    public static final GpuData DEFAULT = new GpuData(true, "Standard-GPU", RamData.MODULE_CAPACITY, 4, List.of());

    public GpuData {
        archName = archName == null ? "" : archName;
        vramCapacity = Math.max(0, vramCapacity);
        computeUnits = Math.max(present ? 1 : 0, computeUnits);
        features = features == null ? List.of() : List.copyOf(features);
    }

    public boolean isPresent() {
        return present;
    }

    public static int calculateRequiredSilicon(int opCount) {
        return games.jvrcruz.siliconcraft.vm.VirtualGpu.calculateRequiredSilicon(opCount);
    }

    public static int calculateRequiredRedstone(int opCount) {
        return games.jvrcruz.siliconcraft.vm.VirtualGpu.calculateRequiredRedstone(opCount);
    }

    public static int calculateRequiredEnergy(int ramModules) {
        return games.jvrcruz.siliconcraft.vm.VirtualGpu.calculateRequiredEnergy(ramModules);
    }

    public static int calculateRequiredEnergy(int opCount, int ramModules) {
        return games.jvrcruz.siliconcraft.vm.VirtualGpu.calculateRequiredEnergy(opCount, ramModules);
    }

    public static int calculateMaxProgress(int ramModules) {
        return games.jvrcruz.siliconcraft.vm.VirtualGpu.calculateMaxProgress(ramModules);
    }

    public static int calculateMaxProgress(int opCount, int ramModules) {
        return games.jvrcruz.siliconcraft.vm.VirtualGpu.calculateMaxProgress(opCount, ramModules);
    }

    public static String formatVram(int bytes) {
        return games.jvrcruz.siliconcraft.vm.VirtualGpu.formatVram(bytes);
    }

    public static final Codec<GpuData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.BOOL.optionalFieldOf("present", false).forGetter(GpuData::present),
                    Codec.STRING.optionalFieldOf("archName", "").forGetter(GpuData::archName),
                    Codec.INT.optionalFieldOf("vramCapacity", 0).forGetter(GpuData::vramCapacity),
                    Codec.INT.optionalFieldOf("computeUnits", 0).forGetter(GpuData::computeUnits),
                    Codec.STRING.listOf().optionalFieldOf("features", List.of()).forGetter(GpuData::features)
            ).apply(instance, GpuData::new)
    );

    public static final StreamCodec<ByteBuf, GpuData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, GpuData::present,
            ByteBufCodecs.STRING_UTF8, GpuData::archName,
            ByteBufCodecs.VAR_INT, GpuData::vramCapacity,
            ByteBufCodecs.VAR_INT, GpuData::computeUnits,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), GpuData::features,
            GpuData::new
    );
}
