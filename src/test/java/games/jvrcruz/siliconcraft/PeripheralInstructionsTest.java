package games.jvrcruz.siliconcraft;

import games.jvrcruz.siliconcraft.compiler.Assembler;
import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import games.jvrcruz.siliconcraft.compiler.n2.N2Compiler;
import games.jvrcruz.siliconcraft.vm.PeripheralBus;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PeripheralInstructionsTest {
    @Test
    void assemblyExchangesOneRequestAndSignedResultWithHost() {
        var assembled = Assembler.assemble(List.of(
                "PCALL A, 3, 20, -42, 7",
                "PINFO B, 0",
                "PSTATUS C",
                "PSTATUS D",
                "PRESULT C",
                "PSTATUS B",
                "PWRAP A, 3",
                "HLT"
        ));
        assertTrue(assembled.success());
        assertTrue(assembled.requiredInstructions().containsAll(Set.of("PCALL", "PINFO", "PSTATUS", "PRESULT", "PWRAP")));

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 256, assembled.bytecode(), false, 0);
        cpu.powerOn();
        cpu.tick(3);
        PeripheralBus bus = cpu.getPeripheralBus();
        PeripheralBus.Request request = bus.pendingRequest();
        assertNotNull(request);
        assertEquals(PeripheralBus.Operation.CALL, request.operation());
        assertEquals(3, request.side());
        assertEquals(20, request.method());
        assertEquals(-42, request.arg0());
        assertEquals(7, request.arg1());
        assertEquals(1, cpu.getRegA());
        assertEquals(0, cpu.getRegB());
        assertEquals(PeripheralBus.PENDING, cpu.getRegC());

        bus.complete(request, PeripheralBus.OK, -123);
        cpu.tick(4);
        assertEquals(PeripheralBus.OK, cpu.getRegD());
        assertEquals(-123, cpu.getRegC());
        assertEquals(PeripheralBus.IDLE, cpu.getRegB());
        PeripheralBus.Request wrap = bus.pendingRequest();
        assertNotNull(wrap);
        assertEquals(PeripheralBus.Operation.WRAP, wrap.operation());

        cpu.powerOff();
        bus.complete(wrap, PeripheralBus.OK, 1);
        assertNull(bus.pendingRequest());
        assertEquals(PeripheralBus.IDLE, bus.status());
    }

    @Test
    void deviceKitLowersPeripheralOperationsToAssembly() {
        var result = N2Compiler.compile("""
                import <DeviceKit>

                func main()
                    peripheral_info(PERIPHERAL_NORTH)
                    peripheral_result()
                    peripheral_wrap(PERIPHERAL_NORTH)
                    peripheral_result()
                    peripheral_call(PERIPHERAL_NORTH, 12, 14 + 2, 18)
                    peripheral_result()
                    speaker_sample_rate(PERIPHERAL_EAST)
                    peripheral_result()
                    speaker_tone(PERIPHERAL_EAST, 440, 100)
                    peripheral_result()
                    note_block_set(PERIPHERAL_NORTH, 12)
                    peripheral_result()
                    note_block_get(PERIPHERAL_NORTH)
                    peripheral_status()
                    peripheral_result()
                end
                """);
        assertTrue(result.success(), result.errors().toString());
        var assembled = Assembler.assemble(result.assembly());
        assertTrue(assembled.success());
        assertTrue(assembled.requiredInstructions().containsAll(Set.of("PCALL", "PINFO", "PSTATUS", "PRESULT", "PWRAP")));

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 65536, assembled.bytecode(), false, 0);
        cpu.powerOn();
        List<PeripheralBus.Request> requests = new ArrayList<>();
        PeripheralBus bus = cpu.getPeripheralBus();
        for (int i = 0; i < 1500 && cpu.getState() == VirtualCpu.State.RUNNING; i++) {
            cpu.step();
            PeripheralBus.Request request = bus.pendingRequest();
            if (request != null) {
                requests.add(request);
                bus.complete(request, PeripheralBus.OK, 1);
            }
        }

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(List.of(
                PeripheralBus.Operation.INFO, PeripheralBus.Operation.WRAP, PeripheralBus.Operation.CALL,
                PeripheralBus.Operation.CALL, PeripheralBus.Operation.CALL, PeripheralBus.Operation.CALL,
                PeripheralBus.Operation.CALL), requests.stream().map(PeripheralBus.Request::operation).toList());
        assertEquals(List.of(2, 2, 2, 5, 5, 2, 2), requests.stream().map(PeripheralBus.Request::side).toList());
        assertEquals(List.of(0, 0, 12, 0x100, 0x101, 0x201, 0x200),
                requests.stream().map(PeripheralBus.Request::method).toList());
        assertEquals(List.of(0, 0, 16, 0, 440, 12, 0), requests.stream().map(PeripheralBus.Request::arg0).toList());
        assertEquals(List.of(0, 0, 18, 0, 100, 0, 0), requests.stream().map(PeripheralBus.Request::arg1).toList());
    }

    @Test
    void deviceKitCopiesStringArgumentBeforeTheHostReadsIt() {
        var compiled = N2Compiler.compile("""
                import <DeviceKit>

                func main()
                    local nickname = "Main engine"
                    peripheral_call_string(PERIPHERAL_EAST, 0x310, nickname, 1)
                end
                """);
        assertTrue(compiled.success(), compiled.errors().toString());
        var assembled = Assembler.assemble(compiled.assembly());
        assertTrue(assembled.requiredInstructions().contains("PCALLS"));

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 65536, assembled.bytecode(), false, 0);
        cpu.powerOn();
        PeripheralBus bus = cpu.getPeripheralBus();
        for (int i = 0; i < 1500 && bus.pendingRequest() == null; i++) cpu.step();
        PeripheralBus.Request request = bus.pendingRequest();
        assertNotNull(request);
        cpu.getRam()[request.arg0()] = 'X';

        assertEquals(PeripheralBus.Operation.CALL_STRING, request.operation());
        assertEquals(5, request.side());
        assertEquals(0x310, request.method());
        assertEquals("Main engine", request.text());
        assertEquals(1, request.arg1());
    }

    @Test
    void deviceKitCompilesSerialDiscoveryToPscan() {
        var compiled = N2Compiler.compile("""
                import <DeviceKit>

                func main()
                    peripheral_scan(PERIPHERAL_EAST)
                end
                """);
        assertTrue(compiled.success(), compiled.errors().toString());
        var assembled = Assembler.assemble(compiled.assembly());
        assertTrue(assembled.requiredInstructions().contains("PSCAN"));

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 65536, assembled.bytecode(), false, 0);
        cpu.powerOn();
        PeripheralBus bus = cpu.getPeripheralBus();
        for (int i = 0; i < 1500 && bus.pendingRequest() == null; i++) cpu.step();

        PeripheralBus.Request request = bus.pendingRequest();
        assertNotNull(request);
        assertEquals(PeripheralBus.Operation.SCAN, request.operation());
        assertEquals(5, request.side());
    }

    @Test
    void noteBlockPlaybackToolsSubmitNoteAndOneShotInstrument() {
        var compiled = N2Compiler.compile("""
                import <DeviceKit>

                func main()
                    note_block_play(PERIPHERAL_WEST, 12)
                    peripheral_result()
                    note_block_play_instrument(PERIPHERAL_WEST, "flute", 24)
                end
                """);
        assertTrue(compiled.success(), compiled.errors().toString());
        var assembled = Assembler.assemble(compiled.assembly());
        assertTrue(assembled.requiredInstructions().containsAll(Set.of("PCALL", "PCALLS")));

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 65536, assembled.bytecode(), false, 0);
        cpu.powerOn();
        PeripheralBus bus = cpu.getPeripheralBus();
        List<PeripheralBus.Request> requests = new ArrayList<>();
        for (int i = 0; i < 1500 && requests.size() < 2; i++) {
            cpu.step();
            var request = bus.pendingRequest();
            if (request != null) {
                requests.add(request);
                bus.complete(request, PeripheralBus.OK, 1);
            }
        }

        assertEquals(2, requests.size());
        assertEquals(0x202, requests.getFirst().method());
        assertEquals(12, requests.getFirst().arg0());
        var override = requests.getLast();
        assertEquals(PeripheralBus.Operation.CALL_STRING, override.operation());
        assertEquals(4, override.side());
        assertEquals(0x203, override.method());
        assertEquals("flute", override.text());
        assertEquals(24, override.arg1());
    }

    @Test
    void assemblyTextResultReservesTerminatorAndConsumesMailbox() {
        var assembled = Assembler.assemble(List.of("PCALL A, 2, 0x420, 0, 0", "PLEN B", "PRESULTS C, 10, 3",
                "PLEN D", "HLT"));
        assertTrue(assembled.success());
        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 256, assembled.bytecode(), false, 0);
        cpu.powerOn();
        cpu.step();
        PeripheralBus bus = cpu.getPeripheralBus();
        assertNotNull(bus.pendingRequest());
        bus.complete(bus.pendingRequest(), PeripheralBus.OK, 4, "é日ab");
        cpu.tick(16);

        assertEquals(4, cpu.getRegB());
        assertEquals(2, cpu.getRegC());
        assertEquals(0, cpu.getRegD());
        assertEquals('é', cpu.getRam()[10]);
        assertEquals('日', cpu.getRam()[11]);
        assertEquals(0, cpu.getRam()[12]);
        assertEquals(PeripheralBus.IDLE, bus.status());
    }

    @Test
    void documentedPeripheralExamplesReadTextFromHost() throws Exception {
        var assemblyExample = Assembler.assemble(Files.readAllLines(Path.of("docs/examples/environment-detector.asm")));
        assertTrue(assemblyExample.success());
        var n2Example = N2Compiler.compile(Files.readString(Path.of("docs/n-language/n2-examples/environment-detector.n2")));
        assertTrue(n2Example.success(), n2Example.errors().toString());
        var compiledExample = Assembler.assemble(n2Example.assembly());
        assertTrue(compiledExample.success());
        for (var program : List.of(assemblyExample, compiledExample)) {
            assertTrue(program.requiredInstructions().containsAll(Set.of("PCALL", "PLEN", "PRESULTS", "PWRAP")));
            VirtualCpu cpu = new VirtualCpu();
            cpu.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 65536, program.bytecode(), false, 0);
            cpu.powerOn();
            PeripheralBus bus = cpu.getPeripheralBus();
            List<Integer> methods = new ArrayList<>();
            String position = "{\"x\":-12.75,\"y\":64.5,\"z\":123.125}";
            for (int i = 0; i < 1000 && cpu.getState() == VirtualCpu.State.RUNNING; i++) {
                cpu.step();
                var request = bus.pendingRequest();
                if (request == null) continue;
                assertEquals(2, request.side());
                if (request.operation() == PeripheralBus.Operation.WRAP) {
                    bus.complete(request, PeripheralBus.OK, 1);
                } else {
                    methods.add(request.method());
                    assertEquals(0, request.arg0());
                    assertEquals(0, request.arg1());
                    String result = switch (request.method()) {
                        case 0x420 -> "minecraft:plains";
                        case 0x42d -> position;
                        default -> throw new AssertionError("Unexpected method: " + request.method());
                    };
                    bus.complete(request, PeripheralBus.OK, result.length(), result);
                }
            }
            assertEquals(VirtualCpu.State.HALTED, cpu.getState());
            assertEquals(List.of(0x420, 0x42d), methods);
            assertTrue(String.join("\n", cpu.getTerminalOutput()).contains("minecraft:plains"));
            assertTrue(String.join("\n", cpu.getTerminalOutput()).contains(position));
        }
    }

    @Test
    void deviceKitReportsIncorrectPeripheralArity() {
        var result = N2Compiler.compile("""
                import <DeviceKit>

                func main()
                    peripheral_wrap()
                end
                """);
        assertFalse(result.success());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("peripheral_wrap expects 1 arguments")));
    }
}
