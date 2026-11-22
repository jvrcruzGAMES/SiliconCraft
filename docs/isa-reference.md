# Instruction Set Architecture (ISA) Reference Manual

This document details the virtual architecture, registers, status flags, and instruction set implemented in SiliconCraft's `VirtualCpu`.

---

## 1. Hardware Architecture

### General-Purpose Registers
The CPU provides four 32-bit integer registers:

| Register | Description | Typical Use Case |
| :---: | :--- | :--- |
| **`A`** | Primary Accumulator | Arithmetic, main calculations, return values |
| **`B`** | Secondary Register | Second operands, loop limits, memory base |
| **`C`** | Counter Register | Loop indexes, loop counters |
| **`D`** | Data / Pointer Register | Base memory addresses, pointer offsets |

### System Registers & Flags
- **`PC` (Program Counter)**: Holds the line index of the next instruction to execute.
- **`Cycles`**: Tracks the total clock cycles executed since last reset.
- **`Z` (Zero Flag)**:
  - Set to `1` (`true`) if the result of an arithmetic, logic, increment, decrement, or compare operation equals `0`.
  - Cleared to `0` (`false`) if the result is non-zero.
- **`S` (Sign Flag)**:
  - Set to `1` (`true`) if the result of an arithmetic or compare operation is negative (`< 0`).
  - Cleared to `0` (`false`) if non-negative.

### Memory Organization
- **RAM Capacity**: Configured by the RAM module(s) installed in the SoC plus up to 4 RAM expansion slots on the Personal Computer.
- **RAM Addressing**:
  - Direct literal address: `[0]`, `[15]`, `[0x20]`
  - Indirect register address: `[A]`, `[B]`, `[C]`, `[D]`

---

## 2. Complete Instruction Set

> **Hardware Rule**: Every instruction executed must be included on the CPU chip during fabrication in the **Chip Forge**. If an instruction is encountered that was not included on the chip, the CPU halts with `[CPU EXCEPTION] Illegal Opcode`.

### 1. Control Flow & Program Subroutines

| Opcode | Syntax | Description |
|---|---|---|
| **`NOP`** | `NOP` | No operation. Advances PC and consumes 1 cycle. |
| **`HLT`** | `HLT` | Halts CPU execution. Enters `HALTED` state. |
| **`JMP`** | `JMP target` | Unconditional jump to label or line index. |
| **`JZ`** | `JZ target` | Jump if Zero flag `Z == 1` (equal). |
| **`JNZ`** | `JNZ target` | Jump if Not Zero `Z == 0` (not equal). |
| **`JS`** | `JS target` | Jump if Sign flag `S == 1` (negative). |
| **`JNS`** | `JNS target` | Jump if Not Sign `S == 0` (non-negative). |
| **`JL`** | `JL target` | Jump if Less (`S == 1 && Z == 0`). |
| **`JLE`** | `JLE target` | Jump if Less or Equal (`S == 1 \|\| Z == 1`). |
| **`JG`** | `JG target` | Jump if Greater (`S == 0 && Z == 0`). |
| **`JGE`** | `JGE target` | Jump if Greater or Equal (`S == 0`). |
| **`CALL`** | `CALL target` | Pushes return line onto call stack and jumps to `target`. |
| **`RET`** | `RET` | Pops return line from call stack and jumps to it. |

---

### 2. Data Movement & Stack

| Opcode | Syntax | Description |
|---|---|---|
| **`MOV`** | `MOV dest, src` | Copies data from `src` to `dest` (registers, memory, or literals). |
| **`PUSH`** | `PUSH src` | Pushes value of `src` onto the CPU data stack. |
| **`POP`** | `POP dest` | Pops top value from data stack into `dest`. |

---

### 3. Arithmetic & Logic Operations

