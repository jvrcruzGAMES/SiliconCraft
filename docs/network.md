# N2 Network library

Import `<Network>` for HTTP and HTTPS requests and outbound TCP and UDP sockets. The Minecraft server makes every connection. In singleplayer the integrated server makes them.

## Server settings

SiliconCraft generates `config/siliconcraft-network.toml` under the server or game directory. Networking and all three transport options start enabled. Server operators can change these settings.

| Setting | Default | Effect |
|---|---|---|
| `enabled` | `true` | Enables the Network instructions. Setting it to false rejects requests and closes existing sockets. |
| `allowHttpAndHttps`, `allowTcp`, `allowUdp` | `true` | Enables each transport independently. |
| `allowNonPublicAddresses` | `false` | Allows loopback, private, link-local, and other non-public addresses when explicitly enabled. |
| `allowedHosts`, `allowedPorts` | `[]` | Exact host and port allowlists. Empty lists allow destinations that pass the other checks. |
| `timeoutMilliseconds` | `5000` | Deadline for a request, including its wait in the worker queue. |
| `maxTransferBytes` | `65507` | Maximum bytes per send or receive and maximum HTTP response body read. |

Connections use the IP addresses checked by the server policy. HTTPS verifies the hostname against the server certificate. HTTP uses HTTP/1.1, returns redirect status codes to the guest, and supports content-length, chunked, and connection-close responses. HTTP headers and redirects are handled by the network layer. The guest receives the status code and body bytes.

Each computer has one request mailbox, up to four open sockets, and a 100 ms interval between submissions. Reset, power-off, unloading, changing programs, and policy reloads close its sockets. A request that times out while using a socket closes that socket.

## N2 and assembly

Enable the required Network opcodes on the computer’s SoC.

| N2 function | Assembly | Result |
|---|---|---|
| `network_open(protocol, host, port)` | `NOPEN dst, protocol, host_ptr, port` | Socket handle. Use `NET_TCP` or `NET_UDP`. |
| `network_send(socket, address, length)` | `NSEND dst, socket, address, length` | Number of bytes sent. |
| `network_receive(socket, max_bytes)` | `NRECV dst, socket, max_bytes` | Number of bytes in a TCP read or UDP datagram. TCP returns −1 at end of stream. |
| `network_close(socket)` | `NCLOSE dst, socket` | 1 on success. |
| `network_http(method, url, address, length)` | `NHTTP dst, method, url_ptr, address, length` | HTTP status code and response body. Pass address and length as 0 for an empty request body. |
| `network_status()` | `NSTATUS dst` | Current mailbox status. |
| `network_length()` | `NLEN dst` | Available result bytes before consuming the response. |
| `network_result(address, max_bytes)` | `NRESULT dst, address, max_bytes` | Reads the result, copies up to that many bytes into RAM, and clears the mailbox. |

The request functions return 1 if accepted and 0 if another request is pending or its result is waiting. Read `network_status()` before consuming the result. Consume failed results too. Status values are `NET_IDLE` (0), `NET_PENDING` (1), `NET_OK` (2), `NET_DISABLED` (−1), `NET_DENIED` (−2), `NET_IO_ERROR` (−3), `NET_LIMIT` (−4), `NET_INVALID` (−5), and `NET_TIMEOUT` (−6).

HTTP methods are `NET_GET`, `NET_POST`, `NET_PUT`, `NET_DELETE`, `NET_PATCH`, and `NET_HEAD`. Network payloads store one unsigned byte per RAM cell. Hostnames and URLs use the normal N2 string representation or a zero-terminated RAM string in assembly. UDP sends and receives individual datagrams.

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
