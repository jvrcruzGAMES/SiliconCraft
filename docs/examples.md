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
