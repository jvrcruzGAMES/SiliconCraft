package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.Siliconcraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public record SyncFramebufferPacket(BlockPos pos, int mode, byte[] compressedPixels) implements CustomPacketPayload {
    public static final Type<SyncFramebufferPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "sync_framebuffer"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncFramebufferPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    SyncFramebufferPacket::pos,
                    ByteBufCodecs.VAR_INT,
                    SyncFramebufferPacket::mode,
                    ByteBufCodecs.BYTE_ARRAY,
                    SyncFramebufferPacket::compressedPixels,
                    SyncFramebufferPacket::new
            );

    public static byte[] compress(int[] pixels) {
        if (pixels == null) return new byte[0];
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DeflaterOutputStream dos = new DeflaterOutputStream(baos);
                 DataOutputStream out = new DataOutputStream(dos)) {
                out.writeInt(pixels.length);
                for (int p : pixels) {
                    out.writeInt(p);
                }
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    public static int[] decompress(byte[] compressed) {
        if (compressed == null || compressed.length == 0) return new int[0];
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
            try (InflaterInputStream iis = new InflaterInputStream(bais);
                 DataInputStream in = new DataInputStream(iis)) {
                int len = in.readInt();
                int[] pixels = new int[len];
                for (int i = 0; i < len; i++) {
                    pixels[i] = in.readInt();
                }
                return pixels;
            }
        } catch (Exception e) {
            return new int[0];
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
