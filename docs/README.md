# SiliconCraft Documentation

Welcome to the **SiliconCraft** documentation. SiliconCraft is a Minecraft 1.21.1 mod (NeoForge) that allows players to design, fabricate, program, and operate custom computer architectures inside Minecraft.

---

## Documentation Guide

| File | Description |
| :--- | :--- |
| **[getting-started.md](getting-started.md)** | Step-by-step walkthrough from mining silicon and deepslate ore to assembling SoCs, expanding PC hardware, and booting your first computer. |
| **[n-language/README.md](n-language/README.md)** | Complete guide to the N language family (N1 classic standard & N2 object-oriented standard), syntax, libraries, and compilation. |
| **[isa-reference.md](isa-reference.md)** | Complete Instruction Set Architecture (ISA) reference detailing registers, status flags, and opcodes for control flow, arithmetic, storage, graphics, audio, peripherals, and networking. |
| **[assembly-syntax.md](assembly-syntax.md)** | Guide to writing `.s` and `.asm` files: syntax rules, labels, comments, number formats, RAM/storage addressing, and device operations. |
| **[examples.md](examples.md)** | Ready-to-use assembly and `.n` programs (Fibonacci, Counters, Array processing, Framebuffer graphics, and Audio synthesis). |
| **[Peripherals](peripherals.md)** | Built-in devices, serial wiring, DeviceKit, optional integration behavior, and Environment Detector world coordinates with or without Sable. |
| **[Network](network.md)** | N2 HTTP utilities, raw sockets, Assembly instructions, and server settings. |
| **[Airship Sensor and Digital Redstone Link](create-peripherals/README.md)** | Airship peripheral setup, method reference, and runnable Assembly and N2 examples. |

---

## SiliconCraft Architecture Overview

SiliconCraft features an accessible 4-register architecture with custom instruction sets, virtual RAM, multi-drive binary storage, hardware framebuffer graphics, and synthesized audio buffers.

```
       +-----------------------------------------------------------+
       |                        Virtual CPU                        |
       |  +-----------------------------------------------------+  |
       |  |  Registers: A, B, C, D                              |  |
       |  |  Pointers & Counters: PC, Cycles, Stack Pointer     |  |
       |  |  Flags: Zero (Z), Sign (S)                          |  |
       |  +-----------------------------------------------------+  |
       |                             |                             |
       |             Fetch & Decode Custom ISA (Chip)              |
       +-----------------------------------------------------------+
               |               |             |              |
               v               v             v              v
         +-----------+   +-----------+  +----------+  +------------+
         |  ROM/BIOS |   | Linear RAM|  | Storage  |  | Terminal & |
         | (Program) |   | (SoC+PC)  |  | Drives   |  | Framebuffer|
         +-----------+   +-----------+  +----------+  +------------+
                                                            |
                                                            v
                                                      +------------+
                                                      | Audio &    |
                                                      | Speaker    |
                                                      +------------+
```

### Key Hardware Specifications

- **Registers**:
  - `A`, `B`, `C`, `D`: 32-bit general purpose registers.
- **Flags**:
  - `Z` (Zero Flag): Set to `1` when an operation yields `0`; cleared to `0` otherwise.
  - `S` (Sign Flag): Set to `1` when an operation yields a negative value; cleared to `0` otherwise.
- **Memory Addressing**:
  - Linear addressable RAM configured by the SoC RAM modules plus up to eight 2048 KB RAM modules in the Personal Computer’s expansion slots.
  - Direct addressing: `[0x10]`, `[15]`
  - Indirect register addressing: `[A]`, `[B]`, `[C]`, `[D]`
- **Persistent Storage Drives**:
  - Supports Flash drives and ultra-speed NVMe storage drives.
  - Each drive has a separate ID and supports up to 2048 MB. Drive contents use sparse world files.
- **Direct Renderer Framebuffer**:
  - Hardware pixel graphics on the computer screen CRT canvas ($960 \times 540$ direct pixel operations, 16:9 aspect ratio).
  - Fast rectangle drawing, lines, and color buffer flipping.
- **Hardware Audio Buffer & Speaker Block**:
  - Real-computer audio queue buffer with waveform frequency, duration, type, and volume.
  - Plays directly on the screen or broadcasts into the surrounding Minecraft world via the **Speaker** block.
- **Custom Instruction Set (ISA)**:
  - CPUs are manufactured in the **Chip Forge**. You choose which instructions your CPU hardware actually supports.
  - Executing an instruction that was not added during chip fabrication will trigger a hardware `[CPU EXCEPTION] Illegal Opcode` halt!
- **Assembly & Project Compilers**:
  - Single `.n` and `.n2` files can be compiled with the **Code Compiler**.
  - Multi-file projects (`.zip` or folders containing `.n2`, `.n`, `.asm`, or `.s`) can be compiled with the **Project Compiler**, resolving imports and assembling into `<zipname>.o`.
  - Flash assembly to ROM chips or Storage drives using the **Programmer** machine.
- **JEI & Jade Compatibility**:
  - Full JEI integration with recipe categories for Chip Forge, Memory Forge, SoC Manufacturer, and Computer Assembly.
  - Native Jade integration showing real-time CPU state, cycles, RAM, storage, machine forging progress, energy, and speaker connections.
