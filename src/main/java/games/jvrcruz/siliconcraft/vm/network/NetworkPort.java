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
    public static final int TCP = 1, UDP = 2;
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

    public enum Operation { OPEN, SEND, RECEIVE, CLOSE, HTTP }
    public record Request(Operation operation, int arg0, int arg1, String text, byte[] data) {}
    public record Result(int status, int value, byte[] data) {}
    private record Connection(Socket tcp, DatagramSocket udp) {
        void close() {
            try {
                if (tcp != null) tcp.close();
                else udp.close();
            } catch (IOException ignored) {
                // The socket is already closed or no longer usable.
            }
        }
    }

    private final Map<Integer, Connection> connections = new HashMap<>();
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
                case OPEN -> open(task);
                case SEND -> send(task);
                case RECEIVE -> receive(task);
                case CLOSE -> close(task);
                case HTTP -> http(task);
            };
        } catch (SecurityException e) {
            completed = failed(DENIED);
        } catch (IllegalArgumentException e) {
            completed = failed(INVALID);
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
        int protocol = request.arg0;
        if (protocol != TCP && protocol != UDP) return failed(INVALID);
        if (protocol == TCP && !task.policy.tcp() || protocol == UDP && !task.policy.udp()) return failed(DENIED);
        synchronized (this) {
            if (connections.size() >= MAX_CONNECTIONS) return failed(LIMIT);
        }
        InetAddress address = task.policy.resolve(request.text, request.arg1)[0];
        InetSocketAddress endpoint = new InetSocketAddress(address, request.arg1);
        Connection connection = protocol == TCP ? new Connection(new Socket(), null) : new Connection(null, new DatagramSocket());
        task.setAbort(connection::close);
        try {
            if (protocol == TCP) {
                connection.tcp.connect(endpoint, task.policy.timeoutMs());
                connection.tcp.setSoTimeout(task.policy.timeoutMs());
            } else {
                connection.udp.connect(endpoint);
                connection.udp.setSoTimeout(task.policy.timeoutMs());
            }
            synchronized (this) {
                if (pending != task || !task.keepOpen()) {
                    connection.close();
                    return failed(TIMEOUT);
                }
                int handle = 1;
                while (connections.containsKey(handle)) handle++;
                connections.put(handle, connection);
                Result opened = ok(handle, EMPTY);
                publish(task, opened);
                return opened;
            }
        } catch (IOException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    private synchronized Connection connection(Task task) {
        if (pending != task) throw new IllegalArgumentException("Request no longer active");
        Connection connection = connections.get(task.request.arg0);
        if (connection == null) throw new IllegalArgumentException("Invalid socket handle");
        task.setAbort(() -> {
            synchronized (NetworkPort.this) {
                connections.remove(task.request.arg0, connection);
            }
            connection.close();
        });
        return connection;
    }

    private Result send(Task task) throws IOException {
        byte[] data = task.request.data;
        if (data.length > task.policy.transferLimit()) return failed(LIMIT);
        Connection socket = connection(task);
        if (socket.tcp != null) socket.tcp.getOutputStream().write(data);
        else socket.udp.send(new DatagramPacket(data, data.length));
        return ok(data.length, EMPTY);
    }

    private Result receive(Task task) throws IOException {
        int length = task.request.arg1;
        if (length < 1 || length > task.policy.transferLimit()) return failed(INVALID);
        Connection socket = connection(task);
        byte[] data = new byte[length];
        int count;
        if (socket.tcp != null) {
            count = socket.tcp.getInputStream().read(data);
        } else {
            DatagramPacket packet = new DatagramPacket(data, data.length);
            socket.udp.receive(packet);
            count = packet.getLength();
        }
        return ok(count, count > 0 ? Arrays.copyOf(data, count) : EMPTY);
    }

    private synchronized Result close(Task task) {
        if (pending != task) return failed(TIMEOUT);
        Connection connection = connections.remove(task.request.arg0);
        if (connection == null) return failed(INVALID);
        connection.close();
        return ok(1, EMPTY);
    }

    private Result http(Task task) throws IOException {
        if (!task.policy.http()) return failed(DENIED);
        Request request = task.request;
        if (request.arg0 < 0 || request.arg0 >= HTTP_METHODS.length || request.data.length > task.policy.transferLimit()) {
            return failed(INVALID);
        }
        URI uri = URI.create(request.text);
        boolean tls = "https".equalsIgnoreCase(uri.getScheme());
        if ((!tls && !"http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null) return failed(INVALID);
        int port = uri.getPort() == -1 ? (tls ? 443 : 80) : uri.getPort();
        InetAddress[] addresses = task.policy.resolve(uri.getHost(), port);

        Socket connection = new Socket();
        task.setAbort(() -> closeSocket(connection));
        try (connection) {
            connection.connect(new InetSocketAddress(addresses[0], port), task.policy.timeoutMs());
            connection.setSoTimeout(task.policy.timeoutMs());
            if (tls) {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                try (SSLSocket secured = (SSLSocket) factory.createSocket(connection,
                        NetworkPolicy.normalizeHost(uri.getHost()), port, true)) {
                    var parameters = secured.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    secured.setSSLParameters(parameters);
                    secured.startHandshake();
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
        String headers = method + " " + target + " HTTP/1.1\r\nHost: " + ascii.getRawAuthority()
                + "\r\nConnection: close\r\nAccept-Encoding: identity\r\nContent-Length: "
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

    private static String readLine(InputStream input) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 8192; i++) {
            int value = input.read();
            if (value == '\r' && input.read() == '\n') return line.toString();
            if (value < 0 || value == '\r' || value == '\n') throw new IOException("Invalid HTTP line");
            line.append((char) value);
        }
        throw new IOException("HTTP line is too long");
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
        private boolean cancelled;
        private Runnable abort;
        private ScheduledFuture<?> deadline;

        private Task(Request request, NetworkPolicy policy) {
            this.request = request;
            this.policy = policy;
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
