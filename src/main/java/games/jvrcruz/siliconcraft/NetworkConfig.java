package games.jvrcruz.siliconcraft;

import games.jvrcruz.siliconcraft.vm.network.NetworkPolicy;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class NetworkConfig {
    private static final String FILE_NAME = "siliconcraft-network.toml";
    private static final String CONFIG_PATH = "siliconcraft/" + FILE_NAME;
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.BooleanValue ENABLED = BUILDER.comment("Allow guest computers to use Network instructions.").define("enabled", true);
    private static final ModConfigSpec.BooleanValue HTTP = BUILDER.define("allowHttpAndHttps", true);
    private static final ModConfigSpec.BooleanValue TCP = BUILDER.define("allowTcp", true);
    private static final ModConfigSpec.BooleanValue UDP = BUILDER.define("allowUdp", true);
    private static final ModConfigSpec.BooleanValue WEBSOCKET = BUILDER.define("allowWebSocket", true);
    private static final ModConfigSpec.BooleanValue SMTP = BUILDER.define("allowSmtp", true);
    private static final ModConfigSpec.BooleanValue IMAP = BUILDER.define("allowImap", true);
    private static final ModConfigSpec.BooleanValue HTTP3 = BUILDER.define("allowHttp3", true);
    private static final ModConfigSpec.BooleanValue NON_PUBLIC = BUILDER.define("allowNonPublicAddresses", true);
    private static final ModConfigSpec.BooleanValue OPEN_PORTS = BUILDER.define("allowOpeningPorts", false);
    private static final ModConfigSpec.ConfigValue<String> LISTEN_PORTS = BUILDER.comment(
            "Comma-separated port numbers (1-65535). Use -1 to allow every port. Empty allows none.")
            .define("allowedListenPorts", "", value -> value instanceof String text && validListenPorts(text));
    private static final ModConfigSpec.ConfigValue<String> LISTEN_KEYSTORE = BUILDER.comment(
            "Path to a PKCS12 server keystore for HTTPS and WSS. Set its password in SILICONCRAFT_TLS_KEYSTORE_PASSWORD.")
            .define("listenTlsKeyStore", "");
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

    static void register(ModContainer container) {
        var directory = FMLPaths.CONFIGDIR.get();
        var oldPath = directory.resolve(FILE_NAME);
        var newPath = directory.resolve(CONFIG_PATH);
        if (Files.exists(oldPath) && Files.notExists(newPath)) {
            try {
                Files.createDirectories(newPath.getParent());
                Files.copy(oldPath, newPath);
            } catch (FileAlreadyExistsException ignored) {
                // An existing config at the new path takes precedence.
            } catch (IOException error) {
                throw new UncheckedIOException("Could not migrate the SiliconCraft network config to " + newPath, error);
            }
        }
        container.registerConfig(ModConfig.Type.SERVER, SPEC, CONFIG_PATH);
    }

    public static NetworkPolicy policy() {
        return policy;
    }

    private static boolean validListenPorts(String text) {
        try {
            NetworkPolicy.ListenOptions.parsePorts(text);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    static void onConfig(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) return;
        if (event instanceof ModConfigEvent.Unloading) {
            policy = NetworkPolicy.DISABLED;
            return;
        }
        Set<String> hosts = HOSTS.get().stream().map(NetworkPolicy::normalizeHost).collect(Collectors.toUnmodifiableSet());
        policy = new NetworkPolicy(ENABLED.get(), HTTP.get(), TCP.get(), UDP.get(),
                hosts, Set.copyOf(PORTS.get()), TIMEOUT.get(), TRANSFER.get(),
                WEBSOCKET.get(), SMTP.get(), IMAP.get(), HTTP3.get(), NON_PUBLIC.get(),
                new NetworkPolicy.ListenOptions(OPEN_PORTS.get(), NetworkPolicy.ListenOptions.parsePorts(LISTEN_PORTS.get()), LISTEN_KEYSTORE.get()));
    }
}
