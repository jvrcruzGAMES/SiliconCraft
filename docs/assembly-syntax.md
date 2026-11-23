# Assembly Language Syntax Guide

This guide describes the syntax, file structure, formatting conventions, and error-handling mechanisms for SiliconCraft assembly files (`.s` and `.asm`).

---

## 1. File Format & Extensions

SiliconCraft accepts assembly source code written as plain UTF-8 text files with any of the following extensions:
- `.s` / `.S`
- `.asm` / `.ASM`

Files can be authored in any external text editor (VS Code, Notepad++, Vim, IntelliJ, etc.) and flashed into ROM chips or Storage Drives using the **Programmer** block in Minecraft.

---

## 2. Comments

Comments are non-executable text lines used to document code. SiliconCraft supports three standard comment prefixes:

```assembly
; Semicolon comments (traditional assembly style)
// Double-slash comments (C / C++ style)
# Hash comments (Python / shell style)
```

Comments can appear on their own lines or at the end of an instruction. Empty lines are automatically ignored by the CPU loader.

---

## 3. Labels and Jumps

Labels mark branch destinations that jump and call instructions (`JMP`, `JZ`, `JNZ`, `JL`, `JG`, `CALL`) can target.

### Defining Labels
A label is defined by an identifier ending with a colon `:` on its own line:

```assembly
start:
    MOV A, 0

loop:
    INC A
    CMP A, 10
    JNZ loop

end:
    HLT
```

### Rules for Labels
- Label names are **case-insensitive** (`Loop:`, `loop:`, and `LOOP:` refer to the same label).
- When jumping, omit the colon: `JMP loop` (not `JMP loop:`).
- Forward references (jumping to a label declared lower in the file) are fully supported.
- You can also jump to a direct line index (e.g. `JMP 0` resets to the first instruction), but labels are recommended for safety and clarity.

### Dynamic Indirect Jumps & Jump Tables
`JMP` and `CALL` can target registers or dereferenced RAM pointers:
```assembly
; Jump to an address stored in a register
MOV A, my_handler
JMP A

; Dynamic jump table lookup
MOV D, jump_table
ADD D, state_index
CALL [D]             ; Call function pointer stored at [jump_table + state_index]
```

---

## 4. Number Literals & Constants

Numbers can be specified in either decimal or hexadecimal notation anywhere numbers are accepted:

- **Decimal**: Standard base-10 integers (`0`, `1`, `42`, `-10`).
- **Hexadecimal**: Prefixed with `0x` or `0X` (`0x0`, `0x0F`, `0xFF`, `0x1000`, `0xFF00FF00`).

```assembly
MOV A, 16            ; Loads decimal 16 into A
MOV B, 0x10          ; Loads hex 0x10 (decimal 16) into B
MOV C, 0xFF0000FF    ; 32-bit ARGB Blue color
```

---

## 5. Memory Addressing (`[address]`)

Brackets `[...]` represent a memory dereference into the system's linear RAM:

### Direct Memory Addressing
```assembly
MOV [0], 42          ; Store immediate 42 into RAM address 0
MOV [0x10], 0xCAFE   ; Store hex 0xCAFE into RAM address 16 (0x10)
MOV A, [0]           ; Load value at RAM address 0 into register A
```

### Indirect (Register) Addressing
You can use any general-purpose register (`A`, `B`, `C`, `D`) as a memory pointer:

```assembly
MOV D, 0x20          ; D holds base RAM address 32 (0x20)
MOV [D], 100         ; Write 100 into RAM address pointed to by D
INC D                ; Advance pointer to address 33
MOV [D], 200         ; Write 200 into RAM address 33
```

---

## 6. Output & String Literals (`OUT`, `OUTC`, `OUTH`)

### Text Strings
```assembly
OUT "Initializing system..."   ; Prints string followed by newline
OUT "Loading... ", 0          ; Second operand 0 suppresses trailing newline
```

### Character Output (`OUTC`)
```assembly
OUTC 'H'
OUTC 'i'
OUTC 10                       ; Output newline (ASCII 10)
```

### Hexadecimal Output (`OUTH`)
```assembly
MOV A, 255
OUTH A                        ; Terminal displays: 0xFF
OUTH 0x1234, 0                ; Prints: 0x1234 (no newline)
```

---

## 7. Storage Drive Operations (`S*`)

