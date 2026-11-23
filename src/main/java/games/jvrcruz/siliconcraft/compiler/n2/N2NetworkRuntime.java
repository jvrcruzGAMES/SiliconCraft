package games.jvrcruz.siliconcraft.compiler.n2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class N2NetworkRuntime {
    static final String PREFIX = "__net_";
    record Api(int arity, int callbackArity) {}
    static final Map<String, Api> APIS = Map.ofEntries(
            Map.entry("http_get", new Api(2, 4)),
            Map.entry("http_post", new Api(3, 4)),
            Map.entry("http_request", new Api(4, 4)),
            Map.entry("http_request_bytes", new Api(5, 4)),
            Map.entry("http3_request", new Api(4, 4)),
            Map.entry("http3_request_bytes", new Api(5, 4)),
            Map.entry("http_text", new Api(2, -1)),
            Map.entry("http_body", new Api(2, -1)),
            Map.entry("http_reply", new Api(2, -1)),
            Map.entry("http_reply_bytes", new Api(3, -1)),
            Map.entry("http_route", new Api(3, 2)),
            Map.entry("http_listen", new Api(2, -1)),
            Map.entry("http_dispatch", new Api(2, -1)),
            Map.entry("http_serve", new Api(2, -1)));

    private N2NetworkRuntime() {}

    static Map<String, N2AST.FunctionNode> load() throws IOException {
        String path = "/siliconcraft/n2/network.n2";
        try (var input = N2NetworkRuntime.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing N2 Network runtime");
            String source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            var program = new N2Parser(new N2Lexer(source, path).tokenize(), path).parse();
            var functions = new LinkedHashMap<String, N2AST.FunctionNode>();
            for (var statement : program.statements) {
                if (!(statement instanceof N2AST.FunctionNode function) || !function.name.startsWith(PREFIX)
                        || functions.putIfAbsent(function.name, function) != null) {
                    throw new IOException("Invalid declaration in N2 Network runtime");
                }
            }
            return functions;
        }
    }
}
