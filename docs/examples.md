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
- `OUT`, `MOV`, `ADD`, `INC`, `CMP`, `JNZ`, `HLT`

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

Draws geometric primitives (filled boxes, diagonal lines, and pixels) directly onto the $228 \times 82$ CRT pixel canvas and renders the frame.

### Required CPU Instructions
- `FBCLR`, `FBRECT`, `FBLINE`, `FBSET`, `FBSWAP`, `HLT`

```assembly
; ============================================
; Program: graphics_demo.asm
; Description: Renders 2D geometric shapes on CRT
; ============================================

; Clear canvas to dark slate (ARGB: 0xFF181824)
FBCLR 0xFF181824

; Draw a blue title banner rectangle (x=10, y=10, w=208, h=16)
FBRECT 10, 10, 208, 16, 0xFF2A4494

; Draw diagonal crosshairs across the canvas
FBLINE 0, 0, 227, 81, 0xFF44AA44
FBLINE 0, 81, 227, 0, 0xFF44AA44

; Draw an accent gold square in the center
FBRECT 104, 36, 20, 20, 0xFFFFCC00

; Plot a single white pixel in the center of the gold box
FBSET 114, 46, 0xFFFFFFFF

; Flip buffer to display on screen
FBSWAP
HLT
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

; Queue A4 (440 Hz) for 8 ticks (~400ms)
ATONE 440, 8

; Queue C#5 (554 Hz) for 8 ticks
ATONE 554, 8

; Queue E5 (659 Hz) for 8 ticks
ATONE 659, 8

; Queue A5 (880 Hz) for 16 ticks
ATONE 880, 16

; Fire audio playback
APLAY

OUT "Playback dispatched to speaker!"
HLT
```

---

## Example 8: Interrupt & Fault Exception Handling (`interrupt_demo.asm`)

Demonstrates registering handlers in the Interrupt Vector Table (IVT) for both fault recovery (`DIV_ZERO`) and real-time user input (`KEYBOARD`).

### Required CPU Instructions
- `OUT`, `OUTC`, `MOV`, `DIV`, `IVEC`, `IRET`, `STI`, `JMP`, `HLT`

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
- `OUT`, `OUTC`, `MOV`, `CMP`, `JZ`, `SCOUNT`, `SSEL`, `SWRITE`, `SREAD`, `HLT`

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

