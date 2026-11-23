package games.jvrcruz.siliconcraft.vm.network;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public record NetworkPolicy(boolean enabled, boolean http, boolean tcp, boolean udp,
                            Set<String> hosts, Set<Integer> ports, int timeoutMs, int transferLimit,
                            boolean webSocket, boolean smtp, boolean imap, boolean http3,
                            boolean allowNonPublicAddresses, ListenOptions listen) {
    public static final NetworkPolicy DISABLED = new NetworkPolicy(false, false, false, false,
            Set.of(), Set.of(), 5000, 65507, false, false, false, false, false, ListenOptions.DISABLED);

    public NetworkPolicy(boolean enabled, boolean http, boolean tcp, boolean udp,
                         Set<String> hosts, Set<Integer> ports, int timeoutMs, int transferLimit) {
        this(enabled, http, tcp, udp, hosts, ports, timeoutMs, transferLimit,
                true, true, true, true, true, ListenOptions.DISABLED);
    }

    public NetworkPolicy(boolean enabled, boolean http, boolean tcp, boolean udp,
                         Set<String> hosts, Set<Integer> ports, int timeoutMs, int transferLimit,
                         boolean webSocket, boolean smtp, boolean imap, boolean http3) {
        this(enabled, http, tcp, udp, hosts, ports, timeoutMs, transferLimit, webSocket, smtp, imap, http3,
                true, ListenOptions.DISABLED);
    }

    public record ListenOptions(boolean enabled, Set<Integer> ports, String tlsKeyStore) {
        public static final ListenOptions DISABLED = new ListenOptions(false, Set.of(), "");

        public ListenOptions {
            ports = Set.copyOf(ports);
        }

        public static Set<Integer> parsePorts(String text) {
            if (text.isBlank()) return Set.of();
            return Arrays.stream(text.split(",", -1)).map(String::strip).map(entry -> {
                if (entry.equals("-1")) return -1;
                if (!entry.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid listen port");
                int port = Integer.parseInt(entry);
                if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid listen port");
                return port;
            }).collect(Collectors.toUnmodifiableSet());
        }


        boolean permits(int port) {
            return enabled && (ports.contains(-1) || ports.contains(port));
        }
    }

    public NetworkPolicy {
        hosts = Set.copyOf(hosts);
        ports = Set.copyOf(ports);
    }

    public static String normalizeHost(String host) {
        String normalized = host.strip();
        int scope = normalized.indexOf('%');
        normalized = scope < 0 ? normalized.toLowerCase(Locale.ROOT)
                : normalized.substring(0, scope).toLowerCase(Locale.ROOT) + normalized.substring(scope);
        if (normalized.startsWith("[") && normalized.endsWith("]")) return normalized.substring(1, normalized.length() - 1);
        return normalized.endsWith(".") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    static String uriHost(URI uri) {
        String host = normalizeHost(uri.getHost());
        return host.contains(":") ? host.replaceFirst("%25", "%") : host;
    }

    static String tlsHost(String host) {
        int scope = host.indexOf('%');
        return scope < 0 ? host : host.substring(0, scope);
    }

    InetAddress resolve(String host, int port) throws UnknownHostException {
        String normalized = normalizeHost(host);
        if (normalized.isEmpty() || !normalized.matches("[a-z0-9.:-]+(?:%[a-zA-Z0-9_.-]+)?")
                || (normalized.contains("%") && !normalized.contains(":")) || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid destination");
        }
        if ((!hosts.isEmpty() && !hosts.contains(normalized)) || (!ports.isEmpty() && !ports.contains(port))) {
            throw new SecurityException("Destination not allowed");
        }
        InetAddress[] addresses = InetAddress.getAllByName(normalized);
        if (!allowNonPublicAddresses) {
            for (InetAddress address : addresses) {
                if (!isPublic(address)) throw new SecurityException("Non-public destination");
            }
        }
        return addresses[0];
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        int third = Byte.toUnsignedInt(bytes[2]);
        if (bytes.length == 16) {
            int prefix = first << 8 | second;
            int subnet = third << 8 | Byte.toUnsignedInt(bytes[3]);
            return (prefix & 0xe000) == 0x2000
                    && !(prefix == 0x2001 && (subnet < 0x0200 || subnet == 0x0db8))
                    && prefix != 0x2002
                    && !(prefix == 0x3fff && subnet < 0x1000);
        }
        return first != 0 && first != 127 && first < 224
                && !(first == 100 && (second & 0xc0) == 64)
                && !(first == 192 && second == 0 && (third == 0 || third == 2))
                && !(first == 192 && second == 88 && third == 99)
                && !(first == 198 && (second == 18 || second == 19 || second == 51 && third == 100))
                && !(first == 203 && second == 0 && third == 113);
    }
}
