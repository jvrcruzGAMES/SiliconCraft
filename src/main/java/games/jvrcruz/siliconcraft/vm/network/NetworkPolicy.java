package games.jvrcruz.siliconcraft.vm.network;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

public record NetworkPolicy(boolean enabled, boolean http, boolean tcp, boolean udp, boolean allowPrivate,
                            Set<String> hosts, Set<Integer> ports, int timeoutMs, int transferLimit) {
    public static final NetworkPolicy DISABLED = new NetworkPolicy(false, false, false, false, false,
            Set.of(), Set.of(), 5000, 65507);

    public NetworkPolicy {
        hosts = Set.copyOf(hosts);
        ports = Set.copyOf(ports);
    }

    public static String normalizeHost(String host) {
        String normalized = host.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) return normalized.substring(1, normalized.length() - 1);
        return normalized.endsWith(".") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    InetAddress[] resolve(String host, int port) throws UnknownHostException {
        String normalized = normalizeHost(host);
        if (normalized.isEmpty() || !normalized.matches("[a-z0-9.:-]+") || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid destination");
        }
        if ((!hosts.isEmpty() && !hosts.contains(normalized)) || (!ports.isEmpty() && !ports.contains(port))) {
            throw new SecurityException("Destination not allowed");
        }
        InetAddress[] addresses = InetAddress.getAllByName(normalized);
        if (!allowPrivate) {
            for (InetAddress address : addresses) {
                if (!isPublic(address)) throw new SecurityException("Non-public destination");
            }
        }
        return addresses;
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        int third = Byte.toUnsignedInt(bytes[2]);
        if (bytes.length == 16) {
            return (first & 0xe0) == 0x20 && !(first == 0x20 && (second == 2
                    || second == 1 && ((third == 0 && bytes[3] == 0) || (third == 0x0d && bytes[3] == (byte) 0xb8))));
        }
        return first != 0 && first != 127 && first < 224
                && !(first == 100 && (second & 0xc0) == 64)
                && !(first == 192 && second == 0 && (third == 0 || third == 2))
                && !(first == 198 && (second == 18 || second == 19 || second == 51 && third == 100))
                && !(first == 203 && second == 0 && third == 113);
    }
}
