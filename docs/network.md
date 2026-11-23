# N2 Network library

Import `<Network>` for HTTP, HTTP/3, WebSocket, SMTP, IMAP, and raw outbound TCP and UDP. The Minecraft server makes every connection, including the integrated server in singleplayer. Guest computers can connect to remote services. Listening on a server port requires operator opt-in.

## Server settings

SiliconCraft generates `config/siliconcraft/siliconcraft-network.toml` under the server or game directory. If the config still exists at `config/siliconcraft-network.toml`, SiliconCraft copies it to the new location when the new file is absent. For a per-world override, use `<world>/serverconfig/siliconcraft/siliconcraft-network.toml`. Update any existing override to that path.

Addons that need config files use `config/siliconcraft/addons/<addonname>/`. Networking and the built-in transports start enabled.

| Setting | Default | Effect |
|---|---|---|
| `enabled` | `true` | Enables the Network instructions. Setting it to false rejects requests and closes existing sockets. |
| `allowHttpAndHttps`, `allowTcp`, `allowUdp` | `true` | Enables each transport independently. |
| `allowWebSocket`, `allowSmtp`, `allowImap` | `true` | Enables the corresponding protocol entry points. These also require `allowTcp`. |
| `allowHttp3` | `true` | Enables HTTP/3. It also requires `allowHttpAndHttps` and `allowUdp`. |
| `allowNonPublicAddresses` | `true` | Allows private, loopback, link-local, and reserved destinations. Set it to false to require public destinations. |
| `allowOpeningPorts` | `false` | Allows guest computers to bind ports listed in `allowedListenPorts`. |
| `allowedListenPorts` | `""` | Comma-separated TCP or UDP port numbers from 1 to 65535, such as `"8080, 8443, 9000"`. An empty string allows no ports. Include `-1` to allow any port. |
| `listenTlsKeyStore` | `""` | Path to a PKCS#12 keystore containing the certificate chain and private key for HTTPS and WSS listeners. Set `SILICONCRAFT_TLS_KEYSTORE_PASSWORD` in the server process environment. |
| `allowedHosts`, `allowedPorts` | `[]` | Exact host and port allowlists. Empty lists allow destinations that pass the other checks. |
| `timeoutMilliseconds` | `5000` | Deadline for a request, including its wait in the worker queue. |
| `maxTransferBytes` | `65507` | Maximum bytes per send or receive and maximum HTTP response body read. |

Connections use the IP addresses checked by the server policy. If `allowNonPublicAddresses` is false, every address returned by DNS must be public, including for allowlisted hosts. Listeners also reject non-public peer addresses when this setting is false. HTTPS verifies the hostname against the server certificate. HTTP uses HTTP/1.1, returns redirect status codes to the guest, and supports content-length, chunked, and connection-close responses. HTTP headers and redirects are handled by the network layer. The guest receives the status code and body bytes.

Each computer has one request mailbox, up to four open connections, up to four open listeners, and a 100 ms interval between submissions. Close requests can be submitted immediately after consuming a result. Reset, power-off, unloading, changing programs, and policy reloads close its sockets. A request that times out while using a connection closes it. A timed-out accept or datagram receive leaves the listener open.

## HTTP callbacks and routes in N2

Import `<Network>` to use the HTTP helpers. The compiler links their N2 implementation into the guest program. It emits ordinary `CALL` instructions for callbacks, and the guest uses the same Network mailbox as hand-written Assembly.

### Make requests

```lua
from <Network> import http_get, http_text, NET_OK
import <std>

func received(status, code, address, length)
    if status == NET_OK then
        println(code)
        println(http_text(address, length))
    else
        println(status)
    end
    return code
end

func main()
    http_get("https://example.org/", received)
end
```

| Function | Use |
|---|---|
| `http_get(url, callback)` | Sends a GET request. |
| `http_post(url, text, callback)` | Sends UTF-8 plain text in a POST request. |
| `http_request(method, url, text, callback)` | Sends UTF-8 plain text with a `NET_*` HTTP method. |
| `http_request_bytes(method, url, address, length, callback)` | Sends the specified bytes from guest RAM. |
| `http3_request(method, url, text, callback)` | Sends UTF-8 text over HTTPS using HTTP/3. |
| `http3_request_bytes(method, url, address, length, callback)` | Sends raw bytes over HTTPS using HTTP/3. |
| `http_text(address, length)` | Decodes UTF-8 bytes to an N2 string. |

Pass the name of a function with four parameters. The callback receives transport status, HTTP status code, response address, and byte count. A response such as 404 has transport status `NET_OK`. A transport failure supplies zero for the code, address, and length. The helper yields the guest CPU while waiting, then calls the callback and returns its result. For binary data, use the address and count directly. N2 text uses a zero terminator.

