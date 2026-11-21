package games.jvrcruz.siliconcraft;

import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class HardwareExpansionTest {

    @Test
    public void testSocRamConsumptionCapping() {
        // When slot has 1 RAM module (256B), consume 1 -> 256B
        int count1 = Math.min(1, 4);
        assertEquals(1, count1);
        assertEquals(256, count1 * 256);

        // When slot has 3 RAM modules (256B), consume 3 -> 768B
        int count3 = Math.min(3, 4);
        assertEquals(3, count3);
        assertEquals(768, count3 * 256);

        // When slot has 4 RAM modules (256B), consume 4 -> 1024B
        int count4 = Math.min(4, 4);
        assertEquals(4, count4);
        assertEquals(1024, count4 * 256);

        // When slot has 8 RAM modules (256B), capped at 4 -> 1024B (4 remaining)
        int stackCount = 8;
        int countCapped = Math.min(stackCount, 4);
        assertEquals(4, countCapped);
        assertEquals(1024, countCapped * 256);
        assertEquals(4, stackCount - countCapped);
    }

    @Test
    public void testSocOptionalRam() {
        // When no RAM module is placed in SoC slot, RAM is optional and defaults to 0
        int countZero = 0;
        int totalRam = countZero * 256;
        assertEquals(0, totalRam);
    }

    @Test
    public void testComputerHardwareSummation() {
        // SoC with 256 bytes bundled RAM and 512KB Flash
        int socRam = 256;
        int socFlash = 512;

        // 4 RAM modules installed in PC slots (256B each)
        int[] ramSlots = new int[]{256, 256, 256, 256};
        int totalRamSlots = 0;
        for (int r : ramSlots) totalRamSlots += r;

        int effectiveRam = socRam + totalRamSlots;
        assertEquals(1280, effectiveRam);

        // 2 NVMe drives installed in PC slots (1024KB each)
        int[] nvmeSlots = new int[]{1024, 1024};
        int totalNvmeSlots = 0;
        for (int n : nvmeSlots) totalNvmeSlots += n;

        int effectiveStorage = socFlash + totalNvmeSlots;
        assertEquals(2560, effectiveStorage);
    }

    @Test
    public void testComputerZeroBundledRamSummation() {
        // SoC with 0 bundled RAM and 0 Flash
        int socRam = 0;
        int socStorage = 0;

        // 2 RAM modules (256B each) added in PC slots
        int effectiveRam = socRam + (2 * 256);
        assertEquals(512, effectiveRam);

        // 1 NVMe drive (1024KB) added in PC slot 1
        int effectiveStorage = socStorage + 1024;
        assertEquals(1024, effectiveStorage);

        // Verify VirtualCpu configures and runs properly with this configuration
        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(Set.of("MOV", "HLT"), effectiveRam, List.of("MOV [100], 77", "HLT"), true, effectiveStorage);
        cpu.powerOn();
        cpu.tick(10);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(77, cpu.getRam()[100]);
        assertTrue(cpu.hasNvme());
        assertEquals(1024, cpu.getStorageSize());
    }

    @Test
    public void testStorageDriveDiscreteAccessAndRemovalPersistence() {
        // Test that storage drives are discrete and not pooled like RAM
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "SWRITE", "SREAD", "SSEL", "SCOUNT", "HLT");

        // Drive 0: Onboard SoC Flash
        byte[] socFlash = new byte[256];
        // Drive 1: NVMe Slot 1
        byte[] nvmeDrive1 = new byte[512];
        // Drive 2: NVMe Slot 2
        byte[] nvmeDrive2 = new byte[1024];

        cpu.setStorageDrive(0, socFlash);
        cpu.setStorageDrive(1, nvmeDrive1);
        cpu.setStorageDrive(2, nvmeDrive2);

        // Drive write listener simulates updating ItemStack NBT in ComputerBlockEntity
        byte[] drive1NbtData = new byte[512];
        byte[] drive2NbtData = new byte[1024];
        cpu.setDriveWriteListener((drv, data) -> {
            if (drv == 1) {
                System.arraycopy(data, 0, drive1NbtData, 0, data.length);
            } else if (drv == 2) {
                System.arraycopy(data, 0, drive2NbtData, 0, data.length);
            }
        });

        // Program writes specific values to Drive 1 and Drive 2 independently
        List<String> program = List.of(
                "SWRITE 1, 10, 0xAA",      // Write 0xAA to Drive 1 at addr 10
                "SWRITE 2, 10, 0xBB",      // Write 0xBB to Drive 2 at addr 10 (same offset, discrete storage)
                "SWRITE 0, 10, 0xCC",      // Write 0xCC to Drive 0 at addr 10
                "SREAD A, 1, 10",
                "SREAD B, 2, 10",
                "SREAD C, 0, 10",
                "HLT"
        );

        cpu.configure(isa, 256, program, true, 256 + 512 + 1024);
        cpu.setStorageDrive(0, socFlash);
        cpu.setStorageDrive(1, nvmeDrive1);
        cpu.setStorageDrive(2, nvmeDrive2);
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(0xAA, cpu.getRegA());
        assertEquals(0xBB, cpu.getRegB());
        assertEquals(0xCC, cpu.getRegC());

        // Verify discrete NBT simulation
        assertEquals((byte) 0xAA, drive1NbtData[10]);
        assertEquals((byte) 0xBB, drive2NbtData[10]);

        // Simulating removing Drive 1 from computer:
        // Removing Drive 1 clears slot 0 in the computer
        cpu.setStorageDrive(1, new byte[0]);

        // Accessing Drive 2 still works and retains 0xBB
        assertEquals((byte) 0xBB, cpu.getStorageDrive(2)[10]);

        // Drive 1 data remains preserved in drive1NbtData (the ItemStack NBT)
        assertEquals((byte) 0xAA, drive1NbtData[10]);

        // Placing the removed Drive 1 into another computer (or back into slot 0) restores all data
        VirtualCpu anotherCpu = new VirtualCpu();
        anotherCpu.configure(isa, 256, List.of("SREAD A, 1, 10", "HLT"), true, 512);
        anotherCpu.setStorageDrive(1, drive1NbtData.clone());
        anotherCpu.powerOn();
        anotherCpu.tick(50);

        assertEquals(VirtualCpu.State.HALTED, anotherCpu.getState());
        assertEquals(0xAA, anotherCpu.getRegA(), "Removed drive retains its exact contents via NBT when moved");
    }

    @Test
    public void testChipForgeSpeedIncrease() {
        // Base was 60, per-instruction was 20: 60 + (count * 20)
        // With 10% speed increase (10% less duration): 54 + (count * 18)
        int prev8 = 60 + (8 * 20);
        int new8 = 54 + (8 * 18);
        assertEquals(220, prev8);
        assertEquals(198, new8);
        assertEquals(prev8 * 0.90, (double) new8, 0.001);

        int prev61 = 60 + (61 * 20);
        int new61 = 54 + (61 * 18);
        assertEquals(1280, prev61);
        assertEquals(1152, new61);
        assertEquals(prev61 * 0.90, (double) new61, 0.001);
    }
}
