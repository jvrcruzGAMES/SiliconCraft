# SiliconCraft Assembly Examples

This document provides complete, working assembly programs that you can copy, save to `.s` or `.asm` files, and flash onto your ROM chips using the **Programmer**.

---

## Example 1: Basic Counter & Banner (`counter.asm`)

A simple countdown program that demonstrates labels, loops, and decrement logic.

### Required CPU Instructions
- `OUT`, `MOV`, `DEC`, `JNZ`, `HLT`

```assembly
; ============================================
; Program: counter.asm
; Description: Counts down from 5 to 1 and halts
; ============================================

OUT "=========================="
OUT "    COUNTDOWN DEMO       "
OUT "=========================="

MOV C, 5

countdown:
    OUT C
    DEC C
    JNZ countdown

OUT "Liftoff! SiliconCraft online."
HLT
```

---

## Example 2: Fibonacci Sequence (`fibonacci.asm`)

Calculates the first 8 numbers of the Fibonacci sequence, storing each number into RAM addresses `[0]` through `[7]` while displaying them on the terminal.

### Required CPU Instructions
- `OUT`, `MOV`, `ADD`, `INC`, `DEC`, `JNZ`, `HLT`

```assembly
; ============================================
; Program: fibonacci.asm
; Description: Computes Fibonacci series into RAM
; ============================================

OUT "Computing Fibonacci Series..."

; Initialize F(0) = 0, F(1) = 1
MOV A, 0
MOV B, 1
MOV D, 0          ; Memory pointer starting at RAM[0]

; Store initial values into RAM
MOV [D], A
INC D
MOV [D], B
INC D

; C will serve as loop counter (compute 6 more terms)
MOV C, 6

fib_loop:
    ; Compute next term: next = A + B
    MOV [D], A
    ADD [D], B
    
    ; Output newly calculated term
    OUT [D]

    ; Shift terms: A = B, B = next
    MOV A, B
    MOV B, [D]

    ; Increment pointer and decrement loop counter
    INC D
    DEC C
    JNZ fib_loop

OUT "Fibonacci computation complete!"
HLT
```

---

## Example 3: Software Multiplication (`multiply.asm`)

Computes $12 \times 7 = 84$ using repeated addition in software.

### Required CPU Instructions
- `OUT`, `MOV`, `ADD`, `DEC`, `CMP`, `JZ`, `JNZ`, `HLT`

```assembly
; ============================================
; Program: multiply.asm
; Description: Computes A * B via repeated addition
; ============================================

OUT "Multiplication Routine"

MOV A, 12        ; Multiplicand
MOV B, 7         ; Multiplier
MOV C, 0         ; Accumulator for result

; Check if multiplier is zero
CMP B, 0
JZ done

mult_loop:
    ADD C, A     ; C = C + A
    DEC B        ; B = B - 1
    JNZ mult_loop

done:
    OUT "Result (12 * 7):"
    OUT C
    HLT
```

---

## Example 4: Memory Buffer & Array Sum (`array_sum.asm`)

Initializes a 5-element array in linear RAM, then iterates over the array using register `D` as a pointer to compute the total sum.

### Required CPU Instructions
- `OUT`, `MOV`, `ADD`, `INC`, `DEC`, `JNZ`, `HLT`

```assembly
; ============================================
; Program: array_sum.asm
; Description: Populates RAM array and sums elements
; ============================================

OUT "Initializing array in RAM..."

; Store values: [10, 20, 30, 40, 50] into RAM[0..4]
MOV [0], 10
MOV [1], 20
MOV [2], 30
MOV [3], 40
MOV [4], 50

; Setup pointers and accumulators
MOV D, 0         ; D = RAM pointer index
MOV C, 5         ; C = Element count
MOV A, 0         ; A = Running sum

sum_loop:
    ADD A, [D]   ; Add RAM[D] to total
    INC D        ; Move to next RAM element
    DEC C        ; Decrement counter
    JNZ sum_loop

OUT "Array Sum:"
OUT A
HLT
```

---

## Example 5: BIOS Boot Routine (`bios_boot.s`)

Simulates a computer firmware POST (Power-On Self-Test) routine suitable for burning into the ROM of an integrated System-on-Chip (SoC).

### Required CPU Instructions
- `OUT`, `MOV`, `INC`, `CMP`, `JNZ`, `HLT`

