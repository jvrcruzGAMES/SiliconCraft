# Server network settings

These settings apply to both [Assembly networking](../assembly/network.md) and the [N2 Network library](../n-language/n2/network.md). Requests originate from the Minecraft server, including the integrated server in singleplayer.


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


## Allow guest listeners

Guest listening ports are disabled by default. For example, to permit ports 8080 and 8443:

```toml
allowOpeningPorts = true
allowedListenPorts = "8080, 8443"
```

Listeners bind on the Minecraft server's network interfaces. The operating system and firewall determine reachability. The selected transport must also be enabled. HTTPS and WSS require the configured TLS keystore.

[Getting Started](README.md)
