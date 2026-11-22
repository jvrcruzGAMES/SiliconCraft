package games.jvrcruz.siliconcraft;

import games.jvrcruz.siliconcraft.compiler.Assembler;
import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import games.jvrcruz.siliconcraft.compiler.n2.N2Compiler;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class N2CompilerTest {

    private VirtualCpu runCpu(List<String> bytecode, int maxSteps) {
        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 65536, bytecode, false, 0);
        cpu.powerOn();
        for (int i = 0; i < maxSteps && cpu.getState() == VirtualCpu.State.RUNNING; i++) {
            cpu.step();
        }
        return cpu;
    }

    @Test
    public void testClassDefinitionAndInstantiation() {
        String code = """
                import <lib/io>

                class Player {
                    func init()
                        print("Player initiated")
                    end
                }

                func main()
                    local p = Player()
                end
                """;

        N2Compiler.CompileResult result = N2Compiler.compile(code);
        assertTrue(result.success(), "N2 compilation should succeed: " + result.errors());
        assertFalse(result.assembly().isEmpty());

        Assembler.AssemblerResult asmResult = Assembler.assemble(result.assembly());
        assertTrue(asmResult.success(), "Assembler should succeed: " + asmResult.errors());

        VirtualCpu cpu = runCpu(asmResult.bytecode(), 500);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState(), "CPU Error: " + cpu.getErrorMessage());
        assertTrue(cpu.getTerminalOutput().stream().anyMatch(l -> l.contains("Player initiated")),
                "Terminal should contain 'Player initiated': " + cpu.getTerminalOutput());
    }

    @Test
    public void testClassMethodsAndFields() {
        String code = """
                import <lib/io>

                class Counter {
                    func init(start)
                        this.count = start
                    end

                    func increment()
                        this.count = this.count + 1
                    end

                    func get()
                        return this.count
                    end
                }

                func main()
                    local c = Counter(10)
                    c.increment()
                    c.increment()
                    print(c.get())
                end
                """;

        N2Compiler.CompileResult result = N2Compiler.compile(code);
        assertTrue(result.success(), "Compilation should succeed: " + result.errors());

        Assembler.AssemblerResult asmResult = Assembler.assemble(result.assembly());
        assertTrue(asmResult.success(), "Assembly should succeed: " + asmResult.errors());

        VirtualCpu cpu = runCpu(asmResult.bytecode(), 1000);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertTrue(cpu.getTerminalOutput().stream().anyMatch(l -> l.contains("12")),
                "Terminal should output 12: " + cpu.getTerminalOutput());
    }

    @Test
    public void testGlobalsAndLocals() {
        String code = """
                import <lib/io>

                global = "contents"
                local scoped = "local_contents"

                func hello()
                    print(global)
                    print(scoped)
                end

                func main()
                    hello()
                end
                """;

        N2Compiler.CompileResult result = N2Compiler.compile(code);
        assertTrue(result.success(), "Compilation should succeed: " + result.errors());

        Assembler.AssemblerResult asmResult = Assembler.assemble(result.assembly());
        assertTrue(asmResult.success(), "Assembly should succeed: " + asmResult.errors());

        VirtualCpu cpu = runCpu(asmResult.bytecode(), 500);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertTrue(cpu.getTerminalOutput().stream().anyMatch(l -> l.contains("contents")),
                "Terminal should contain 'contents': " + cpu.getTerminalOutput());
        assertTrue(cpu.getTerminalOutput().stream().anyMatch(l -> l.contains("local_contents")),
                "Terminal should contain 'local_contents': " + cpu.getTerminalOutput());
    }

    @Test
    public void testListsAndMaps() {
        String code = """
                import <lib/io>

                func main()
                    local list = [10, 20, 30]
                    local map = {1: 100, 2: 200}
                    print(list[1])
                end
                """;

        N2Compiler.CompileResult result = N2Compiler.compile(code);
        assertTrue(result.success(), "Compilation should succeed: " + result.errors());

        Assembler.AssemblerResult asmResult = Assembler.assemble(result.assembly());
        assertTrue(asmResult.success(), "Assembly should succeed: " + asmResult.errors());

        VirtualCpu cpu = runCpu(asmResult.bytecode(), 500);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertTrue(cpu.getTerminalOutput().stream().anyMatch(l -> l.contains("20")),
                "Terminal should print list[1] which is 20: " + cpu.getTerminalOutput());
    }

    @Test
    public void testSystemLibraryImports() {
        String code = """
                import <lib/math>
                from <lib/io> import print

                func main()
                    local r = sqrt(16)
                    print(r)
                end
                """;

        N2Compiler.CompileResult result = N2Compiler.compile(code);
        assertTrue(result.success(), "Compilation should succeed: " + result.errors());

        Assembler.AssemblerResult asmResult = Assembler.assemble(result.assembly());
        assertTrue(asmResult.success(), "Assembly should succeed: " + asmResult.errors());

        VirtualCpu cpu = runCpu(asmResult.bytecode(), 500);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertTrue(cpu.getTerminalOutput().stream().anyMatch(l -> l.contains("4")),
                "Terminal should print sqrt(16) = 4: " + cpu.getTerminalOutput());
    }

    @Test
    public void testProjectImportWithQuotes() {
        Map<String, List<String>> files = Map.of(
                "main.n2", List.of(
                        "import <lib/io>",
                        "import \"player.n2\"",
                        "",
                        "func main()",
                        "    local p = Player(\"Steve\")",
                        "    p.speak()",
                        "end"
                ),
                "player.n2", List.of(
                        "import <lib/io>",
                        "",
                        "class Player {",
                        "    func init(name)",
                        "        this.name = name",
                        "    end",
                        "    func speak()",
                        "        print(this.name)",
                        "    end",
                        "}"
                )
        );

        N2Compiler.CompileResult result = N2Compiler.compileProject(files, "main.n2");
        assertTrue(result.success(), "Project compilation should succeed: " + result.errors());

        Assembler.AssemblerResult asmResult = Assembler.assemble(result.assembly());
        assertTrue(asmResult.success(), "Assembly should succeed: " + asmResult.errors());

        VirtualCpu cpu = runCpu(asmResult.bytecode(), 800);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState(), "CPU Error: " + cpu.getErrorMessage());
        assertTrue(cpu.getTerminalOutput().stream().anyMatch(l -> l.contains("Steve")),
                "Terminal should contain 'Steve': " + cpu.getTerminalOutput());
    }

    @Test
    public void testRejectionOfAngleBracketsForProjectFiles() {
        String invalidCode = """
                import <player.n2>

                func main()
                end
                """;

        N2Compiler.CompileResult result = N2Compiler.compile(invalidCode);
        assertFalse(result.success(), "Should reject non-system import using angle brackets");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("quotes") || e.contains("system")),
                "Error should suggest using quotes: " + result.errors());
    }

    @Test
    public void testFromImportWithQuotes() {
        Map<String, List<String>> files = Map.of(
                "main.n2", List.of(
                        "import <lib/io>",
                        "from \"math_utils.n2\" import doubleValue",
                        "",
                        "func main()",
                        "    local x = doubleValue(21)",
                        "    print(x)",
                        "end"
                ),
                "math_utils.n2", List.of(
                        "func doubleValue(n)",
                        "    return n * 2",
                        "end"
                )
        );

        N2Compiler.CompileResult result = N2Compiler.compileProject(files, "main.n2");
        assertTrue(result.success(), "Project compilation should succeed: " + result.errors());

        Assembler.AssemblerResult asmResult = Assembler.assemble(result.assembly());
        assertTrue(asmResult.success(), "Assembly should succeed: " + asmResult.errors());

        VirtualCpu cpu = runCpu(asmResult.bytecode(), 500);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertTrue(cpu.getTerminalOutput().stream().anyMatch(l -> l.contains("42")),
                "Terminal should contain 42: " + cpu.getTerminalOutput());
    }

    @Test
    public void testStandardLibrariesRenamed() {
        String code = """
                import <std>
                import <math>
                import <Glass>
                import <AudioKit>
                import <DeviceKit>
                import <GameController>

                func main()
                    local c = clamp(15, 0, 10)
                    local s = sqrt(25)
                    print(c)
                    print(s)
                    print(KEY_SPACE)
                    print(GC_BUTTON_A)
                end
                """;

        N2Compiler.CompileResult result = N2Compiler.compile(code);
        assertTrue(result.success(), "Compilation should succeed with new library names: " + result.errors());

        Assembler.AssemblerResult asmResult = Assembler.assemble(result.assembly());
        assertTrue(asmResult.success(), "Assembly should succeed: " + asmResult.errors());

        VirtualCpu cpu = runCpu(asmResult.bytecode(), 1000);
        assertEquals(VirtualCpu.State.HALTED, cpu.getState(), "CPU Error: " + cpu.getErrorMessage());
        assertTrue(cpu.getTerminalOutput().stream().anyMatch(l -> l.contains("10")), "Should output clamped value 10");
        assertTrue(cpu.getTerminalOutput().stream().anyMatch(l -> l.contains("5")), "Should output sqrt 5");
        assertTrue(cpu.getTerminalOutput().stream().anyMatch(l -> l.contains("32")), "Should output KEY_SPACE 32");
        assertTrue(cpu.getTerminalOutput().stream().anyMatch(l -> l.contains("128")), "Should output GC_BUTTON_A 128");
    }

    @Test
    public void testKeyboardAndGamepadHandling() {
        String code = """
                import <std>
                import <DeviceKit>
                import <GameController>

                func main()
                    local hasK = key_available()
                    print(hasK)
                    local k = read_key()
                    print(k)
                    local pad = poll_gamepad(0)
                    local btnA = gc_button_south(0)
                    print(btnA)
                end
                """;

        N2Compiler.CompileResult result = N2Compiler.compile(code);
        assertTrue(result.success(), "Compilation should succeed: " + result.errors());

        Assembler.AssemblerResult asmResult = Assembler.assemble(result.assembly());
        assertTrue(asmResult.success(), "Assembly should succeed: " + asmResult.errors());

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 65536, asmResult.bytecode(), false, 0);
        cpu.powerOn();
        cpu.queueKeyboardInterrupt(65); // ASCII 'A'
        cpu.setPadState(0, 0x0040); // GC_BUTTON_SOUTH / PAD_B pressed
        for (int i = 0; i < 1000 && cpu.getState() == VirtualCpu.State.RUNNING; i++) {
            cpu.step();
        }

        assertEquals(VirtualCpu.State.HALTED, cpu.getState(), "CPU Error: " + cpu.getErrorMessage());
        assertTrue(cpu.getTerminalOutput().stream().anyMatch(l -> l.contains("1")), "has_key should return 1");
        assertTrue(cpu.getTerminalOutput().stream().anyMatch(l -> l.contains("65")), "read_key should return 65 ('A')");
        assertTrue(cpu.getTerminalOutput().stream().anyMatch(l -> l.contains("1")), "gc_button_south should return 1");
    }
}
