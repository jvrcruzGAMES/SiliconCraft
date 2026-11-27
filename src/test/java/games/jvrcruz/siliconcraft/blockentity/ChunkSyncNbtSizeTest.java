package games.jvrcruz.siliconcraft.blockentity;

import games.jvrcruz.siliconcraft.data.GpuData;
import games.jvrcruz.siliconcraft.data.SocData;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ChunkSyncNbtSizeTest {

    private static final long VANILLA_CHUNK_NBT_LIMIT = 2_097_152L;

    @Test
    void testComputerUpdateTagStaysUnderVanilla2MbNbtLimitWithMassiveSocBios() throws Exception {
        int instructionCount = 300_000;
        long[] words = new long[instructionCount];
        long movWord = games.jvrcruz.siliconcraft.vm.BinaryBytecode.encodeFmt0(
                games.jvrcruz.siliconcraft.vm.BinaryBytecode.OP_MOV, 2,
                games.jvrcruz.siliconcraft.vm.BinaryBytecode.MODE_REG,
                games.jvrcruz.siliconcraft.vm.BinaryBytecode.REG_A,
                games.jvrcruz.siliconcraft.vm.BinaryBytecode.MODE_IMM, 0, 0, 7);
        java.util.Arrays.fill(words, movWord);
        byte[] massiveBios = games.jvrcruz.siliconcraft.vm.BinaryBytecode.encodeScbc(
                new games.jvrcruz.siliconcraft.vm.BinaryBytecode.ProgramImage(words, null, null, List.of())
        );
        GpuData gpu = new GpuData(true, "RTX-Custom", 4096 * 1024, 8, List.of("GFILL", "GFLIP"));
        SocData socData = new SocData(
                List.of("MOV", "ADD", "JMP", "HLT", "GFILL", "GFLIP"),
                1024 * 1024,
                massiveBios,
                true,
                2048 * 1024,
                gpu
        );

        // Full disk-persistence SocData keeps all 300k 64-bit instructions (~2.4MB) and exceeds chunk sync cap
        CompoundTag fullSocTag = ComputerBlockEntity.serializeSocTag(socData, true);
        assertArrayEquals(massiveBios, ComputerBlockEntity.deserializeSocTag(fullSocTag).bios());

        FriendlyByteBuf rawFullBuf = new FriendlyByteBuf(Unpooled.buffer());
        rawFullBuf.writeNbt(fullSocTag);
        assertTrue(rawFullBuf.readableBytes() > VANILLA_CHUNK_NBT_LIMIT,
                "Expected full disk SocData NBT to exceed Vanilla's 2 MiB NbtAccounter cap");
        assertThrows(Exception.class, () -> {
            FriendlyByteBuf readCopy = new FriendlyByteBuf(rawFullBuf.duplicate());
            readCopy.readNbt(NbtAccounter.create(VANILLA_CHUNK_NBT_LIMIT));
        });

        // Build oversized terminal output (>60 lines + single 100k-char line) and oversized error string
        List<String> hugeTerminal = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            hugeTerminal.add("line-" + i);
        }
        hugeTerminal.add("Z".repeat(100_000));
        String hugeError = "E".repeat(10_000);

        CompoundTag updateTag = ComputerBlockEntity.buildUpdateTag(
                new CompoundTag(),
                1,
                2,
                1,
                hugeError,
                socData,
                hugeTerminal
        );

        // Verify wire serialization fits comfortably under Vanilla's 2 MiB NbtAccounter limit
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            NbtIo.write(updateTag, dos);
        }
        byte[] nbtBytes = baos.toByteArray();
        assertTrue(nbtBytes.length < 65_536, "Update tag should stay compact (<64 KB), was " + nbtBytes.length);

        CompoundTag decodedViaIo;
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(nbtBytes))) {
            decodedViaIo = NbtIo.read(dis, NbtAccounter.create(VANILLA_CHUNK_NBT_LIMIT));
        }

        FriendlyByteBuf packetBuf = new FriendlyByteBuf(Unpooled.buffer());
        packetBuf.writeNbt(updateTag);
        CompoundTag decodedViaBuf = (CompoundTag) packetBuf.readNbt(NbtAccounter.create(VANILLA_CHUNK_NBT_LIMIT));
        assertNotNull(decodedViaBuf);
        assertEquals(decodedViaIo, decodedViaBuf);

        // Client still receives hardware capabilities, GPU presence, and bounded terminal/error state
        SocData syncedSoc = ComputerBlockEntity.deserializeSocTag(decodedViaBuf.getCompound("SocData"));
        assertEquals(0, syncedSoc.bios().length);
        assertEquals(socData.instructions(), syncedSoc.instructions());
        assertEquals(socData.ramCapacity(), syncedSoc.ramCapacity());
        assertTrue(syncedSoc.hasGpu());
        assertEquals("RTX-Custom", syncedSoc.gpu().archName());

        assertTrue(decodedViaBuf.getString("ErrorMessage").length() <= 1024);
        ListTag termList = decodedViaBuf.getList("TerminalOutput", Tag.TAG_STRING);
        assertEquals(1, termList.size());
        assertEquals(32_768, termList.getString(0).length());
    }

    @Test
    void testBaseMachineUpdateTagOmitsInventoryAndPassesNbtAccounter() {
        CompoundTag tag = BaseMachineBlockEntity.buildUpdateTag(IntTag.valueOf(12500), 120, 200);
        assertFalse(tag.contains("Inventory"));
        assertEquals(12500, tag.getInt("Energy"));
        assertEquals(120, tag.getInt("BurnTime"));
        assertEquals(200, tag.getInt("MaxBurnTime"));

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeNbt(tag);
        assertTrue(buf.readableBytes() < 256);
        CompoundTag decoded = (CompoundTag) buf.readNbt(NbtAccounter.create(VANILLA_CHUNK_NBT_LIMIT));
        assertNotNull(decoded);
        assertEquals(tag, decoded);
    }

    @Test
    void testBoundedTerminalOutputKeepsMostRecentLinesWithinLimits() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            lines.add("entry-" + i);
        }
        ListTag bounded = ComputerBlockEntity.serializeBoundedTerminalOutput(lines);
        assertEquals(60, bounded.size());
        assertEquals("entry-40", bounded.getString(0));
        assertEquals("entry-99", bounded.getString(59));
    }
}
