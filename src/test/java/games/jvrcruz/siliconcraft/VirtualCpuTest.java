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
}
