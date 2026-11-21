# SiliconCraft Documentation

Welcome to the **SiliconCraft** documentation. SiliconCraft is a Minecraft 1.21.1 mod (NeoForge) that allows players to design, fabricate, program, and operate custom computer architectures inside Minecraft.

---

## Documentation Guide

| File | Description |
| :--- | :--- |
| **[getting-started.md](getting-started.md)** | Step-by-step walkthrough from mining raw silicon to assembling and booting your first computer. |
| **[n-language-guide.md](n-language-guide.md)** | Guide to writing programs in the high-level `.n` language and using the Code Compiler. |
| **[isa-reference.md](isa-reference.md)** | Complete Instruction Set Architecture (ISA) reference detailing registers, status flags, and all opcodes. |
| **[assembly-syntax.md](assembly-syntax.md)** | Guide to writing `.s` and `.asm` files: syntax rules, labels, comments, number formats, and memory addressing. |
| **[examples.md](examples.md)** | Ready-to-use assembly programs (Fibonacci, Counters, Array processing, Multiplication, and BIOS routines). |

---

## SiliconCraft Architecture Overview

SiliconCraft features an accessible 4-register architecture with custom instruction sets, virtual RAM, and terminal I/O.

```
       +---------------------------------------------+
       |               Virtual CPU                   |
       |  +---------------------------------------+  |
       |  |  Registers: A, B, C, D                |  |
       |  |  Pointers & Counters: PC, Cycles      |  |
       |  |  Flags: Zero (Z), Sign (S)            |  |
       |  +---------------------------------------+  |
       |                      |                      |
       |      Fetch & Decode Custom ISA (Chip)       |
       +---------------------------------------------+
               |               |              |
               v               v              v
         +-----------+   +-----------+  +------------+
         |  ROM/BIOS |   | Linear RAM|  | Terminal   |
         | (Program) |   | (Storage) |  | Output     |
         +-----------+   +-----------+  +------------+
```

### Key Hardware Specifications

- **Registers**:
  - `A`, `B`, `C`, `D`: 32-bit general purpose registers.
- **Flags**:
  - `Z` (Zero Flag): Set to `1` when an operation yields `0`; cleared to `0` otherwise.
  - `S` (Sign Flag): Set to `1` when an operation yields a negative value; cleared to `0` otherwise.
- **Memory Addressing**:
  - Linear byte-addressable array configured by RAM modules (minimum 16 bytes, up to 4096 bytes).
  - Direct addressing: `[0x10]`, `[15]`
  - Indirect register addressing: `[A]`, `[B]`
- **Custom Instruction Set (ISA)**:
  - CPUs are manufactured in the **Chip Forge**. You choose which instructions your CPU hardware actually supports.
  - Executing an instruction that was not added during chip fabrication will trigger a hardware `[CPU EXCEPTION] Illegal Opcode` halt!
- **Assembly Files**:
  - Write standard `.s` or `.asm` files on your computer.
  - Load and flash them into ROM chips using the **Programmer** machine.
