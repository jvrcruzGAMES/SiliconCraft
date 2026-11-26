# Chapter 2: Assembly

Assembly controls SiliconCraft's virtual CPU directly. Programs use registers `A`–`D`, linear RAM, stack operations, jumps, interrupts, and the instructions enabled by your chip's architecture.

1. [Syntax and projects](syntax.md): literals, labels, addressing, static data, includes, and compilation.
2. [Instruction reference](reference.md): CPU state and instruction operands.
3. [Peripherals](peripherals.md): mailbox status, numeric device methods, strings, and serial addresses.
4. [Networking](network.md): requests, buffers, sockets, HTTP, TLS, WebSocket, and listeners.
5. [Create and airship peripherals](create-peripherals.md): sensor readings and named Redstone Link channels.
6. [Examples](examples/README.md): CPU programs plus runnable peripheral and networking sources.

Use the Assembly Writer for `.asm` or `.s` files. Use the Project Compiler for projects with `main.asm` and includes. Burn the compiled `.o` program to ROM or storage with the Programmer. See [Getting Started](../getting-started/README.md) for the hardware workflow.

[All chapters](../README.md) · [N2 equivalent APIs](../n-language/n2/README.md)
