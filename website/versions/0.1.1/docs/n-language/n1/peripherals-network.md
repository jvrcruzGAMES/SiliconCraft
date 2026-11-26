# N1 peripherals and networking (deprecated)

N1 is deprecated. Its `asm { ... }` blocks can issue the same peripheral and networking instructions as an Assembly program. `<DeviceKit>` and `<Network>` are N2 libraries; they are not N1 imports.

## Peripheral calls

Connect the detector according to [peripheral wiring](../../getting-started/peripherals.md). The following inline Assembly block wraps a north-facing port and reads the Environment Detector's world coordinates. It uses a zero-terminated RAM response buffer and prints each character. Local negative and fractional coordinates are preserved in the JSON response; Sable is optional.

See the [runnable N1 source](examples/environment-detector.n) and [Assembly method catalog](../../assembly/peripherals.md) for status values and other device IDs. The program needs the instructions in its inline block enabled on the SoC. Consume a result before submitting another request.

## Networking

Use `NREADY`, a request instruction such as `NHTTP`, `NSTATUS`, `NLEN`, and `NRESULT` inside an inline Assembly block. The [runnable HTTP example](examples/network-http.n) uses exactly the same mailbox as [Assembly networking](../../assembly/network.md). Network requests originate from the Minecraft server and obey [server network settings](../../getting-started/network-settings.md).

For new projects, prefer [N2 DeviceKit](../n2/peripherals.md) and [N2 Network](../n2/network.md), which provide named constants and managed text helpers.

[N1 chapter](README.md)
