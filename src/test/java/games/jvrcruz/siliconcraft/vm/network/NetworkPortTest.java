package games.jvrcruz.siliconcraft.vm.network;

import games.jvrcruz.siliconcraft.compiler.Assembler;
import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import games.jvrcruz.siliconcraft.compiler.n2.N2Compiler;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class NetworkPortTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final byte[] EMPTY = new byte[0];

    @Test
    void destinationPolicyAllowsPrivateAddressesByDefaultAndCanRejectThem() throws Exception {
        NetworkPort port = new NetworkPort();
        try {
            assertEquals(NetworkPort.DISABLED, execute(port, open(NetworkPort.TCP, 80)).status());
            var defaultAddresses = new NetworkPolicy(true, true, true, true, Set.of(), Set.of(80), 1000, 1024);
            assertEquals(LOOPBACK, defaultAddresses.resolve(LOOPBACK.getHostAddress(), 80));
            for (String destination : new String[]{"10.1.2.3", "169.254.1.1", "192.0.2.1", "fe80::1"}) {
                assertEquals(InetAddress.getByName(destination), defaultAddresses.resolve(destination, 80));
            }
            port.setPolicy(publicOnly(80));
            assertEquals(NetworkPort.DENIED, execute(port, open(NetworkPort.TCP, 80)).status());
            assertFalse(NetworkPolicy.isPublic(InetAddress.getByName("100.100.100.200")));
            assertFalse(NetworkPolicy.isPublic(InetAddress.getByName("169.254.169.254")));
            assertFalse(NetworkPolicy.isPublic(InetAddress.getByName("fc00::1")));
            assertFalse(NetworkPolicy.isPublic(InetAddress.getByName("2002:7f00:1::")));
            assertFalse(NetworkPolicy.isPublic(InetAddress.getByName("::ffff:127.0.0.1")));
            assertFalse(NetworkPolicy.isPublic(InetAddress.getByName("64:ff9b::7f00:1")));
            assertFalse(NetworkPolicy.isPublic(InetAddress.getByName("2001:2::1")));
            assertFalse(NetworkPolicy.isPublic(InetAddress.getByName("3fff:fff::1")));
            assertFalse(NetworkPolicy.isPublic(InetAddress.getByName("192.88.99.2")));
            assertTrue(NetworkPolicy.isPublic(InetAddress.getByName("93.184.216.34")));
            assertTrue(NetworkPolicy.isPublic(InetAddress.getByName("2606:4700:4700::1111")));
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

            NetworkPort port = loopbackPort();
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
            NetworkPort port = loopbackPort();
            port.setPolicy(localPolicy(endpoint.getLocalPort()));
            try {
                var opened = execute(port, open(NetworkPort.UDP, endpoint.getLocalPort()));
                assertEquals(NetworkPort.OK, opened.status());
                assertEquals(NetworkPort.INVALID, execute(port, new NetworkPort.Request(NetworkPort.Operation.RECEIVE,
                        opened.value(), 32, null, EMPTY)).status());
                byte[] data = {4, 5, 6};
                assertEquals(3, execute(port, new NetworkPort.Request(NetworkPort.Operation.SEND, opened.value(), 0, null, data)).value());
                assertArrayEquals(data, execute(port, new NetworkPort.Request(NetworkPort.Operation.RECEIVE, opened.value(), 32, null, EMPTY)).data());
            } finally {
                port.reset();
            }
        }
    }

    @Test
    void webSocketExchangesMaskedFramesAndReassemblesMessagesWithInterleavedPing() throws Exception {
        try (ServerSocket endpoint = new ServerSocket(0, 1, LOOPBACK)) {
            var handled = new CompletableFuture<Void>();
            Thread.ofVirtual().start(() -> {
                try (var socket = endpoint.accept()) {
                    socket.setSoTimeout(2500);
                    var input = new BufferedInputStream(socket.getInputStream());
                    assertEquals("GET /echo?q=1 HTTP/1.1", NetworkPort.readLine(input));
                    String key = null;
                    for (String line; !(line = NetworkPort.readLine(input)).isEmpty(); ) {
                        if (line.startsWith("Sec-WebSocket-Key: ")) key = line.substring(19);
                    }
                    assertNotNull(key);
                    var output = socket.getOutputStream();
                    output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                            + "Connection: keep-alive, Upgrade\r\nSec-WebSocket-Accept: "
                            + WebSocketStream.acceptKey(key) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    assertArrayEquals(new byte[]{0, (byte) 255}, readMaskedFrame(input, 2));
                    assertArrayEquals(new byte[]{'h', 'i'}, readMaskedFrame(input, 1));

                    output.write(new byte[]{1, 2, 'a', 'b', (byte) 0x89, 1, '?', (byte) 0x80, 1, 'c'});
                    assertArrayEquals(new byte[]{'?'}, readMaskedFrame(input, 10));
                    assertArrayEquals(new byte[]{3, (byte) 232}, readMaskedFrame(input, 8));
                    handled.complete(null);
                } catch (Throwable error) {
                    handled.completeExceptionally(error);
                }
            });

            NetworkPort port = loopbackPort();
            port.setPolicy(localPolicy(endpoint.getLocalPort()));
            try {
                var opened = execute(port, new NetworkPort.Request(NetworkPort.Operation.WEBSOCKET, 0, 0,
                        "ws://" + host() + ":" + endpoint.getLocalPort() + "/echo?q=1", EMPTY));
                assertEquals(NetworkPort.OK, opened.status());
                int handle = opened.value();
                assertEquals(NetworkPort.OK, execute(port, new NetworkPort.Request(NetworkPort.Operation.SEND,
                        handle, 0, null, new byte[]{0, (byte) 255})).status());
                assertEquals(NetworkPort.OK, execute(port, new NetworkPort.Request(NetworkPort.Operation.SEND_TEXT,
                        handle, 0, null, new byte[]{'h', 'i'})).status());
                assertArrayEquals(new byte[]{'a', 'b', 'c'}, execute(port,
                        new NetworkPort.Request(NetworkPort.Operation.RECEIVE, handle, 16, null, EMPTY)).data());
                assertEquals(1, execute(port, new NetworkPort.Request(NetworkPort.Operation.CLOSE,
                        handle, 0, null, EMPTY)).value());
                handled.get(3, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                port.reset();
            }
        }
    }

    @Test
    void mailProtocolsShareStreamHandlesAndRespectPolicy() throws Exception {
        try (ServerSocket endpoint = new ServerSocket(0, 2, LOOPBACK)) {
            byte[] greeting = "220 or * OK\r\n".getBytes(StandardCharsets.US_ASCII);
            Thread.ofVirtual().start(() -> {
                for (int i = 0; i < 2; i++) {
                    try (var socket = endpoint.accept()) {
                        socket.getOutputStream().write(greeting);
                    } catch (Exception error) {
                        return;
                    }
                }
            });

            NetworkPort port = loopbackPort();
            try {
                port.setPolicy(localPolicy(endpoint.getLocalPort()));
                for (int protocol : new int[]{NetworkPort.SMTP, NetworkPort.IMAP}) {
                    var opened = execute(port, open(protocol, endpoint.getLocalPort()));
                    assertEquals(NetworkPort.OK, opened.status());
                    var received = execute(port, new NetworkPort.Request(NetworkPort.Operation.RECEIVE,
                            opened.value(), greeting.length, null, EMPTY));
                    assertArrayEquals(greeting, received.data());
                    assertEquals(NetworkPort.OK, execute(port, new NetworkPort.Request(NetworkPort.Operation.CLOSE,
                            opened.value(), 0, null, EMPTY)).status());
                }
                port.setPolicy(new NetworkPolicy(true, true, true, true, Set.of(), Set.of(),
                        1000, 1024, false, false, false, true));
                assertEquals(NetworkPort.DENIED, execute(port, open(NetworkPort.SMTPS, endpoint.getLocalPort())).status());
                assertEquals(NetworkPort.DENIED, execute(port, open(NetworkPort.IMAPS, endpoint.getLocalPort())).status());
                assertEquals(NetworkPort.DENIED, execute(port, new NetworkPort.Request(NetworkPort.Operation.WEBSOCKET,
                        0, 0, "ws://" + host() + ":" + endpoint.getLocalPort(), EMPTY)).status());
            } finally {
                port.reset();
            }
        }
    }

    @Test
    void disabledHttp3DeniesRequestsWithoutFallingBack() throws Exception {
        var port = loopbackPort();
        port.setPolicy(new NetworkPolicy(true, true, true, true,
                Set.of(LOOPBACK.getHostAddress()), Set.of(443), 2000, 1024, true, true, true, false));
        try {
            var http3 = new NetworkPort.Request(NetworkPort.Operation.HTTP3, 0, 0, "https://" + host(), EMPTY);
            assertEquals(NetworkPort.DENIED, execute(port, http3).status());
        } finally {
            port.reset();
        }
    }

    private static byte[] readMaskedFrame(BufferedInputStream input, int opcode) throws Exception {
        assertEquals(0x80 | opcode, input.read());
        int size = input.read();
        assertTrue(size >= 128 && size < 254);
        byte[] mask = input.readNBytes(4);
        byte[] data = input.readNBytes(size & 127);
        assertEquals(size & 127, data.length);
        for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
        return data;
    }

    private static NetworkPort.Request open(int protocol, int port) {
        return new NetworkPort.Request(NetworkPort.Operation.OPEN, protocol, port, LOOPBACK.getHostAddress(), EMPTY);
    }

    private static NetworkPort loopbackPort() {
        return new NetworkPort((policy, host, port) -> {
            if (!policy.hosts().contains(NetworkPolicy.normalizeHost(host)) || !policy.ports().contains(port)) {
                throw new SecurityException("Unexpected test destination");
            }
            return LOOPBACK;
        });
    }

    @Test
    void allNetworkEntryPointsRejectLoopbackEvenWhenAllowlisted() throws Exception {
        var port = new NetworkPort();
        port.setPolicy(publicOnly(443));
        try {
            for (int protocol : new int[]{NetworkPort.TCP, NetworkPort.UDP, NetworkPort.SMTP, NetworkPort.SMTPS,
                    NetworkPort.IMAP, NetworkPort.IMAPS}) {
                assertEquals(NetworkPort.DENIED, execute(port, open(protocol, 443)).status());
            }
            for (var request : new NetworkPort.Request[]{
                    new NetworkPort.Request(NetworkPort.Operation.HTTP, 0, 0, "https://" + host(), EMPTY),
                    new NetworkPort.Request(NetworkPort.Operation.HTTP3, 0, 0, "https://" + host(), EMPTY),
                    new NetworkPort.Request(NetworkPort.Operation.WEBSOCKET, 0, 0, "wss://" + host(), EMPTY)}) {
                assertEquals(NetworkPort.DENIED, execute(port, request).status());
            }
        } finally {
            port.reset();
        }
    }

    @Test
    void listenerPolicyChecksBothTheToggleAndTheAllowlistAndClosesOnReload() throws Exception {
        int number = freePort(false);
        var port = new NetworkPort();
        try {
            port.setPolicy(localPolicy(number));
            assertEquals(NetworkPort.DENIED, execute(port, listen(NetworkPort.TCP, number)).status());
            port.setPolicy(listenerPolicy(Set.of(), false));
            assertEquals(NetworkPort.DENIED, execute(port, listen(NetworkPort.TCP, number)).status());
            port.setPolicy(listenerPolicy(Set.of(number), true));
            int other = number == 65535 ? 65534 : number + 1;
            assertEquals(NetworkPort.DENIED, execute(port, listen(NetworkPort.TCP, other)).status());
            assertEquals(NetworkPort.OK, execute(port, listen(NetworkPort.TCP, number)).status());
            port.setPolicy(localPolicy(number));
            try (var rebound = new ServerSocket(number)) {
                assertEquals(number, rebound.getLocalPort());
            }
            port.setPolicy(listenerPolicy(Set.of(-1), false));
            assertEquals(NetworkPort.INVALID, execute(port, listen(NetworkPort.TCP, 0)).status());
            assertEquals(NetworkPort.OK, execute(port, listen(NetworkPort.TCP, number)).status());
        } finally {
            port.reset();
        }
    }

    @Test
    void tcpListenerAcceptsAClientAndExchangesBytes() throws Exception {
        int number = freePort(false);
        var port = new NetworkPort();
        port.setPolicy(listenerPolicy(Set.of(number), true));
        try {
            int listener = checked(execute(port, listen(NetworkPort.TCP, number))).value();
            try (var client = new java.net.Socket(LOOPBACK, number)) {
                client.setSoTimeout(2000);
                int accepted = checked(execute(port, request(NetworkPort.Operation.ACCEPT, listener, 0, EMPTY))).value();
                assertNotEquals(listener, accepted);
                client.getOutputStream().write('a');
                assertArrayEquals(new byte[]{'a'}, checked(execute(port,
                        request(NetworkPort.Operation.RECEIVE, accepted, 10, EMPTY))).data());
                checked(execute(port, request(NetworkPort.Operation.SEND, accepted, 0, new byte[]{'b'})));
                assertEquals('b', client.getInputStream().read());
            }
        } finally {
            port.reset();
        }
    }

    @Test
    void udpListenerRepliesToTheMostRecentSender() throws Exception {
        int number = freePort(true);
        var port = new NetworkPort();
        port.setPolicy(listenerPolicy(Set.of(number), true));
        try (var client = new DatagramSocket(0, LOOPBACK)) {
            client.setSoTimeout(2000);
            int listener = checked(execute(port, listen(NetworkPort.UDP, number))).value();
            assertEquals(NetworkPort.INVALID, execute(port,
                    request(NetworkPort.Operation.SEND, listener, 0, new byte[]{'b'})).status());
            client.send(new DatagramPacket(new byte[]{'a'}, 1, LOOPBACK, number));
            assertArrayEquals(new byte[]{'a'}, checked(execute(port,
                    request(NetworkPort.Operation.RECEIVE, listener, 16, EMPTY))).data());
            checked(execute(port, request(NetworkPort.Operation.SEND, listener, 0, new byte[]{'b'})));
            var reply = new DatagramPacket(new byte[8], 8);
            client.receive(reply);
            assertEquals(1, reply.getLength());
            assertEquals('b', reply.getData()[0]);
            assertEquals(number, reply.getPort());
        } finally {
            port.reset();
        }
    }

    @Test
    void httpListenerReturnsRequestBytesAndWritesAFramedResponse() throws Exception {
        int number = freePort(false);
        var port = new NetworkPort();
        port.setPolicy(listenerPolicy(Set.of(number), true));
        try {
            int listener = checked(execute(port, listen(NetworkPort.HTTP, number))).value();
            try (var client = new java.net.Socket(LOOPBACK, number)) {
                client.setSoTimeout(2000);
                byte[] request = "POST /store HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\n\r\nabc"
                        .getBytes(StandardCharsets.US_ASCII);
                client.getOutputStream().write(request);
                var accepted = checked(execute(port, request(NetworkPort.Operation.ACCEPT, listener, 0, EMPTY)));
                assertArrayEquals(request, accepted.data());
                checked(execute(port, request(NetworkPort.Operation.RESPOND, accepted.value(), 201, new byte[]{'x'})));
                String response = new String(client.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                assertTrue(response.startsWith("HTTP/1.1 201 "), response);
                assertTrue(response.endsWith("\r\n\r\nx"), response);
            }
        } finally {
            port.reset();
        }
    }

    @Test
    void webSocketListenerWorksWithTheBuiltInWebSocketClient() throws Exception {
        int number = freePort(false);
        var server = new NetworkPort();
        var client = new NetworkPort();
        server.setPolicy(listenerPolicy(Set.of(number), true));
        client.setPolicy(localPolicy(number));
        try {
            int listener = checked(execute(server, listen(NetworkPort.WS, number))).value();
            assertTrue(client.submit(new NetworkPort.Request(NetworkPort.Operation.WEBSOCKET, 0, 0,
                    "ws://" + host() + ":" + number + "/echo", EMPTY)));
            var accepted = checked(execute(server, request(NetworkPort.Operation.ACCEPT, listener, 0, EMPTY)));
            int outgoing = checked(await(client)).value();
            assertTrue(new String(accepted.data(), StandardCharsets.US_ASCII).startsWith("GET /echo HTTP/1.1"));
            checked(execute(client, request(NetworkPort.Operation.SEND_TEXT, outgoing, 0, new byte[]{'a'})));
            assertArrayEquals(new byte[]{'a'}, checked(execute(server,
                    request(NetworkPort.Operation.RECEIVE, accepted.value(), 16, EMPTY))).data());
            checked(execute(server, request(NetworkPort.Operation.SEND, accepted.value(), 0, new byte[]{'b'})));
            assertArrayEquals(new byte[]{'b'}, checked(execute(client,
                    request(NetworkPort.Operation.RECEIVE, outgoing, 16, EMPTY))).data());
        } finally {
            client.reset();
            server.reset();
        }
    }

    @Test
    void n2HttpServerRunsThroughAssembledVmInstructions() throws Exception {
        int number = freePort(false);
        var compiled = N2Compiler.compile("""
                import <Network>
                import <std>

                func collect(address, count)
                    while network_status() == NET_PENDING do
                        sleep(1)
                    end
                    local status = network_status()
                    local result = network_result(address, count)
                    if status != NET_OK then
                        return -1
                    end
                    return result
                end

                func main()
                    if network_listen(NET_HTTP, %d) != 1 then
                        return
                    end
                    local listener = collect(0, 0)
                    if listener < 1 then
                        return
                    end
                    sleep(3)
                    if network_accept(listener) != 1 then
                        return
                    end
                    local connection = collect(8192, 1024)
                    if connection < 1 then
                        return
                    end
                    sleep(3)
                    if network_respond(connection, 201, 8192, 3) != 1 then
                        return
                    end
                    collect(0, 0)
                    if network_close(listener) == 1 then
                        collect(0, 0)
                    end
                end
                """.formatted(number));
        assertTrue(compiled.success(), compiled.errors().toString());
        var assembled = Assembler.assemble(compiled.assembly());
        assertTrue(assembled.success(), assembled.errors().toString());
        assertTrue(assembled.requiredInstructions().containsAll(Set.of(
                "NLISTEN", "NACCEPT", "NRESPOND", "NCLOSE", "NSTATUS", "NRESULT")));

        var cpu = new VirtualCpu();
        cpu.configure(assembled.requiredInstructions(), 65536, assembled.bytecode(), false, 0);
        cpu.getNetworkPort().setPolicy(listenerPolicy(Set.of(number), true));
        cpu.powerOn();
        var response = new CompletableFuture<String>();
        Thread.ofVirtual().start(() -> {
            try {
                // The VM starts listening asynchronously. Give it time to bind.
                for (int attempt = 0; attempt < 40; attempt++) {
                    try (var socket = new java.net.Socket(LOOPBACK, number)) {
                        socket.setSoTimeout(3000);
                        socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"
                                .getBytes(StandardCharsets.US_ASCII));
                        response.complete(new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII));
                        return;
                    } catch (java.net.ConnectException retry) {
                        Thread.sleep(25);
                    }
                }
                response.completeExceptionally(new AssertionError("VM listener never bound"));
            } catch (Exception error) {
                response.completeExceptionally(error);
            }
        });
        try {
            for (int tick = 0; tick < 80 && cpu.getState() == VirtualCpu.State.RUNNING; tick++) {
                cpu.tick(2500);
                Thread.sleep(50);
            }
            assertEquals(VirtualCpu.State.HALTED, cpu.getState(), cpu.getErrorMessage());
            String message = response.get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(message.startsWith("HTTP/1.1 201 "), message);
            assertTrue(message.endsWith("\r\n\r\nGET"), message);
        } finally {
            cpu.getNetworkPort().reset();
        }
    }

    @Test
    void assemblyCannotOpenAPortWithoutTheListenOpcodeOnTheSoc() {
        var cpu = new VirtualCpu();
        var instructions = new java.util.HashSet<>(InstructionValidator.ALL_INSTRUCTIONS_SET);
        instructions.remove("NLISTEN");
        var assembly = Assembler.assemble(java.util.List.of("NLISTEN A, 1, 8443", "HLT"));
        assertTrue(assembly.success());
        cpu.configure(instructions, 256, assembly.bytecode(), false, 0);
        cpu.powerOn();
        cpu.step();
        assertEquals(VirtualCpu.State.ERROR, cpu.getState());
        assertTrue(cpu.getErrorMessage().contains("NLISTEN"), cpu.getErrorMessage());
        assertEquals(NetworkPort.IDLE, cpu.getNetworkPort().status());
    }

    @Test
    void highLevelHttpRoutesAndClientCallbacksRunOnTheGuestCpu() throws Exception {
        int number = freePort(false);
        var server = compileHttpCpu("""
                import <Network>

                func echo(address, length)
                    return http_reply(201, http_body(address, length))
                end

                func fallback(address, length)
                    return http_reply(200, "matched")
                end

                func main()
                    local listener = http_listen(NET_HTTP, %d)
                    local routes = [http_route(NET_POST, "/echo", echo), http_route(NET_ANY, "/ok", fallback)]
                    http_dispatch(listener, routes)
                    http_dispatch(listener, routes)
                    http_dispatch(listener, routes)
                    network_close(listener)
                end
                """.formatted(number));
        String base = "http://" + host() + ":" + number;
        var client = compileHttpCpu("""
                from <Network> import http_get, http_post, http_text
                import <std>

                func completed(status, code, address, length)
                    println(status)
                    println(code)
                    println(length)
                    if code == 200 then
                        println(http_text(address, length))
                    end
                end

                func main()
                    http_get("%s/missing", completed)
                    http_post("%s/echo?test=1", "Héllo 🌍", completed)
                    http_get("%s/ok", completed)
                end
                """.formatted(base, base, base));
        server.getNetworkPort().setPolicy(listenerPolicy(Set.of(number), true));
        client.getNetworkPort().setPolicy(localPolicy(number));
        server.powerOn();
        try {
            for (int tick = 0; tick < 60; tick++) {
                server.tick(2500);
                Thread.sleep(3);
            }
            client.powerOn();
            for (int tick = 0; tick < 1500 && (client.getState() == VirtualCpu.State.RUNNING
                    || server.getState() == VirtualCpu.State.RUNNING); tick++) {
                server.tick(2500);
                client.tick(2500);
                Thread.sleep(3);
            }
            assertEquals(VirtualCpu.State.HALTED, server.getState(), server.getErrorMessage());
            assertEquals(VirtualCpu.State.HALTED, client.getState(), client.getErrorMessage());
            var output = client.getTerminalOutput();
            assertEquals(java.util.List.of("2", "404", "0", "2", "201", "11", "2", "200", "7", "matched"),
                    output.subList(0, Math.min(output.size(), 10)), output.toString());
        } finally {
            server.getNetworkPort().reset();
            client.getNetworkPort().reset();
        }
    }

    @Test
    void httpHelpersRejectCallbacksWithWrongArity() {
        var result = N2Compiler.compile("""
                from <Network> import http_get
                func done(status)
                    return status
                end
                func main()
                    http_get("https://example.org", done)
                end
                """);
        assertFalse(result.success());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("4-parameter callback")), result.errors().toString());
    }

    @Test
    void httpCallbacksReportBusyWithoutConsumingTheRawMailbox() {
        var compiled = N2Compiler.compile("""
                import <Network>
                import <std>

                func completed(status, code, address, length)
                    println(status)
                    return status
                end

                func main()
                    network_http(NET_GET, "http://localhost/", 0, 0)
                    http_get("http://localhost/", completed)
                    println(network_status())
                    network_result(0, 0)
                    http_get("http://localhost/", completed)
                end
                """);
        assertTrue(compiled.success(), compiled.errors().toString());
        var assembled = Assembler.assemble(compiled.assembly());
        assertTrue(assembled.success(), assembled.errors().toString());
        var cpu = new VirtualCpu();
        cpu.configure(assembled.requiredInstructions(), 65536, assembled.bytecode(), false, 0);
        cpu.powerOn();
        for (int tick = 0; tick < 200 && cpu.getState() == VirtualCpu.State.RUNNING; tick++) cpu.tick(2500);
        assertEquals(VirtualCpu.State.HALTED, cpu.getState(), cpu.getErrorMessage());
        var output = cpu.getTerminalOutput();
        assertEquals(java.util.List.of("-7", "-1", "-1"), output.subList(0, Math.min(output.size(), 3)), output.toString());
    }

    private static VirtualCpu compileHttpCpu(String source) {
        var compiled = N2Compiler.compile(source);
        assertTrue(compiled.success(), compiled.errors().toString());
        var assembled = Assembler.assemble(compiled.assembly());
        assertTrue(assembled.success(), assembled.errors().toString());
        assertTrue(assembled.requiredInstructions().containsAll(Set.of("CALL", "NREADY", "NUTF8", "NSTATUS", "NRESULT")));
        assertTrue(assembled.bytecode().contains("CALL A"));
        assertFalse(assembled.requiredInstructions().contains("NHTTP3"));
        var cpu = new VirtualCpu();
        cpu.configure(assembled.requiredInstructions(), 65536, assembled.bytecode(), false, 0);
        return cpu;
    }

    private static int freePort(boolean udp) throws Exception {
        if (udp) {
            try (var socket = new DatagramSocket(0, LOOPBACK)) {
                return socket.getLocalPort();
            }
        }
        try (var socket = new ServerSocket(0, 1, LOOPBACK)) {
            return socket.getLocalPort();
        }
    }

    private static NetworkPort.Result checked(NetworkPort.Result result) {
        assertEquals(NetworkPort.OK, result.status());
        return result;
    }

    private static NetworkPort.Request listen(int protocol, int port) {
        return request(NetworkPort.Operation.LISTEN, protocol, port, EMPTY);
    }

    private static NetworkPort.Request request(NetworkPort.Operation operation, int first, int second, byte[] data) {
        return new NetworkPort.Request(operation, first, second, null, data);
    }

    private static NetworkPolicy listenerPolicy(Set<Integer> ports, boolean privateAllowed) {
        return new NetworkPolicy(true, true, true, true, Set.of(), Set.of(), 2000, 1024,
                true, true, true, true, privateAllowed, new NetworkPolicy.ListenOptions(true, ports, ""));
    }

    private static NetworkPolicy publicOnly(int port) {
        return new NetworkPolicy(true, true, true, true, Set.of(LOOPBACK.getHostAddress()), Set.of(port), 2000, 1024,
                true, true, true, true, false, NetworkPolicy.ListenOptions.DISABLED);
    }

    private static NetworkPolicy localPolicy(int port) {
        return new NetworkPolicy(true, true, true, true, Set.of(LOOPBACK.getHostAddress()), Set.of(port), 2000, 1024);
    }

    private static String host() {
        String host = LOOPBACK.getHostAddress();
        return host.contains(":") ? "[" + host + "]" : host;
    }

    private static NetworkPort.Result execute(NetworkPort port, NetworkPort.Request request) throws InterruptedException {
        Thread.sleep(110);
        assertTrue(port.submit(request));
        return await(port);
    }

    private static NetworkPort.Result await(NetworkPort port) {
        return assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
            while (port.status() == NetworkPort.PENDING) Thread.sleep(2);
            return port.takeResult();
        });
    }
}
