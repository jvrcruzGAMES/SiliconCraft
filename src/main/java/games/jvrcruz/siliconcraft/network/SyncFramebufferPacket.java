package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.Siliconcraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record SyncFramebufferPacket(BlockPos pos, int mode, int offset, byte[] compressedPixels) implements CustomPacketPayload {
    public static final Type<SyncFramebufferPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "sync_framebuffer"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncFramebufferPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    SyncFramebufferPacket::pos,
                    ByteBufCodecs.VAR_INT,
                    SyncFramebufferPacket::mode,
                    ByteBufCodecs.VAR_INT,
                    SyncFramebufferPacket::offset,
                    ByteBufCodecs.byteArray(FramebufferCodec.PACKET_BYTES),
                    SyncFramebufferPacket::compressedPixels,
                    SyncFramebufferPacket::new
            );

    public static List<SyncFramebufferPacket> frame(BlockPos pos, int mode, int[] pixels) {
        if (mode != 1) return List.of(new SyncFramebufferPacket(pos, mode, 0, new byte[0]));
        List<SyncFramebufferPacket> strips = new ArrayList<>();
        for (int offset = 0; offset < pixels.length; offset += FramebufferCodec.PACKET_PIXELS) {
            int count = Math.min(FramebufferCodec.PACKET_PIXELS, pixels.length - offset);
            strips.add(new SyncFramebufferPacket(pos, mode, offset, FramebufferCodec.compress(pixels, offset, count)));
        }
        return strips;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
