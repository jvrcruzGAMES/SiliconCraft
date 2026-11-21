package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.data.ProgramEntry;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record SyncProgramsPacket(List<ProgramEntry> programs) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SyncProgramsPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "sync_programs"));

    public static final StreamCodec<ByteBuf, SyncProgramsPacket> STREAM_CODEC = StreamCodec.composite(
            ProgramEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), SyncProgramsPacket::programs,
            SyncProgramsPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
