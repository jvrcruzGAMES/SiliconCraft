package games.jvrcruz.siliconcraft.vm.network;

import tech.kwik.core.QuicClientConnection;
import tech.kwik.flupke.Http3SingleConnectionClient;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class Http3Client {
    private static final int QUIC_OVERHEAD_BUDGET = 256 * 1024;
    private static final byte[] EMPTY = new byte[0];

    private Http3Client() {}

    static NetworkPort.Result exchange(NetworkPolicy policy, URI uri, InetAddress address, int port, String method,
                                       byte[] body, boolean text, Consumer<Runnable> abort) throws IOException {
        Duration timeout = Duration.ofMillis(policy.timeoutMs());
        var endpoint = new InetSocketAddress(address, port);
        try (var socket = new BoundedSocket(policy.transferLimit() + QUIC_OVERHEAD_BUDGET)) {
            abort.accept(socket::close);
            socket.connect(endpoint);
            // Keep the HTTPS origin for TLS and use the vetted IP for QUIC transport.
            QuicClientConnection quic = QuicClientConnection.newBuilder()
                    .host(NetworkPolicy.tlsHost(NetworkPolicy.uriHost(uri)))
                    .port(port)
                    .proxy(address.getHostAddress())
                    .applicationProtocol("h3")
                    .connectTimeout(timeout)
                    .maxIdleTimeout(timeout)
                    .socketFactory(remote -> {
                        if (!address.equals(remote) || socket.isClosed()) throw new SocketException("Invalid QUIC destination");
                        return socket;
                    })
                    .build();
            var closed = new AtomicBoolean();
            Runnable cancel = () -> {
                if (closed.compareAndSet(false, true)) {
                    try {
                        quic.close();
                    } finally {
                        socket.close();
                    }
                }
            };
            abort.accept(cancel);
            try {
                var client = new Http3SingleConnectionClient(quic, timeout, null);
                var request = HttpRequest.newBuilder(uri).timeout(timeout).method(method,
                        method.equals("HEAD") || body.length == 0
                                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
                if (text) request.header("Content-Type", "text/plain; charset=utf-8");
                var response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                try (var input = response.body()) {
                    if (method.equals("HEAD")) return new NetworkPort.Result(NetworkPort.OK, response.statusCode(), EMPTY);
                    if (input == null) throw new IOException("Missing HTTP/3 body");
                    byte[] data = input.readNBytes(policy.transferLimit() + 1);
                    if (data.length > policy.transferLimit()) return new NetworkPort.Result(NetworkPort.LIMIT, 0, EMPTY);
                    return new NetworkPort.Result(NetworkPort.OK, response.statusCode(), data);
                }
            } catch (IOException error) {
                if (socket.exceeded) return new NetworkPort.Result(NetworkPort.LIMIT, 0, EMPTY);
                throw error;
            } finally {
                cancel.run();
            }
        }
    }

    private static final class BoundedSocket extends DatagramSocket {
        private int remaining;
        private volatile boolean exceeded;

        private BoundedSocket(int budget) throws SocketException {
            super((SocketAddress) null);
            remaining = budget;
        }

        @Override
        public void receive(DatagramPacket packet) throws IOException {
            super.receive(packet);
            remaining -= packet.getLength();
            if (remaining < 0) {
                exceeded = true;
                close();
                throw new SocketException("HTTP/3 datagram budget exhausted");
            }
        }
    }
}