```assembly
; ============================================
; Program: bios_boot.s
; Description: Silicon-OS POST & System Boot
; ============================================

OUT "[POST] Silicon BIOS 1.0"
OUT "[POST] Checking CPU Registers..."
MOV A, 0xAA
MOV B, 0x55
OUT "Reg A Test OK"
OUT "Reg B Test OK"

OUT "[POST] Testing RAM Banks..."
MOV [0], 0xFF
CMP [0], 0xFF
JNZ memory_error
OUT "RAM Bank 0 Passed."

OUT "[SYSTEM] Loading OS Kernel..."
MOV C, 0
kernel_load:
    INC C
    CMP C, 3
    JNZ kernel_load

OUT "[SYSTEM] SiliconCraft Personal Computer Ready."
HLT

memory_error:
    OUT "[FATAL] Memory verification failed!"
    HLT
```

---

## Example 6: Framebuffer Direct Pixel Graphics (`graphics_demo.asm`)

Draw geometric primitives (filled boxes, diagonal lines, and pixels) directly onto the $960 \times 540$ (16:9) CRT pixel canvas and renders the frame in an active display loop.

### Required CPU Instructions
- `FBMODE`, `FBCLR`, `FBRECT`, `FBLINE`, `FBSET`, `FBSWAP`, `JMP`

```assembly
; ============================================
; Program: graphics_demo.asm
; Description: Renders 2D geometric shapes on CRT (960x540 16:9)
; ============================================

; Switch CRT display to graphics framebuffer mode (1 = graphics, 0 = text)
FBMODE 1

; Clear canvas to dark slate (ARGB: 0xFF181824)
FBCLR 0xFF181824

; Draw a blue title banner rectangle (x=10, y=10, w=220, h=16)
FBRECT 10, 10, 220, 16, 0xFF2A4494

; Draw diagonal crosshairs across the canvas
FBLINE 0, 0, 239, 134, 0xFF44AA44
FBLINE 0, 134, 239, 0, 0xFF44AA44

; Draw an accent gold square in the center
FBRECT 110, 57, 20, 20, 0xFFFFCC00

; Plot a single white pixel in the center of the gold box
FBSET 120, 67, 0xFFFFFFFF

; Flip buffer to display on screen
FBSWAP

; Keep display running in an active loop instead of halting
idle:
    JMP idle
```

---

## Example 7: Audio Buffer & Melody Playback (`audio_tune.asm`)

Queues musical note frequencies into the 8000 Hz audio buffer and initiates playback on the local computer and adjacent Speaker blocks.

### Required CPU Instructions
- `OUT`, `ATONE`, `APLAY`, `HLT`

```assembly
; ============================================
; Program: audio_tune.asm
; Description: Plays an arpeggiated chime melody
; ============================================

OUT "Synthesizing chime chord..."

; Queue A4 (440 Hz) for 300 ms
ATONE 440, 300

; Queue C#5 (554 Hz) for 300 ms
ATONE 554, 300

; Queue E5 (659 Hz) for 300 ms
ATONE 659, 300

; Queue A5 (880 Hz) for 600 ms
ATONE 880, 600

; Fire audio playback
APLAY

OUT "Playback dispatched to speaker!"
HLT
```

---

## Example 8: Interrupt & Fault Exception Handling (`interrupt_demo.asm`)

Demonstrates registering handlers in the Interrupt Vector Table (IVT) for both fault recovery (`DIV_ZERO`) and real-time user input (`KEYBOARD`).

### Required CPU Instructions
- `OUT`, `OUTC`, `MOV`, `DIV`, `IVEC`, `IRET`, `STI`, `JMP`

```assembly
; ============================================
; Program: interrupt_demo.asm
; Description: Recovers from divide-by-zero & handles keys
; ============================================

; Register IVT handlers:
; Vector 0 = DIV_ZERO exception
; Vector 10 = KEYBOARD interactive interrupt
IVEC 0, handle_div_zero
IVEC 10, handle_key

; Enable hardware interrupts
STI

OUT "Testing exception handler..."

; Intentional fault: divide 100 by 0
MOV A, 100
MOV B, 0
DIV A, B              ; Triggers Vector 0 fault

; Execution resumes here after handler returns
OUT "Division result after recovery: ", 0
OUT A
OUT "Now waiting for keyboard input..."

idle_loop:
    JMP idle_loop

; --- Handlers ---
handle_div_zero:
    OUT "[INTERRUPT] Caught DIV_ZERO exception!"
    MOV B, 2          ; Fix divisor to 2
    MOV A, 50         ; Set safe quotient
    IRET              ; Resume execution after fault

handle_key:
    ; Register A holds the typed character code
    OUT "Key pressed: ", 0
    OUTC A
    OUTC 10
    IRET
```

---

## Example 9: Storage Drive Persistence (`storage_io.asm`)

Demonstrates writing binary data to persistent Storage Drives (Flash / NVMe) that remain stored in NBT even when the drive is detached.

### Required CPU Instructions
- `OUT`, `OUTC`, `CMP`, `JZ`, `SCOUNT`, `SSEL`, `SWRITE`, `SREAD`, `HLT`

