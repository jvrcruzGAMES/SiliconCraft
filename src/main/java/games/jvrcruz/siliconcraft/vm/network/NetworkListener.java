package games.jvrcruz.siliconcraft.vm.network;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

final class NetworkListener implements AutoCloseable {
    private static final Semaphore SLOTS = new Semaphore(64);
    final int protocol;
    final ServerSocket stream;
    final DatagramSocket datagram;
    private final SSLContext tls;
    InetSocketAddress replyPeer;
    private final AtomicBoolean closed = new AtomicBoolean();

    private NetworkListener(int protocol, ServerSocket stream, DatagramSocket datagram, SSLContext tls) {
        this.protocol = protocol;
        this.stream = stream;
        this.datagram = datagram;
        this.tls = tls;
    }

    static NetworkListener bind(int protocol, int port, NetworkPolicy policy) throws IOException {
        if (!SLOTS.tryAcquire()) return null;
        NetworkListener listener = null;
        boolean bound = false;
        try {
            var address = new InetSocketAddress(port);
            if (protocol == NetworkPort.UDP) {
                listener = new NetworkListener(protocol, null, new DatagramSocket(null), null);
                listener.datagram.bind(address);
            } else {
                SSLContext context = protocol == NetworkPort.HTTPS || protocol == NetworkPort.WSS
                        ? tlsContext(policy.listen().tlsKeyStore()) : null;
                ServerSocket socket = new ServerSocket();
                listener = new NetworkListener(protocol, socket, null, context);
                socket.setReuseAddress(true);
                socket.bind(address, 16);
            }
            bound = true;
            return listener;
        } finally {
            if (!bound) {
                if (listener == null) SLOTS.release();
                else listener.close();
            }
        }
    }

    private static SSLContext tlsContext(String path) throws IOException {
        String secret = System.getenv("SILICONCRAFT_TLS_KEYSTORE_PASSWORD");
        if (path.isBlank() || secret == null) throw new IOException("Server TLS credentials are missing");
        char[] password = secret.toCharArray();
        try {
            var keys = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(Path.of(path))) {
                keys.load(input, password);
            }
            var aliases = keys.aliases();
            boolean hasPrivateKey = false;
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (keys.entryInstanceOf(alias, KeyStore.PrivateKeyEntry.class)) {
                    hasPrivateKey = true;
                    break;
                }
            }
            if (!hasPrivateKey) throw new IOException("Server TLS keystore has no private key");
            var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(keys, password);
            var context = SSLContext.getInstance("TLS");
            context.init(managers.getKeyManagers(), null, null);
            return context;
        } catch (GeneralSecurityException error) {
            throw new IOException("Could not load server TLS credentials", error);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    Socket secure(Socket raw) throws IOException {
        if (tls == null) return raw;
        var socket = (SSLSocket) tls.getSocketFactory().createSocket(raw, null, raw.getPort(), true);
        socket.setUseClientMode(false);
        socket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        socket.startHandshake();
        return socket;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            if (stream != null) stream.close();
            else datagram.close();
        } catch (IOException ignored) {
            // The socket is being discarded.
        } finally {
            SLOTS.release();
        }
    }
}
