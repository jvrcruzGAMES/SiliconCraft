package games.jvrcruz.siliconcraft;

import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import games.jvrcruz.siliconcraft.compiler.NCompiler;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
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
                "    println a",
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
        List<String> fullArch = List.of("MOV", "INC", "OUT", "OUTC", "CMP", "JNZ", "HLT", "ADD");
        List<String> noneMissing = InstructionValidator.getMissingInstructions(required, fullArch);
        assertTrue(noneMissing.isEmpty());
    }

    @Test
    public void testNCompilerSyntaxError() {
        List<String> badSource = List.of(
                "var x = 5",
                "while x > {",
                "    println x"
        );

        NCompiler.CompileResult result = NCompiler.compile(badSource);
        assertFalse(result.success(), "Invalid syntax should fail compilation");
        assertFalse(result.errors().isEmpty());
    }

    @Test
    public void testExplicitTypeDeclarationsRejected() {
        List<String> intSource = List.of("int x = 5");
        NCompiler.CompileResult r1 = NCompiler.compile(intSource);
        assertFalse(r1.success(), "Explicit 'int' should be rejected");
        assertTrue(r1.errors().get(0).contains("Explicit type declaration"));

        List<String> charSource = List.of("char c = 'a'");
        NCompiler.CompileResult r2 = NCompiler.compile(charSource);
        assertFalse(r2.success(), "Explicit 'char' should be rejected");

        List<String> charArrSource = List.of("char[] s = \"hello\"");
        NCompiler.CompileResult r3 = NCompiler.compile(charArrSource);
        assertFalse(r3.success(), "Explicit 'char[]' should be rejected");
    }

    @Test
    public void testConstantImmutability() {
        List<String> constSource = List.of(
                "const MAX = 100",
                "MAX = 200"
        );
        NCompiler.CompileResult result = NCompiler.compile(constSource);
        assertFalse(result.success(), "Reassigning to constant should fail");
        assertTrue(result.errors().get(0).contains("Cannot assign to constant"));
    }

    @Test
    public void testPrintCharRemoved() {
        List<String> source = List.of("print_char 'x'");
        NCompiler.CompileResult result = NCompiler.compile(source);
        assertFalse(result.success(), "print_char should be removed/rejected");
        assertTrue(result.errors().get(0).contains("'print_char' has been removed"));
    }

    @Test
    public void testBooleanAndPrintlnExecution() {
        List<String> nSource = List.of(
                "var isOnline = true",
                "const hasErrors = false",
                "println isOnline",
                "println hasErrors",
                "if isOnline {",
                "    println \"System Ready\"",
                "}",
                "halt"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "Boolean code should compile: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "CMP", "JZ", "JNZ", "JMP", "OUT", "OUTC", "HLT");
        cpu.configure(isa, 256, result.assembly(), false, 0);
        cpu.powerOn();
        cpu.tick(200);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        List<String> output = cpu.getTerminalOutput();
        assertTrue(output.contains("true"), "Output should contain 'true': " + output);
        assertTrue(output.contains("false"), "Output should contain 'false': " + output);
        assertTrue(output.contains("System Ready"), "Output should contain 'System Ready': " + output);
    }

    @Test
    public void testLuaStyleTablesAndExecution() {
        List<String> nSource = List.of(
                "var user = { name = \"Steve\", age = 30, alive = true }",
                "var nums = { 10, 20, 30 }",
                "println user.name",
                "println user.age",
                "println user.alive",
                "println nums[0]",
                "user.age = 31",
                "nums[0] = 99",
                "println user.age",
                "println nums[0]",
                "halt"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "Lua-style table code should compile: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "ADD", "CMP", "JZ", "JNZ", "JMP", "INC", "OUT", "OUTC", "HLT");
        cpu.configure(isa, 256, result.assembly(), false, 0);
        cpu.powerOn();
        cpu.tick(300);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        List<String> output = cpu.getTerminalOutput();
        assertTrue(output.contains("Steve"), "Output should contain 'Steve': " + output);
        assertTrue(output.contains("30"), "Output should contain '30': " + output);
        assertTrue(output.contains("true"), "Output should contain 'true': " + output);
        assertTrue(output.contains("10"), "Output should contain '10': " + output);
        assertTrue(output.contains("31"), "Output should contain modified age '31': " + output);
        assertTrue(output.contains("99"), "Output should contain modified element '99': " + output);
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
    public void testPrintOnCurrentLine() {
        List<String> nSource = List.of(
                "print \"Hello \"",
                "println \"World\"",
                "var x = 42",
                "print \"Value: \"",
                "print x",
                "println \"!\"",
                "var flag = true",
                "print \"Flag is \"",
                "println flag",
                "halt"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "Compilation should succeed: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "CMP", "JZ", "JNZ", "JMP", "OUT", "OUTC", "HLT");
        cpu.configure(isa, 256, result.assembly(), false, 0);
        cpu.powerOn();
        cpu.tick(300);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        List<String> output = cpu.getTerminalOutput();

        assertTrue(output.contains("Hello World"), "Output should contain 'Hello World' on one line: " + output);
        assertTrue(output.contains("Value: 42!"), "Output should contain 'Value: 42!' on one line: " + output);
        assertTrue(output.contains("Flag is true"), "Output should contain 'Flag is true' on one line: " + output);
    }

    @Test
    public void testPrintWithTablesAndStrings() {
        List<String> nSource = List.of(
                "var user = { name = \"Alex\", score = 100 }",
                "print \"Player: \"",
                "print user.name",
                "print \" | Score: \"",
                "println user.score",
                "halt"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "Compilation should succeed: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "ADD", "CMP", "JZ", "JNZ", "JMP", "INC", "OUT", "OUTC", "HLT");
        cpu.configure(isa, 256, result.assembly(), false, 0);
        cpu.powerOn();
        cpu.tick(300);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        List<String> output = cpu.getTerminalOutput();

        assertTrue(output.contains("Player: Alex | Score: 100"),
                "Output should contain 'Player: Alex | Score: 100' on a single line: " + output);
    }
}
