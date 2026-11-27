package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.Siliconcraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record SyncTerminalPacket(BlockPos pos, List<String> lines) implements CustomPacketPayload {
    public static final Type<SyncTerminalPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "sync_terminal"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncTerminalPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    SyncTerminalPacket::pos,
                    LargePayloadCodec.STRING_LIST_STREAM_CODEC,
                    SyncTerminalPacket::lines,
                    SyncTerminalPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
