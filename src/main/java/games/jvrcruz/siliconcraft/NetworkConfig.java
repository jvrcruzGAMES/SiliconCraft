package games.jvrcruz.siliconcraft;

import games.jvrcruz.siliconcraft.vm.network.NetworkPolicy;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class NetworkConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.BooleanValue ENABLED = BUILDER.comment("Allow guest computers to use Network instructions.").define("enabled", true);
    private static final ModConfigSpec.BooleanValue HTTP = BUILDER.define("allowHttpAndHttps", true);
    private static final ModConfigSpec.BooleanValue TCP = BUILDER.define("allowTcp", true);
    private static final ModConfigSpec.BooleanValue UDP = BUILDER.define("allowUdp", true);
    private static final ModConfigSpec.BooleanValue PRIVATE = BUILDER.comment(
            "Allow private, loopback, link-local and other non-public IP addresses. Prefer an explicit host allowlist when enabling this.").define("allowNonPublicAddresses", false);
    private static final ModConfigSpec.ConfigValue<List<? extends String>> HOSTS = BUILDER.comment(
            "Exact destination hostnames or IP addresses. Empty allows all hosts permitted by the address policy.").defineListAllowEmpty(
            "allowedHosts", List.of(), entry -> entry instanceof String host && !host.isBlank());
    private static final ModConfigSpec.ConfigValue<List<? extends Integer>> PORTS = BUILDER.comment(
            "Destination ports. Empty allows any valid port.").defineListAllowEmpty(
            "allowedPorts", List.of(), entry -> entry instanceof Integer port && port >= 1 && port <= 65535);
    private static final ModConfigSpec.IntValue TIMEOUT = BUILDER.defineInRange("timeoutMilliseconds", 5000, 250, 30000);
    private static final ModConfigSpec.IntValue TRANSFER = BUILDER.defineInRange("maxTransferBytes", 65507, 256, 65507);

    public static final ModConfigSpec SPEC = BUILDER.build();
    private static volatile NetworkPolicy policy = NetworkPolicy.DISABLED;

    private NetworkConfig() {}

    public static NetworkPolicy policy() {
        return policy;
    }

    static void onConfig(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) return;
        if (event instanceof ModConfigEvent.Unloading) {
            policy = NetworkPolicy.DISABLED;
            return;
        }
        Set<String> hosts = HOSTS.get().stream().map(NetworkPolicy::normalizeHost).collect(Collectors.toUnmodifiableSet());
        policy = new NetworkPolicy(ENABLED.get(), HTTP.get(), TCP.get(), UDP.get(), PRIVATE.get(),
                hosts, Set.copyOf(PORTS.get()), TIMEOUT.get(), TRANSFER.get());
    }
}
