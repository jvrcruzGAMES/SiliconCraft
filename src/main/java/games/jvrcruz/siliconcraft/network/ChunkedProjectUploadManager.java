package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.blockentity.ProjectManagerBlockEntity;
import games.jvrcruz.siliconcraft.data.ProgramEntry;
import games.jvrcruz.siliconcraft.data.ProgramSavedData;
import games.jvrcruz.siliconcraft.data.ScProjectFile;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public final class ChunkedProjectUploadManager {
    public record UploadMeta(BlockPos pos, String fileName) {}

    private static final ChunkedTransferStore<UploadMeta> STORE = new ChunkedTransferStore<>();

    private ChunkedProjectUploadManager() {}

    public static void handleStart(ServerPlayer player, UploadProjectStartPacket packet) {
        if (!isValidTarget(player, packet.pos())) {
            STORE.cancelPlayer(player.getUUID());
            player.displayClientMessage(
                    Component.literal("Project upload rejected: invalid or out-of-range Project Manager.")
                            .withStyle(ChatFormatting.RED),
                    false
            );
            return;
        }

        String error = STORE.startTransfer(
                player.getUUID(),
                packet.transferId(),
                new UploadMeta(packet.pos(), packet.fileName()),
                packet.totalChunks(),
                packet.totalBytes()
        );
        if (error != null) {
            player.displayClientMessage(Component.literal(error).withStyle(ChatFormatting.RED), false);
        }
    }

    public static void handleChunk(ServerPlayer player, UploadProjectChunkPacket packet) {
        ChunkedTransferStore.ChunkOutcome<UploadMeta> outcome = STORE.acceptChunk(
                player.getUUID(),
                packet.transferId(),
                packet.chunkIndex(),
                packet.data()
        );

        if (outcome.status() == ChunkedTransferStore.Status.REJECTED) {
            player.displayClientMessage(
                    Component.literal("Project upload failed: " + outcome.error()).withStyle(ChatFormatting.RED),
                    false
            );
            return;
        }

        if (outcome.status() != ChunkedTransferStore.Status.COMPLETE) {
            return;
        }

        UploadMeta meta = outcome.metadata();
        if (!isValidTarget(player, meta.pos())) {
            player.displayClientMessage(
                    Component.literal("Project upload failed: Project Manager is no longer accessible.")
                            .withStyle(ChatFormatting.RED),
                    false
            );
            return;
        }

        ScProjectFile.ParsedProject parsed;
        try {
            parsed = ScProjectFile.parseBytes(outcome.payload(), meta.fileName());
        } catch (RuntimeException e) {
            player.displayClientMessage(
                    Component.literal("Invalid .sc project: " + e.getMessage()).withStyle(ChatFormatting.RED),
                    false
            );
            return;
        }

        ProgramEntry entry = parsed.toProgramEntry();
        ProgramSavedData savedData = ProgramSavedData.get(player.serverLevel());
        savedData.addProgram(entry);
        ModMessages.syncProgramsToAll(savedData.getPrograms());
        player.displayClientMessage(
                Component.literal("Added .sc project: " + entry.name() + " (" + entry.lines().size() + " instructions)")
                        .withStyle(ChatFormatting.GREEN),
                false
        );
    }

    private static boolean isValidTarget(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null || !player.level().isLoaded(pos)) {
            return false;
        }
        if (player.distanceToSqr(pos.getCenter()) > 100.0) {
            return false;
        }
        return player.level().getBlockEntity(pos) instanceof ProjectManagerBlockEntity;
    }

    public static void cancelPlayer(UUID playerId) {
        STORE.cancelPlayer(playerId);
    }

    public static void clearAll() {
        STORE.clearAll();
    }
}