| Opcode | Syntax | Description |
|---|---|---|
| **`ADD`** | `ADD dest, src` | `dest = dest + src`. Sets `Z` and `S`. |
| **`SUB`** | `SUB dest, src` | `dest = dest - src`. Sets `Z` and `S`. |
| **`MUL`** | `MUL dest, src` | `dest = dest * src`. Sets `Z` and `S`. |
| **`DIV`** | `DIV dest, src` | `dest = dest / src` (integer division). Sets `Z` and `S`. |
| **`MOD`** | `MOD dest, src` | `dest = dest % src` (remainder). Sets `Z` and `S`. |
| **`INC`** | `INC dest` | `dest = dest + 1`. Sets `Z` and `S`. |
| **`DEC`** | `DEC dest` | `dest = dest - 1`. Sets `Z` and `S`. |
| **`CMP`** | `CMP reg, val` | Computes `(reg - val)` without modifying `reg`. Sets `Z` and `S`. |
| **`AND`** | `AND dest, src` | Bitwise AND. Sets `Z`. |
| **`OR`** | `OR dest, src` | Bitwise OR. Sets `Z`. |
| **`XOR`** | `XOR dest, src` | Bitwise XOR. Sets `Z`. |
| **`NOT`** | `NOT dest` | Bitwise bit inversion. Sets `Z`. |
| **`SHL`** | `SHL dest, count` | Bitwise Shift Left. Sets `Z`. |
| **`SHR`** | `SHR dest, count` | Bitwise Shift Right. Sets `Z`. |

---

### 4. I/O & System BIOS

| Opcode | Syntax | Description |
|---|---|---|
| **`OUT`** | `OUT val[, mode]` | Outputs string or decimal value to terminal. Mode `0` suppresses newline. |
| **`OUTC`** | `OUTC char` | Outputs ASCII character or streaming UTF-8 byte. Code `10` flushes line. |
| **`OUTH`** | `OUTH val[, mode]` | Outputs value formatted as hexadecimal (`0x...`). Mode `0` suppresses newline. |
| **`CLS`** | `CLS` | Clears terminal text lines and resets cursor. |
| **`SLEEP`** | `SLEEP ticks` | Pauses execution for specified Minecraft ticks (20 ticks = 1 second). |
| **`TIME`** | `TIME dest` | Stores current Minecraft world game time in ticks into `dest`. |
| **`RAND`** | `RAND dest, max` | Generates pseudo-random integer in `[0, max - 1]` into `dest`. |

---

### 5. Multi-Drive Binary Storage

| Opcode | Syntax | Description |
|---|---|---|
| **`SCOUNT`** | `SCOUNT dest` | Stores total number of connected storage drives into `dest`. |
| **`SSEL`** | `SSEL drive` | Sets the active default storage drive index. |
| **`SDRV`** | `SDRV dest` | Stores currently active storage drive index into `dest`. |
| **`SSIZE`** | `SSIZE dest[, drive]` | Stores storage drive capacity in bytes into `dest`. |
| **`SREAD`** | `SREAD dest[, drive], addr` | Reads single byte from drive at `addr` into `dest`. |
| **`SWRITE`** | `SWRITE [drive,] addr, val` | Writes single byte `val` to drive at `addr`. |
| **`SBLKR`** | `SBLKR ramAddr, drvAddr, len` | Fast block read: copies `len` bytes from drive to RAM. |
| **`SBLKW`** | `SBLKW drvAddr, ramAddr, len` | Fast block write: copies `len` bytes from RAM to drive. |
| **`SEXEC`** | `SEXEC drive` | Chainloads and executes the program burned to (or UTF-8 code stored on) `drive`. Resets PC to 0, preserves registers & RAM. |

---

### 6. Framebuffer Graphics

The CRT display features a $240 \times 135$ (16:9 aspect ratio) direct pixel renderer canvas:

