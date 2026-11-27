package games.jvrcruz.siliconcraft;

import games.jvrcruz.siliconcraft.compiler.Assembler;
import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import games.jvrcruz.siliconcraft.compiler.n2.N2Compiler;
import games.jvrcruz.siliconcraft.vm.GamepadState;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GameControllerInputsTest {
    @Test
    void analogSamplesKeepSeparateSticksTriggersAndClicksWithLegacyAliases() {
        var sample = GamepadState.fromAnalog(VirtualCpu.PAD_LEFT_STICK | VirtualCpu.PAD_RIGHT_STICK,
                -0.75f, 0.6f, 0.35f, -1f, 0.8f, 0.25f);
        assertEquals(-750, sample.leftX());
        assertEquals(600, sample.leftY());
        assertEquals(350, sample.rightX());
        assertEquals(-1000, sample.rightY());
        assertEquals(800, sample.leftTrigger());
        assertEquals(250, sample.rightTrigger());
        int expected = VirtualCpu.PAD_LEFT_STICK | VirtualCpu.PAD_RIGHT_STICK
                | VirtualCpu.PAD_LEFT | VirtualCpu.PAD_DOWN | VirtualCpu.PAD_L | VirtualCpu.PAD_LEFT_TRIGGER;
        assertEquals(expected, sample.buttons());
        var shoulderOnly = GamepadState.fromAnalog(VirtualCpu.PAD_R, 0, 0, 0, 0, 0, 0);
        assertEquals(0, shoulderOnly.rightTrigger());
        assertEquals(0, shoulderOnly.buttons() & VirtualCpu.PAD_RIGHT_TRIGGER);
    }

    @Test
    void neutralDeadzoneNonFiniteAndOutOfRangeInputAreSanitized() {
        assertEquals(GamepadState.NEUTRAL, GamepadState.fromAnalog(0, 0.1f, -0.15f, Float.NaN,
                Float.POSITIVE_INFINITY, Float.NaN, -1f));
        var extreme = new GamepadState(-1, Integer.MIN_VALUE, Integer.MAX_VALUE, -2000, 2000, -5, 5000);
        assertEquals(0xffff, extreme.buttons());
        assertEquals(List.of(-1000, 1000, -1000, 1000, 0, 1000),
                java.util.stream.IntStream.range(0, 6).mapToObj(extreme::axis).toList());
        assertEquals(0, extreme.axis(-1));
        assertEquals(0, extreme.axis(6));
        assertEquals(0, GamepadState.fromAnalog(0, 0, 0, 0, 0, 0.5f, 0.5f).buttons());
        assertEquals(VirtualCpu.PAD_R | VirtualCpu.PAD_RIGHT_TRIGGER,
                GamepadState.fromAnalog(0, 0, 0, 0, 0, 0, 1).buttons());
    }

    @Test
    void assemblyReadsAllAxesAndPreservesKeyboardPolling() {
        var source = List.of("INAXIS A, 1, 0", "MOV [100], A", "INAXIS B, 1, 1", "MOV [101], B",
                "INAXIS C, 1, 2", "MOV [102], C", "MOV D, 3", "INAXIS D, 1, D", "MOV [103], D",
                "INAXIS [104], 1, 4", "INAXIS [105], 1, 5", "INPAD [106], 1",
                "INAXIS [107], -1, 0", "INPAD [108], -1", "INAXIS [109], 0, 99", "HLT");
        var assembled = Assembler.assemble(source);
        assertTrue(assembled.success(), assembled.errors().toString());
        assertTrue(assembled.requiredInstructions().contains("INAXIS"));
        VirtualCpu cpu = cpu(assembled.bytecode(), 256);
        cpu.setGamepadState(1, new GamepadState(0xf040, -700, 300, 900, -400, 250, 1000));
        cpu.queueKeyboardInterrupt(65);
        cpu.tick(50);
        assertEquals(VirtualCpu.State.HALTED, cpu.getState(), cpu.getErrorMessage());
        assertArrayEquals(new int[]{-700, 300, 900, -400, 250, 1000, 0xf040, 0, 65, 0},
                java.util.Arrays.copyOfRange(cpu.getRam(), 100, 110));
    }

    @Test
    void disabledInstructionAndMalformedOperandsUseCpuFaultHandling() {
        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(Set.of("HLT"), 256, List.of("INAXIS A, 0, 0", "HLT"), false, 0);
        cpu.powerOn();
        cpu.tick(5);
        assertEquals(VirtualCpu.State.ERROR, cpu.getState());
        assertTrue(cpu.getErrorMessage().contains("INAXIS"));
        cpu = cpu(List.of("INAXIS A, 0", "HLT"), 256);
        cpu.tick(5);
        assertEquals(VirtualCpu.State.ERROR, cpu.getState());
        assertTrue(cpu.getErrorMessage().contains("destination, pad, axis"));
    }

    @Test
    void resetPowerOffAndLegacyStateReplacementClearAnalogInputs() {
        VirtualCpu cpu = cpu(List.of("HLT"), 256);
        GamepadState held = new GamepadState(0xffff, 1000, -1000, 500, -500, 800, 900);
        cpu.setGamepadState(0, held);
        cpu.setPadState(0, VirtualCpu.PAD_B);
        assertEquals(VirtualCpu.PAD_B, cpu.getPadState(0));
        assertEquals(0, cpu.getPadAxis(0, 0));
        cpu.setGamepadState(3, held);
        cpu.reset();
        assertEquals(0, cpu.getPadAxis(3, 4));
        assertEquals(0, cpu.getPadState(3));
        cpu.setGamepadState(0, held);
        cpu.powerOff();
        assertEquals(0, cpu.getPadAxis(0, 5));
        assertEquals(0, cpu.getPadState(0));
        cpu.setGamepadState(-1, held);
        cpu.setGamepadState(4, held);
        assertEquals(0, cpu.getPadAxis(-1, 0));
        assertEquals(0, cpu.getPadAxis(4, 0));
    }

    @Test
    void n2GameControllerHelpersExecuteAgainstLiveVmInput() {
        var compiled = N2Compiler.compile("""
                import <GameController>
                import <std>
                func selected_pad()
                    return 1
                end
                func selected_axis()
                    return GC_AXIS_RIGHT_Y
                end
                func main()
                    println(gc_left_stick_x(1))
                    println(gc_left_stick_y(1))
                    println(gc_right_stick_x(1))
                    println(gc_right_stick_y(1))
                    println(gc_left_trigger(1))
                    println(gc_right_trigger(1))
                    println(gc_left_trigger_pressed(1))
                    println(gc_right_trigger_pressed(1))
                    println(gc_left_stick_button(1))
                    println(gc_right_stick_button(1))
                    println(gc_axis(selected_pad(), selected_axis()))
                    println(gc_axis(1, GC_AXIS_LEFT_TRIGGER))
                    println(gc_poll(1) & GC_LEFT_STICK_BUTTON)
                    println(gc_left_stick_x(7))
                    println(gc_axis(1, 99))
                end
                """);
        assertTrue(compiled.success(), compiled.errors().toString());
        var assembled = Assembler.assemble(compiled.assembly());
        assertTrue(assembled.success(), assembled.errors().toString());
        assertTrue(assembled.requiredInstructions().contains("INAXIS"));
        VirtualCpu cpu = cpu(assembled.bytecode(), 65536);
        cpu.setGamepadState(1, GamepadState.fromAnalog(VirtualCpu.PAD_LEFT_STICK,
                -0.75f, 0.6f, 0.35f, -1f, 0.8f, 0.25f));
        cpu.tick(10000);
        assertEquals(VirtualCpu.State.HALTED, cpu.getState(), cpu.getErrorMessage());
        var values = cpu.getTerminalOutput().stream().filter(line -> line.matches("-?\\d+")).toList();
        assertEquals(List.of("-750", "600", "350", "-1000", "800", "250", "1", "0", "1", "0",
                "-1000", "800", "16384", "0", "0"), values);
    }

    @Test
    void newN2FunctionsRejectIncorrectArityWithoutCrashingTheCompiler() {
        for (String call : List.of("gc_axis(0)", "gc_left_trigger()", "gc_right_stick_button(0, 1)")) {
            var result = N2Compiler.compile("import <GameController>\nfunc main()\n    " + call + "\nend\n");
            assertFalse(result.success());
            assertTrue(result.errors().stream().anyMatch(error -> error.contains("expects")), result.errors().toString());
        }
    }

    private static VirtualCpu cpu(List<String> program, int ram) {
        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, ram, program, false, 0);
        cpu.powerOn();
        return cpu;
    }

    private static VirtualCpu cpu(byte[] program, int ram) {
        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, ram, program, false, 0);
        cpu.powerOn();
        return cpu;
    }
}
