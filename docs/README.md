# SiliconCraft Documentation

Welcome to the **SiliconCraft** documentation. SiliconCraft is a Minecraft 1.21.1 mod (NeoForge) that allows players to design, fabricate, program, and operate custom computer architectures inside Minecraft.

---

## Documentation Guide

| File | Description |
| :--- | :--- |
| **[getting-started.md](getting-started.md)** | Step-by-step walkthrough from mining silicon and deepslate ore to assembling SoCs, expanding PC hardware, and booting your first computer. |
| **[n-language-guide.md](n-language-guide.md)** | Complete guide to the `.n` language: syntax, variables, tables, pointers, hex numbers, storage, framebuffer graphics, audio, and multi-file project compilation. |
| **[isa-reference.md](isa-reference.md)** | Complete Instruction Set Architecture (ISA) reference detailing registers, status flags, and all 46+ opcodes (control, math, storage, framebuffer, and audio). |
| **[assembly-syntax.md](assembly-syntax.md)** | Guide to writing `.s` and `.asm` files: syntax rules, labels, comments, number formats, RAM/storage addressing, and device operations. |
| **[examples.md](examples.md)** | Ready-to-use assembly and `.n` programs (Fibonacci, Counters, Array processing, Framebuffer graphics, and Audio synthesis). |

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
  - Linear addressable RAM configured by the SoC RAM modules plus up to 4 RAM expansion slots on the Personal Computer.
  - Direct addressing: `[0x10]`, `[15]`
  - Indirect register addressing: `[A]`, `[B]`, `[C]`, `[D]`
- **Persistent Storage Drives**:
  - Supports Flash drives and ultra-speed NVMe storage drives.
  - Treated individually (drive 0, drive 1, etc.) and retained in item NBT when extracted.
- **Direct Renderer Framebuffer**:
  - Hardware pixel graphics on the computer screen CRT canvas ($240 \times 135$ direct pixel operations, 16:9 aspect ratio).
  - Fast rectangle drawing, lines, and color buffer flipping.
- **Hardware Audio Buffer & Speaker Block**:
  - Real-computer audio queue buffer with waveform frequency, duration, type, and volume.
  - Plays directly on the screen or broadcasts into the surrounding Minecraft world via the **Speaker** block.
- **Custom Instruction Set (ISA)**:
  - CPUs are manufactured in the **Chip Forge**. You choose which instructions your CPU hardware actually supports.
  - Executing an instruction that was not added during chip fabrication will trigger a hardware `[CPU EXCEPTION] Illegal Opcode` halt!
- **Assembly & Project Compilers**:
  - Single `.n` files can be compiled with the **Code Compiler**.
  - Multi-file `.zip` projects can be compiled with the **Project Compiler**, resolving subfolders and modular imports into `<zipname>.asm`.
  - Flash assembly to ROM chips or Storage drives using the **Programmer** machine.
- **JEI & Jade Compatibility**:
  - Full JEI integration with recipe categories for Chip Forge, Memory Forge, SoC Manufacturer, and Computer Assembly.
  - Native Jade integration showing real-time CPU state, cycles, RAM, storage, machine forging progress, energy, and speaker connections.
