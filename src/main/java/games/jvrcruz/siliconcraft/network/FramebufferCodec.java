package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class FramebufferCodec {
    public static final int PACKET_PIXELS = VirtualCpu.FB_WIDTH * 90;
    public static final int PACKET_BYTES = 512 * 1024;
    public static final int STREAM_CHUNK_BYTES = 256 * 1024;
    private static final int FB_WIDTH = VirtualCpu.FB_WIDTH;
    private static final int FB_HEIGHT = VirtualCpu.FB_HEIGHT;
    private static final int FB_PIXELS = FB_WIDTH * FB_HEIGHT;

    public record EncodedFrame(boolean changed, boolean keyframe, List<byte[]> chunks) {}

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

    public static EncodedFrame encodeStreamFrame(int[] current, int[] previous, boolean forceKeyframe) {
        if (current == null || current.length < FB_PIXELS) {
            return new EncodedFrame(false, false, List.of());
        }
        if (forceKeyframe || previous == null || previous.length < FB_PIXELS) {
            byte[] compressed = compressFullBulk(current);
            return new EncodedFrame(true, true, splitChunks(compressed));
        }

        boolean[] dirtyRows = new boolean[FB_HEIGHT];
        int dirtyCount = 0;
        for (int y = 0; y < FB_HEIGHT; y++) {
            int rowStart = y * FB_WIDTH;
            if (!Arrays.equals(current, rowStart, rowStart + FB_WIDTH, previous, rowStart, rowStart + FB_WIDTH)) {
                dirtyRows[y] = true;
                dirtyCount++;
            }
        }

        if (dirtyCount == 0) {
            return new EncodedFrame(false, false, List.of());
        }

        if (dirtyCount > (FB_HEIGHT * 3) / 4) {
            byte[] compressed = compressFullBulk(current);
            return new EncodedFrame(true, true, splitChunks(compressed));
        }

        byte[] compressed = compressRowDelta(current, dirtyRows, dirtyCount);
        return new EncodedFrame(true, false, splitChunks(compressed));
    }

    public static boolean applyStreamFrame(byte[] payload, boolean keyframe, int[] dest) {
        if (dest == null || dest.length < FB_PIXELS || payload == null || payload.length == 0) {
            return false;
        }
        try (InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(payload));
             DataInputStream in = new DataInputStream(new BufferedInputStream(iis, 32 * 1024))) {
            if (keyframe) {
                int len = in.readInt();
                if (len != FB_PIXELS) {
                    return false;
                }
                readIntsBulk(in, dest, 0, len);
                return true;
            } else {
                int runCount = in.readInt();
                if (runCount < 0 || runCount > FB_HEIGHT) {
                    return false;
                }
                for (int r = 0; r < runCount; r++) {
                    int startRow = Short.toUnsignedInt(in.readShort());
                    int rowCount = Short.toUnsignedInt(in.readShort());
                    if (rowCount == 0 || startRow + rowCount > FB_HEIGHT) {
                        return false;
                    }
                    readIntsBulk(in, dest, startRow * FB_WIDTH, rowCount * FB_WIDTH);
                }
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] compressFullBulk(int[] pixels) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baos, deflater, 32 * 1024)) {
            byte[] header = new byte[4];
            ByteBuffer.wrap(header).putInt(FB_PIXELS);
            dos.write(header);
            writeIntsBulk(dos, pixels, 0, FB_PIXELS);
            dos.finish();
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        } finally {
            deflater.end();
        }
    }

    private static byte[] compressRowDelta(int[] pixels, boolean[] dirtyRows, int dirtyRowCount) {
        List<int[]> runs = new ArrayList<>();
        int y = 0;
        while (y < FB_HEIGHT) {
            if (!dirtyRows[y]) {
                y++;
                continue;
            }
            int start = y;
            while (y < FB_HEIGHT && dirtyRows[y]) {
                y++;
            }
            runs.add(new int[]{start, y - start});
        }

        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(4096, dirtyRowCount * 1024));
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baos, deflater, 32 * 1024)) {
            byte[] hdr = new byte[4];
            ByteBuffer hdrBb = ByteBuffer.wrap(hdr);
            hdrBb.putInt(runs.size());
            dos.write(hdr);

            for (int[] run : runs) {
                hdrBb.clear();
                hdrBb.putShort((short) run[0]);
                hdrBb.putShort((short) run[1]);
                dos.write(hdr, 0, 4);
                writeIntsBulk(dos, pixels, run[0] * FB_WIDTH, run[1] * FB_WIDTH);
            }
            dos.finish();
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        } finally {
            deflater.end();
        }
    }

    private static void writeIntsBulk(DeflaterOutputStream dos, int[] src, int offset, int count) throws Exception {
        byte[] buf = new byte[32 * 1024];
        ByteBuffer bb = ByteBuffer.wrap(buf);
        int intsPerChunk = buf.length >> 2;
        int pos = offset;
        int remaining = count;
        while (remaining > 0) {
            int batch = Math.min(remaining, intsPerChunk);
            bb.clear();
            bb.asIntBuffer().put(src, pos, batch);
            dos.write(buf, 0, batch << 2);
            pos += batch;
            remaining -= batch;
        }
    }

    private static void readIntsBulk(DataInputStream in, int[] dst, int offset, int count) throws Exception {
        byte[] buf = new byte[32 * 1024];
        ByteBuffer bb = ByteBuffer.wrap(buf);
        int intsPerChunk = buf.length >> 2;
        int pos = offset;
        int remaining = count;
        while (remaining > 0) {
            int batch = Math.min(remaining, intsPerChunk);
            int bytesToRead = batch << 2;
            in.readFully(buf, 0, bytesToRead);
            bb.position(0).limit(bytesToRead);
            bb.asIntBuffer().get(dst, pos, batch);
            pos += batch;
            remaining -= batch;
        }
    }

    public static List<byte[]> splitChunks(byte[] compressed) {
        if (compressed == null || compressed.length == 0) {
            return List.of(new byte[0]);
        }
        if (compressed.length <= STREAM_CHUNK_BYTES) {
            return List.of(compressed);
        }
        int totalChunks = (compressed.length + STREAM_CHUNK_BYTES - 1) / STREAM_CHUNK_BYTES;
        List<byte[]> chunks = new ArrayList<>(totalChunks);
        for (int off = 0; off < compressed.length; off += STREAM_CHUNK_BYTES) {
            int end = Math.min(compressed.length, off + STREAM_CHUNK_BYTES);
            chunks.add(Arrays.copyOfRange(compressed, off, end));
        }
        return chunks;
    }

    public static byte[] joinChunks(List<byte[]> chunks) {
        int total = 0;
        for (byte[] chunk : chunks) {
            if (chunk != null) total += chunk.length;
        }
        byte[] combined = new byte[total];
        int pos = 0;
        for (byte[] chunk : chunks) {
            if (chunk != null && chunk.length > 0) {
                System.arraycopy(chunk, 0, combined, pos, chunk.length);
                pos += chunk.length;
            }
        }
        return combined;
    }
}
