package games.jvrcruz.siliconcraft.vm.network;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A single request at a time per computer. Socket I/O only runs on network workers. */
public final class NetworkPort {
    public static final int IDLE = 0, PENDING = 1, OK = 2, DISABLED = -1, DENIED = -2, IO_ERROR = -3,
            LIMIT = -4, INVALID = -5, TIMEOUT = -6;
    public static final int TCP = 1, UDP = 2, SMTP = 3, SMTPS = 4, IMAP = 5, IMAPS = 6;
    public static final int HTTP = 7, HTTPS = 8, WS = 9, WSS = 10;
    public static final int MAX_TEXT = 2048;
    private static final byte[] EMPTY = new byte[0];
    private static final String[] HTTP_METHODS = {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD"};
    private static final int MAX_CONNECTIONS = 4;
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(8, 8, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), runnable -> daemon(runnable, "SiliconCraft-Network"));
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(1,
            runnable -> daemon(runnable, "SiliconCraft-Network-Deadline"));

    static {
        WORKERS.allowCoreThreadTimeOut(true);
        DEADLINES.setRemoveOnCancelPolicy(true);
    }

    public enum Operation { OPEN, SEND, RECEIVE, CLOSE, HTTP, WEBSOCKET, SEND_TEXT, TLS, HTTP3, LISTEN, ACCEPT, RESPOND, RESPOND_TEXT }
    public record Request(Operation operation, int arg0, int arg1, String text, byte[] data) {}
    public record Result(int status, int value, byte[] data) {}
    private static final class UdpClient {
        private final DatagramSocket socket = new DatagramSocket(null);
        private final InetSocketAddress endpoint;
        private boolean sent;

        private UdpClient(InetSocketAddress endpoint, int timeout) throws java.net.SocketException {
            this.endpoint = endpoint;
            socket.setSoTimeout(timeout);
        }

        void send(byte[] data) throws IOException {
            if (!socket.isConnected()) socket.connect(endpoint);
            socket.send(new DatagramPacket(data, data.length));
            sent = true;
        }
    }

    private record Connection(Socket tcp, UdpClient udp, WebSocketStream webSocket, String host, int port, String httpMethod, Socket transport) {
        void close() {
            try {
                if (transport != null && transport != tcp) closeSocket(transport);
                if (tcp != null) tcp.close();
                else if (udp != null) udp.socket.close();
            } catch (IOException ignored) {
                // The socket is already closed or no longer usable.
            }
        }
    }

    @FunctionalInterface
    interface DestinationResolver {
        InetAddress resolve(NetworkPolicy policy, String host, int port) throws IOException;
    }

    private final DestinationResolver destinations;

    public NetworkPort() {
        this(NetworkPolicy::resolve);
    }

    NetworkPort(DestinationResolver destinations) {
        this.destinations = destinations;
    }

