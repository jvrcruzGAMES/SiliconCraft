# Assembly networking

Network instructions submit asynchronous requests to the Minecraft server. Include the opcodes used by your program in its SoC architecture. Connections use the server's network access, including in singleplayer.

Read [server network settings](../getting-started/network-settings.md) before using remote services or listeners. The [N2 networking chapter](../n-language/n2/network.md) provides higher-level HTTP callbacks and routing.

## Mailbox workflow

1. Use `NREADY dst` to check whether a request can be submitted. If it is 0, wait with `SLEEP 1` before retrying.
2. Submit a request. Its destination register receives 1 when queued, or 0 when the mailbox is busy or cannot accept it yet.
3. Poll `NSTATUS dst` while it is 1 (pending), yielding with `SLEEP 1`.
4. Save the final status and use `NLEN dst` to inspect response size before consuming it.
5. Call `NRESULT dst, address, capacity` to copy response bytes and consume the result. For numeric-only results, use address and capacity 0. Consume failures too.

| Status | Value |
|---|---:|
| Idle | 0 |
| Pending | 1 |
| Success | 2 |
| Disabled | −1 |
| Denied by policy | −2 |
| I/O error | −3 |
| Resource or transfer limit | −4 |
| Invalid request | −5 |
| Timeout | −6 |

Each computer has one mailbox, up to four connections, up to four listeners, and a 100 ms submission interval. Close requests can be submitted immediately after consuming a result. Reset, power-off, chunk unload, program replacement, and policy reload close sockets. A timed-out connection request closes that connection; a timed-out accept or datagram receive leaves the listener open.

## Instruction reference

| Instruction and operands | Completed result |
|---|---|
| `NOPEN dst, protocol, host_ptr, port` | Handle for raw TCP/UDP or an SMTP/IMAP stream. |
| `NSEND dst, socket, address, length` | Number of bytes sent. |
| `NRECV dst, socket, max_bytes` | Number of bytes in a stream read, UDP datagram, or WebSocket message. Streams and message connections return −1 at end of stream. |
| `NCLOSE dst, socket` | 1 on success. |
| `NHTTP dst, method, url_ptr, address, length` | HTTP status code and response body. Pass address and length as 0 for an empty request body. |
| `NHTTP3 dst, method, url_ptr, address, length` | HTTP status code and response body over QUIC. |
| `NWSOPEN dst, url_ptr` | WebSocket handle for a `ws://` or `wss://` URL. |
| `NWSTEXT dst, handle, address, length` | Sends one UTF-8 WebSocket text message. Returns its byte count. |
| `NTLS dst, handle` | Upgrades a plain TCP-based stream to verified TLS. Returns 1. |
| `NLISTEN dst, protocol, port` | Opens a server port and returns its listener handle. |
| `NACCEPT dst, listener` | Accepts one TCP, HTTP(S), or WebSocket(S) connection and returns its handle. HTTP(S) and WebSocket(S) also return the request or upgrade bytes. |
| `NRESPOND dst, handle, status, address, length` | Sends a final HTTP(S) response with the supplied status and body, closes the connection, and returns 1. |
| `NRESPONDT dst, handle, status, address, length` | Sends a final HTTP(S) response with the given UTF-8 bytes and a text content type. |
| `NREADY dst` | Returns 1 when the mailbox is idle and eligible for submission. |
| `NUTF8 dst, source, count, target, capacity` | With count −1, encodes a zero-terminated guest string to UTF-8. Otherwise decodes the specified byte count to a zero-terminated guest string. Capacity 0 measures the result without writing. The returned count excludes the terminator. |
| `NSTATUS dst` | Current mailbox status. |
| `NLEN dst` | Available result bytes before consuming the response. |
| `NRESULT dst, address, max_bytes` | Reads the result, copies up to that many bytes into RAM, and clears the mailbox. |

Instruction destinations receive the submission flag for asynchronous operations; retrieve the completed result with `NRESULT`. `NREADY`, `NUTF8`, `NSTATUS`, `NLEN`, and `NRESULT` operate directly on CPU/mailbox state.

## Protocol and HTTP method values

| Protocol | Value |
|---|---:|
| TCP / UDP | 1 / 2 |
| SMTP / SMTPS | 3 / 4 |
| IMAP / IMAPS | 5 / 6 |
| HTTP / HTTPS | 7 / 8 |
| WebSocket / secure WebSocket | 9 / 10 |

`NHTTP` and `NHTTP3` methods are GET = 0, POST = 1, PUT = 2, DELETE = 3, PATCH = 4, HEAD = 5. Hostnames and URLs are zero-terminated guest strings. Payload buffers use one unsigned byte per RAM cell.

## HTTP and text

`NHTTP` returns the HTTP status code plus response body bytes. An HTTP 404 still has mailbox status 2; transport failure is a negative mailbox status. Use address and length 0 for an empty request body. Append a final `1` operand to `NHTTP` or `NHTTP3` for a plain-text request content type.

`NUTF8` encodes or decodes guest strings. Count −1 encodes a zero-terminated guest string; a nonnegative count decodes that many input bytes. Capacity 0 measures the output without writing. Counts exclude the terminating zero.

HTTP uses HTTP/1.1; redirects are returned to the guest. `NHTTP3` requires HTTPS and enabled HTTP/UDP transports, validates the certificate, and requests HTTP/3 only. Oversized bodies fail with the limit status.

The [HTTP request example](examples/network-http.asm) prints the HTTP status and received byte count. The response buffer has a fixed 4096-cell capacity and the example caps copying to that size.

## Streams, datagrams, and messages

Use `NOPEN` for TCP, UDP, SMTP, SMTPS, IMAP, or IMAPS. Read the result to obtain the handle. Use `NSEND` and `NRECV` with that handle, consuming each result before submitting another request. TCP reads may split or combine protocol lines. Receive returns −1 at end of stream or message connection.

UDP sends and receives datagrams. An outbound UDP handle must send before receiving; replies are accepted only from its selected peer. `NWSOPEN` takes a `ws://` or `wss://` URL. `NSEND` sends binary WebSocket messages and `NWSTEXT` sends UTF-8 text. Each receive returns one complete message. Oversized messages fail and close the handle; ping/pong processing occurs during receives.

SMTPS and IMAPS negotiate verified TLS immediately. For STARTTLS, use SMTP or IMAP, complete the service's upgrade handshake, then issue `NTLS`. It can also upgrade a raw TCP stream. Send credentials only after a successful upgrade.

## Listening on a server port

Operators must enable and allowlist listener ports in [server settings](../getting-started/network-settings.md). `NLISTEN` accepts TCP, UDP, HTTP, HTTPS, WS, or WSS protocol values. Read its result for the listener handle.

- TCP: `NACCEPT` yields a connection handle, then use stream send/receive instructions.
- UDP: receive on the listener handle, then send on that handle to reply to the last successfully received sender.
- HTTP(S): accept, copy request bytes, then use `NRESPOND` or `NRESPONDT`. The response closes the accepted connection.
- WebSocket(S): accept the upgrade, then use message send/receive instructions. The accept result includes the upgrade request bytes.

HTTP listeners accept one HTTP/1.0 or HTTP/1.1 request per connection. Request bodies need `Content-Length`. Returned bytes contain the request line, headers, and body up to the configured transfer limit. `NRESPOND` uses a binary content type; `NRESPONDT` uses UTF-8 plain text. HTTPS and WSS listeners require the configured TLS keystore. Close listener and accepted handles independently with `NCLOSE`.


[Chapter contents](README.md)
