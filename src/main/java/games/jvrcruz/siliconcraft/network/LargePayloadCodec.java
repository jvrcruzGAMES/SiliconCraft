package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.data.ProgramEntry;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class LargePayloadCodec {
    public static final int MAX_WIRE_BYTES = PayloadCompression.MAX_WIRE_BYTES;
    public static final int MAX_UNCOMPRESSED_BYTES = PayloadCompression.MAX_UNCOMPRESSED_BYTES;
    public static final int SYNC_BATCH_TARGET_BYTES = PayloadCompression.SYNC_BATCH_TARGET_BYTES;
    public static final int MAX_SINGLE_STRING_CHARS = PayloadCompression.MAX_SINGLE_STRING_CHARS;

    private static final StreamCodec<ByteBuf, byte[]> BOUNDED_BYTES = ByteBufCodecs.byteArray(MAX_WIRE_BYTES);

    private LargePayloadCodec() {}

    @FunctionalInterface
    public interface BiDataWriter<T> {
        void write(DataOutputStream out, T value) throws IOException;
    }

    public static final StreamCodec<ByteBuf, String> LARGE_STRING = ByteBufCodecs.stringUtf8(MAX_SINGLE_STRING_CHARS);

    public static final StreamCodec<ByteBuf, byte[]> BYTE_ARRAY_STREAM_CODEC = compressedCodec(
            PayloadCompression::writeByteArray,
            PayloadCompression::readByteArray
    );

    public static final StreamCodec<ByteBuf, List<String>> STRING_LIST_STREAM_CODEC = compressedCodec(
            PayloadCompression::writeStringList,
            PayloadCompression::readStringList
    );

    public static final StreamCodec<ByteBuf, Map<String, List<String>>> PROJECT_FILES_STREAM_CODEC = compressedCodec(
            PayloadCompression::writeProjectFiles,
            PayloadCompression::readProjectFiles
    );

    public static final StreamCodec<ByteBuf, List<ProgramEntry>> PROGRAM_LIST_STREAM_CODEC = compressedCodec(
            LargePayloadCodec::writeProgramList,
            LargePayloadCodec::readProgramList
    );

    public static <T> StreamCodec<ByteBuf, T> compressedCodec(BiDataWriter<T> writer, PayloadCompression.DataReader<T> reader) {
        return new StreamCodec<>() {
            @Override
            public T decode(ByteBuf buf) {
                byte[] compressed = BOUNDED_BYTES.decode(buf);
                try {
                    return PayloadCompression.decompress(compressed, reader);
                } catch (RuntimeException e) {
                    throw new DecoderException(e.getMessage(), e);
                }
            }

            @Override
            public void encode(ByteBuf buf, T value) {
                byte[] compressed;
                try {
                    compressed = PayloadCompression.compress(out -> writer.write(out, value));
                } catch (RuntimeException e) {
                    throw new EncoderException(e.getMessage(), e);
                }
                if (compressed.length > MAX_WIRE_BYTES) {
                    throw new EncoderException("Compressed payload too large: " + compressed.length + " bytes (max " + MAX_WIRE_BYTES + ")");
                }
                BOUNDED_BYTES.encode(buf, compressed);
            }
        };
    }

    public static byte[] compressByteArray(byte[] data) {
        return PayloadCompression.compressByteArray(data);
    }

    public static byte[] compressStringList(List<String> lines) {
        return PayloadCompression.compressStringList(lines);
    }

    public static byte[] compressProjectFiles(Map<String, List<String>> files) {
        return PayloadCompression.compressProjectFiles(files);
    }

    public static byte[] compressProgramList(List<ProgramEntry> programs) {
        return PayloadCompression.compress(out -> writeProgramList(out, programs));
    }

    public static void writeProgramList(DataOutputStream out, List<ProgramEntry> programs) throws IOException {
        if (programs == null) {
            out.writeInt(0);
            return;
        }
        out.writeInt(programs.size());
        for (ProgramEntry entry : programs) {
            PayloadCompression.writeString(out, entry.id());
            PayloadCompression.writeString(out, entry.name());
            PayloadCompression.writeString(out, entry.archName());
            PayloadCompression.writeByteArray(out, entry.bytecode());
            PayloadCompression.writeStringList(out, entry.requiredInstructions());
        }
    }

    public static List<ProgramEntry> readProgramList(DataInputStream in, int[] remainingBudget) throws IOException {
        int count = PayloadCompression.readCount(in, remainingBudget);
        List<ProgramEntry> programs = new ArrayList<>(Math.min(count, 128));
        for (int i = 0; i < count; i++) {
            String id = PayloadCompression.readString(in, remainingBudget);
            String name = PayloadCompression.readString(in, remainingBudget);
            String archName = PayloadCompression.readString(in, remainingBudget);
            byte[] bytecode = PayloadCompression.readByteArray(in, remainingBudget);
            List<String> req = PayloadCompression.readStringList(in, remainingBudget);
            programs.add(new ProgramEntry(id, name, archName, bytecode, req));
        }
        return programs;
    }
}
