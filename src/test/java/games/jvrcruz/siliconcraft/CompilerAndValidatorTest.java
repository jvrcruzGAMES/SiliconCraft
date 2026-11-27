package games.jvrcruz.siliconcraft;

import games.jvrcruz.siliconcraft.compiler.Assembler;
import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class CompilerAndValidatorTest {

    @Test
    public void testInstructionValidatorMissingOpcodes() {
        List<String> assembly = List.of(
                "MOV A, 0",
                "INC A",
                "OUT A",
                "CMP A, 10",
                "JNZ loop",
                "HLT"
        );

        Set<String> required = InstructionValidator.getRequiredInstructions(assembly);
        assertTrue(required.contains("MOV"));
        assertTrue(required.contains("INC"));
        assertTrue(required.contains("OUT"));
        assertTrue(required.contains("CMP"));
        assertTrue(required.contains("JNZ"));
        assertTrue(required.contains("HLT"));

        // Test with architecture missing OUT and CMP
        List<String> archSupported = List.of("MOV", "INC", "JNZ", "HLT");
        List<String> missing = InstructionValidator.getMissingInstructions(required, archSupported);

        assertEquals(2, missing.size());
        assertTrue(missing.contains("OUT"));
        assertTrue(missing.contains("CMP"));

        // Test with full architecture
        List<String> fullArch = List.of("MOV", "INC", "OUT", "OUTC", "CMP", "JNZ", "HLT", "ADD");
        List<String> noneMissing = InstructionValidator.getMissingInstructions(required, fullArch);
        assertTrue(noneMissing.isEmpty());
    }

    @Test
    public void testCharLiteralEscapes() {
        assertEquals(10, VirtualCpu.parseCharLiteral("\\n"));
        assertEquals(9, VirtualCpu.parseCharLiteral("\\t"));
        assertEquals(0, VirtualCpu.parseCharLiteral("\\0"));
        assertEquals(65, VirtualCpu.parseCharLiteral("A"));
        assertEquals(0x2605, VirtualCpu.parseCharLiteral("★"));
    }

    @Test
    public void testVirtualCpuAssemblyExpressionSupport() {
        List<String> assembly = List.of(
                "MOV [86], 100",
                "ADD [86], (4 * 16)",        // Expression in ADD source
                "MOV A, (2 + 3 * 4)",        // Precedence: 2 + 12 = 14
                "MOV B, ((2 + 3) * 4)",      // Parenthesized: 5 * 4 = 20
                "MOV C, (1 << 4)",           // Shift: 16
                "MOV D, (0xFF & 0x0F)",      // Bitwise AND: 15
                "MOV [10 + (2 * 4)], 42",    // Expression in RAM destination: ram[18] = 42
                "CMP A, (7 * 2)",            // Compare A with 14 -> should be equal (flag Z)
                "JZ match",
                "MOV A, 0",
                "match:",
                "HLT"
        );

        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "ADD", "CMP", "JZ", "HLT");
        cpu.configure(isa, 512, assembly, false, 0);
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState(), "CPU should execute assembly expressions without error: " + cpu.getErrorMessage());
        assertEquals(164, cpu.getRam()[86], "RAM[86] should be 100 + 64 = 164");
        assertEquals(14, cpu.getRegA(), "RegA should be 14");
        assertEquals(20, cpu.getRegB(), "RegB should be 20");
        assertEquals(16, cpu.getRegC(), "RegC should be 16");
        assertEquals(15, cpu.getRegD(), "RegD should be 15");
        assertEquals(42, cpu.getRam()[18], "RAM[18] should be 42");
    }
}
