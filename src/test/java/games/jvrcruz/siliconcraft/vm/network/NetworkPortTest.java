package games.jvrcruz.siliconcraft.vm.network;

import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NetworkPortTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final byte[] EMPTY = new byte[0];

    @Test
    void disabledAndDefaultAddressChecksRejectConnections() throws Exception {
        NetworkPort port = new NetworkPort();
        try {
            assertEquals(NetworkPort.DISABLED, execute(port, open(NetworkPort.TCP, 80)).status());
            port.setPolicy(new NetworkPolicy(true, true, true, true, false, Set.of(), Set.of(), 1000, 1024));
            assertEquals(NetworkPort.DENIED, execute(port, open(NetworkPort.TCP, 80)).status());
            assertFalse(NetworkPolicy.isPublic(InetAddress.getByName("100.100.100.200")));
            assertFalse(NetworkPolicy.isPublic(InetAddress.getByName("169.254.169.254")));
            assertFalse(NetworkPolicy.isPublic(InetAddress.getByName("fc00::1")));
            assertFalse(NetworkPolicy.isPublic(InetAddress.getByName("2002:7f00:1::")));
            assertTrue(NetworkPolicy.isPublic(InetAddress.getByName("93.184.216.34")));
        } finally {
            port.reset();
        }
    }

    @Test
    void tcpAndHttpUseTheAllowedServerEndpoint() throws Exception {
        try (ServerSocket endpoint = new ServerSocket(0, 4, LOOPBACK)) {
            Thread.ofVirtual().start(() -> {
                try (var tcp = endpoint.accept()) {
                    tcp.getOutputStream().write(tcp.getInputStream().readNBytes(3));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                try (var http = endpoint.accept()) {
                    var input = http.getInputStream();
                    int end = 0;
                    while (end != 0x0d0a0d0a) {
                        int next = input.read();
                        if (next < 0) return;
                        end = end << 8 | next;
                    }
                    http.getOutputStream().write(("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                            + "3\r\nabc\r\n0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            NetworkPort port = new NetworkPort();
            port.setPolicy(localPolicy(endpoint.getLocalPort()));
            try {
                var opened = execute(port, open(NetworkPort.TCP, endpoint.getLocalPort()));
                assertEquals(NetworkPort.OK, opened.status());
                int handle = opened.value();
                byte[] sent = {0, 10, (byte) 255};
                assertEquals(3, execute(port, new NetworkPort.Request(NetworkPort.Operation.SEND, handle, 0, null, sent)).value());
                assertArrayEquals(sent, execute(port, new NetworkPort.Request(NetworkPort.Operation.RECEIVE, handle, 3, null, EMPTY)).data());
                assertEquals(1, execute(port, new NetworkPort.Request(NetworkPort.Operation.CLOSE, handle, 0, null, EMPTY)).value());

                String url = "http://" + host() + ":" + endpoint.getLocalPort() + "/silicon";
                var response = execute(port, new NetworkPort.Request(NetworkPort.Operation.HTTP, 0, 0, url, EMPTY));
                assertEquals(NetworkPort.OK, response.status());
                assertEquals(200, response.value());
                assertArrayEquals(new byte[]{'a', 'b', 'c'}, response.data());
            } finally {
                port.reset();
            }
        }
    }

    @Test
    void udpSendsAndReceivesOneDatagram() throws Exception {
        try (DatagramSocket endpoint = new DatagramSocket(0, LOOPBACK)) {
            Thread.ofVirtual().start(() -> {
                try {
                    DatagramPacket packet = new DatagramPacket(new byte[32], 32);
                    endpoint.receive(packet);
                    endpoint.send(packet);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            NetworkPort port = new NetworkPort();
            port.setPolicy(localPolicy(endpoint.getLocalPort()));
            try {
                var opened = execute(port, open(NetworkPort.UDP, endpoint.getLocalPort()));
                assertEquals(NetworkPort.OK, opened.status());
                byte[] data = {4, 5, 6};
                assertEquals(3, execute(port, new NetworkPort.Request(NetworkPort.Operation.SEND, opened.value(), 0, null, data)).value());
                assertArrayEquals(data, execute(port, new NetworkPort.Request(NetworkPort.Operation.RECEIVE, opened.value(), 32, null, EMPTY)).data());
            } finally {
                port.reset();
            }
        }
    }

    private static NetworkPort.Request open(int protocol, int port) {
        return new NetworkPort.Request(NetworkPort.Operation.OPEN, protocol, port, LOOPBACK.getHostAddress(), EMPTY);
    }

    private static NetworkPolicy localPolicy(int port) {
        return new NetworkPolicy(true, true, true, true, true, Set.of(LOOPBACK.getHostAddress()), Set.of(port), 2000, 1024);
    }

    private static String host() {
        String host = LOOPBACK.getHostAddress();
        return host.contains(":") ? "[" + host + "]" : host;
    }

    private static NetworkPort.Result execute(NetworkPort port, NetworkPort.Request request) throws InterruptedException {
        Thread.sleep(110);
        assertTrue(port.submit(request));
        return assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
            while (port.status() == NetworkPort.PENDING) Thread.sleep(2);
            return port.takeResult();
        });
    }
}
