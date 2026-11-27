package games.jvrcruz.siliconcraft.network;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Bounded, per-player chunked byte transfer reassembler with expiration and strict ordering checks.
 */
public final class ChunkedTransferStore<T> {
    public static final int CHUNK_SIZE = 256 * 1024;
    public static final int MAX_UPLOAD_COMPRESSED_BYTES = 16 * 1024 * 1024;
    public static final int MAX_CHUNKS = (MAX_UPLOAD_COMPRESSED_BYTES + CHUNK_SIZE - 1) / CHUNK_SIZE;
    public static final long TRANSFER_TIMEOUT_MS = 60_000L;

    public enum Status {
        ACCEPTED,
        COMPLETE,
        REJECTED
    }

    public record ChunkOutcome<T>(Status status, T metadata, byte[] payload, String error) {
        public static <T> ChunkOutcome<T> accepted() {
            return new ChunkOutcome<>(Status.ACCEPTED, null, null, null);
        }

        public static <T> ChunkOutcome<T> complete(T metadata, byte[] payload) {
            return new ChunkOutcome<>(Status.COMPLETE, metadata, payload, null);
        }

        public static <T> ChunkOutcome<T> rejected(String error) {
            return new ChunkOutcome<>(Status.REJECTED, null, null, error);
        }
    }

    private static final class PendingTransfer<T> {
        final UUID transferId;
        final T metadata;
        final int totalChunks;
        final byte[] buffer;
        int nextChunkIndex = 0;
        int receivedBytes = 0;
        long lastActivityMs;

        PendingTransfer(UUID transferId, T metadata, int totalChunks, int totalBytes, long nowMs) {
            this.transferId = transferId;
            this.metadata = metadata;
            this.totalChunks = totalChunks;
            this.buffer = new byte[totalBytes];
            this.lastActivityMs = nowMs;
        }
    }

    private final int chunkSize;
    private final int maxTotalBytes;
    private final int maxChunks;
    private final long timeoutMs;
    private final LongSupplier clockMs;
    private final Map<UUID, PendingTransfer<T>> pendingByPlayer = new ConcurrentHashMap<>();

    public ChunkedTransferStore() {
        this(CHUNK_SIZE, MAX_UPLOAD_COMPRESSED_BYTES, TRANSFER_TIMEOUT_MS, System::currentTimeMillis);
    }

    public ChunkedTransferStore(int chunkSize, int maxTotalBytes, long timeoutMs, LongSupplier clockMs) {
        this.chunkSize = chunkSize;
        this.maxTotalBytes = maxTotalBytes;
        this.maxChunks = (maxTotalBytes + chunkSize - 1) / chunkSize;
        this.timeoutMs = timeoutMs;
        this.clockMs = clockMs;
    }

    public static List<byte[]> splitIntoChunks(byte[] data) {
        return splitIntoChunks(data, CHUNK_SIZE);
    }

    public static List<byte[]> splitIntoChunks(byte[] data, int chunkSize) {
        if (data == null || data.length == 0) {
            return List.of();
        }
        int totalChunks = (data.length + chunkSize - 1) / chunkSize;
        List<byte[]> chunks = new ArrayList<>(totalChunks);
        for (int offset = 0; offset < data.length; offset += chunkSize) {
            int end = Math.min(data.length, offset + chunkSize);
            chunks.add(Arrays.copyOfRange(data, offset, end));
        }
        return chunks;
    }

    public String startTransfer(UUID playerId, UUID transferId, T metadata, int totalChunks, int totalBytes) {
        if (playerId == null || transferId == null || metadata == null) {
            return "Invalid upload header";
        }
        if (totalBytes <= 0 || totalBytes > maxTotalBytes) {
            return "Project exceeds max upload size (" + (maxTotalBytes / (1024 * 1024)) + " MB compressed)";
        }
        if (totalChunks <= 0 || totalChunks > maxChunks) {
            return "Invalid chunk count: " + totalChunks;
        }
        long minBytesForChunks = (long) (totalChunks - 1) * chunkSize + 1L;
        long maxBytesForChunks = (long) totalChunks * chunkSize;
        if (totalBytes < minBytesForChunks || totalBytes > maxBytesForChunks) {
            return "Chunk count does not match upload size";
        }

        long now = clockMs.getAsLong();
        pruneExpired(now);
        pendingByPlayer.put(playerId, new PendingTransfer<>(transferId, metadata, totalChunks, totalBytes, now));
        return null;
    }

    public ChunkOutcome<T> acceptChunk(UUID playerId, UUID transferId, int chunkIndex, byte[] data) {
        if (playerId == null || transferId == null) {
            return ChunkOutcome.rejected("Missing upload transfer identifier");
        }
        long now = clockMs.getAsLong();
        PendingTransfer<T> pending = pendingByPlayer.get(playerId);
        if (pending == null) {
            return ChunkOutcome.rejected("No active project upload session");
        }
        if (now - pending.lastActivityMs > timeoutMs) {
            pendingByPlayer.remove(playerId, pending);
            return ChunkOutcome.rejected("Project upload timed out");
        }
        if (!pending.transferId.equals(transferId)) {
            return ChunkOutcome.rejected("Project upload superseded or mismatched");
        }
        if (chunkIndex != pending.nextChunkIndex || chunkIndex < 0 || chunkIndex >= pending.totalChunks) {
            pendingByPlayer.remove(playerId, pending);
            return ChunkOutcome.rejected("Out-of-order project chunk " + chunkIndex);
        }
        if (data == null || data.length == 0 || data.length > chunkSize) {
            pendingByPlayer.remove(playerId, pending);
            return ChunkOutcome.rejected("Invalid project chunk size");
        }
        if (pending.receivedBytes + data.length > pending.buffer.length) {
            pendingByPlayer.remove(playerId, pending);
            return ChunkOutcome.rejected("Project chunk overflows declared size");
        }
        boolean isLast = (chunkIndex == pending.totalChunks - 1);
        if (isLast && pending.receivedBytes + data.length != pending.buffer.length) {
            pendingByPlayer.remove(playerId, pending);
            return ChunkOutcome.rejected("Incomplete project upload");
        }

        System.arraycopy(data, 0, pending.buffer, pending.receivedBytes, data.length);
        pending.receivedBytes += data.length;
        pending.nextChunkIndex++;
        pending.lastActivityMs = now;

        if (isLast) {
            pendingByPlayer.remove(playerId, pending);
            return ChunkOutcome.complete(pending.metadata, pending.buffer);
        }
        return ChunkOutcome.accepted();
    }

    public void cancelPlayer(UUID playerId) {
        if (playerId != null) {
            pendingByPlayer.remove(playerId);
        }
    }

    public void clearAll() {
        pendingByPlayer.clear();
    }

    public int activeTransferCount() {
        return pendingByPlayer.size();
    }

    public void pruneExpired() {
        pruneExpired(clockMs.getAsLong());
    }

    private void pruneExpired(long nowMs) {
        Iterator<Map.Entry<UUID, PendingTransfer<T>>> it = pendingByPlayer.entrySet().iterator();
        while (it.hasNext()) {
            PendingTransfer<T> pending = it.next().getValue();
            if (nowMs - pending.lastActivityMs > timeoutMs) {
                it.remove();
            }
        }
    }
}