| Opcode | Syntax | Description |
|---|---|---|
| **`FBWIDTH`** | `FBWIDTH dest` | Stores screen width in pixels (240) into `dest`. |
| **`FBHEIGHT`** | `FBHEIGHT dest` | Stores screen height in pixels (135) into `dest`. |
| **`FBSET`** | `FBSET x, y, color` | Sets pixel at `(x, y)` to 32-bit ARGB `color`. |
| **`FBGET`** | `FBGET dest, x, y` | Reads 32-bit ARGB pixel color at `(x, y)` into `dest`. |
| **`FBLINE`** | `FBLINE x0, y0, x1, y1, color` | Draws line using Bresenham's algorithm. |
| **`FBRECT`** | `FBRECT x, y, w, h, color` | Draws filled rectangle. |
| **`FBCLR`** | `FBCLR color` | Fills entire screen with `color`. |
| **`FBSWAP`** | `FBSWAP` | Flips / refreshes back buffer to display canvas. |
| **`FBMODE`** | `FBMODE mode` | Sets display rendering mode (0 = text, 1 = graphics). |
| **`FBLIT`** | `FBLIT x, y, w, h, ramAddr` | Blits pixel block from RAM array into screen. |

---

### 7. Audio Buffer & Sound Synthesis

SiliconCraft provides a bare-bones hardware audio queue (8000 Hz, 8-bit unsigned PCM):

| Opcode | Syntax | Description |
|---|---|---|
| **`APUSH`** | `APUSH sample` | Pushes single 8-bit unsigned PCM sample (0..255) into the audio queue. |
| **`ABUFW`** | `ABUFW ramSrc, count` | Fast block write: copies `count` samples from RAM into audio queue. |
| **`ATONE`** | `ATONE freq, dur` | Generates a tone of `freq` Hz for `dur` Minecraft ticks. |
| **`ACLR`** | `ACLR` | Clears and flushes all pending samples from audio buffer. |
| **`ASTAT`** | `ASTAT dest` | Stores remaining queued sample count into `dest`. |
| **`APLAY`** | `APLAY` | Triggers playback of the queued audio buffer. |

---

### 8. Interrupts & Exceptions

The SiliconCraft CPU features an Interrupt Vector Table (IVT), hardware event queues, and fault exception handling:

| Opcode | Syntax | Description |
|---|---|---|
| **`IVEC`** | `IVEC vector, handler` | Registers `handler` label or address in the IVT for `vector`. |
| **`IRET`** | `IRET` | Returns from interrupt/exception handler. Restores PC and flags. |
| **`CLI`** | `CLI` | Clears Interrupt Enable flag (masks hardware interrupts; events stay queued). |
| **`STI`** | `STI` | Sets Interrupt Enable flag (unmasks interrupts; queued events are dispatched). |
| **`INT`** | `INT vector` | Executes a software interrupt, jumping to `vector` in the IVT. |

#### Standard Vector Table

| Vector | Name / Constant | Type | Arguments Passed | Description |
|---|---|---|---|---|
| **`0`** | `DIV_ZERO` | Exception | `A = 0` | Divide by zero or modulo by zero. |
| **`1`** | `ILLEGAL_OPCODE` | Exception | `A = 1` | Instruction not supported by chip architecture. |
| **`2`** | `OUT_OF_BOUNDS` | Exception | `A = 2` | Memory address outside physical RAM range. |
| **`3`** | `STACK_FAULT` | Exception | `A = 3` | Stack underflow or overflow. |
| **`4`** | `GENERAL_EXCEPTION` | Exception | `A = 4` | Unclassified runtime exception. |
| **`10`** | `KEYBOARD` | Interrupt | `A = keyCode` | Client key typed in Computer Screen. |
| **`11`** | `MOUSE_MOVE` | Interrupt | `A = x, B = y` | Mouse motion inside CRT monitor canvas. |
| **`12`** | `MOUSE_CLICK` | Interrupt | `A = x, B = y, C = button` | Mouse click inside CRT monitor canvas (`0`=left, `1`=right). |
| **`13`** | `TIMER` | Interrupt | `A = 13` | Timer tick interrupt. |

> **Unhandled Exceptions**: If an exception occurs on a vector with no registered handler in the IVT, the CPU halts with state `ERROR` and displays:
> `[CPU EXCEPTION] Exception not handled: <description> at line <line>: '<lineCode>'`
