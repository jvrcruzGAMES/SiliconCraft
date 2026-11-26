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
- **RAM Capacity**: Configured by the RAM module(s) installed in the SoC plus up to 8 RAM expansion slots on the Personal Computer.
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
| **`SSIZE`** | `SSIZE dest[, drive]` | Stores capacity in bytes as an unsigned 32-bit value. A full 2 GiB drive returns `0x80000000`. |
| **`SREAD`** | `SREAD dest[, drive], addr` | Reads single byte from drive at `addr` into `dest`. |
| **`SWRITE`** | `SWRITE [drive,] addr, val` | Writes single byte `val` to drive at `addr`. |
| **`SBLKR`** | `SBLKR ramAddr, drvAddr, len` | Fast block read: copies `len` bytes from drive to RAM. |
| **`SBLKW`** | `SBLKW drvAddr, ramAddr, len` | Fast block write: copies `len` bytes from RAM to drive. |
| **`SEXEC`** | `SEXEC drive` | Chainloads and executes the program burned to (or UTF-8 code stored on) `drive`. Resets PC to 0, preserves registers & RAM. |

---

### 6. Framebuffer Graphics

The CRT display features a $960 \times 540$ (16:9 aspect ratio) direct pixel renderer canvas:

| Opcode | Syntax | Description |
|---|---|---|
| **`FBWIDTH`** | `FBWIDTH dest` | Stores screen width in pixels (960) into `dest`. |
| **`FBHEIGHT`** | `FBHEIGHT dest` | Stores screen height in pixels (540) into `dest`. |
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
| **`ATONE`** | `ATONE freq, dur` | Generates square wave tone of `freq` Hz for `dur` milliseconds (`freq = 0` queues silence/pause). |
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
| **`13`** | `TIMER` / `VBLANK` | Interrupt | `A = 13` | Periodic hardware timer / 60 Hz V-Blank interrupt. |
| **`14`** | `RESET` / `RST` | Interrupt | `A = 14` | Dispatched right after CPU reset or power-on. |

> **Unhandled Exceptions**: If an exception occurs on a vector with no registered handler in the IVT, the CPU halts with state `ERROR` and displays:
> `[CPU EXCEPTION] Exception not handled: <description> at line <line>: '<lineCode>'`

#### Reset & Power-On Lifecycle
- **RAM Non-Volatility**: CPU reset (`reset()`) and power shutdown (`powerOff()`) **do not clear RAM**. Memory buffers, application state, and heap variables remain preserved in physical RAM across reboots and power cycles.
- **IVT Persistence**: The Interrupt Vector Table persists across resets. Handlers registered with `IVEC` remain registered when the CPU resets.
- **Reset Interrupt (`VEC_RESET = 14`)**: Immediately following a reset or power-on, the CPU places a `VEC_RESET` interrupt at the head of the interrupt queue. If a handler is registered (via `IVEC RESET, handler` or `IVEC 14, handler`), the CPU jumps directly to it on the first cycle with `pc = 0` saved on the interrupt stack. This enables software to inspect preserved RAM buffers, verify data integrity, repair corrupted state, and resume with `IRET` or `JMP`. If no handler is registered, execution begins at `pc = 0` normally.

---

### 9. Gamepad & Controller Button Polling

SiliconCraft supports real-time polling of held keys and controller buttons for continuous motion, jumping, and action inputs (such as Super Mario World style controls):

| Opcode | Syntax | Description |
|---|---|---|
| **`INPAD`** | `INPAD dest [, padId]` | Reads the 16-bit bitmask of currently held buttons on controller `padId` (default 0) into `dest`. |
| **`INAXIS`** | `INAXIS dest, padId, axis` | Reads a stick or trigger as an integer. Added in 0.1.2; enable it on the SoC for analog input. |

