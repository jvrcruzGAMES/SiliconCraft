package games.jvrcruz.siliconcraft.vm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Sparse storage. Only written pages occupy memory or disk. */
public abstract class DriveStorage {
    private static final int PAGE_SIZE = 4096;

    public abstract long length();
    public abstract void read(int address, byte[] data, int count);
    public abstract void write(int address, byte[] data, int count);

    public static DriveStorage inMemory(byte[] initial) {
        return new MemoryDrive(initial == null ? new byte[0] : initial);
    }

    public static DriveStorage onDisk(Path path) {
        return new FileDrive(path);
    }

    private static final class MemoryDrive extends DriveStorage {
        private final Map<Integer, byte[]> pages = new HashMap<>();
        private final byte[] initial;
        private long length;

        private MemoryDrive(byte[] initial) {
            this.initial = initial;
            this.length = initial.length;
        }

        @Override public long length() { return length; }

        @Override
        public void read(int address, byte[] data, int count) {
            for (int i = 0; i < count;) {
                int position = address + i;
                if (position < initial.length) {
                    int size = Math.min(count - i, initial.length - position);
                    System.arraycopy(initial, position, data, i, size);
                    i += size;
                    continue;
                }
                int offset = position % PAGE_SIZE;
                int size = Math.min(count - i, PAGE_SIZE - offset);
                byte[] page = pages.get(position / PAGE_SIZE);
                if (page == null) Arrays.fill(data, i, i + size, (byte) 0);
                else System.arraycopy(page, offset, data, i, size);
                i += size;
            }
        }

        @Override
        public void write(int address, byte[] data, int count) {
            for (int i = 0; i < count;) {
                int position = address + i;
                if (position < initial.length) {
                    int size = Math.min(count - i, initial.length - position);
                    System.arraycopy(data, i, initial, position, size);
                    i += size;
                    continue;
                }
                int offset = position % PAGE_SIZE;
                int size = Math.min(count - i, PAGE_SIZE - offset);
                byte[] page = pages.computeIfAbsent(position / PAGE_SIZE, key -> new byte[PAGE_SIZE]);
                System.arraycopy(data, i, page, offset, size);
                i += size;
            }
            length = Math.max(length, (long) address + count);
        }
    }

    private static final class FileDrive extends DriveStorage {
        private final Path path;

        private FileDrive(Path path) {
            this.path = path;
        }

        @Override
        public long length() {
            try {
                return Files.size(path);
            } catch (NoSuchFileException unused) {
                return 0;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void read(int address, byte[] data, int count) {
            ByteBuffer buffer = ByteBuffer.wrap(data, 0, count);
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                while (buffer.hasRemaining() && channel.read(buffer, (long) address + buffer.position()) > 0) {
                    // Read until the requested bytes are filled or the file ends.
                }
            } catch (NoSuchFileException unused) {
                // A new drive has no backing file.
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Arrays.fill(data, buffer.position(), count, (byte) 0);
        }

        @Override
        public void write(int address, byte[] data, int count) {
            ByteBuffer buffer = ByteBuffer.wrap(data, 0, count);
            try {
                Files.createDirectories(path.getParent());
                try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
                    while (buffer.hasRemaining()) {
                        channel.write(buffer, (long) address + buffer.position());
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
