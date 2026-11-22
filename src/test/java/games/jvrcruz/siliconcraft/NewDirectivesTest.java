package games.jvrcruz.siliconcraft;

import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import games.jvrcruz.siliconcraft.compiler.NCompiler;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class NewDirectivesTest {

    @Test
    public void testInpadHeldButtonPolling() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("INPAD", "MOV", "AND", "CMP", "JZ", "HLT");
        List<String> code = List.of(
                "INPAD A",           // read pad 0
                "INPAD B, 1",        // read pad 1
                "HLT"
        );

        cpu.configure(isa, 256, code, false, 0);
        cpu.powerOn();
        // Set pad states after power-on (representing user holding controller buttons)
        cpu.setPadState(0, VirtualCpu.PAD_RIGHT | VirtualCpu.PAD_B | VirtualCpu.PAD_START);
        cpu.setPadState(1, VirtualCpu.PAD_UP | VirtualCpu.PAD_Y);

        cpu.tick(50);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(VirtualCpu.PAD_RIGHT | VirtualCpu.PAD_B | VirtualCpu.PAD_START, cpu.getRegA());
        assertEquals(VirtualCpu.PAD_UP | VirtualCpu.PAD_Y, cpu.getRegB());
    }

    @Test
    public void testDynamicAndIndirectJumps() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "JMP", "CALL", "RET", "HLT", "ADD");
        List<String> code = List.of(
                // Jump table addresses stored in RAM
                "MOV [100], target1",
                "MOV [101], target2",
                // Dynamic register jump
                "MOV A, target1",
                "JMP A",
                "target2:",
                "MOV B, 999",
                "HLT",
                "target1:",
                "MOV B, 111",
                // Indirect register jump
                "MOV D, 101",
                "CALL [D]",
                "HLT"
        );

        cpu.configure(isa, 256, code, false, 0);
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(999, cpu.getRegB());
    }

    @Test
    public void testStaticDataDirectives() throws IOException {
        // Prepare temporary file for INCBIN
        File tempBin = File.createTempFile("siliconcraft_test_bin", ".dat");
        tempBin.deleteOnExit();
        try (FileOutputStream fos = new FileOutputStream(tempBin)) {
            fos.write(new byte[]{0x41, 0x42, 0x43, 0x44}); // "ABCD"
        }

        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "HLT");
        List<String> code = List.of(
                "ORG 0x20",
                "myData:",
                "DB 10, 20, 30, \"XYZ\"",
                "DW 0x1234, 0x5678",
                "DL 0x100000",
                "ORG 0x40",
                "binData:",
                "INCBIN \"" + tempBin.getAbsolutePath() + "\"",
                "start:",
                "MOV A, [0x20]",       // 10
                "MOV B, [0x21]",       // 20
                "MOV C, [0x23]",       // 'X' = 88
                "HLT"
        );

        cpu.configure(isa, 256, code, false, 0);
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(10, cpu.getRegA());
        assertEquals(20, cpu.getRegB());
        assertEquals('X', cpu.getRegC());

        // Check 16-bit little-endian DW at 0x26, 0x27 (0x1234)
        assertEquals(0x34, cpu.getRam()[0x26]);
        assertEquals(0x12, cpu.getRam()[0x27]);
        int wordVal = (cpu.getRam()[0x26] & 0xFF) | ((cpu.getRam()[0x27] & 0xFF) << 8);
        assertEquals(0x1234, wordVal);

        // Check 24-bit little-endian DL at 0x2A, 0x2B, 0x2C (0x100000)
        // 0x20..0x22 (3 bytes DB), 0x23..0x25 (3 bytes string), 0x26..0x29 (4 bytes DW)
        int longVal = (cpu.getRam()[0x2A] & 0xFF) | ((cpu.getRam()[0x2B] & 0xFF) << 8) | ((cpu.getRam()[0x2C] & 0xFF) << 16);
        assertEquals(0x100000, longVal);

        // Check INCBIN data at 0x40
        assertEquals(0x41, cpu.getRam()[0x40]);
        assertEquals(0x42, cpu.getRam()[0x41]);
        assertEquals(0x43, cpu.getRam()[0x42]);
        assertEquals(0x44, cpu.getRam()[0x43]);

        // Assembler directives should not be required in chip forge / ISA
        Set<String> required = InstructionValidator.getRequiredInstructions(code);
        assertFalse(required.contains("ORG"));
        assertFalse(required.contains("DB"));
        assertFalse(required.contains("DW"));
        assertFalse(required.contains("DL"));
        assertFalse(required.contains("INCBIN"));
        assertTrue(required.contains("MOV"));
        assertTrue(required.contains("HLT"));
    }

    @Test
    public void testHardwareTimerAndVBlankInterrupt() {
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("TIMER", "VBLANK", "IVEC", "IRET", "MOV", "INC", "CMP", "JNZ", "HLT", "NOP");
        List<String> code = List.of(
                "IVEC TIMER, onTimer",
                "TIMER 60",
                "loop:",
                "CMP [100], 2",
                "JNZ loop",
                "VBLANK 0",
                "HLT",
                "onTimer:",
                "MOV D, [100]",
                "INC D",
                "MOV [100], D",
                "IRET"
        );

        cpu.configure(isa, 256, code, false, 0);
        cpu.powerOn();
        assertEquals(0, cpu.getTimerHz());

        // Execute IVEC and TIMER 60
        cpu.tick(5);
        assertEquals(60, cpu.getTimerHz());

        // The timer triggers automatically (or via queueTimerInterrupt)
        // Run enough cycles to reach [100] == 2 and halt
        for (int i = 0; i < 20 && cpu.getRam()[100] < 2; i++) {
            cpu.queueTimerInterrupt();
            cpu.tick(50);
        }

        assertEquals(2, cpu.getRam()[100]);
        // After reaching 2, the loop exits, runs VBLANK 0, and halts
        cpu.tick(20);
        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(0, cpu.getTimerHz()); // VBLANK 0 turned it off
    }

    @Test
    public void testNCompilerDirectivesAndNewFeatures() {
        List<String> nSource = List.of(
                "vblank.enable()",
                "timer.start(60)",
                "on_vblank(vblank_handler)",
                "var pad = pad.get()",
                "if (pad & pad.RIGHT) != 0 {",
                "    var target = 50",
                "    call(target)",
                "}",
                "vblank.disable()",
                "halt",
                "fn vblank_handler {",
                "    var x = 1",
                "    iret",
                "}",
                "ORG 0x100",
                "DB 1, 2, 3, \"HELLO\"",
                "DW 0x55AA"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "N compilation should succeed: " + result.errors());
        List<String> asm = result.assembly();
        assertNotNull(asm);
        String fullAsm = String.join("\n", asm);
        assertTrue(fullAsm.contains("VBLANK 1"));
        assertTrue(fullAsm.contains("TIMER 60"));
        assertTrue(fullAsm.contains("IVEC 13, vblank_handler"));
        assertTrue(fullAsm.contains("INPAD"));
        assertTrue(fullAsm.contains("CALL"));
        assertTrue(fullAsm.contains("VBLANK 0"));
        assertTrue(fullAsm.contains("ORG 0x100"));
        assertTrue(fullAsm.contains("DB 1, 2, 3, \"HELLO\""));
        assertTrue(fullAsm.contains("DW 0x55AA"));
    }
}
