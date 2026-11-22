package games.jvrcruz.siliconcraft;

import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class VirtualCpuTest {

    @Test
    public void testBasicArithmetic() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "ADD", "SUB", "HLT");
        List<String> code = List.of(
                "MOV A, 10",
                "MOV B, 25",
                "ADD A, B",
                "SUB A, 5",
                "HLT"
        );

        cpu.configure(isa, 256, code, false, 0);
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(30, cpu.getRegA());
        assertEquals(25, cpu.getRegB());
    }

    @Test
    public void testLoopAndFlags() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "INC", "CMP", "JNZ", "HLT");
        List<String> code = List.of(
                "MOV A, 0",
                "loop:",
                "INC A",
                "CMP A, 5",
                "JNZ loop",
                "HLT"
        );

        cpu.configure(isa, 256, code, false, 0);
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(5, cpu.getRegA());
        assertTrue(cpu.isFlagZ());
    }

    @Test
    public void testIllegalInstructionException() {
        VirtualCpu cpu = new VirtualCpu();
        // ISA only has MOV and HLT, lacks ADD!
        Set<String> isa = Set.of("MOV", "HLT");
        List<String> code = List.of(
                "MOV A, 10",
                "ADD A, 5",
                "HLT"
        );

        cpu.configure(isa, 256, code, false, 0);
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.ERROR, cpu.getState());
        assertTrue(cpu.getErrorMessage().contains("ADD"));
    }

    @Test
    public void testMemoryAccess() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "ADD", "HLT");
        List<String> code = List.of(
                "MOV [10], 42",
                "MOV A, [10]",
                "ADD A, 8",
                "MOV [11], A",
                "HLT"
        );

        cpu.configure(isa, 256, code, false, 0);
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(50, cpu.getRegA());
        assertEquals(42, cpu.getRam()[10]);
        assertEquals(50, cpu.getRam()[11]);
    }

    @Test
    public void testImmediateOutputDuringRunning() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "OUTC", "SLEEP", "HLT");
        List<String> code = List.of(
                "MOV A, 'H'",
                "OUTC A",
                "MOV A, 'i'",
                "OUTC A",
                "MOV A, '\\n'",
                "OUTC A",
                "SLEEP 1",
                "HLT"
        );

        cpu.configure(isa, 256, code, false, 0);
        cpu.powerOn();

        // Run until sleep (6 instructions: MOV, OUTC, MOV, OUTC, MOV, OUTC)
        for (int i = 0; i < 6; i++) {
            cpu.step();
        }

        // CPU is RUNNING (not halted yet)
        assertEquals(VirtualCpu.State.RUNNING, cpu.getState());
        List<String> output = cpu.getTerminalOutput();
        assertTrue(output.contains("Hi"), "Terminal output must contain 'Hi' immediately during RUNNING: " + output);
    }

    @Test
    public void testPrintWithoutNewlineImmediateVisibility() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "OUTC", "HLT");
        List<String> code = List.of(
                "MOV A, 'J'",
                "OUTC A",
                "MOV A, 'T'",
                "OUTC A",
                "MOV A, 'e'",
                "OUTC A",
                "MOV A, 'c'",
                "OUTC A",
                "MOV A, 'h'",
                "OUTC A"
        );

        cpu.configure(isa, 256, code, false, 0);
        cpu.powerOn();

        // Step 4 times ('J', 'T')
        for (int i = 0; i < 4; i++) {
            cpu.step();
        }

        // Even without newline, "JT" must be returned immediately in getTerminalOutput
        List<String> output = cpu.getTerminalOutput();
        assertEquals(1, output.size());
        assertEquals("JT", output.get(0));
    }

    @Test
    public void testSetTerminalOutput() {
        VirtualCpu cpu = new VirtualCpu();
        cpu.setTerminalOutput(List.of("Line 1", "Line 2"));
        assertEquals(List.of("Line 1", "Line 2"), cpu.getTerminalOutput());

        cpu.setTerminalOutput(List.of("Replaced"));
        assertEquals(List.of("Replaced"), cpu.getTerminalOutput());
    }

    @Test
    public void testDynamicHardwareUpdate() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "HLT");
        List<String> code = List.of(
                "MOV [10], 42",
                "MOV [300], 99",
                "HLT"
        );

        cpu.configure(isa, 256, code, false, 0);
        assertFalse(cpu.hasNvme());
        assertEquals(0, cpu.getStorageSize());
        assertEquals(256, cpu.getRam().length);

        cpu.powerOn();
        cpu.step(); // executes MOV [10], 42
        assertEquals(42, cpu.getRam()[10]);

        // Dynamically add RAM modules and NVMe storage
        cpu.updateHardware(1024, true, 2048);
        assertTrue(cpu.hasNvme());
        assertEquals(2048, cpu.getStorageSize());
        assertEquals(1024, cpu.getRam().length);
        // Existing memory at address 10 must be preserved!
        assertEquals(42, cpu.getRam()[10]);

        // Continue execution with enlarged RAM: writes to address 300
        cpu.step(); // executes MOV [300], 99
        assertEquals(99, cpu.getRam()[300]);
        cpu.step(); // HLT
        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
    }

    @Test
    public void testStorageInstructions() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "SSIZE", "SREAD", "SWRITE", "SBLKR", "SBLKW", "HLT");
        List<String> code = List.of(
                "SSIZE A",                  // Query storage size into A
                "SWRITE 10, 77",            // Write byte 77 into storage[10]
                "SREAD B, 10",              // Read byte from storage[10] into B
                "MOV [0], 10",              // RAM[0] = 10
                "MOV [1], 20",              // RAM[1] = 20
                "MOV [2], 30",              // RAM[2] = 30
                "SBLKW 100, 0, 3",          // Write RAM[0..2] to storage[100..102]
                "SBLKR 50, 100, 3",         // Read storage[100..102] into RAM[50..52]
                "HLT"
        );

        // Configure with 512KB storage = 524,288 bytes
        cpu.configure(isa, 256, code, false, 512);
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(524288, cpu.getRegA(), "Storage size in bytes should be 512 * 1024");
        assertEquals(77, cpu.getRegB(), "Byte at storage[10] should be 77");
        assertEquals(77, cpu.getStorage()[10]);

        assertEquals(10, cpu.getRam()[50]);
        assertEquals(20, cpu.getRam()[51]);
        assertEquals(30, cpu.getRam()[52]);

        // Reset CPU: verify storage is non-volatile and persists!
        cpu.reset();
        assertEquals(10, cpu.getRam()[50], "RAM must NOT be wiped on reset");
        assertEquals(20, cpu.getRam()[51], "RAM must NOT be wiped on reset");
        assertEquals(30, cpu.getRam()[52], "RAM must NOT be wiped on reset");
        assertEquals(77, cpu.getStorage()[10], "Storage must NOT be wiped on reset");
    }

    @Test
    public void testFramebufferInstructions() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of(
                "MOV", "FBMODE", "FBSET", "FBGET", "FBCLR", "FBRECT", "FBLINE", "FBLIT", "FBSWAP", "FBWIDTH", "FBHEIGHT", "HLT"
        );
        List<String> code = List.of(
                "FBWIDTH A",
                "FBHEIGHT B",
                "FBMODE 1",
                "FBCLR 0x112233",
                "FBSET 10, 20, 0xFF00FF",
                "FBGET C, 10, 20",
                "FBRECT 2, 2, 4, 3, 0x00FF00",
                "FBLINE 0, 0, 5, 0, 0xFF0000",
                "MOV [10], 0x123456",
                "FBLIT 50, 50, 10, 1, 1",
                "FBSWAP",
                "HLT"
        );

        cpu.configure(isa, 256, code, false, 0);
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(VirtualCpu.FB_WIDTH, cpu.getRegA());
        assertEquals(VirtualCpu.FB_HEIGHT, cpu.getRegB());
        assertEquals(1, cpu.getDisplayMode());
        assertEquals(0xFFFF00FF, cpu.getRegC());

        int[] fb = cpu.getFramebuffer();
        assertEquals(0xFFFF00FF, fb[20 * VirtualCpu.FB_WIDTH + 10]);
        assertEquals(0xFF00FF00, fb[2 * VirtualCpu.FB_WIDTH + 2]);
        assertEquals(0xFFFF0000, fb[0 * VirtualCpu.FB_WIDTH + 3]);
        assertEquals(0xFF123456, fb[50 * VirtualCpu.FB_WIDTH + 50]);
        assertTrue(cpu.isFbDirty());
    }

    @Test
    public void testAudioInstructions() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of(
                "MOV", "APUSH", "ABUFW", "ACLR", "ASTAT", "ATONE", "APLAY", "HLT"
        );
        List<String> code = List.of(
                "APUSH 128",
                "APUSH 200",
                "MOV [0], 100",
                "MOV [1], 150",
                "ABUFW 0, 2",
                "ASTAT A",
                "APLAY",
                "HLT"
        );

        cpu.configure(isa, 256, code, false, 0);
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(4, cpu.getRegA());

        byte[] samples = cpu.pollAudioSamples();
        assertNotNull(samples);
        assertEquals(4, samples.length);
        assertEquals((byte) 128, samples[0]);
        assertEquals((byte) 200, samples[1]);
        assertEquals((byte) 100, samples[2]);
        assertEquals((byte) 150, samples[3]);

        // Tone test with silence/pause (freq = 0) and long capacity
        List<String> toneCode = List.of(
                "ATONE 440, 50",   // 50ms tone = 400 samples
                "ATONE 0, 50",     // 50ms silence (value 128) = 400 samples
                "ASTAT B",
                "HLT"
        );
        cpu.configure(isa, 256, toneCode, false, 0);
        cpu.powerOn();
        cpu.tick(100);
        assertEquals(800, cpu.getRegB(), "Tone + pause should populate 800 audio buffer samples");
        byte[] toneSamples = cpu.pollAudioSamples();
        assertNotNull(toneSamples);
        assertEquals(800, toneSamples.length);
        // Verify tone samples are wave
        boolean hasHigh = false;
        boolean hasLow = false;
        for (int i = 0; i < 400; i++) {
            if (toneSamples[i] == (byte) 200) hasHigh = true;
            if (toneSamples[i] == (byte) 50) hasLow = true;
        }
        assertTrue(hasHigh && hasLow, "First 400 samples should be square wave");
        // Verify pause samples are silence (128)
        for (int i = 400; i < 800; i++) {
            assertEquals((byte) 128, toneSamples[i], "Pause samples must be PCM silence (128)");
        }
    }

    @Test
    public void testMultiDriveAccessAndSeparation() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of(
                "MOV", "SSIZE", "SREAD", "SWRITE", "SBLKR", "SBLKW",
                "SCOUNT", "SSEL", "SDRV", "HLT"
        );
        List<String> code = List.of(
                "SCOUNT A",                 // A should be 3 (drives 0, 1, 2)
                "SSEL 1",                   // Select drive 1
                "SDRV B",                   // B should be 1
                "SWRITE 0, 42",             // Write 42 to drive 1 at addr 0 (via selected drive)
                "SWRITE 2, 0, 84",          // Write 84 to drive 2 at addr 0 (via drive operand)
                "SWRITE 0, 0, 21",          // Write 21 to drive 0 at addr 0 (via drive operand)
                "SREAD C, 1, 0",            // Read from drive 1 at addr 0 -> C should be 42
                "SREAD D, 2, 0",            // Read from drive 2 at addr 0 -> D should be 84
                "HLT"
        );

        cpu.configure(isa, 256, code, false, 0);

        // Setup 3 separate drives
        byte[] drv0 = new byte[64];
        byte[] drv1 = new byte[128];
        byte[] drv2 = new byte[256];
        cpu.setStorageDrive(0, drv0);
        cpu.setStorageDrive(1, drv1);
        cpu.setStorageDrive(2, drv2);

        java.util.Map<Integer, Integer> writeEvents = new java.util.HashMap<>();
        cpu.setDriveWriteListener((drv, data) -> writeEvents.put(drv, writeEvents.getOrDefault(drv, 0) + 1));

        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(3, cpu.getRegA()); // count
        assertEquals(1, cpu.getRegB()); // selected drive
        assertEquals(42, cpu.getRegC()); // drive 1 read
        assertEquals(84, cpu.getRegD()); // drive 2 read

        // Verify independent drive memory contents
        assertEquals((byte) 21, cpu.getStorageDrive(0)[0]);
        assertEquals((byte) 42, cpu.getStorageDrive(1)[0]);
        assertEquals((byte) 84, cpu.getStorageDrive(2)[0]);

        // Verify listeners fired for all 3 drives
        assertEquals(1, writeEvents.get(0));
        assertEquals(1, writeEvents.get(1));
        assertEquals(1, writeEvents.get(2));
    }

    @Test
    public void testUnhandledDivideByZeroException() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "DIV", "HLT");
        List<String> code = List.of(
                "MOV A, 100",
                "MOV B, 0",
                "DIV A, B",
                "HLT"
        );

        cpu.configure(isa, 256, code, false, 0);
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.ERROR, cpu.getState());
        assertTrue(cpu.getErrorMessage().contains("Exception not handled: Division by zero"),
                "Error should show unhandled divide by zero: " + cpu.getErrorMessage());
        assertTrue(cpu.getTerminalOutput().stream().anyMatch(line -> line.contains("Exception not handled: Division by zero")),
                "Terminal should contain unhandled exception message");
    }

    @Test
    public void testHandledDivideByZeroException() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "DIV", "IVEC", "IRET", "HLT");
        List<String> code = List.of(
                "IVEC 0, div_handler",
                "MOV A, 100",
                "MOV B, 0",
                "DIV A, B",
                "MOV C, 42",
                "HLT",
                "div_handler:",
                "MOV A, 999",
                "IRET"
        );

        cpu.configure(isa, 256, code, false, 0);
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(999, cpu.getRegA(), "Handler should have set A to 999");
        assertEquals(42, cpu.getRegC(), "Execution should resume after faulting instruction");
    }

    @Test
    public void testUnhandledOutOfBoundsException() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "HLT");
        List<String> code = List.of(
                "MOV A, [500]",
                "HLT"
        );

        cpu.configure(isa, 64, code, false, 0);
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.ERROR, cpu.getState());
        assertTrue(cpu.getErrorMessage().contains("Exception not handled: RAM address 500 out of bounds"),
                "Error should show out of bounds: " + cpu.getErrorMessage());
    }

    @Test
    public void testKeyboardInterruptHandling() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "CMP", "JZ", "IVEC", "IRET", "HLT");
        List<String> code = List.of(
                "IVEC 10, key_handler",
                "MOV [0], 0",
                "wait_key:",
                "CMP [0], 0",
                "JZ wait_key",
                "HLT",
                "key_handler:",
                "MOV [1], A", // A contains key code
                "MOV [0], 1",
                "IRET"
        );

        cpu.configure(isa, 256, code, false, 0);
        cpu.powerOn();
        cpu.tick(50); // Run until looping in wait_key

        assertEquals(VirtualCpu.State.RUNNING, cpu.getState());
        assertEquals(0, cpu.getRam()[0]);

        // Send keyboard input 'Z' (ASCII 90)
        cpu.queueKeyboardInterrupt(90);
        cpu.tick(50); // Process interrupt and continue

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(1, cpu.getRam()[0]);
        assertEquals(90, cpu.getRam()[1], "RAM[1] should contain received key code 90");
    }

    @Test
    public void testMouseInterrupts() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "CMP", "JNZ", "IVEC", "IRET", "HLT");
        List<String> code = List.of(
                "IVEC 11, move_handler",
                "IVEC 12, click_handler",
                "wait_input:",
                "CMP [0], 2",
                "JNZ wait_input",
                "HLT",
                "move_handler:",
                "MOV [10], A", // mouse X
                "MOV [11], B", // mouse Y
                "MOV [0], 1",
                "IRET",
                "click_handler:",
                "MOV [20], A", // mouse X
                "MOV [21], B", // mouse Y
                "MOV [22], C", // button
                "MOV [0], 2",
                "IRET"
        );

        cpu.configure(isa, 256, code, false, 0);
        cpu.powerOn();
        cpu.tick(20);

        // Send mouse move to (120, 45)
        cpu.queueMouseMoveInterrupt(120, 45);
        cpu.tick(20);
        assertEquals(1, cpu.getRam()[0]);
        assertEquals(120, cpu.getRam()[10]);
        assertEquals(45, cpu.getRam()[11]);

        // Send mouse click at (150, 60) button 1 (right click)
        cpu.queueMouseClickInterrupt(150, 60, 1);
        cpu.tick(50);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(2, cpu.getRam()[0]);
        assertEquals(150, cpu.getRam()[20]);
        assertEquals(60, cpu.getRam()[21]);
        assertEquals(1, cpu.getRam()[22]);
    }

    @Test
    public void testCliAndStiInterruptMasking() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "CMP", "JZ", "JMP", "CLI", "STI", "IVEC", "IRET", "HLT");
        List<String> code = List.of(
                "IVEC 10, key_handler",
                "CLI",           // Disable interrupts
                "MOV [0], 0",
                "loop1:",
                "CMP [0], 1",
                "JZ loop1_exit",
                "CMP [2], 5",
                "JZ enable_ints",
                "MOV A, [2]",
                "MOV B, 1",
                "MOV C, 0",
                "MOV [2], 5",
                "JMP loop1",
                "enable_ints:",
                "STI",           // Re-enable interrupts
                "JMP loop1",
                "loop1_exit:",
                "HLT",
                "key_handler:",
                "MOV [0], 1",
                "IRET"
        );

        cpu.configure(isa, 256, code, false, 0);
        cpu.powerOn();

        // Run until CLI has executed
        cpu.tick(3);
        assertFalse(cpu.isInterruptsEnabled(), "Interrupts should be disabled after CLI");

        // While CLI is active, queue an interrupt
        cpu.queueKeyboardInterrupt(75);
        cpu.tick(5); // Loop in loop1; interrupt must not fire
        assertEquals(0, cpu.getRam()[0], "Interrupt should not fire while CLI is active");

        // Step further so STI executes and interrupt gets serviced
        cpu.tick(50);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(1, cpu.getRam()[0], "Interrupt should fire after STI");
    }

    @Test
    public void testSoftwareInterruptInstruction() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "INT", "IVEC", "IRET", "HLT");
        List<String> code = List.of(
                "IVEC 15, custom_sw_handler",
                "INT 15",
                "MOV B, 100",
                "HLT",
                "custom_sw_handler:",
                "MOV A, 777",
                "IRET"
        );

        cpu.configure(isa, 256, code, false, 0);
        cpu.powerOn();
        cpu.tick(50);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(777, cpu.getRegA());
        assertEquals(100, cpu.getRegB());
    }

    @Test
    public void testExample6GraphicsDemo() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("FBMODE", "FBCLR", "FBRECT", "FBLINE", "FBSET", "FBSWAP", "JMP");
        List<String> code = List.of(
                "FBMODE 1",
                "FBCLR 0xFF181824",
                "FBRECT 10, 10, 220, 16, 0xFF2A4494",
                "FBLINE 0, 0, 239, 134, 0xFF44AA44",
                "FBLINE 0, 134, 239, 0, 0xFF44AA44",
                "FBRECT 110, 57, 20, 20, 0xFFFFCC00",
                "FBSET 120, 67, 0xFFFFFFFF",
                "FBSWAP",
                "loop:",
                "JMP loop"
        );

        cpu.configure(isa, 256, code, false, 0);
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.RUNNING, cpu.getState());
        assertEquals(1, cpu.getDisplayMode());
        assertTrue(cpu.getErrorMessage().isEmpty());
        // Verify center pixel
        assertEquals(0xFFFFFFFF, cpu.getFramebuffer()[67 * VirtualCpu.FB_WIDTH + 120]);
        // Verify line pixel
        assertEquals(0xFF44AA44, cpu.getFramebuffer()[0]);
    }

    @Test
    public void testSExecBurnedRomProgram() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "ADD", "OUT", "SEXEC", "HLT");

        // Bootloader in ROM
        List<String> bootloader = List.of(
                "MOV A, 10",
                "MOV B, 20",
                "SEXEC 1",
                "HLT"
        );

        // Target OS code burned to Drive 1
        List<String> osKernel = List.of(
                "ADD A, B",
                "OUT A",
                "HLT"
        );

        cpu.configure(isa, 256, bootloader, false, 0);
        cpu.setStorageDrive(1, new byte[512]);
        cpu.setDriveProgram(1, osKernel);

        cpu.powerOn();
        cpu.tick(50);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(30, cpu.getRegA(), "Target program should compute A = 10 + 20");
        assertEquals(1, cpu.getSelectedDrive());
        assertTrue(cpu.getTerminalOutput().contains("30"));
    }

    @Test
    public void testSExecBinaryStorageUtf8() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "INC", "SEXEC", "HLT");

        List<String> bootloader = List.of(
                "MOV C, 99",
                "SEXEC 0",
                "HLT"
        );

        cpu.configure(isa, 256, bootloader, false, 0);

        // Write UTF-8 assembly program into Drive 0's binary storage
        String programText = "INC C\nHLT\n";
        byte[] bytes = programText.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] driveData = new byte[256];
        System.arraycopy(bytes, 0, driveData, 0, bytes.length);
        cpu.setStorageDrive(0, driveData);

        cpu.powerOn();
        cpu.tick(50);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(100, cpu.getRegC(), "Program loaded from UTF-8 binary storage should increment C from 99 to 100");
    }

    @Test
    public void testLargeRamAnd1024MBStorageDrive() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "SSIZE", "SREAD", "SWRITE", "SBLKR", "SBLKW", "SSEL", "HLT");
        List<String> code = List.of(
                "SSEL 1",
                "SSIZE A",                       // Should return 1024 MB = 1,073,741,824 bytes
                "SREAD B, 500000",               // Unwritten sector: returns 0
                "SWRITE 500000, 85",             // Write 85 (0x55) at address 500,000
                "SREAD C, 500000",               // Read back: should be 85
                "MOV [1000000], 42",             // Write to 1 MB RAM address
                "SBLKW 1, 600000, 1000000, 1",   // Write RAM[1000000] to storage drive 1 at 600,000
                "SBLKR 2000000, 1, 600000, 1",   // Read from drive 1 at 600,000 to RAM[2000000]
                "HLT"
        );

        // 1 module of 1024 KB = 1048576 bytes
        cpu.configure(isa, 1024 * 1024, code, true, 0);
        assertEquals(1048576, cpu.getRam().length, "RAM should be 1024 KB = 1048576 bytes");

        // Dynamically upgrade to 4 modules = 4096 KB = 4194304 bytes
        cpu.updateHardware(4 * 1024 * 1024, true, 1024 * 1024);
        assertEquals(4194304, cpu.getRam().length, "RAM should be 4096 KB = 4194304 bytes");

        // Drive 1: 1024 MB storage drive
        int drive1CapBytes = 1024 * 1024 * 1024;
        cpu.setDriveCapacity(1, drive1CapBytes);
        cpu.setStorageDrive(1, new byte[0]);

        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(drive1CapBytes, cpu.getRegA(), "SSIZE should report 1024 MB = 1,073,741,824 bytes");
        assertEquals(0, cpu.getRegB(), "Unwritten sector should read 0");
        assertEquals(85, cpu.getRegC(), "Written sector should read 85");
        assertEquals(42, cpu.getRam()[1000000], "RAM[1000000] should be 42");
        assertEquals(42, cpu.getRam()[2000000], "RAM[2000000] should be 42 via SBLKR");
    }

    @Test
    void testAudioStoppedOnPowerOff() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("ATONE", "APLAY", "HLT");
        List<String> code = List.of(
                "ATONE 440, 500",
                "APLAY",
                "HLT"
        );

        cpu.configure(isa, 1024 * 1024, code, true, 0);
        cpu.powerOn();
        cpu.tick(10);

        // Powering off must clear any pending or playing audio
        cpu.powerOff();

        assertEquals(VirtualCpu.State.OFF, cpu.getState());
        assertNull(cpu.pollAudioSamples(), "Audio queue must be empty when powered off");
    }

    @Test
    void testResetInterruptAndRamPreservation() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "IVEC", "IRET", "HLT");
        List<String> code = List.of(
                "IVEC RESET, on_reset",
                "MOV [100], 42",
                "HLT",
                "on_reset:",
                "MOV A, [100]",
                "MOV [101], A",
                "IRET"
        );

        cpu.configure(isa, 1024, code, false, 0);
        cpu.powerOn();
        cpu.tick(20);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(42, cpu.getRam()[100]);
        assertEquals(0, cpu.getRam()[101]);

        // Reset CPU
        cpu.reset();

        // RAM must still hold 42 at address 100!
        assertEquals(42, cpu.getRam()[100], "RAM must persist across reset");

        // Next ticks should trigger the reset interrupt handler
        cpu.tick(20);

        // on_reset handler must have read [100] (42) and stored it into [101]
        assertEquals(42, cpu.getRam()[101], "Reset handler should run right after reset and see previous RAM contents");
    }
}
