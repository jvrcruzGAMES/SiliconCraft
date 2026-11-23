package games.jvrcruz.siliconcraft;

import games.jvrcruz.siliconcraft.compiler.Assembler;
import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import games.jvrcruz.siliconcraft.compiler.n2.N2Compiler;
import games.jvrcruz.siliconcraft.network.FramebufferCodec;
import games.jvrcruz.siliconcraft.vm.DriveStorage;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import games.jvrcruz.siliconcraft.vm.network.NetworkPolicy.ListenOptions;
import games.jvrcruz.siliconcraft.vm.network.NetworkPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HardwareUpgradeTest {
    @Test
    void highStorageAddressesAcrossTheSignedCapacityBoundary(@TempDir Path directory) {
        Path disk = directory.resolve("drive.bin");
        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 256, List.of(
                "SSIZE A, 1", "SWRITE 1, 2147483647, 173", "SREAD B, 1, 2147483647",
                "SREAD C, 1, 2147483646", "SBLKR 10, 1, 2147483646, 4", "HLT"), false, 0);
        cpu.attachDrive(1, DriveStorage.onDisk(disk), 1L << 31);
        cpu.powerOn();
        cpu.tick(20);
        assertEquals(0x80000000, cpu.getRegA());
        assertEquals(173, cpu.getRegB());
        assertEquals(0, cpu.getRegC());
        assertEquals(0, cpu.getRam()[10]);
        assertEquals(173, cpu.getRam()[11]);
        byte[] read = new byte[1];
        DriveStorage.onDisk(disk).read(Integer.MAX_VALUE, read, 1);
        assertEquals(173, Byte.toUnsignedInt(read[0]));
    }

    @Test
    void splitsNoisyFramebufferWithinThePacketLimitAndRestoresLegacyPixels() {
        int[] original = new Random(7).ints(VirtualCpu.FB_WIDTH * VirtualCpu.FB_HEIGHT).toArray();
        int[] received = new int[original.length];
        for (int offset = 0; offset < original.length; offset += FramebufferCodec.PACKET_PIXELS) {
            int count = Math.min(FramebufferCodec.PACKET_PIXELS, original.length - offset);
            byte[] compressed = FramebufferCodec.compress(original, offset, count);
            assertTrue(compressed.length <= FramebufferCodec.PACKET_BYTES);
            int[] strip = FramebufferCodec.decompress(compressed);
            System.arraycopy(strip, 0, received, offset, strip.length);
        }
        assertArrayEquals(original, received);
        int[] legacy = new int[480 * 270];
        legacy[480 * 270 - 1] = 17;
        FramebufferCodec.restore(legacy, received);
        assertEquals(17, received[960 * 540 - 1]);
        assertEquals(17, received[960 * 539 - 2]);
    }

    @Test
    void listenPortConfigSupportsCommaSeparatedPortsAndTheWildcard() {
        assertEquals(Set.of(), ListenOptions.parsePorts(""));
        assertEquals(Set.of(80, 8443, 65535), ListenOptions.parsePorts("80, 8443, 65535"));
        assertEquals(Set.of(-1, 8443), ListenOptions.parsePorts("-1, 8443"));
        assertThrows(IllegalArgumentException.class, () -> ListenOptions.parsePorts("8080,"));
        assertThrows(IllegalArgumentException.class, () -> ListenOptions.parsePorts("65536"));
        assertThrows(IllegalArgumentException.class, () -> ListenOptions.parsePorts("0"));
    }

    @Test
    void networkLibraryUsesAssemblyAndHonorsDisabledPort() {
        var compile = N2Compiler.compile("""
                import <Network>

                func main()
                    network_http(NET_GET, "https://example.org/", 0, 0)
                    local received = network_result(0, 0)
                    network_status()
                    network_http3(NET_GET, "https://example.org/", 0, 0)
                    network_websocket("wss://example.org/")
                    network_send_text(1, 0, 0)
                    network_tls(1)
                    network_open(NET_IMAPS, "example.org", 993)
                    network_listen(NET_WSS, 8443)
                    network_accept(1)
                    network_respond(2, 200, 0, 0)
                end
                """);
        assertTrue(compile.success(), compile.errors().toString());
        var assembled = Assembler.assemble(compile.assembly());
        assertTrue(assembled.success(), assembled.errors().toString());
        assertTrue(assembled.requiredInstructions().containsAll(Set.of(
                "NHTTP", "NSTATUS", "NRESULT", "NHTTP3", "NWSOPEN", "NWSTEXT", "NTLS", "NOPEN", "NLISTEN", "NACCEPT", "NRESPOND")));

        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 65536, assembled.bytecode(), false, 0);
        cpu.powerOn();
        for (int i = 0; i < 1500 && cpu.getNetworkPort().status() == NetworkPort.IDLE; i++) cpu.step();
        assertEquals(NetworkPort.DISABLED, cpu.getNetworkPort().status());
    }
}