```assembly
; ============================================
; Program: storage_io.asm
; Description: Writes persistent bytes to Storage Drive
; ============================================

SCOUNT A
CMP A, 0
JZ no_drive

OUT "Found installed Storage Drive(s): ", 0
OUT A

; Select Drive 0
SSEL 0

; Write magic byte signature 'SC' (0x53, 0x43) at address 0x00
SWRITE 0, 0x00, 0x53
SWRITE 0, 0x01, 0x43

; Read bytes back from drive
SREAD B, 0, 0x00
SREAD C, 0, 0x01

OUT "Read magic signature: ", 0
OUTC B
OUTC C
OUTC 10
OUT "Data persisted successfully."
HLT

no_drive:
    OUT "[ERROR] No storage drive inserted!"
    HLT
```

---

## Example 10: Running Code from Storage Drive (`bootloader.asm` & `os_kernel.asm`)

Demonstrates probing for installed storage drives (Flash or NVMe) and chainloading/executing a program burned to **Storage Drive X** using the `SEXEC` opcode.

Registers and RAM are preserved across `SEXEC` and CPU resets. The bootloader performs memory sanitization and registers a `RESET` interrupt handler (Vector 14) to clean system RAM upon reboot, ensuring a clean memory state before handing off control to the loaded operating system.

### Required CPU Instructions
- `OUT`, `MOV`, `CMP`, `JZ`, `JNZ`, `JMP`, `INC`, `IVEC`, `IRET`, `SSIZE`, `SEXEC`, `HLT`

### Part 1: Bootloader (`bootloader.asm`)
*Burned into SoC ROM (BIOS)*

```assembly
; ============================================
; Program: bootloader.asm
; Description: BIOS bootloader that launches OS from Storage Drive
; ============================================

; Register Vector 14 (RESET) handler to clean memory after a reset
IVEC RESET, handle_reset

OUT "[BIOS] SiliconCraft Bootloader 1.0"

; Memory cleanup after reset: sanitize system RAM (addresses 0..63)
OUT "[BIOS] Sanitizing system RAM after reset..."
MOV D, 0
clean_mem_loop:
    MOV [D], 0
    INC D
    CMP D, 64
    JNZ clean_mem_loop
OUT "[BIOS] RAM sanitized: 64 words cleared."

OUT "[BIOS] Searching for bootable Storage Drive..."

; Check if Storage Drive 1 (Expansion Slot 1: Flash or NVMe) is installed
MOV B, 1
SSIZE C, B
CMP C, 0
JNZ boot_selected

; Fallback: Check if Storage Drive 0 (Onboard SoC Flash) is installed
MOV B, 0
SSIZE C, B
CMP C, 0
JNZ boot_selected

; No bootable drives available
JMP error_no_drives

boot_selected:
    OUT "[BIOS] Booting from Storage Drive: ", 0
    OUT B
    OUT "[BIOS] Drive capacity (bytes): ", 0
    OUT C

    OUT "[BIOS] Chainloading program..."

    ; Pass boot parameters to the loaded OS kernel in registers:
    ; Register A: Boot drive index
    ; Register B: Handshake magic signature (0x5343 = 'SC')
    MOV A, B
    MOV B, 0x5343

    ; Chainload and execute program burned to Drive A (Flash or NVMe)
    SEXEC A

error_no_drives:
    OUT "[FATAL] No bootable storage drive detected!"
    HLT

; --- Reset Handler: Memory Cleanup after Reset ---
handle_reset:
    OUT "[BIOS] Reset event caught. Sanitizing system RAM..."
    MOV D, 0
reset_clean_loop:
    MOV [D], 0
    INC D
    CMP D, 64
    JNZ reset_clean_loop
    OUT "[BIOS] Memory sanitized. Resuming boot sequence."
    IRET
```

### Part 2: Operating System Kernel (`os_kernel.asm`)
*Burned to Storage Drive 1 (Flash or NVMe) using the Programmer*

```assembly
; ============================================
; Program: os_kernel.asm
; Description: OS Kernel burned onto Storage Drive
; ============================================

OUT "=========================================="
OUT "       SILICON-OS KERNEL INITIALIZED      "
OUT "=========================================="

; Inspect boot parameters passed by the bootloader in registers:
OUT "Booted from Storage Drive: ", 0
OUT A

CMP B, 0x5343
JNZ unverified_boot
OUT "Bootloader handoff signature verified: OK"
JMP os_main

unverified_boot:
    OUT "Warning: Bootloader signature mismatch!"

os_main:
    OUT "Silicon-OS kernel running from Storage Drive."
    OUT "System ready for commands."
    HLT
```

---

## Example 11: Audio Buffer – Jingle Bells (`jingle_bells.asm`)

