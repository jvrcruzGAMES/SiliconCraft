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
}