The helpers wait for the mailbox rate limit. If a raw request already owns the mailbox, the client callback receives `NET_BUSY` (−7) and the pending raw result stays available to its caller. Finish raw mailbox operations before invoking another helper. Call these helpers from ordinary guest code since waiting suspends execution.

### Serve routes

Enable and allowlist the desired port in the server config before running this program.

```lua
import <Network>
import <std>

func home(address, length)
    return http_reply(200, "Hello from SiliconCraft")
end

func echo(address, length)
    return http_reply(201, http_body(address, length))
end

func main()
    local listener = http_listen(NET_HTTP, 8080)
    if listener < 0 then
        println(listener)
        return
    end
    local routes = [http_route(NET_GET, "/", home), http_route(NET_POST, "/echo", echo)]
    http_serve(listener, routes)
    if network_close(listener) == 1 then
        while network_status() == NET_PENDING do
            sleep(1)
        end
        network_result(0, 0)
    end
end
```

- `http_listen(protocol, port)` takes `NET_HTTP` or `NET_HTTPS` and returns a listener handle or a negative status.
- `http_route(method, path, callback)` creates a route. The first matching route wins. Paths are case-sensitive and exclude the query string. Use the URL-encoded path and `NET_ANY` to match every method.
- A route callback takes the request address and length and returns `http_reply(status, text)` for UTF-8 plain text or `http_reply_bytes(status, address, length)` for binary data. `http_body(address, length)` reads the request body as UTF-8 text. The original request line and headers are still available through the callback's buffer.
- `http_dispatch(listener, routes)` handles one request and returns a transport status. `http_serve(listener, routes)` dispatches in a loop, retrying idle timeouts and malformed-client failures. An unmatched request receives 404. A callback that supplies a zero response descriptor receives 500.

## N2 and assembly

N2 compiles each Network call to the Assembly instruction shown below. The assembler resolves the program, and the virtual CPU executes the instructions and submits requests to the server-side mailbox. Enable the required Network opcodes on the computer’s SoC.

| N2 function | Assembly | Result |
|---|---|---|
| `network_open(protocol, host, port)` | `NOPEN dst, protocol, host_ptr, port` | Handle for raw TCP/UDP or an SMTP/IMAP stream. |
| `network_send(socket, address, length)` | `NSEND dst, socket, address, length` | Number of bytes sent. |
| `network_receive(socket, max_bytes)` | `NRECV dst, socket, max_bytes` | Number of bytes in a stream read, UDP datagram, or WebSocket message. Streams and message connections return −1 at end of stream. |
| `network_close(socket)` | `NCLOSE dst, socket` | 1 on success. |
| `network_http(method, url, address, length)` | `NHTTP dst, method, url_ptr, address, length` | HTTP status code and response body. Pass address and length as 0 for an empty request body. |
| `network_http3(method, url, address, length)` | `NHTTP3 dst, method, url_ptr, address, length` | HTTP status code and response body over QUIC. |
| `network_websocket(url)` | `NWSOPEN dst, url_ptr` | WebSocket handle for a `ws://` or `wss://` URL. |
| `network_send_text(handle, address, length)` | `NWSTEXT dst, handle, address, length` | Sends one UTF-8 WebSocket text message. Returns its byte count. |
| `network_tls(handle)` | `NTLS dst, handle` | Upgrades a plain TCP-based stream to verified TLS. Returns 1. |
| `network_listen(protocol, port)` | `NLISTEN dst, protocol, port` | Opens a server port and returns its listener handle. |
| `network_accept(listener)` | `NACCEPT dst, listener` | Accepts one TCP, HTTP(S), or WebSocket(S) connection and returns its handle. HTTP(S) and WebSocket(S) also return the request or upgrade bytes. |
| `network_respond(handle, status, address, length)` | `NRESPOND dst, handle, status, address, length` | Sends a final HTTP(S) response with the supplied status and body, closes the connection, and returns 1. |
| `network_respond_text(handle, status, address, length)` | `NRESPONDT dst, handle, status, address, length` | Sends a final HTTP(S) response with the given UTF-8 bytes and a text content type. |
| `network_ready()` | `NREADY dst` | Returns 1 when the mailbox is idle and eligible for submission. |
| `network_utf8(source, count, target, capacity)` | `NUTF8 dst, source, count, target, capacity` | With count −1, encodes a zero-terminated guest string to UTF-8. Otherwise decodes the specified byte count to a zero-terminated guest string. Capacity 0 measures the result without writing. The returned count excludes the terminator. |
| `network_status()` | `NSTATUS dst` | Current mailbox status. |
| `network_length()` | `NLEN dst` | Available result bytes before consuming the response. |
| `network_result(address, max_bytes)` | `NRESULT dst, address, max_bytes` | Reads the result, copies up to that many bytes into RAM, and clears the mailbox. |