Plays the opening melody of *Jingle Bells* ("Jingle bells, jingle bells, jingle all the way") using the CPU's audio tone buffer, outputting sound through the personal computer and adjacent Speaker blocks.

### Required CPU Instructions
- `OUT`, `ACLR`, `ATONE`, `APLAY`, `HLT`

```assembly
; ============================================
; Program: jingle_bells.asm
; Description: Plays the opening of Jingle Bells
; Notes: E4, E4, E4 | E4, E4, E4 | E4, G4, C4, D4, E4
; ============================================

OUT "=========================================="
OUT "       SILICON AUDIO: JINGLE BELLS        "
OUT "=========================================="
OUT "Synthesizing Jingle Bells melody..."

; Flush any existing audio queue
ACLR

; Phrase 1: "Jin-gle bells" (E4 = 330 Hz)
ATONE 330, 250
ATONE 0, 50                      ; Short pause between identical notes
ATONE 330, 250
ATONE 0, 50
ATONE 330, 500
ATONE 0, 200                     ; Phrase breath pause

; Phrase 2: "Jin-gle bells" (E4 = 330 Hz)
ATONE 330, 250
ATONE 0, 50
ATONE 330, 250
ATONE 0, 50
ATONE 330, 500
ATONE 0, 200

; Phrase 3: "Jin-gle all the way"
; E4 (330 Hz), G4 (392 Hz), C4 (262 Hz), D4 (294 Hz), E4 (330 Hz)
ATONE 330, 250
ATONE 0, 50
ATONE 392, 250
ATONE 0, 50
ATONE 262, 250
ATONE 0, 50
ATONE 294, 250
ATONE 0, 50
ATONE 330, 600

; Trigger audio playback through computer/speaker
APLAY

OUT "Jingle Bells dispatched to audio buffer!"
HLT
```

---

## Example 12: CPU Reset Interrupt & Non-Volatile RAM Recovery (`reset_recovery.asm`)

Demonstrates registering a `RESET` interrupt handler (Vector 14). Because the CPU does not clear RAM upon reset or power-off, this program detects warm reboots, inspects a session counter and magic signature preserved in memory, repairs state, and continues execution.

### Required CPU Instructions
- `OUT`, `MOV`, `CMP`, `JZ`, `JNZ`, `INC`, `IVEC`, `IRET`, `STI`, `HLT`

```assembly
; ============================================
; Program: reset_recovery.asm
; Description: Demonstrates Vector 14 RESET handling & RAM persistence
; ============================================

; Register IVEC 14 (RESET / RST) handler
IVEC RESET, handle_cpu_reset

; Enable hardware interrupts
STI

; Check magic signature at RAM[100] (0xCAFE = 51966)
MOV A, [100]
CMP A, 51966
JZ warm_boot

cold_boot:
    OUT "Cold boot detected. Initializing RAM state..."
    MOV [100], 51966          ; Store magic signature in RAM[100]
    MOV [101], 1              ; Initialize session boot counter at RAM[101]
    OUT "Session counter initialized to: 1"
    HLT

warm_boot:
    OUT "Normal execution running after reset recovery..."
    OUT "Current session counter: ", 0
    MOV A, [101]
    OUT A
    HLT

; --- Reset Handler (Vector 14) ---
handle_cpu_reset:
    OUT "[RESET INTERRUPT] CPU reset or power-on caught!"
    ; Verify that previous RAM contents persisted
    MOV A, [100]
    CMP A, 51966
    JNZ cold_boot_reset

    ; State intact: increment session counter
    MOV A, [101]
    INC A
    MOV [101], A
    OUT "[RESET INTERRUPT] Preserved RAM verified. Incremented boot count."
    IRET                      ; Return to pc = 0 to execute main logic

cold_boot_reset:
    OUT "[RESET INTERRUPT] RAM uninitialized."
    IRET
```



## Peripheral example

The Environment Detector example wraps a device to the north and prints its biome ID. The Assembly version reads a RAM buffer with `PRESULTS`. The N2 version reads a managed string with `DeviceKit`.

- [Assembly source](environment-detector.asm)
- [N2 source](../../n-language/n2/examples/environment-detector.n2)
- [Device method reference](../peripherals.md#siliconcraft-peripheral-blocks)


## Networking and integrations

- [Digital Explorer's Compass](digital-compass.asm): structure search with text and numeric results.
- [HTTP GET](network-http.asm): asynchronous Network mailbox and a bounded response buffer.
- [Airship telemetry and wireless redstone](airship_demo.asm): wrap, poll, read altitude, and switch a named channel.
- [Assembly networking reference](../network.md)
- [Create setup and method reference](../create-peripherals.md)

[Assembly chapter](../README.md)
