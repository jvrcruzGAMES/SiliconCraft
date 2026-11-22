package games.jvrcruz.siliconcraft;

import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import games.jvrcruz.siliconcraft.compiler.NCompiler;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
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
                "ATONE 440, 300",
                "ATONE 554, 300",
                "ATONE 659, 300",
                "ATONE 880, 600",
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
        Set<String> documentedIsa = Set.of("OUT", "MOV", "CMP", "JZ", "JNZ", "JMP", "INC", "IVEC", "IRET", "SSIZE", "SEXEC", "HLT");

        // Bootloader program in ROM / BIOS
        List<String> bootloader = List.of(
                "; Register Vector 14 (RESET) handler to clean memory after a reset",
                "IVEC RESET, handle_reset",
                "OUT \"[BIOS] SiliconCraft Bootloader 1.0\"",
                "; Memory cleanup after reset: sanitize system RAM (addresses 0..63)",
                "OUT \"[BIOS] Sanitizing system RAM after reset...\"",
                "MOV D, 0",
                "clean_mem_loop:",
                "    MOV [D], 0",
                "    INC D",
                "    CMP D, 64",
                "    JNZ clean_mem_loop",
                "OUT \"[BIOS] RAM sanitized: 64 words cleared.\"",
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
                "    HLT",
                "; --- Reset Handler: Memory Cleanup after Reset ---",
                "handle_reset:",
                "    OUT \"[BIOS] Reset event caught. Sanitizing system RAM...\"",
                "    MOV D, 0",
                "reset_clean_loop:",
                "    MOV [D], 0",
                "    INC D",
                "    CMP D, 64",
                "    JNZ reset_clean_loop",
                "    OUT \"[BIOS] Memory sanitized. Resuming boot sequence.\"",
                "    IRET"
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

        // Simulate dirty RAM prior to boot
        cpu.getRam()[10] = 0xDEAD;
        cpu.getRam()[50] = 0xBEEF;

        cpu.powerOn();
        cpu.tick(400);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(1, cpu.getRegA(), "Register A should preserve the boot drive index 1");
        assertEquals(0x5343, cpu.getRegB(), "Register B should preserve the magic word signature");
        assertEquals(0, cpu.getRam()[10], "RAM[10] should be cleared by bootloader memory sanitization");
        assertEquals(0, cpu.getRam()[50], "RAM[50] should be cleared by bootloader memory sanitization");

        String terminal = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminal.contains("[BIOS] SiliconCraft Bootloader 1.0"));
        assertTrue(terminal.contains("[BIOS] Sanitizing system RAM after reset..."));
        assertTrue(terminal.contains("[BIOS] RAM sanitized: 64 words cleared."));
        assertTrue(terminal.contains("[BIOS] Booting from Storage Drive: 1"));
        assertTrue(terminal.contains("SILICON-OS KERNEL INITIALIZED"));
        assertTrue(terminal.contains("Bootloader handoff signature verified: OK"));
        assertTrue(terminal.contains("Silicon-OS kernel running from Storage Drive."));

        // Verify warm reset handling and memory sanitization by reset handler
        VirtualCpu cpuReset = new VirtualCpu();
        cpuReset.configure(documentedIsa, 256, bootloader, false, 0);
        cpuReset.setStorageDrive(1, drive1Bytes);
        cpuReset.setDriveProgram(1, osKernel);
        cpuReset.powerOn();
        cpuReset.tick(5); // Registers IVEC RESET

        // Stale data left in memory
        cpuReset.getRam()[25] = 0x5555;
        // Warm reset
        cpuReset.reset();
        assertEquals(0x5555, cpuReset.getRam()[25], "RAM preserved across reset before handler executes");
        cpuReset.tick(400);
        assertEquals(0, cpuReset.getRam()[25], "RAM[25] cleaned by reset handler");
        assertTrue(String.join("\n", cpuReset.getTerminalOutput()).contains("Reset event caught. Sanitizing system RAM..."));
    }

    @Test
    public void testExample10StorageDriveChainloadN() {
        List<String> bootloaderN = List.of(
                "on_reset(handle_reset)",
                "println \"[BIOS] SiliconCraft Bootloader 1.0\"",
                "println \"[BIOS] Sanitizing RAM after reset...\"",
                "var addr = 64",
                "while addr < 128 {",
                "    mem[addr] = 0",
                "    addr++",
                "}",
                "println \"[BIOS] RAM sanitized: 64 words cleared.\"",
                "println \"[BIOS] Searching for bootable Storage Drive...\"",
                "var bootDrive = 1",
                "var cap = storage[1].size()",
                "if cap == 0 {",
                "    bootDrive = 0",
                "    cap = storage[0].size()",
                "}",
                "if cap == 0 {",
                "    println \"[FATAL] No bootable storage drive detected!\"",
                "    halt",
                "}",
                "print \"[BIOS] Booting from Storage Drive: \"",
                "println bootDrive",
                "print \"[BIOS] Drive capacity (bytes): \"",
                "println cap",
                "println \"[BIOS] Chainloading program...\"",
                "A = bootDrive",
                "B = 0x5343",
                "storage.exec(A)",
                "fn handle_reset() {",
                "    println \"[BIOS] Reset event caught. Sanitizing RAM...\"",
                "    var raddr = 64",
                "    while raddr < 128 {",
                "        mem[raddr] = 0",
                "        raddr++",
                "    }",
                "    println \"[BIOS] Memory sanitized. Resuming boot sequence.\"",
                "    IRET",
                "}"
        );

        List<String> osKernelN = List.of(
                "println \"==========================================\"",
                "println \"       SILICON-OS KERNEL INITIALIZED      \"",
                "println \"==========================================\"",
                "print \"Booted from Storage Drive: \"",
                "println A",
                "if B != 0x5343 {",
                "    println \"Warning: Bootloader signature mismatch!\"",
                "}",
                "if B == 0x5343 {",
                "    println \"Bootloader handoff signature verified: OK\"",
                "}",
                "println \"Silicon-OS kernel running from Storage Drive.\"",
                "println \"System ready for commands.\""
        );

        NCompiler.CompileResult bootResult = NCompiler.compile(bootloaderN);
        assertTrue(bootResult.success(), "Bootloader N compilation failed: " + bootResult.errors());

        NCompiler.CompileResult kernelResult = NCompiler.compile(osKernelN);
        assertTrue(kernelResult.success(), "Kernel N compilation failed: " + kernelResult.errors());

        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = new HashSet<>(bootResult.requiredInstructions());
        isa.addAll(kernelResult.requiredInstructions());
        cpu.configure(isa, 256, bootResult.assembly(), false, 0);

        byte[] drive1Bytes = new byte[512];
        cpu.setStorageDrive(1, drive1Bytes);
        cpu.setDriveProgram(1, kernelResult.assembly());

        // Simulate dirty RAM in user area
        cpu.getRam()[70] = 0xABCD;
        cpu.getRam()[100] = 0x1234;

        cpu.powerOn();
        cpu.tick(800);

        assertEquals(1, cpu.getRegA());
        assertEquals(0x5343, cpu.getRegB());
        assertEquals(0, cpu.getRam()[70], "RAM[70] should be cleared by bootloader memory sanitization");
        assertEquals(0, cpu.getRam()[100], "RAM[100] should be cleared by bootloader memory sanitization");

        String terminal = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminal.contains("[BIOS] SiliconCraft Bootloader 1.0"));
        assertTrue(terminal.contains("[BIOS] Sanitizing RAM after reset..."));
        assertTrue(terminal.contains("[BIOS] RAM sanitized: 64 words cleared."));
        assertTrue(terminal.contains("SILICON-OS KERNEL INITIALIZED"));
        assertTrue(terminal.contains("Bootloader handoff signature verified: OK"));

        // Verify warm reset in N
        VirtualCpu cpuResetN = new VirtualCpu();
        cpuResetN.configure(isa, 256, bootResult.assembly(), false, 0);
        cpuResetN.setStorageDrive(1, drive1Bytes);
        cpuResetN.setDriveProgram(1, kernelResult.assembly());
        cpuResetN.powerOn();
        cpuResetN.tick(5); // Registers on_reset

        cpuResetN.getRam()[80] = 0x9999;
        cpuResetN.reset();
        assertEquals(0x9999, cpuResetN.getRam()[80], "RAM preserved across reset before handler executes");
        cpuResetN.tick(800);
        assertEquals(0, cpuResetN.getRam()[80], "RAM[80] cleaned by N reset handler");
        assertTrue(String.join("\n", cpuResetN.getTerminalOutput()).contains("Reset event caught. Sanitizing RAM..."));
    }

    @Test
    public void testExample11JingleBells() {
        Set<String> documentedIsa = Set.of("OUT", "ACLR", "ATONE", "APLAY", "HLT");
        List<String> code = List.of(
                "OUT \"==========================================\"",
                "OUT \"       SILICON AUDIO: JINGLE BELLS        \"",
                "OUT \"==========================================\"",
                "OUT \"Synthesizing Jingle Bells melody...\"",
                "ACLR",
                "ATONE 330, 250",
                "ATONE 0, 50",
                "ATONE 330, 250",
                "ATONE 0, 50",
                "ATONE 330, 500",
                "ATONE 0, 200",
                "ATONE 330, 250",
                "ATONE 0, 50",
                "ATONE 330, 250",
                "ATONE 0, 50",
                "ATONE 330, 500",
                "ATONE 0, 200",
                "ATONE 330, 250",
                "ATONE 0, 50",
                "ATONE 392, 250",
                "ATONE 0, 50",
                "ATONE 262, 250",
                "ATONE 0, 50",
                "ATONE 294, 250",
                "ATONE 0, 50",
                "ATONE 330, 600",
                "APLAY",
                "OUT \"Jingle Bells dispatched to audio buffer!\"",
                "HLT"
        );

        Set<String> required = InstructionValidator.getRequiredInstructions(code);
        List<String> missing = InstructionValidator.getMissingInstructions(required, new ArrayList<>(documentedIsa));
        assertTrue(missing.isEmpty(), "Missing instructions in documented ISA: " + missing);

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(documentedIsa, 256, code, false, 0);
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertTrue(cpu.getErrorMessage().isEmpty());

        String terminal = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminal.contains("Synthesizing Jingle Bells melody..."));
        assertTrue(terminal.contains("Jingle Bells dispatched to audio buffer!"));

        // Poll audio samples and ensure the tones were generated into the buffer
        byte[] audio = cpu.pollAudioSamples();
        assertNotNull(audio, "Audio buffer should contain synthesized samples for Jingle Bells");
        assertTrue(audio.length > 500, "Audio buffer should contain at least 500 samples: " + audio.length);
    }

    // ==========================================
    // N Language Documentation Equivalents Tests
    // ==========================================

    @Test
    public void testNExample1Counter() {
        List<String> nSource = List.of(
                "println \"==========================\"",
                "println \"    COUNTDOWN DEMO       \"",
                "println \"==========================\"",
                "var c = 5",
                "while c > 0 {",
                "    println c",
                "    c--",
                "}",
                "println \"Liftoff! SiliconCraft online.\""
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "N compilation failed: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(result.requiredInstructions(), 256, result.assembly(), false, 0);
        cpu.powerOn();
        cpu.tick(150);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        String terminal = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminal.contains("COUNTDOWN DEMO"));
        assertTrue(terminal.contains("5"));
        assertTrue(terminal.contains("1"));
        assertTrue(terminal.contains("Liftoff! SiliconCraft online."));
    }

    @Test
    public void testNExample2Fibonacci() {
        List<String> nSource = List.of(
                "println \"Computing Fibonacci Series...\"",
                "var termA = 0",
                "var termB = 1",
                "var ptr = 0x20",
                "mem[ptr] = termA",
                "ptr++",
                "mem[ptr] = termB",
                "ptr++",
                "var count = 6",
                "while count > 0 {",
                "    var next = termA + termB",
                "    mem[ptr] = next",
                "    println next",
                "    termA = termB",
                "    termB = next",
                "    ptr++",
                "    count--",
                "}",
                "println \"Fibonacci computation complete!\""
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "N compilation failed: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(result.requiredInstructions(), 256, result.assembly(), false, 0);
        cpu.powerOn();
        cpu.tick(300);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        int[] ram = cpu.getRam();
        assertEquals(0, ram[0x20]);
        assertEquals(1, ram[0x21]);
        assertEquals(1, ram[0x22]);
        assertEquals(2, ram[0x23]);
        assertEquals(3, ram[0x24]);
        assertEquals(5, ram[0x25]);
        assertEquals(8, ram[0x26]);
        assertEquals(13, ram[0x27]);

        String terminal = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminal.contains("Fibonacci computation complete!"));
    }

    @Test
    public void testNExample3Multiply() {
        List<String> nSource = List.of(
                "println \"Multiplication Routine\"",
                "var a = 12",
                "var b = 7",
                "var result = 0",
                "while b > 0 {",
                "    result += a",
                "    b--",
                "}",
                "println \"Result (12 * 7):\"",
                "println result"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "N compilation failed: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(result.requiredInstructions(), 256, result.assembly(), false, 0);
        cpu.powerOn();
        cpu.tick(200);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        String terminal = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminal.contains("Result (12 * 7):"));
        assertTrue(terminal.contains("84"));
    }

    @Test
    public void testNExample4ArraySum() {
        List<String> nSource = List.of(
                "println \"Initializing array in RAM...\"",
                "var numbers = { 10, 20, 30, 40, 50 }",
                "var sum = 0",
                "var i = 0",
                "while i < 5 {",
                "    sum += numbers[i]",
                "    i++",
                "}",
                "println \"Array Sum:\"",
                "println sum"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "N compilation failed: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(result.requiredInstructions(), 256, result.assembly(), false, 0);
        cpu.powerOn();
        cpu.tick(200);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        String terminal = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminal.contains("Array Sum:"));
        assertTrue(terminal.contains("150"));
    }

    @Test
    public void testNExample5BiosBoot() {
        List<String> nSource = List.of(
                "println \"[POST] Silicon BIOS 1.0\"",
                "println \"[POST] Checking CPU Registers...\"",
                "var regA = 0xAA",
                "var regB = 0x55",
                "println \"Reg A Test OK\"",
                "println \"Reg B Test OK\"",
                "println \"[POST] Testing RAM Banks...\"",
                "mem[0x80] = 0xFF",
                "if mem[0x80] != 0xFF {",
                "    println \"[FATAL] Memory verification failed!\"",
                "    halt",
                "}",
                "println \"RAM Bank 0 Passed.\"",
                "println \"[SYSTEM] Loading OS Kernel...\"",
                "var step = 0",
                "while step < 3 {",
                "    step++",
                "}",
                "println \"[SYSTEM] SiliconCraft Personal Computer Ready.\""
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "N compilation failed: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(result.requiredInstructions(), 256, result.assembly(), false, 0);
        cpu.powerOn();
        cpu.tick(200);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        String terminal = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminal.contains("[POST] Silicon BIOS 1.0"));
        assertTrue(terminal.contains("RAM Bank 0 Passed."));
        assertTrue(terminal.contains("[SYSTEM] SiliconCraft Personal Computer Ready."));
    }

    @Test
    public void testNExample6GraphicsDemo() {
        List<String> nSource = List.of(
                "display.mode(1)",
                "display.clear(0xFF181824)",
                "display.rect(10, 10, 220, 16, 0xFF2A4494)",
                "display.line(0, 0, 239, 134, 0xFF44AA44)",
                "display.line(0, 134, 239, 0, 0xFF44AA44)",
                "display.rect(110, 57, 20, 20, 0xFFFFCC00)",
                "display.set(120, 67, 0xFFFFFFFF)",
                "display.swap()",
                "while true {",
                "}"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "N compilation failed: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(result.requiredInstructions(), 256, result.assembly(), false, 0);
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(1, cpu.getDisplayMode());
        int[] fb = cpu.getFramebuffer();
        assertEquals(0xFFFFFFFF, fb[67 * VirtualCpu.FB_WIDTH + 120]);
        assertEquals(0xFFFFCC00, fb[57 * VirtualCpu.FB_WIDTH + 110]);
        assertEquals(0xFF2A4494, fb[15 * VirtualCpu.FB_WIDTH + 20]);
        assertEquals(0xFF181824, fb[50 * VirtualCpu.FB_WIDTH + 5]);
    }

    @Test
    public void testNExample7AudioTune() {
        List<String> nSource = List.of(
                "println \"Synthesizing chime chord...\"",
                "audio.tone(440, 300)",
                "audio.tone(554, 300)",
                "audio.tone(659, 300)",
                "audio.tone(880, 600)",
                "audio.play()",
                "println \"Playback dispatched to speaker!\""
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "N compilation failed: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(result.requiredInstructions(), 256, result.assembly(), false, 0);
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        byte[] samples = cpu.pollAudioSamples();
        assertNotNull(samples);
        assertTrue(samples.length > 0);
        String terminal = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminal.contains("Playback dispatched to speaker!"));
    }

    @Test
    public void testNExample8InterruptDemo() {
        List<String> nSource = List.of(
                "catch(0, handle_div_zero)",
                "on_key(handle_key)",
                "STI",
                "println \"Testing exception handler...\"",
                "A = 100",
                "B = 0",
                "DIV A, B",
                "print \"Division result after recovery: \"",
                "println A",
                "println \"Now waiting for keyboard input...\"",
                "while true {",
                "}",
                "fn handle_div_zero() {",
                "    println \"[INTERRUPT] Caught DIV_ZERO exception!\"",
                "    B = 2",
                "    A = 50",
                "    IRET",
                "}",
                "fn handle_key() {",
                "    print \"Key pressed: \"",
                "    OUTC A",
                "    OUTC 10",
                "    IRET",
                "}"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "N compilation failed: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(result.requiredInstructions(), 256, result.assembly(), false, 0);
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(50, cpu.getRegA());
        String terminal = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminal.contains("[INTERRUPT] Caught DIV_ZERO exception!"));
        assertTrue(terminal.contains("Division result after recovery: "));
        assertTrue(terminal.contains("50"));
    }

    @Test
    public void testNExample9StorageIO() {
        List<String> nSource = List.of(
                "var drives = storage.count()",
                "if drives == 0 {",
                "    println \"[ERROR] No storage drive inserted!\"",
                "    halt",
                "}",
                "print \"Found installed Storage Drive(s): \"",
                "println drives",
                "storage.select(0)",
                "storage.write(0x00, 0x53)",
                "storage.write(0x01, 0x43)",
                "B = storage.read(0x00)",
                "C = storage.read(0x01)",
                "print \"Read magic signature: \"",
                "OUTC B",
                "OUTC C",
                "OUTC 10",
                "println \"Data persisted successfully.\""
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "N compilation failed: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(result.requiredInstructions(), 256, result.assembly(), false, 0);
        byte[] drive0 = new byte[256];
        cpu.setStorageDrive(0, drive0);

        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(0x53, drive0[0]);
        assertEquals(0x43, drive0[1]);
        String terminal = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminal.contains("Found installed Storage Drive(s): "));
        assertTrue(terminal.contains("Read magic signature: SC"));
        assertTrue(terminal.contains("Data persisted successfully."));
    }

    @Test
    public void testNExample10BootloaderAndKernel() {
        List<String> bootloaderSource = List.of(
                "println \"[BIOS] SiliconCraft Bootloader 1.0\"",
                "println \"[BIOS] Searching for bootable Storage Drive...\"",
                "var bootDrive = 1",
                "var cap = storage[1].size()",
                "if cap == 0 {",
                "    bootDrive = 0",
                "    cap = storage[0].size()",
                "}",
                "if cap == 0 {",
                "    println \"[FATAL] No bootable storage drive detected!\"",
                "    halt",
                "}",
                "print \"[BIOS] Booting from Storage Drive: \"",
                "println bootDrive",
                "print \"[BIOS] Drive capacity (bytes): \"",
                "println cap",
                "println \"[BIOS] Chainloading program...\"",
                "A = bootDrive",
                "B = 0x5343",
                "storage.exec(A)"
        );

        List<String> kernelSource = List.of(
                "println \"==========================================\"",
                "println \"       SILICON-OS KERNEL INITIALIZED      \"",
                "println \"==========================================\"",
                "print \"Booted from Storage Drive: \"",
                "println A",
                "if B != 0x5343 {",
                "    println \"Warning: Bootloader signature mismatch!\"",
                "}",
                "if B == 0x5343 {",
                "    println \"Bootloader handoff signature verified: OK\"",
                "}",
                "println \"Silicon-OS kernel running from Storage Drive.\"",
                "println \"System ready for commands.\""
        );

        NCompiler.CompileResult bootResult = NCompiler.compile(bootloaderSource);
        assertTrue(bootResult.success(), "Bootloader N compilation failed: " + bootResult.errors());

        NCompiler.CompileResult kernelResult = NCompiler.compile(kernelSource);
        assertTrue(kernelResult.success(), "Kernel N compilation failed: " + kernelResult.errors());

        VirtualCpu cpu = new VirtualCpu();
        Set<String> combinedIsa = new java.util.HashSet<>(bootResult.requiredInstructions());
        combinedIsa.addAll(kernelResult.requiredInstructions());
        cpu.configure(combinedIsa, 256, bootResult.assembly(), false, 0);

        byte[] drive1 = new byte[512];
        cpu.setStorageDrive(1, drive1);
        cpu.setDriveProgram(1, kernelResult.assembly());

        cpu.powerOn();
        cpu.tick(150);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(1, cpu.getRegA());
        assertEquals(0x5343, cpu.getRegB());

        String terminal = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminal.contains("[BIOS] SiliconCraft Bootloader 1.0"));
        assertTrue(terminal.contains("[BIOS] Booting from Storage Drive: 1"));
        assertTrue(terminal.contains("SILICON-OS KERNEL INITIALIZED"));
        assertTrue(terminal.contains("Bootloader handoff signature verified: OK"));
        assertTrue(terminal.contains("Silicon-OS kernel running from Storage Drive."));
    }

    @Test
    public void testNExample11JingleBells() {
        List<String> nSource = List.of(
                "println \"==========================================\"",
                "println \"       SILICON AUDIO: JINGLE BELLS        \"",
                "println \"==========================================\"",
                "println \"Synthesizing Jingle Bells melody...\"",
                "audio.clear()",
                "audio.tone(330, 250)",
                "audio.pause(50)",
                "audio.tone(330, 250)",
                "audio.pause(50)",
                "audio.tone(330, 500)",
                "audio.pause(200)",
                "audio.tone(330, 250)",
                "audio.pause(50)",
                "audio.tone(330, 250)",
                "audio.pause(50)",
                "audio.tone(330, 500)",
                "audio.pause(200)",
                "audio.tone(330, 250)",
                "audio.pause(50)",
                "audio.tone(392, 250)",
                "audio.pause(50)",
                "audio.tone(262, 250)",
                "audio.pause(50)",
                "audio.tone(294, 250)",
                "audio.pause(50)",
                "audio.tone(330, 600)",
                "audio.play()",
                "println \"Jingle Bells dispatched to audio buffer!\""
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "N compilation failed: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(result.requiredInstructions(), 256, result.assembly(), false, 0);
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        byte[] audio = cpu.pollAudioSamples();
        assertNotNull(audio);
        assertTrue(audio.length > 500);

        String terminal = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminal.contains("Synthesizing Jingle Bells melody..."));
        assertTrue(terminal.contains("Jingle Bells dispatched to audio buffer!"));
    }

    @Test
    public void testExample12ResetRecoveryAsm() {
        Set<String> documentedIsa = Set.of("OUT", "MOV", "CMP", "JZ", "JNZ", "INC", "IVEC", "IRET", "STI", "HLT");
        List<String> code = List.of(
                "IVEC RESET, handle_cpu_reset",
                "STI",
                "MOV A, [100]",
                "CMP A, 51966",
                "JZ warm_boot",
                "cold_boot:",
                "    OUT \"Cold boot detected. Initializing RAM state...\"",
                "    MOV [100], 51966",
                "    MOV [101], 1",
                "    OUT \"Session counter initialized to: 1\"",
                "    HLT",
                "warm_boot:",
                "    OUT \"Normal execution running after reset recovery...\"",
                "    OUT \"Current session counter: \", 0",
                "    MOV A, [101]",
                "    OUT A",
                "    HLT",
                "handle_cpu_reset:",
                "    OUT \"[RESET INTERRUPT] CPU reset or power-on caught!\"",
                "    MOV A, [100]",
                "    CMP A, 51966",
                "    JNZ cold_boot_reset",
                "    MOV A, [101]",
                "    INC A",
                "    MOV [101], A",
                "    OUT \"[RESET INTERRUPT] Preserved RAM verified. Incremented boot count.\"",
                "    IRET",
                "cold_boot_reset:",
                "    OUT \"[RESET INTERRUPT] RAM uninitialized.\"",
                "    IRET"
        );

        Set<String> required = InstructionValidator.getRequiredInstructions(code);
        List<String> missing = InstructionValidator.getMissingInstructions(required, new ArrayList<>(documentedIsa));
        assertTrue(missing.isEmpty(), "Missing instructions in documented ISA: " + missing);

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(documentedIsa, 256, code, false, 0);

        // Cold boot
        cpu.powerOn();
        cpu.tick(50);
        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(51966, cpu.getRam()[100]);
        assertEquals(1, cpu.getRam()[101]);

        // Reset CPU (simulating reset button or power cycle)
        cpu.reset();
        assertEquals(51966, cpu.getRam()[100], "RAM should persist across reset");

        // Execute ticks: reset handler triggers, increments count to 2, and warm_boot runs
        cpu.tick(50);
        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(2, cpu.getRam()[101]);

        String terminal = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminal.contains("Preserved RAM verified. Incremented boot count."));
        assertTrue(terminal.contains("Current session counter: 2"));
    }

    @Test
    public void testExample12ResetRecoveryN() {
        List<String> nSource = List.of(
                "on_reset(handle_cpu_reset)",
                "var magic = mem[100]",
                "if magic == 51966 {",
                "    println \"Normal execution running after reset recovery...\"",
                "    var count = mem[101]",
                "    print \"Current session counter: \"",
                "    println count",
                "}",
                "if magic != 51966 {",
                "    println \"Cold boot detected. Initializing RAM state...\"",
                "    mem[100] = 51966",
                "    mem[101] = 1",
                "    println \"Session counter initialized to: 1\"",
                "}",
                "fn handle_cpu_reset() {",
                "    println \"[RESET INTERRUPT] CPU reset caught!\"",
                "    var sig = mem[100]",
                "    if sig == 51966 {",
                "        var old = mem[101]",
                "        mem[101] = old + 1",
                "        println \"[RESET INTERRUPT] Preserved RAM verified. Incremented boot count.\"",
                "    }",
                "    IRET",
                "}"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "N compilation failed: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(result.requiredInstructions(), 256, result.assembly(), false, 0);

        // Cold boot
        cpu.powerOn();
        cpu.tick(100);
        assertEquals(51966, cpu.getRam()[100]);
        assertEquals(1, cpu.getRam()[101]);

        // Reset
        cpu.reset();
        assertEquals(51966, cpu.getRam()[100]);

        // Run post-reset
        cpu.tick(100);
        assertEquals(2, cpu.getRam()[101]);

        String terminal = String.join("\n", cpu.getTerminalOutput());
        assertTrue(terminal.contains("Preserved RAM verified. Incremented boot count."));
        assertTrue(terminal.contains("Current session counter: 2"));
    }
}

