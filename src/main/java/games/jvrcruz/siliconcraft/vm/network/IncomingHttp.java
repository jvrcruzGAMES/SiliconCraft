package games.jvrcruz.siliconcraft.vm.network;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class IncomingHttp {
    private IncomingHttp() {}

    record Request(String method, String version, Map<String, String> headers, byte[] bytes) {}

    static Request read(BufferedInputStream input, int limit, boolean upgrade) throws IOException {
        var bytes = new ByteArrayOutputStream();
        String requestLine = NetworkPort.readLine(input);
        String[] parts = requestLine.split(" ", -1);
        if (parts.length != 3 || !isToken(parts[0]) || !parts[1].matches("/[!-~]*")
                || !parts[2].matches("HTTP/1\\.[01]")) throw new IOException("Invalid HTTP request line");
        appendLine(bytes, requestLine, limit);

        Map<String, String> headers = new HashMap<>();
        for (int i = 0; ; i++) {
            String line = NetworkPort.readLine(input);
            appendLine(bytes, line, limit);
            if (line.isEmpty()) break;
            int separator = line.indexOf(':');
            if (i >= 64 || separator < 1 || !isToken(line.substring(0, separator))) {
                throw new IOException("Invalid HTTP request header");
            }
            String name = line.substring(0, separator).toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1);
            if (value.chars().anyMatch(c -> c < 32 && c != '\t' || c == 127)) throw new IOException("Invalid HTTP header value");
            headers.merge(name, value.strip(), (first, second) -> first + "," + second);
        }
        String host = headers.get("host");
        if (parts[2].equals("HTTP/1.1") && (host == null || !host.matches("[^,\\s]+"))) {
            throw new IOException("Invalid HTTP Host header");
        }
        if (headers.containsKey("transfer-encoding") || headers.containsKey("expect")) {
            throw new IOException("Unsupported HTTP request framing");
        }
        String length = headers.getOrDefault("content-length", "0");
        if (!length.matches("[0-9]{1,18}")) throw new IOException("Invalid HTTP content length");
        long bodyLength = Long.parseLong(length);
        if (upgrade && bodyLength != 0) throw new IOException("Unexpected WebSocket upgrade body");
        if (bodyLength > limit - bytes.size()) throw new TooLarge();
        byte[] body = input.readNBytes((int) bodyLength);
        if (body.length != bodyLength) throw new EOFException("Truncated HTTP request");
        bytes.writeBytes(body);
        return new Request(parts[0], parts[2], headers, bytes.toByteArray());
    }

    private static boolean isToken(String value) {
        return value.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    }

    private static void appendLine(ByteArrayOutputStream bytes, String line, int limit) throws IOException {
        if (line.length() + 2 > limit - bytes.size()) throw new TooLarge();
        bytes.writeBytes(line.getBytes(StandardCharsets.ISO_8859_1));
        bytes.write('\r');
        bytes.write('\n');
    }

    static void respond(OutputStream out, int status, String method, byte[] body, boolean text) throws IOException {
        boolean noBody = status == 204 || status == 205 || status == 304;
        String length = status == 204 || status == 304 ? ""
                : "Content-Length: " + (noBody ? 0 : body.length) + "\r\n";
        String type = text ? "text/plain; charset=utf-8" : "application/octet-stream";
        out.write(("HTTP/1.1 " + status + " \r\nConnection: close\r\nContent-Type: " + type + "\r\n"
                + length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        if (!noBody && !method.equals("HEAD")) out.write(body);
        out.flush();
    }

    static final class TooLarge extends IOException {}
}