    private final Map<Integer, Connection> connections = new HashMap<>();
    private final Map<Integer, NetworkListener> listeners = new HashMap<>();
    private NetworkPolicy policy = NetworkPolicy.DISABLED;
    private Task pending;
    private Result result;
    private long nextSubmission;

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = Executors.defaultThreadFactory().newThread(runnable);
        thread.setName(name);
        thread.setDaemon(true);
        return thread;
    }

    public synchronized void setPolicy(NetworkPolicy updated) {
        if (policy != updated) {
            reset();
            policy = updated;
        }
    }

    public synchronized int transferLimit() { return policy.transferLimit(); }

    public synchronized boolean ready() {
        return pending == null && result == null
                && (!policy.enabled() || nextSubmission == 0 || System.nanoTime() - nextSubmission >= 0);
    }

    public synchronized boolean submit(Request request) {
        if (pending != null || result != null) return false;
        if (!policy.enabled()) {
            result = failed(DISABLED);
            return true;
        }
        long now = System.nanoTime();
        if (nextSubmission != 0 && now - nextSubmission < 0 && request.operation != Operation.CLOSE) {
            result = failed(LIMIT);
            return true;
        }
        nextSubmission = now + TimeUnit.MILLISECONDS.toNanos(100);
        Task task = new Task(request, policy);
        pending = task;
        try {
            task.deadline = DEADLINES.schedule(() -> expire(task), policy.timeoutMs(), TimeUnit.MILLISECONDS);
            WORKERS.execute(() -> run(task));
        } catch (RejectedExecutionException e) {
            if (task.deadline != null) task.deadline.cancel(false);
            pending = null;
            result = failed(LIMIT);
        }
        return true;
    }

    public synchronized int status() {
        return pending != null ? PENDING : result == null ? IDLE : result.status;
    }

    public synchronized int resultLength() {
        return result == null ? 0 : result.data.length;
    }

    public synchronized Result takeResult() {
        Result completed = result;
        result = null;
        return completed;
    }

    public synchronized void reset() {
        Task task = pending;
        pending = null;
        result = null;
        nextSubmission = 0;
        if (task != null) {
            task.deadline.cancel(false);
            task.cancel();
        }
        for (Connection connection : connections.values()) connection.close();
        connections.clear();
        for (NetworkListener listener : listeners.values()) listener.close();
        listeners.clear();
    }

    private void expire(Task task) {
        synchronized (this) {
            if (pending != task) return;
            pending = null;
            result = failed(TIMEOUT);
        }
        task.cancel();
    }

    private void run(Task task) {
        Result completed;
        try {
            if (task.cancelled()) return;
            completed = switch (task.request.operation) {
                case OPEN, WEBSOCKET -> open(task);
                case SEND, SEND_TEXT -> send(task);
                case RECEIVE -> receive(task);
                case CLOSE -> close(task);
                case HTTP, HTTP3 -> http(task);
                case TLS -> upgradeTls(task);
                case LISTEN -> listen(task);
                case ACCEPT -> accept(task);
                case RESPOND, RESPOND_TEXT -> respond(task);
            };
        } catch (SecurityException e) {
            completed = failed(DENIED);
        } catch (IllegalArgumentException e) {
            completed = failed(INVALID);
        } catch (IncomingHttp.TooLarge e) {
            completed = failed(LIMIT);
        } catch (java.net.SocketTimeoutException e) {
            completed = failed(TIMEOUT);
        } catch (IOException | RuntimeException e) {
            completed = failed(IO_ERROR);
        }
        publish(task, completed);
    }

    private synchronized void publish(Task task, Result completed) {
        if (pending != task) return;
        task.deadline.cancel(false);
        pending = null;
        result = completed;
    }

    private Result open(Task task) throws IOException {
        Request request = task.request;
        boolean webSocket = request.operation == Operation.WEBSOCKET;
        int protocol = webSocket ? TCP : request.arg0;
        if (protocol < TCP || protocol > IMAPS) return failed(INVALID);
        if (protocol == UDP ? !task.policy.udp() : !task.policy.tcp()) return failed(DENIED);
        if (webSocket && !task.policy.webSocket()
                || (protocol == SMTP || protocol == SMTPS) && !task.policy.smtp()
                || (protocol == IMAP || protocol == IMAPS) && !task.policy.imap()) return failed(DENIED);
        synchronized (this) {
            if (connections.size() >= MAX_CONNECTIONS) return failed(LIMIT);
        }

        URI uri = webSocket ? URI.create(request.text) : null;
        boolean tls = protocol == SMTPS || protocol == IMAPS;
        if (webSocket) {
            tls = "wss".equalsIgnoreCase(uri.getScheme());
            if ((!tls && !"ws".equalsIgnoreCase(uri.getScheme())) || !validUri(uri)) return failed(INVALID);
        }
        String host = webSocket ? NetworkPolicy.uriHost(uri) : NetworkPolicy.normalizeHost(request.text);
        int port = webSocket ? uri.getPort() == -1 ? (tls ? 443 : 80) : uri.getPort() : request.arg1;
        InetAddress address = destinations.resolve(task.policy, host, port);
        if (task.cancelled()) return failed(TIMEOUT);
        InetSocketAddress endpoint = new InetSocketAddress(address, port);
        Socket tcp = protocol == UDP ? null : new Socket();
        UdpClient udp = protocol == UDP ? new UdpClient(endpoint, task.policy.timeoutMs()) : null;
        Connection original = new Connection(tcp, udp, null, host, port, null, tcp);
        task.setAbort(original::close);
        boolean registered = false;
        try {
            if (tcp != null) {
                tcp.connect(endpoint, task.policy.timeoutMs());
                tcp.setSoTimeout(task.policy.timeoutMs());
                if (tls) tcp = secure(tcp, host, port);
            }
            WebSocketStream framed = webSocket ? new WebSocketStream(tcp, uri) : null;
            synchronized (this) {
                if (pending != task || !task.keepOpen()) return failed(TIMEOUT);
                registered = true;
                return register(task, new Connection(tcp, udp, framed, host, port, null, original.tcp), EMPTY);
            }
        } finally {
            if (!registered) original.close();
        }
    }

    // Called with the mailbox lock held after cancellation has been ruled out.
    private Result register(Task task, Connection connection, byte[] data) {
        int handle = nextHandle();
        connections.put(handle, connection);
        Result opened = ok(handle, data);
        publish(task, opened);
        return opened;
    }

    private int nextHandle() {
        int handle = 1;
        while (connections.containsKey(handle) || listeners.containsKey(handle)) handle++;
        return handle;
    }

    private Result listen(Task task) throws IOException {
        int protocol = task.request.arg0;
        int port = task.request.arg1;
        if (port < 1 || port > 65535) return failed(INVALID);
        boolean allowed = switch (protocol) {
            case TCP -> task.policy.tcp();
            case UDP -> task.policy.udp();
            case HTTP, HTTPS -> task.policy.tcp() && task.policy.http();
            case WS, WSS -> task.policy.tcp() && task.policy.webSocket();
            default -> throw new IllegalArgumentException("Unknown listener protocol");
        };
        if (!allowed || !task.policy.listen().permits(port)) return failed(DENIED);
        synchronized (this) {
            if (listeners.size() >= MAX_CONNECTIONS) return failed(LIMIT);
        }
        NetworkListener listener = NetworkListener.bind(protocol, port, task.policy);
        if (listener == null) return failed(LIMIT);
        task.setAbort(listener::close);
        synchronized (this) {
            if (pending != task || !task.keepOpen()) {
                listener.close();
                return failed(TIMEOUT);
            }
            int handle = nextHandle();
            listeners.put(handle, listener);
            Result opened = ok(handle, EMPTY);
            publish(task, opened);
            return opened;
        }
    }

    private synchronized NetworkListener listener(Task task) {
        if (pending != task) throw new IllegalArgumentException("Request no longer active");
        return listeners.get(task.request.arg0);
    }

    private Result accept(Task task) throws IOException {
        NetworkListener listener = listener(task);
        if (listener == null || listener.stream == null) return failed(INVALID);
        synchronized (this) {
            if (connections.size() >= MAX_CONNECTIONS) return failed(LIMIT);
        }
        listener.stream.setSoTimeout(task.remainingMs());
        Socket raw = listener.stream.accept();
        task.setAbort(() -> closeSocket(raw));
        boolean registered = false;
        try {
            raw.setSoTimeout(task.remainingMs());
            if (!task.policy.allowNonPublicAddresses() && !NetworkPolicy.isPublic(raw.getInetAddress())) return failed(DENIED);
            Socket socket = listener.secure(raw);
            WebSocketStream framed = null;
            String method = null;
            byte[] data = EMPTY;
            if (listener.protocol != TCP) {
                boolean upgrade = listener.protocol == WS || listener.protocol == WSS;
                var input = new BufferedInputStream(socket.getInputStream());
                var request = IncomingHttp.read(input, task.policy.transferLimit(), upgrade);
                data = request.bytes();
                if (upgrade) framed = WebSocketStream.accept(socket, input, request);
                else method = request.method();
            }
            socket.setSoTimeout(task.policy.timeoutMs());
            synchronized (this) {
                if (pending != task || !task.keepOpen()) return failed(TIMEOUT);
                registered = true;
                return register(task, new Connection(socket, null, framed, null, 0, method, raw), data);
            }
        } finally {
            if (!registered) closeSocket(raw);
        }
    }

    private Result respond(Task task) throws IOException {
        int status = task.request.arg1;
        byte[] data = task.request.data;
        if (status < 200 || status > 599) return failed(INVALID);
        if (data.length > task.policy.transferLimit()) return failed(LIMIT);
        Connection socket = connection(task);
        if (socket.httpMethod == null) return failed(INVALID);
        try {
            IncomingHttp.respond(socket.tcp.getOutputStream(), status, socket.httpMethod, data,
                    task.request.operation == Operation.RESPOND_TEXT);
            finishTls(socket.tcp);
            return ok(1, EMPTY);
        } finally {
            remove(task.request.arg0, socket);
        }
    }

    private Result sendDatagram(Task task, NetworkListener listener) throws IOException {
        if (listener.datagram == null || task.request.operation == Operation.SEND_TEXT) return failed(INVALID);
        InetSocketAddress peer;
        synchronized (this) {
            peer = listener.replyPeer;
        }
        if (peer == null) return failed(INVALID);
        byte[] data = task.request.data;
        listener.datagram.send(new DatagramPacket(data, data.length, peer));
        return ok(data.length, EMPTY);
    }

    private Result receiveDatagram(Task task, NetworkListener listener, int limit) throws IOException {
        if (listener.datagram == null) return failed(INVALID);
        byte[] data = new byte[limit + 1];
        var packet = new DatagramPacket(data, data.length);
        listener.datagram.setSoTimeout(task.remainingMs());
        listener.datagram.receive(packet);
        if (!task.policy.allowNonPublicAddresses() && !NetworkPolicy.isPublic(packet.getAddress())) return failed(DENIED);
        if (packet.getLength() > limit) return failed(LIMIT);
        synchronized (this) {
            if (pending != task) return failed(TIMEOUT);
            listener.replyPeer = (InetSocketAddress) packet.getSocketAddress();
            Result received = ok(packet.getLength(), Arrays.copyOf(data, packet.getLength()));
            publish(task, received);
            return received;
        }
    }

    private static boolean validUri(URI uri) {
        return uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawFragment() == null;
    }

    private static SSLSocket secure(Socket socket, String host, int port) throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket secured = (SSLSocket) factory.createSocket(socket, NetworkPolicy.tlsHost(host), port, true);
        try {
            var parameters = secured.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            secured.setSSLParameters(parameters);
            secured.startHandshake();
            return secured;
        } catch (IOException | RuntimeException error) {
            closeSocket(socket);
            closeSocket(secured);
            throw error;
        }
    }

    private Result upgradeTls(Task task) throws IOException {
        Connection connection = connection(task);
        if (connection.host == null || connection.tcp == null || connection.tcp instanceof SSLSocket || connection.webSocket != null) return failed(INVALID);
        SSLSocket secured;
        try {
            secured = secure(connection.tcp, connection.host, connection.port);
        } catch (IOException | RuntimeException error) {
            remove(task.request.arg0, connection);
            throw error;
        }
        synchronized (this) {
            if (pending != task || !task.keepOpen()) {
                closeSocket(secured);
                return failed(TIMEOUT);
            }
            connections.put(task.request.arg0, new Connection(secured, null, null, connection.host, connection.port, null, connection.tcp));
            Result upgraded = ok(1, EMPTY);
            publish(task, upgraded);
            return upgraded;
        }
    }

    private void remove(int handle, Connection connection) {
        synchronized (this) {
            connections.remove(handle, connection);
        }
        connection.close();
    }

    private synchronized Connection connection(Task task) {
        if (pending != task) throw new IllegalArgumentException("Request no longer active");
        Connection connection = connections.get(task.request.arg0);
        if (connection == null) throw new IllegalArgumentException("Invalid socket handle");
        task.setAbort(() -> remove(task.request.arg0, connection));
        return connection;
    }

    private Result send(Task task) throws IOException {
        byte[] data = task.request.data;
        if (data.length > task.policy.transferLimit()) return failed(LIMIT);
        NetworkListener listener = listener(task);
        if (listener != null) return sendDatagram(task, listener);
        Connection socket = connection(task);
        if (socket.httpMethod != null) return failed(INVALID);
        boolean text = task.request.operation == Operation.SEND_TEXT;
        if (socket.webSocket != null) {
            try {
                socket.webSocket.send(data, text);
            } catch (java.nio.charset.CharacterCodingException error) {
                return failed(INVALID);
            } catch (IOException error) {
                remove(task.request.arg0, socket);
                throw error;
            }
        } else if (text) return failed(INVALID);
        else if (socket.tcp != null) socket.tcp.getOutputStream().write(data);
        else socket.udp.send(data);
        return ok(data.length, EMPTY);
    }

    private Result receive(Task task) throws IOException {
        int length = task.request.arg1;
        if (length < 1 || length > task.policy.transferLimit()) return failed(INVALID);
        NetworkListener listener = listener(task);
        if (listener != null) return receiveDatagram(task, listener, length);
        Connection socket = connection(task);
        if (socket.httpMethod != null) return failed(INVALID);
        if (socket.webSocket != null) {
            try {
                byte[] message = socket.webSocket.receive(length);
                if (message != null) return ok(message.length, message);
                remove(task.request.arg0, socket);
                return ok(-1, EMPTY);
            } catch (WebSocketStream.MessageTooLarge error) {
                remove(task.request.arg0, socket);
                return failed(LIMIT);
            } catch (IOException error) {
                remove(task.request.arg0, socket);
                throw error;
            }
        }
        byte[] data = new byte[length];
        int count;
        if (socket.tcp != null) {
            count = socket.tcp.getInputStream().read(data);
        } else {
            if (!socket.udp.sent) return failed(INVALID);
            DatagramPacket packet = new DatagramPacket(data, data.length);
            socket.udp.socket.receive(packet);
            count = packet.getLength();
        }
        return ok(count, count > 0 ? Arrays.copyOf(data, count) : EMPTY);
    }

    private Result close(Task task) throws IOException {
        synchronized (this) {
            if (pending != task) return failed(TIMEOUT);
            NetworkListener listener = listeners.remove(task.request.arg0);
            if (listener != null) {
                listener.close();
                return ok(1, EMPTY);
            }
        }
        Connection connection = connection(task);
        try {
            if (connection.webSocket != null) connection.webSocket.close();
            finishTls(connection.tcp);
            return ok(1, EMPTY);
        } finally {
            remove(task.request.arg0, connection);
        }
    }

    private Result http(Task task) throws IOException {
        if (!task.policy.http()) return failed(DENIED);
        Request request = task.request;
        if (request.arg0 < 0 || request.arg0 >= HTTP_METHODS.length || request.arg1 < 0 || request.arg1 > 1
                || request.data.length > task.policy.transferLimit()) {
            return failed(INVALID);
        }
        URI uri = URI.create(request.text);
        boolean tls = "https".equalsIgnoreCase(uri.getScheme());
        boolean http3 = request.operation == Operation.HTTP3;
        if ((!tls && (http3 || !"http".equalsIgnoreCase(uri.getScheme()))) || !validUri(uri)) return failed(INVALID);
        if (http3 && (!task.policy.udp() || !task.policy.http3())) return failed(DENIED);
        int port = uri.getPort() == -1 ? (tls ? 443 : 80) : uri.getPort();
        InetAddress address = destinations.resolve(task.policy, NetworkPolicy.uriHost(uri), port);
        if (task.cancelled()) return failed(TIMEOUT);
        if (http3) {
            return Http3Client.exchange(task.policy, uri, address, port, HTTP_METHODS[request.arg0],
                    request.data, request.arg1 == 1, task::setAbort);
        }

        Socket connection = new Socket();
        task.setAbort(() -> closeSocket(connection));
        try (connection) {
            connection.connect(new InetSocketAddress(address, port), task.policy.timeoutMs());
            connection.setSoTimeout(task.policy.timeoutMs());
            if (tls) {
                try (SSLSocket secured = secure(connection, NetworkPolicy.uriHost(uri), port)) {
                    return exchangeHttp(secured, uri, request, task.policy.transferLimit());
                }
            }
            return exchangeHttp(connection, uri, request, task.policy.transferLimit());
        }
    }

    private static Result exchangeHttp(Socket socket, URI uri, Request request, int limit) throws IOException {
        URI ascii = URI.create(uri.toASCIIString());
        String target = ascii.getRawPath().isEmpty() ? "/" : ascii.getRawPath();
        if (ascii.getRawQuery() != null) target += "?" + ascii.getRawQuery();
        String method = HTTP_METHODS[request.arg0];
        String contentType = request.arg1 == 1 ? "Content-Type: text/plain; charset=utf-8\r\n" : "";
        String headers = method + " " + target + " HTTP/1.1\r\nHost: " + ascii.getRawAuthority()
                + "\r\nConnection: close\r\nAccept-Encoding: identity\r\n" + contentType + "Content-Length: "
                + request.data.length + "\r\n\r\n";
        var output = socket.getOutputStream();
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(request.data);

        var input = new BufferedInputStream(socket.getInputStream());
        for (int interim = 0; interim < 8; interim++) {
            String[] statusLine = readLine(input).split(" ", 3);
            if (statusLine.length < 2 || !statusLine[0].matches("HTTP/1\\.[01]") || !statusLine[1].matches("[1-5][0-9]{2}")) {
                throw new IOException("Invalid HTTP status");
            }
            int status = Integer.parseInt(statusLine[1]);
            long contentLength = -1;
            boolean chunked = false;
            for (int i = 0; ; i++) {
                String line = readLine(input);
                if (line.isEmpty()) break;
                if (i >= 64) throw new IOException("Too many HTTP headers");
                int colon = line.indexOf(':');
                if (colon <= 0) throw new IOException("Invalid HTTP header");
                String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).strip();
                if (name.equals("content-length")) {
                    if (contentLength >= 0 || !value.matches("[0-9]{1,18}")) throw new IOException("Invalid content length");
                    contentLength = Long.parseLong(value);
                } else if (name.equals("transfer-encoding")) {
                    if (chunked || !value.equalsIgnoreCase("chunked")) throw new IOException("Unsupported HTTP transfer encoding");
                    chunked = true;
                }
            }
            if (status == 101) throw new IOException("HTTP protocol upgrades are unsupported");
            if (status < 200) continue;
            if (method.equals("HEAD") || status == 204 || status == 304) return ok(status, EMPTY);
            if (chunked) return ok(status, readChunks(input, limit));
            int wanted = contentLength < 0 ? limit : (int) Math.min(limit, contentLength);
            byte[] body = input.readNBytes(wanted);
            if (contentLength >= 0 && body.length != wanted) throw new IOException("Truncated HTTP body");
            return ok(status, body);
        }
        throw new IOException("Too many interim HTTP responses");
    }

    private static byte[] readChunks(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        while (response.size() < limit) {
            String line = readLine(input).split(";", 2)[0].strip();
            if (!line.matches("[0-9a-fA-F]{1,15}")) throw new IOException("Invalid HTTP chunk length");
            long size = Long.parseLong(line, 16);
            if (size == 0) break;
            int wanted = (int) Math.min(size, limit - response.size());
            byte[] bytes = input.readNBytes(wanted);
            if (bytes.length != wanted) throw new IOException("Truncated HTTP chunk");
            response.writeBytes(bytes);
            if (response.size() == limit) break;
            if (!readLine(input).isEmpty()) throw new IOException("Invalid HTTP chunk terminator");
        }
        return response.toByteArray();
    }

    static String readLine(InputStream input) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 8192; i++) {
            int value = input.read();
            if (value == '\r' && input.read() == '\n') return line.toString();
            if (value < 0 || value == '\r' || value == '\n') throw new IOException("Invalid HTTP line");
            line.append((char) value);
        }
        throw new IOException("HTTP line is too long");
    }

    private static void finishTls(Socket socket) throws IOException {
        if (socket instanceof SSLSocket tls && !tls.isOutputShutdown()) tls.shutdownOutput();
    }

    private static void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing a broken socket can fail.
        }
    }

    private static Result ok(int value, byte[] data) { return new Result(OK, value, data); }
    private static Result failed(int status) { return new Result(status, 0, EMPTY); }

    private static final class Task {
        private final Request request;
        private final NetworkPolicy policy;
        private final long expires;
        private boolean cancelled;
        private Runnable abort;
        private ScheduledFuture<?> deadline;

        private Task(Request request, NetworkPolicy policy) {
            this.request = request;
            this.policy = policy;
            this.expires = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(policy.timeoutMs());
        }

        int remainingMs() throws java.net.SocketTimeoutException {
            long remaining = expires - System.nanoTime();
            if (remaining <= 0 || cancelled()) throw new java.net.SocketTimeoutException();
            return Math.max(1, (int) TimeUnit.NANOSECONDS.toMillis(remaining));
        }

        synchronized boolean cancelled() { return cancelled; }

        synchronized boolean keepOpen() {
            abort = null;
            return !cancelled;
        }

        void setAbort(Runnable next) {
            synchronized (this) {
                if (!cancelled) {
                    abort = next;
                    return;
                }
            }
            next.run();
        }

        void cancel() {
            Runnable action;
            synchronized (this) {
                cancelled = true;
                action = abort;
                abort = null;
            }
            if (action != null) action.run();
        }
    }
}
