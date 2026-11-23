package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class FramebufferCodec {
    public static final int PACKET_PIXELS = VirtualCpu.FB_WIDTH * 90;
    public static final int PACKET_BYTES = 512 * 1024;

    private FramebufferCodec() {}

    public static byte[] compress(int[] pixels) {
        return pixels == null ? new byte[0] : compress(pixels, 0, pixels.length);
    }

    public static byte[] compress(int[] pixels, int offset, int count) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(baos), 32 * 1024))) {
                out.writeInt(count);
                for (int i = offset; i < offset + count; i++) {
                    out.writeInt(pixels[i]);
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
                 DataInputStream in = new DataInputStream(new BufferedInputStream(iis, 32 * 1024))) {
                int len = in.readInt();
                if (len < 0 || len > VirtualCpu.FB_WIDTH * VirtualCpu.FB_HEIGHT) return new int[0];
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

    public static void restore(int[] saved, int[] framebuffer) {
        if (saved.length == framebuffer.length) {
            System.arraycopy(saved, 0, framebuffer, 0, saved.length);
        } else if (saved.length == 480 * 270) {
            for (int y = 0; y < VirtualCpu.FB_HEIGHT; y++) {
                for (int x = 0; x < VirtualCpu.FB_WIDTH; x++) {
                    framebuffer[y * VirtualCpu.FB_WIDTH + x] = saved[(y / 2) * 480 + x / 2];
                }
            }
        }
    }

}
