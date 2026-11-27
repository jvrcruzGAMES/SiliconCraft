package games.jvrcruz.siliconcraft.network;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class PayloadCompression {
    public static final int MAX_WIRE_BYTES = 1_800_000;
    public static final int MAX_UNCOMPRESSED_BYTES = 64 * 1024 * 1024;
    public static final int SYNC_BATCH_TARGET_BYTES = 900_000;
    public static final int MAX_SINGLE_STRING_CHARS = 4 * 1024 * 1024;

    private PayloadCompression() {}

    @FunctionalInterface
    public interface DataWriter {
        void write(DataOutputStream out) throws IOException;
    }

    @FunctionalInterface
    public interface DataReader<T> {
        T read(DataInputStream in, int[] remainingBudget) throws IOException;
    }

    public static byte[] compress(DataWriter writer) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(baos), 16 * 1024))) {
                writer.write(out);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to compress payload", e);
        }
    }

    public static <T> T decompress(byte[] compressed, DataReader<T> reader) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new ByteArrayInputStream(compressed)), 16 * 1024))) {
            int[] remainingBudget = new int[]{MAX_UNCOMPRESSED_BYTES};
            return reader.read(in, remainingBudget);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to decompress payload", e);
        }
    }

    public static byte[] compressStringList(List<String> lines) {
        return compress(out -> writeStringList(out, lines));
    }

    public static List<String> decompressStringList(byte[] compressed) {
        return decompress(compressed, PayloadCompression::readStringList);
    }

    public static byte[] compressProjectFiles(Map<String, List<String>> files) {
        return compress(out -> writeProjectFiles(out, files));
    }

    public static Map<String, List<String>> decompressProjectFiles(byte[] compressed) {
        return decompress(compressed, PayloadCompression::readProjectFiles);
    }

    public static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    public static String readString(DataInputStream in, int[] remainingBudget) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > remainingBudget[0]) {
            throw new IllegalArgumentException("Decoded string length out of bounds: " + len);
        }
        remainingBudget[0] -= len;
        byte[] bytes = in.readNBytes(len);
        if (bytes.length != len) {
            throw new IllegalArgumentException("Unexpected EOF while reading string of length " + len);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static int readCount(DataInputStream in, int[] remainingBudget) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > remainingBudget[0] / 4) {
            throw new IllegalArgumentException("Decoded collection size out of bounds: " + count);
        }
        remainingBudget[0] -= count * 4;
        return count;
    }

    public static void writeStringList(DataOutputStream out, List<String> lines) throws IOException {
        if (lines == null) {
            out.writeInt(0);
            return;
        }
        out.writeInt(lines.size());
        for (String line : lines) {
            writeString(out, line);
        }
    }

    public static List<String> readStringList(DataInputStream in, int[] remainingBudget) throws IOException {
        int count = readCount(in, remainingBudget);
        List<String> lines = new ArrayList<>(Math.min(count, 1024));
        for (int i = 0; i < count; i++) {
            lines.add(readString(in, remainingBudget));
        }
        return lines;
    }

    public static void writeProjectFiles(DataOutputStream out, Map<String, List<String>> files) throws IOException {
        if (files == null) {
            out.writeInt(0);
            return;
        }
        out.writeInt(files.size());
        for (Map.Entry<String, List<String>> entry : files.entrySet()) {
            writeString(out, entry.getKey());
            writeStringList(out, entry.getValue());
        }
    }

    public static Map<String, List<String>> readProjectFiles(DataInputStream in, int[] remainingBudget) throws IOException {
        int fileCount = readCount(in, remainingBudget);
        Map<String, List<String>> files = new LinkedHashMap<>(Math.min(fileCount, 128));
        for (int i = 0; i < fileCount; i++) {
            String path = readString(in, remainingBudget);
            List<String> lines = readStringList(in, remainingBudget);
            files.put(path, lines);
        }
        return files;
    }
}
