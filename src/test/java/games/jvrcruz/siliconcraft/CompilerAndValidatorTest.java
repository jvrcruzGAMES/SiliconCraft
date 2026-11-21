package games.jvrcruz.siliconcraft;

import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import games.jvrcruz.siliconcraft.compiler.NCompiler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class CompilerAndValidatorTest {

    @Test
    public void testNCompilerSuccess() {
        List<String> nSource = List.of(
                "var a = 0",
                "var b = 1",
                "var count = 5",
                "while count > 0 {",
                "    print a",
                "    var next = a + b",
                "    a = b",
                "    b = next",
                "    count--",
                "}",
                "halt"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "N compilation should succeed");
        assertFalse(result.assembly().isEmpty(), "Generated assembly should not be empty");
        assertTrue(result.requiredInstructions().contains("OUT"));
        assertTrue(result.requiredInstructions().contains("HLT"));
        assertTrue(result.requiredInstructions().contains("MOV"));
    }

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
        List<String> fullArch = List.of("MOV", "INC", "OUT", "CMP", "JNZ", "HLT", "ADD");
        List<String> noneMissing = InstructionValidator.getMissingInstructions(required, fullArch);
        assertTrue(noneMissing.isEmpty());
    }

    @Test
    public void testNCompilerSyntaxError() {
        List<String> badSource = List.of(
                "var x = 5",
                "while x > {",
                "    print x"
        );

        NCompiler.CompileResult result = NCompiler.compile(badSource);
        assertFalse(result.success(), "Invalid syntax should fail compilation");
        assertFalse(result.errors().isEmpty());
    }
}
