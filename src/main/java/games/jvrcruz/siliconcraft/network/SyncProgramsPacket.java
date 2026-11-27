package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.data.ProgramEntry;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record SyncProgramsPacket(boolean append, List<ProgramEntry> programs) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SyncProgramsPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "sync_programs"));

    public static final StreamCodec<ByteBuf, SyncProgramsPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, SyncProgramsPacket::append,
            LargePayloadCodec.PROGRAM_LIST_STREAM_CODEC, SyncProgramsPacket::programs,
            SyncProgramsPacket::new
    );

    public SyncProgramsPacket(List<ProgramEntry> programs) {
        this(false, programs);
    }

    public static List<SyncProgramsPacket> buildBatches(List<ProgramEntry> programs) {
        if (programs == null || programs.isEmpty()) {
            return List.of(new SyncProgramsPacket(false, List.of()));
        }

        long estimatedChars = 0;
        for (ProgramEntry entry : programs) {
            estimatedChars += entry.name().length() + entry.archName().length() + 64L;
            for (String line : entry.lines()) {
                estimatedChars += line.length() + 4L;
            }
        }
        if (estimatedChars <= 200_000L
                || LargePayloadCodec.compressProgramList(programs).length <= LargePayloadCodec.SYNC_BATCH_TARGET_BYTES) {
            return List.of(new SyncProgramsPacket(false, List.copyOf(programs)));
        }

        List<SyncProgramsPacket> batches = new ArrayList<>();
        List<ProgramEntry> current = new ArrayList<>();
        for (ProgramEntry entry : programs) {
            if (!current.isEmpty()) {
                List<ProgramEntry> candidate = new ArrayList<>(current.size() + 1);
                candidate.addAll(current);
                candidate.add(entry);
                if (LargePayloadCodec.compressProgramList(candidate).length > LargePayloadCodec.SYNC_BATCH_TARGET_BYTES) {
                    batches.add(new SyncProgramsPacket(!batches.isEmpty(), List.copyOf(current)));
                    current.clear();
                }
            }
            current.add(entry);
        }
        if (!current.isEmpty()) {
            batches.add(new SyncProgramsPacket(!batches.isEmpty(), List.copyOf(current)));
        }
        return batches;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