SiliconCraft supports multi-drive binary storage. Drives are indexed starting from `0`:

```assembly
SCOUNT A                      ; Store total attached drive count in A
SSEL 0                        ; Select default drive 0
SSIZE B, 0                    ; Get size in bytes of drive 0 into B
SWRITE 0, 0x10, 0xAA          ; Write byte 0xAA to drive 0, address 0x10
SREAD A, 0, 0x10              ; Read byte from drive 0, address 0x10 into A
SBLKR 100, 0, 16              ; Fast block read: copy 16 bytes from drive into RAM [100..115]
SEXEC 1                       ; Chainload / execute program burned to Drive 1 (Flash or NVMe)
```

---

## 8. Framebuffer Operations (`FB*`)

The CRT screen features a direct $480 \times 270$ (16:9) hardware pixel canvas:

```assembly
FBMODE 1                          ; Switch CRT display to graphics mode (1 = graphics, 0 = text)
FBCLR 0xFF000000                  ; Clear canvas to black
FBSET 10, 10, 0xFFFF0000          ; Draw red pixel at (10, 10)
FBLINE 0, 0, 479, 269, 0xFF00FF00 ; Draw green diagonal line across CRT
FBRECT 20, 20, 40, 20, 0xFF0000FF ; Draw blue filled rectangle
FBSWAP                            ; Swap / flip buffer to display canvas
```

---

## 9. Audio Buffer Operations (`A*`)

Synthesize audio directly through the computer's sound buffer (8000 Hz, 8-bit unsigned PCM):

```assembly
; Generate a tone directly (freqHz, durationMs)
ATONE 440, 500                   ; 440 Hz (A4) tone for 500 ms (0.5 seconds)
ATONE 0, 100                     ; 100 ms silence / pause between notes
APLAY                            ; Start audio playback

; Or push raw 8-bit unsigned PCM samples (0..255)
APUSH 128
APUSH 200
APUSH 64
APLAY                            ; Trigger queued PCM sample playback
```

---

## 10. Interrupt & Exception Handling (`IVEC`, `IRET`, `CLI`, `STI`, `INT`)

SiliconCraft CPUs support hardware event interrupts (keyboard, mouse) and CPU fault exceptions through an Interrupt Vector Table (IVT):

```assembly
; Register handler for vector 0 (DIV_ZERO)
IVEC 0, div_zero_handler

; Register handler for vector 10 (KEYBOARD)
IVEC 10, key_handler

; Register handler for vector 14 (RESET / RST)
IVEC RESET, reset_handler

; Enable hardware interrupts
STI

main_loop:
    JMP main_loop

key_handler:
    ; A contains the pressed character code
    OUT "Key Pressed: ", 0
    OUTC A
    OUTC 10
    IRET                         ; Return from interrupt, restoring registers and flags

div_zero_handler:
    OUT "[EXCEPTION] Division by zero intercepted!"
    MOV B, 1                     ; Recover by setting divisor to 1
    IRET                         ; Resume execution after faulting instruction

reset_handler:
    ; Executed immediately after CPU reset or power-on!
    ; RAM is NOT cleared on reset or power off, allowing state recovery.
    OUT "[RESET] CPU was reset or powered on. Inspecting RAM..."
    ; Inspect or fix variables in RAM here
    IRET                         ; Return to pc = 0 to resume normal program
```

> **RAM Preservation on Reset & Power Off**:
> Resetting the computer (`cpu.reset()`) or turning it off (`powerOff()`) **does not wipe RAM**. The Interrupt Vector Table (`ivt`) also remains registered. Registering a handler for `RESET` (Vector 14) allows the program to run custom initialization or state recovery routines directly after a reset occurs.

---

## 11. Static Data Directives (`ORG`, `DB`, `DW`, `DL`, `INCBIN`)

SiliconCraft's assembler allows you to embed static assets, lookup tables, tilemaps, and raw binaries directly into RAM at arbitrary addresses. Because these are assembler directives rather than executable opcodes, they do not require chip architecture support.

```assembly
; Set initial load address to RAM location 0x200
ORG 0x200

sprite_lut:
    DB 0x01, 0x02, 0x04, 0x08       ; 8-bit bytes
    DB "HERO_WALK", 0                ; Null-terminated string

pointer_table:
    DW state_idle, state_walk        ; 16-bit little-endian words

long_pointers:
    DL 0x100000, 0x100040            ; 24-bit / 32-bit addresses

rom_data:
    INCBIN "tiles.bin"               ; Load raw binary file into RAM at current address
```