#### Gamepad Button Bitmask Layout (SNES / Retro Layout)
| Button Constant | Bit / Value | Hex | Description |
|---|---|---|---|
| `PAD_RIGHT` | Bit 0 (`1`) | `0x0001` | D-Pad Right / Key D / Right Arrow |
| `PAD_LEFT` | Bit 1 (`2`) | `0x0002` | D-Pad Left / Key A / Left Arrow |
| `PAD_DOWN` | Bit 2 (`4`) | `0x0004` | D-Pad Down / Key S / Down Arrow |
| `PAD_UP` | Bit 3 (`8`) | `0x0008` | D-Pad Up / Key W / Up Arrow |
| `PAD_START` | Bit 4 (`16`) | `0x0010` | Start / Key Enter |
| `PAD_SELECT`| Bit 5 (`32`) | `0x0020` | Select / Shift |
| `PAD_B` | Bit 6 (`64`) | `0x0040` | South button / Space or Z |
| `PAD_A` | Bit 7 (`128`)| `0x0080` | East button / X |
| `PAD_Y` | Bit 8 (`256`)| `0x0100` | West button / C or J |
| `PAD_X` | Bit 9 (`512`)| `0x0200` | North button / V or K |
| `PAD_L` | Bit 10 (`1024`)| `0x0400`| Left shoulder or trigger / Q or U |
| `PAD_R` | Bit 11 (`2048`)| `0x0800`| Right shoulder or trigger / E or I |
| `PAD_LEFT_TRIGGER` | Bit 12 (`4096`) | `0x1000` | Left trigger reading greater than 500 |
| `PAD_RIGHT_TRIGGER` | Bit 13 (`8192`) | `0x2000` | Right trigger reading greater than 500 |
| `PAD_LEFT_STICK` | Bit 14 (`16384`) | `0x4000` | Left stick button (L3) |
| `PAD_RIGHT_STICK` | Bit 15 (`32768`) | `0x8000` | Right stick button (R3) |

#### Analog axes (0.1.2)

| Axis ID | Reading | Range |
|---|---|---|
| 0 | Left stick X | −1000 left to +1000 right |
| 1 | Left stick Y | −1000 up to +1000 down |
| 2 | Right stick X | −1000 left to +1000 right |
| 3 | Right stick Y | −1000 up to +1000 down |
| 4 | Left trigger | 0 released to 1000 fully pressed |
| 5 | Right trigger | 0 released to 1000 fully pressed |

Values are rounded to integer thousandths. Each stick axis has a 0.15 deadzone: magnitudes at or below 0.15 read zero; values outside it retain their original scale. Physical input requires the optional Controlify mod. Its selected controller, or the first GLFW fallback gamepad, feeds virtual controller 0 while the computer screen is open. Slots 1–3 remain available to the VM but are not additional physical controller mappings. Keyboard buttons still work without Controlify and leave analog axes at zero. Invalid axis or pad IDs return zero; `INAXIS` does not use the special keyboard IDs of `INPAD`.

For compatibility, the left stick still sets D-pad bits beyond ±500, and a trigger above 500 also sets the corresponding `PAD_L` or `PAD_R` bit. The new trigger bits distinguish triggers from bumpers. Both triggers and stick buttons can be held independently. Screen close, controller disconnect, reset, and power-off clear input. Controlify GUI cursor mode suppresses physical buttons and axes from the guest.

