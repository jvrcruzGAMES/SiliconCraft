package games.jvrcruz.siliconcraft;

import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates that all assembly examples presented in docs/examples.md
 * compile correctly, match their documented required CPU ISA instructions,
 * and execute with expected outcomes on the VirtualCpu.
 */
public class DocumentationExamplesTest {

    @Test
    public void testExample1Countdown() {
        Set<String> documentedIsa = Set.of("OUT", "MOV", "DEC", "JNZ", "HLT");
        List<String> code = List.of(
                "OUT \"==========================\"",
                "OUT \"    COUNTDOWN DEMO       \"",
                "OUT \"==========================\"",
                "MOV C, 5",
                "countdown:",
                "    OUT C",
                "    DEC C",
                "    JNZ countdown",
                "OUT \"Liftoff! SiliconCraft online.\"",
                "HLT"
        );

        // Verify required instructions match
        Set<String> required = InstructionValidator.getRequiredInstructions(code);
        List<String> missing = InstructionValidator.getMissingInstructions(required, new ArrayList<>(documentedIsa));
        assertTrue(missing.isEmpty(), "Missing instructions in documented ISA: " + missing);

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(documentedIsa, 256, code, false, 0);
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(0, cpu.getRegC());

        String terminal = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminal.contains("COUNTDOWN DEMO"));
        assertTrue(terminal.contains("5"));
        assertTrue(terminal.contains("1"));
        assertTrue(terminal.contains("Liftoff! SiliconCraft online."));
    }

    @Test
    public void testExample2Fibonacci() {
        Set<String> documentedIsa = Set.of("OUT", "MOV", "ADD", "INC", "DEC", "JNZ", "HLT");
        List<String> code = List.of(
                "OUT \"Computing Fibonacci Series...\"",
                "MOV A, 0",
                "MOV B, 1",
                "MOV D, 0",
                "MOV [D], A",
                "INC D",
                "MOV [D], B",
                "INC D",
                "MOV C, 6",
                "fib_loop:",
                "    MOV [D], A",
                "    ADD [D], B",
                "    OUT [D]",
                "    MOV A, B",
                "    MOV B, [D]",
                "    INC D",
                "    DEC C",
                "    JNZ fib_loop",
                "OUT \"Fibonacci computation complete!\"",
                "HLT"
        );

        Set<String> required = InstructionValidator.getRequiredInstructions(code);
        List<String> missing = InstructionValidator.getMissingInstructions(required, new ArrayList<>(documentedIsa));
        assertTrue(missing.isEmpty(), "Missing instructions in documented ISA: " + missing);

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(documentedIsa, 256, code, false, 0);
        cpu.powerOn();
        cpu.tick(200);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        int[] ram = cpu.getRam();
        // F(0)=0, F(1)=1, F(2)=1, F(3)=2, F(4)=3, F(5)=5, F(6)=8, F(7)=13
        assertEquals(0, ram[0]);
        assertEquals(1, ram[1]);
        assertEquals(1, ram[2]);
        assertEquals(2, ram[3]);
        assertEquals(3, ram[4]);
        assertEquals(5, ram[5]);
        assertEquals(8, ram[6]);
        assertEquals(13, ram[7]);

        String terminal = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminal.contains("Computing Fibonacci Series..."));
        assertTrue(terminal.contains("Fibonacci computation complete!"));
    }

    @Test
    public void testExample3Multiplication() {
        Set<String> documentedIsa = Set.of("OUT", "MOV", "ADD", "DEC", "CMP", "JZ", "JNZ", "HLT");
        List<String> code = List.of(
                "OUT \"Multiplication Routine\"",
                "MOV A, 12",
                "MOV B, 7",
                "MOV C, 0",
                "CMP B, 0",
                "JZ done",
                "mult_loop:",
                "    ADD C, A",
                "    DEC B",
                "    JNZ mult_loop",
                "done:",
                "    OUT \"Result (12 * 7):\"",
                "    OUT C",
                "    HLT"
        );

        Set<String> required = InstructionValidator.getRequiredInstructions(code);
        List<String> missing = InstructionValidator.getMissingInstructions(required, new ArrayList<>(documentedIsa));
        assertTrue(missing.isEmpty(), "Missing instructions in documented ISA: " + missing);

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(documentedIsa, 256, code, false, 0);
        cpu.powerOn();
        cpu.tick(150);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(84, cpu.getRegC());

        String terminal = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminal.contains("84"));
    }

    @Test
    public void testExample4ArraySum() {
        Set<String> documentedIsa = Set.of("OUT", "MOV", "ADD", "INC", "DEC", "JNZ", "HLT");
        List<String> code = List.of(
                "OUT \"Initializing array in RAM...\"",
                "MOV [0], 10",
                "MOV [1], 20",
                "MOV [2], 30",
                "MOV [3], 40",
                "MOV [4], 50",
                "MOV D, 0",
                "MOV C, 5",
                "MOV A, 0",
                "sum_loop:",
                "    ADD A, [D]",
                "    INC D",
                "    DEC C",
                "    JNZ sum_loop",
                "OUT \"Array Sum:\"",
                "OUT A",
                "HLT"
        );

        Set<String> required = InstructionValidator.getRequiredInstructions(code);
        List<String> missing = InstructionValidator.getMissingInstructions(required, new ArrayList<>(documentedIsa));
        assertTrue(missing.isEmpty(), "Missing instructions in documented ISA: " + missing);

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(documentedIsa, 256, code, false, 0);
        cpu.powerOn();
        cpu.tick(150);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(150, cpu.getRegA());

        String terminal = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminal.contains("150"));
    }

    @Test
    public void testExample5BiosBoot() {
        Set<String> documentedIsa = Set.of("OUT", "MOV", "INC", "CMP", "JNZ", "HLT");
        List<String> code = List.of(
                "OUT \"[POST] Silicon BIOS 1.0\"",
                "OUT \"[POST] Checking CPU Registers...\"",
                "MOV A, 0xAA",
                "MOV B, 0x55",
                "OUT \"Reg A Test OK\"",
                "OUT \"Reg B Test OK\"",
                "OUT \"[POST] Testing RAM Banks...\"",
                "MOV [0], 0xFF",
                "CMP [0], 0xFF",
                "JNZ memory_error",
                "OUT \"RAM Bank 0 Passed.\"",
                "OUT \"[SYSTEM] Loading OS Kernel...\"",
                "MOV C, 0",
                "kernel_load:",
                "    INC C",
                "    CMP C, 3",
                "    JNZ kernel_load",
                "OUT \"[SYSTEM] SiliconCraft Personal Computer Ready.\"",
                "HLT",
                "memory_error:",
                "    OUT \"[FATAL] Memory verification failed!\"",
                "    HLT"
        );

        Set<String> required = InstructionValidator.getRequiredInstructions(code);
        List<String> missing = InstructionValidator.getMissingInstructions(required, new ArrayList<>(documentedIsa));
        assertTrue(missing.isEmpty(), "Missing instructions in documented ISA: " + missing);

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(documentedIsa, 256, code, false, 0);
        cpu.powerOn();
        cpu.tick(150);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(0xAA, cpu.getRegA());
        assertEquals(0x55, cpu.getRegB());
        assertEquals(0xFF, cpu.getRam()[0]);
        assertEquals(3, cpu.getRegC());

        String terminal = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminal.contains("[POST] Silicon BIOS 1.0"));
        assertTrue(terminal.contains("RAM Bank 0 Passed."));
        assertTrue(terminal.contains("SiliconCraft Personal Computer Ready."));
    }

    @Test
    public void testExample6GraphicsDemo() {
        Set<String> documentedIsa = Set.of("FBMODE", "FBCLR", "FBRECT", "FBLINE", "FBSET", "FBSWAP", "JMP");
        List<String> code = List.of(
                "FBMODE 1",
                "FBCLR 0xFF181824",
                "FBRECT 10, 10, 220, 16, 0xFF2A4494",
                "FBLINE 0, 0, 239, 134, 0xFF44AA44",
                "FBLINE 0, 134, 239, 0, 0xFF44AA44",
                "FBRECT 110, 57, 20, 20, 0xFFFFCC00",
                "FBSET 120, 67, 0xFFFFFFFF",
                "FBSWAP",
                "idle:",
                "    JMP idle"
        );

        Set<String> required = InstructionValidator.getRequiredInstructions(code);
        List<String> missing = InstructionValidator.getMissingInstructions(required, new ArrayList<>(documentedIsa));
        assertTrue(missing.isEmpty(), "Missing instructions in documented ISA: " + missing);

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(documentedIsa, 256, code, false, 0);
        cpu.powerOn();
        cpu.tick(50);

        // CPU is running in the idle loop
        assertEquals(VirtualCpu.State.RUNNING, cpu.getState());
        assertEquals(1, cpu.getDisplayMode(), "Display mode must be set to 1 (Graphics)");
        int[] fb = cpu.getFramebuffer();
        // Background color (at 5, 50 away from lines, banner, and center box)
        assertEquals(0xFF181824, fb[50 * VirtualCpu.FB_WIDTH + 5]);
        // Title banner
        assertEquals(0xFF2A4494, fb[12 * VirtualCpu.FB_WIDTH + 50]);
        // Crosshair line
        assertEquals(0xFF44AA44, fb[0]);
        // Gold rectangle
        assertEquals(0xFFFFCC00, fb[60 * VirtualCpu.FB_WIDTH + 115]);
        // Single white pixel
        assertEquals(0xFFFFFFFF, fb[67 * VirtualCpu.FB_WIDTH + 120]);
    }

    @Test
    public void testExample7AudioTune() {
        Set<String> documentedIsa = Set.of("OUT", "ATONE", "APLAY", "HLT");
        List<String> code = List.of(
                "OUT \"Synthesizing chime chord...\"",
                "ATONE 440, 8",
                "ATONE 554, 8",
                "ATONE 659, 8",
                "ATONE 880, 16",
                "APLAY",
                "OUT \"Playback dispatched to speaker!\"",
                "HLT"
        );

        Set<String> required = InstructionValidator.getRequiredInstructions(code);
        List<String> missing = InstructionValidator.getMissingInstructions(required, new ArrayList<>(documentedIsa));
        assertTrue(missing.isEmpty(), "Missing instructions in documented ISA: " + missing);

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(documentedIsa, 256, code, false, 0);
        cpu.powerOn();
        cpu.tick(50);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        byte[] audio = cpu.pollAudioSamples();
        assertNotNull(audio, "Audio buffer should contain generated PCM samples");
        assertTrue(audio.length > 0, "Audio sample count should be positive");

        String terminal = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminal.contains("Synthesizing chime chord..."));
        assertTrue(terminal.contains("Playback dispatched to speaker!"));
    }

    @Test
    public void testExample8InterruptAndException() {
        Set<String> documentedIsa = Set.of("OUT", "OUTC", "MOV", "DIV", "IVEC", "IRET", "STI", "JMP");
        List<String> code = List.of(
                "IVEC 0, handle_div_zero",
                "IVEC 10, handle_key",
                "STI",
                "OUT \"Testing exception handler...\"",
                "MOV A, 100",
                "MOV B, 0",
                "DIV A, B",
                "OUT \"Division result after recovery: \", 0",
                "OUT A",
                "OUT \"Now waiting for keyboard input...\"",
                "idle_loop:",
                "    JMP idle_loop",
                "handle_div_zero:",
                "    OUT \"[INTERRUPT] Caught DIV_ZERO exception!\"",
                "    MOV B, 2",
                "    MOV A, 50",
                "    IRET",
                "handle_key:",
                "    OUT \"Key pressed: \", 0",
                "    OUTC A",
                "    OUTC 10",
                "    IRET"
        );

        Set<String> required = InstructionValidator.getRequiredInstructions(code);
        List<String> missing = InstructionValidator.getMissingInstructions(required, new ArrayList<>(documentedIsa));
        assertTrue(missing.isEmpty(), "Missing instructions in documented ISA: " + missing);

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(documentedIsa, 256, code, false, 0);
        cpu.powerOn();

        // Tick to let exception 0 trigger and recover
        cpu.tick(20);
        assertEquals(VirtualCpu.State.RUNNING, cpu.getState());
        assertEquals(50, cpu.getRegA());

        String terminalBeforeKey = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminalBeforeKey.contains("Caught DIV_ZERO exception!"));
        assertTrue(terminalBeforeKey.contains("Division result after recovery: 50"));

        // Queue interactive keyboard event 'K' (ASCII 75)
        cpu.queueKeyboardInterrupt(75);
        cpu.tick(10);

        String terminalAfterKey = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminalAfterKey.contains("Key pressed: K"));
    }

    @Test
    public void testExample9StoragePersistence() {
        Set<String> documentedIsa = Set.of("OUT", "OUTC", "CMP", "JZ", "SCOUNT", "SSEL", "SWRITE", "SREAD", "HLT");
        List<String> code = List.of(
                "SCOUNT A",
                "CMP A, 0",
                "JZ no_drive",
                "OUT \"Found installed Storage Drive(s): \", 0",
                "OUT A",
                "SSEL 0",
                "SWRITE 0, 0x00, 0x53",
                "SWRITE 0, 0x01, 0x43",
                "SREAD B, 0, 0x00",
                "SREAD C, 0, 0x01",
                "OUT \"Read magic signature: \", 0",
                "OUTC B",
                "OUTC C",
                "OUTC 10",
                "OUT \"Data persisted successfully.\"",
                "HLT",
                "no_drive:",
                "    OUT \"[ERROR] No storage drive inserted!\"",
                "    HLT"
        );

        Set<String> required = InstructionValidator.getRequiredInstructions(code);
        List<String> missing = InstructionValidator.getMissingInstructions(required, new ArrayList<>(documentedIsa));
        assertTrue(missing.isEmpty(), "Missing instructions in documented ISA: " + missing);

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(documentedIsa, 256, code, false, 0);
        byte[] driveData = new byte[256];
        cpu.setStorageDrive(0, driveData);
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        // Verify bytes written to storage drive
        assertEquals(0x53, driveData[0]); // 'S'
        assertEquals(0x43, driveData[1]); // 'C'
        assertEquals(0x53, cpu.getRegB());
        assertEquals(0x43, cpu.getRegC());

        String terminal = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminal.contains("Found installed Storage Drive(s): 1"));
        assertTrue(terminal.contains("Read magic signature: SC"));
        assertTrue(terminal.contains("Data persisted successfully."));
    }

    @Test
    public void testExample10StorageDriveChainload() {
        Set<String> documentedIsa = Set.of("OUT", "MOV", "CMP", "JZ", "JNZ", "JMP", "SSIZE", "SEXEC", "HLT");

        // Bootloader program in ROM / BIOS
        List<String> bootloader = List.of(
                "OUT \"[BIOS] SiliconCraft Bootloader 1.0\"",
                "OUT \"[BIOS] Searching for bootable Storage Drive...\"",
                "; Check if Storage Drive 1 (Flash / NVMe) is installed",
                "MOV B, 1",
                "SSIZE C, B",
                "CMP C, 0",
                "JNZ boot_selected",
                "; Fallback to Drive 0 (Onboard Flash)",
                "MOV B, 0",
                "SSIZE C, B",
                "CMP C, 0",
                "JNZ boot_selected",
                "JMP error_no_drives",
                "boot_selected:",
                "    OUT \"[BIOS] Booting from Storage Drive: \", 0",
                "    OUT B",
                "    OUT \"[BIOS] Drive capacity (bytes): \", 0",
                "    OUT C",
                "    OUT \"[BIOS] Chainloading program...\"",
                "    MOV A, B",
                "    MOV B, 0x5343",
                "    SEXEC A",
                "error_no_drives:",
                "    OUT \"[FATAL] No bootable storage drive detected!\"",
                "    HLT"
        );

        // Target OS code burned to Storage Drive 1 (Flash or NVMe)
        List<String> osKernel = List.of(
                "OUT \"==========================================\"",
                "OUT \"       SILICON-OS KERNEL INITIALIZED      \"",
                "OUT \"==========================================\"",
                "OUT \"Booted from Storage Drive: \", 0",
                "OUT A",
                "CMP B, 0x5343",
                "JNZ unverified_boot",
                "OUT \"Bootloader handoff signature verified: OK\"",
                "JMP os_main",
                "unverified_boot:",
                "    OUT \"Warning: Bootloader signature mismatch!\"",
                "os_main:",
                "    OUT \"Silicon-OS kernel running from Storage Drive.\"",
                "    OUT \"System ready for commands.\"",
                "    HLT"
        );

        Set<String> bootRequired = InstructionValidator.getRequiredInstructions(bootloader);
        List<String> bootMissing = InstructionValidator.getMissingInstructions(bootRequired, new ArrayList<>(documentedIsa));
        assertTrue(bootMissing.isEmpty(), "Missing instructions in bootloader ISA: " + bootMissing);

        Set<String> kernelRequired = InstructionValidator.getRequiredInstructions(osKernel);
        List<String> kernelMissing = InstructionValidator.getMissingInstructions(kernelRequired, new ArrayList<>(documentedIsa));
        assertTrue(kernelMissing.isEmpty(), "Missing instructions in kernel ISA: " + kernelMissing);

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(documentedIsa, 256, bootloader, false, 0);

        // Attach Storage Drive 1 with 512 bytes capacity and burned program
        byte[] drive1Bytes = new byte[512];
        cpu.setStorageDrive(1, drive1Bytes);
        cpu.setDriveProgram(1, osKernel);

        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(1, cpu.getRegA(), "Register A should preserve the boot drive index 1");
        assertEquals(0x5343, cpu.getRegB(), "Register B should preserve the magic word signature");

        String terminal = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminal.contains("[BIOS] SiliconCraft Bootloader 1.0"));
        assertTrue(terminal.contains("[BIOS] Booting from Storage Drive: 1"));
        assertTrue(terminal.contains("SILICON-OS KERNEL INITIALIZED"));
        assertTrue(terminal.contains("Bootloader handoff signature verified: OK"));
        assertTrue(terminal.contains("Silicon-OS kernel running from Storage Drive."));
    }
}