---

## 12. Gamepad Polling & 60 Hz V-Blank Timing

For continuous player controls and smooth 60 Hz arcade/retro game loops:

```assembly
; Hook 60 Hz frame interrupt (Vector 13)
IVEC TIMER, vblank_handler
VBLANK 1                             ; Start 60 Hz V-Blank interrupt

STI

game_loop:
    ; Poll held controller buttons (bitmask: Right=1, Left=2, Down=4, Up=8, B=64, Y=256)
    INPAD A                          ; Read controller 0 held bitmask into A
    TEST A, 64                       ; Check if B button (jump) is held down
    JNZ do_jump
    JMP game_loop

vblank_handler:
    ; Executed 60 times per second
    FBSWAP                           ; Flip display canvas
    IRET
```

---

## 13. Flashing Assembly with the Programmer

1. Open the **Programmer** block in Minecraft.
2. Insert either a blank **ROM Chip** OR a **Storage Drive** (Flash / NVMe).
3. Select your compiled program from the scrollable Program Library.
4. Click **⚡ Burn to ROM** or **⚡ Write to Drive**.
5. To clean up old assembly programs, click **Delete**.

---

## 14. Common CPU Errors & Troubleshooting

| Error in Terminal | Cause | Solution |
| :--- | :--- | :--- |
| `[CPU EXCEPTION] Exception not handled: <msg>` | A hardware or software fault occurred (e.g. division by zero, bounds violation) without a registered handler in the IVT. | Register an exception handler using `IVEC <vector>, <handler>` or correct the underlying code error. |
| `[CPU EXCEPTION] Illegal Opcode: XYZ` | The instruction `XYZ` was used in the program, but the CPU chip was forged **without** selecting `XYZ`. | Re-forge your CPU chip in the **Chip Forge** and ensure the checkmark for `XYZ` is selected. |
| `[CPU EXCEPTION] RAM address X out of bounds` | The code attempted to access memory beyond the size of installed RAM. | Check loop boundaries, or install additional RAM modules in the SoC / PC expansion slots. |
| `[CPU EXCEPTION] Label not found: XYZ` | A `JMP`, `JZ`, or `JNZ` targeted a label `XYZ` that does not exist in the source file. | Verify label spelling and ensure the target has a trailing colon (`XYZ:`). |
| `[CPU EXCEPTION] MOV requires dest, src` | Missing comma or operand in instruction syntax. | Ensure instructions have both operands separated by a comma (e.g. `MOV A, 5`). |
| `[CPU EXCEPTION] Cannot write to destination: XYZ` | Attempted to write a value into an invalid destination (such as an immediate constant). | Ensure the first operand is a register (`A`, `B`, `C`, `D`) or memory bracket `[...]`. |


---

## 15. Modular Assembly & Multi-File Projects

SiliconCraft supports multi-file assembly projects with file inclusion directives:

### Supported Directives
```assembly
include "math_helpers.asm"     ; Relative or project-root include
.include "constants.s"         ; Alternative dot syntax
#include <hardware.asm>        ; C-style bracket syntax
import "logic.n"               ; Run high-level .n modules directly from assembly!
```

- **Cross-Language Interoperability**: Assembly files can include and call `.n` files, and `.n` files can call assembly routines.
- **Entrypoint Discovery**: The Project Compiler automatically detects entrypoints (`main.asm`, `main.s`, `main.n`, `entry.*`, `start.*`).
- **Standard Object Format (`.o`)**: The assembled output is a unified, fully linked bytecode file with the standard `.o` extension.

## Peripheral strings

`PCALLS dst, port, method, ptr, arg` reads a zero-terminated UTF-16 string from RAM, one code unit per cell. For a constant argument, give the data an explicit address with `ORG`.

```assembly
PCALLS A, 2, 0x401, 0x200, 0
; Wait for completion and read PRESULT before issuing another request.

ORG 0x200
DB "Alex|Ready", 0
```

For a text response, use `PLEN dst` to size the buffer, then `PRESULTS dst, ptr, capacity` to copy and consume it. Capacity counts RAM cells and includes the zero terminator. Check `PSTATUS` before consuming a response. [Peripheral mailbox reference](peripherals.md#assembly-and-devicekit)