See the [runnable analog example](examples/controller-inputs.asm) and [N2 helpers](../n-language/n2/libraries.md#analog-sticks-triggers-and-stick-buttons-012).

---

### 10. Dynamic Indirect Jumps & Jump Tables

`JMP` and `CALL` support dynamic jumping to registers and memory locations, enabling dynamic function pointers, jump tables, and sprite/state dispatchers:

| Opcode | Syntax | Description |
|---|---|---|
| **`JMP`** | `JMP reg` | Jumps to instruction address stored in register `reg` (`A`, `B`, `C`, `D`). |
| **`JMP`** | `JMP [reg]` | Jumps to instruction address stored in RAM at `[reg]`. |
| **`CALL`** | `CALL reg` | Pushes return address and calls function at address in register `reg`. |
| **`CALL`** | `CALL [reg]`| Pushes return address and calls function at address stored in RAM at `[reg]`. |

---

### 11. Periodic Hardware Timer & 60 Hz V-Blank

The CPU hardware features a real-time periodic frame timer that dispatches interrupt vector 13:

| Opcode | Syntax | Description |
|---|---|---|
| **`TIMER`** | `TIMER hz` | Configures periodic timer frequency in Hz (e.g. `TIMER 60` for 60 Hz). `0` disables timer. |
| **`VBLANK`**| `VBLANK enable` | Shortcut for 60 Hz frame synchronization (`VBLANK 1` = 60 Hz, `VBLANK 0` = off). |

---

### 12. Assembler Static Data Directives

Directives allow placing raw binary data, tables, and external files directly into the RAM address space without executing instructions. Assembler directives are processed at CPU initialization and **do not require Chip Forge crafting**:

| Directive | Syntax | Description |
|---|---|---|
| **`ORG`** | `ORG addr` | Sets current memory location counter to `addr` (decimal or `0x...`). |
| **`DB`** | `DB byte1, byte2, "str", ...` | Emits 8-bit bytes and UTF-8 string bytes into RAM. |
| **`DW`** | `DW word1, word2, ...` | Emits 16-bit little-endian words (2 bytes per entry) into RAM. |
| **`DL`** | `DL long1, long2, ...` | Emits 24-bit/32-bit little-endian longs (3 bytes per entry) into RAM. |
| **`INCBIN`**| `INCBIN "path" [, offset, len]` | Includes raw binary file contents directly into RAM. |


### 13. Adjacent Peripherals

| Opcode | Syntax | Effect |
|---|---|---|
| `PSCAN` | `PSCAN dst, after` | Queue a scan for the next wired peripheral address, or −1 if there is none. |
| `PINFO` | `PINFO dst, side` | Queue a request for the peripheral flags. |
| `PWRAP` | `PWRAP dst, side` | Queue a request to wrap a peripheral manually. |
| `PCALL` | `PCALL dst, side, method, arg0, arg1` | Queue an API call with two 32-bit arguments. |
| `PCALLS` | `PCALLS dst, side, method, ptr, arg1` | Queue a call with a zero-terminated RAM string and a 32-bit argument. |
| `PLEN` | `PLEN dst` | Read the completed response length in UTF-16 cells without consuming it. |
| `PRESULTS` | `PRESULTS dst, ptr, capacity` | Consume the response, copy text to guest RAM with a zero terminator if capacity permits, and return the copied length. |
| `PSTATUS` | `PSTATUS dst` | Read the request status. |
| `PRESULT` | `PRESULT dst` | Read the result and clear a completed request. |

See [Adjacent peripherals and DeviceKit](peripherals.md) for port numbers, status codes, built-in tools, and NeoForge capability registration.

### 14. Network

See the [Network library](network.md) for the server config and `NOPEN`, `NSEND`, `NRECV`, `NCLOSE`, `NHTTP`, `NSTATUS`, `NLEN`, and `NRESULT` syntax.

Additional Network instructions open WebSocket (`NWSOPEN`), send UTF-8 WebSocket text (`NWSTEXT`), upgrade a TCP stream to TLS (`NTLS`) and make an HTTP/3 request (`NHTTP3`). See the [Network library reference](network.md#instruction-reference) for operands and server requirements.

The operator can enable inbound connections. Use `NLISTEN` to bind a configured port, `NACCEPT` to accept stream connections, and `NRESPOND` to send an HTTP or HTTPS response. See the [listener reference](network.md#listening-on-a-server-port).

The N2 HTTP helpers compile to guest code that uses `NREADY` for mailbox pacing, `NUTF8` for text conversion, and `NRESPONDT` for responses with a UTF-8 text content type. Named callbacks are called with ordinary guest `CALL` instructions. See the [HTTP helpers](network.md#http-and-text).

### 15. Managed Guest Heap

| Instruction | Syntax | Effect |
| --- | --- | --- |
| `GALLOC` | `GALLOC size, base` | With a positive size in RAM cells, allocates zero-initialized memory and returns its address in `A`. The size can be an immediate or a register. The heap base is immediate. A size of zero initializes an empty heap at that base. |

The collector treats values in RAM below the heap, CPU registers, the data stack, and saved interrupt registers as possible references. It follows references from reachable heap blocks. Reserve the RAM from the heap base onward for the allocator. An allocation that still cannot fit after collection stops the program with a memory error.


[Chapter contents](README.md)
