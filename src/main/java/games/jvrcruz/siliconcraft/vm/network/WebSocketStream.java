package games.jvrcruz.siliconcraft.vm.network;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

final class WebSocketStream {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private final BufferedInputStream input;
    private final OutputStream output;
    private final boolean server;

    private WebSocketStream(Socket socket, BufferedInputStream input, boolean server) throws IOException {
        this.input = input;
        this.output = socket.getOutputStream();
        this.server = server;
    }

    WebSocketStream(Socket socket, URI uri) throws IOException {
        this(socket, new BufferedInputStream(socket.getInputStream()), false);
        URI ascii = URI.create(uri.toASCIIString());
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        String target = ascii.getRawPath().isEmpty() ? "/" : ascii.getRawPath();
        if (ascii.getRawQuery() != null) target += "?" + ascii.getRawQuery();
        output.write(("GET " + target + " HTTP/1.1\r\nHost: " + ascii.getRawAuthority()
                + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: "
                + key + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        if (!NetworkPort.readLine(input).matches("HTTP/1\\.1 101(?: .*)?")) throw new IOException("WebSocket upgrade refused");

        String accept = null;
        boolean upgrade = false;
        boolean connection = false;
        for (int i = 0; ; i++) {
            String line = NetworkPort.readLine(input);
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (i >= 64 || colon <= 0) throw new IOException("Invalid WebSocket upgrade headers");
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            switch (name) {
                case "upgrade" -> upgrade |= hasToken(value, "websocket");
                case "connection" -> connection |= hasToken(value, "upgrade");
                case "sec-websocket-accept" -> {
                    if (accept != null) throw new IOException("Duplicate WebSocket accept");
                    accept = value;
                }
                case "sec-websocket-extensions", "sec-websocket-protocol" ->
                        throw new IOException("Unrequested WebSocket extension or subprotocol");
                default -> { }
            }
        }
        if (!upgrade || !connection || !acceptKey(key).equals(accept)) throw new IOException("Invalid WebSocket upgrade");
    }

    static WebSocketStream accept(Socket socket, BufferedInputStream input, IncomingHttp.Request request) throws IOException {
        var headers = request.headers();
        String key = headers.getOrDefault("sec-websocket-key", "");
        boolean validKey;
        try {
            validKey = Base64.getDecoder().decode(key).length == 16;
        } catch (IllegalArgumentException invalid) {
            validKey = false;
        }
        if (!request.method().equals("GET") || !request.version().equals("HTTP/1.1") || !validKey
                || !headers.getOrDefault("sec-websocket-version", "").equals("13")
                || !hasToken(headers.getOrDefault("upgrade", ""), "websocket")
                || !hasToken(headers.getOrDefault("connection", ""), "upgrade")) {
            throw new IOException("Invalid WebSocket upgrade request");
        }
        var stream = new WebSocketStream(socket, input, true);
        stream.output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + acceptKey(key) + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        stream.output.flush();
        return stream;
    }

    private static boolean hasToken(String value, String expected) {
        for (String token : value.split(",")) {
            if (expected.equalsIgnoreCase(token.strip())) return true;
        }
        return false;
    }

    static String acceptKey(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    void send(byte[] payload, boolean text) throws IOException {
        if (text) validateText(payload);
        writeFrame(text ? 1 : 2, payload);
    }

    private void writeFrame(int type, byte[] payload) throws IOException {
        var frame = new ByteArrayOutputStream(payload.length + 8);
        frame.write(0x80 | type);
        int masked = server ? 0 : 0x80;
        if (payload.length < 126) frame.write(masked | payload.length);
        else {
            frame.write(masked | 126);
            frame.write(payload.length >>> 8);
            frame.write(payload.length);
        }
        if (server) frame.writeBytes(payload);
        else {
            byte[] mask = new byte[4];
            RANDOM.nextBytes(mask);
            frame.writeBytes(mask);
            for (int i = 0; i < payload.length; i++) frame.write(payload[i] ^ mask[i & 3]);
        }
        frame.writeTo(output);
    }

    byte[] receive(int limit) throws IOException {
        var message = new ByteArrayOutputStream();
        int messageType = 0;
        while (true) {
            int first = readByte();
            int second = readByte();
            int type = first & 15;
            boolean last = (first & 128) != 0;
            boolean control = type >= 8;
            if ((first & 112) != 0 || ((second & 128) != 0) != server) throw new IOException("Invalid WebSocket frame mask");
            int length = second & 127;
            if (control && (!last || length > 125)) throw new IOException("Invalid WebSocket control frame");
            if (length == 127) {
                long expanded = 0;
                for (int i = 0; i < 8; i++) expanded = (expanded << 8) | readByte();
                if (expanded < 65536) throw new IOException("Invalid WebSocket frame length");
                throw new MessageTooLarge();
            }
            if (length == 126) {
                length = readByte() << 8 | readByte();
                if (length < 126) throw new IOException("Invalid WebSocket frame length");
            }
            if (!control && length > limit - message.size()) throw new MessageTooLarge();
            byte[] mask = server ? input.readNBytes(4) : null;
            if (mask != null && mask.length != 4) throw new EOFException("Truncated WebSocket frame mask");
            byte[] payload = input.readNBytes(length);
            if (payload.length != length) throw new EOFException("Truncated WebSocket frame");
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            }
            switch (type) {
                case 8 -> {
                    if (payload.length == 1) throw new IOException("Invalid WebSocket close frame");
                    if (payload.length > 2) validateText(java.util.Arrays.copyOfRange(payload, 2, payload.length));
                    writeFrame(8, payload);
                    return null;
                }
                case 9 -> writeFrame(10, payload);
                case 10 -> { }
                case 0, 1, 2 -> {
                    if ((type == 0) != (messageType != 0)) throw new IOException("Unexpected WebSocket fragment");
                    if (type != 0) messageType = type;
                    message.writeBytes(payload);
                    if (last) {
                        byte[] bytes = message.toByteArray();
                        if (messageType == 1) validateText(bytes);
                        return bytes;
                    }
                }
                default -> throw new IOException("Unknown WebSocket frame type");
            }
        }
    }

    void close() throws IOException {
        writeFrame(8, new byte[]{3, (byte) 232});
    }

    private static void validateText(byte[] payload) throws IOException {
        StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(payload));
    }

    private int readByte() throws IOException {
        int value = input.read();
        if (value < 0) throw new EOFException("WebSocket ended");
        return value;
    }

    static final class MessageTooLarge extends IOException {}
}