The request functions return 1 if accepted and 0 if another request is pending or its result is waiting. Read `network_status()` before consuming the result. Consume failed results too. Status values are `NET_IDLE` (0), `NET_PENDING` (1), `NET_OK` (2), `NET_DISABLED` (−1), `NET_DENIED` (−2), `NET_IO_ERROR` (−3), `NET_LIMIT` (−4), `NET_INVALID` (−5), and `NET_TIMEOUT` (−6).

For an outbound UTF-8 text body, use `network_http_text` or `network_http3_text` with the same operands as the raw functions. In Assembly, append a final `1` operand to `NHTTP` or `NHTTP3` to set the plain-text content type.

HTTP methods are `NET_GET`, `NET_POST`, `NET_PUT`, `NET_DELETE`, `NET_PATCH`, and `NET_HEAD`. Network payloads store one unsigned byte per RAM cell. Hostnames and URLs use the normal N2 string representation or a zero-terminated RAM string in assembly. UDP sends and receives individual datagrams. An outbound UDP handle requires a send before receiving and accepts replies only from the selected peer address and port.

```lua
import <Network>
import <std>

func main()
    if network_http(NET_GET, "https://example.org/", 0, 0) == 1 then
        while network_status() == NET_PENDING do
            sleep(1)
        end
        local status = network_status()
        local count = network_length()
        if count > 4096 then
            count = 4096
        end
        local http_code = network_result(8192, count)
        if status == NET_OK then
            println(http_code)
            println(count)
        else
            println(status)
        end
    end
end
```

## Listening on a server port

For example, to let computers listen on ports 8080 and 8443, add these settings to `siliconcraft-network.toml`.

```toml
allowOpeningPorts = true
allowedListenPorts = "8080, 8443"
```

The listener binds on the Minecraft server's network interfaces. The operating system and firewall control whether other devices can reach it. The matching transport switch must also be enabled. Closing a listener releases the port. Close accepted handles independently.

| Listener | Protocol value | Guest workflow |
| --- | ---: | --- |
| TCP | `NET_TCP` (1) | Accept a connection, then send and receive stream bytes on its handle. |
| UDP | `NET_UDP` (2) | Receive one datagram on the listener handle. Send on that handle to reply to the last successfully received sender. |
| HTTP | `NET_HTTP` (7) | Accept and copy the request bytes, then reply with `network_respond`. |
| HTTPS | `NET_HTTPS` (8) | Use the HTTP workflow over TLS. |
| WebSocket | `NET_WS` (9) | Accept the upgrade, then send or receive messages on the returned connection. |
| Secure WebSocket | `NET_WSS` (10) | Use the WebSocket workflow over TLS. |

HTTP listeners accept one HTTP/1.0 or HTTP/1.1 request per connection. Send request bodies with `Content-Length`. The mailbox returns the request line, headers, and body within `maxTransferBytes`. Responses use `application/octet-stream` and close the connection. WebSocket listeners return the upgrade request bytes with the handle, so guests can read the requested path. WSS clients verify the certificate and hostname before upgrading.

## WebSocket

Open the URL with `network_websocket("wss://example.org/echo")`. Consume the result to get the handle. Use `network_send` for binary messages or `network_send_text` for UTF-8 text. Each receive returns one complete message and copies its bytes through `network_result`. Use a receive limit large enough for a complete message. An oversized message returns `NET_LIMIT` and closes the handle. Ping and pong processing occurs during receives. The client supports the standard upgrade and message framing. Servers that require an extension, subprotocol, or custom handshake headers need a compatible endpoint.

## SMTP, IMAP, and custom protocols

`network_open` takes `NET_SMTP` (3), `NET_SMTPS` (4), `NET_IMAP` (5), or `NET_IMAPS` (6), plus the hostname and port. The secure variants negotiate TLS before sending or receiving application bytes and verify the server's identity.

The guest sends protocol commands and reads replies through the stream handle. For example, after reading an IMAP greeting, send the ASCII bytes for `A001 CAPABILITY\r\n`. Read until you receive the tagged `A001` reply. An SMTP session similarly starts by reading its greeting and then sending an `EHLO` command terminated with CRLF. TCP reads can split or combine lines, so keep partial replies between reads.

For explicit TLS, use `NET_SMTP` or `NET_IMAP`, send the service's `STARTTLS` command, read the complete successful reply, and then call `network_tls(handle)` before sending credentials. Use `NET_TCP` or `NET_UDP` for custom protocols. Raw TCP can also be upgraded with `network_tls`. The protocol settings govern their named entry points. Use the transport switches and destination allowlists to control raw socket access.

## HTTP/3

Use `network_http3` with an HTTPS URL and the same method constants as `network_http`. It makes an HTTP/3-only request to a policy-approved address, validates the TLS certificate, and returns the response status and body. Oversized response bodies return `NET_LIMIT`. SiliconCraft ships the Java HTTP/3 stack inside its JAR. [Flupke](https://github.com/ptrd/flupke) · [Kwik](https://github.com/ptrd/kwik)
