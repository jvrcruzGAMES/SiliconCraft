# Instruction Set Architecture (ISA) Reference Manual

This document details the virtual architecture, registers, status flags, and instruction set implemented in SiliconCraft's `VirtualCpu`.

---

## 1. Hardware Architecture

### General-Purpose Registers
The CPU provides four 32-bit integer registers:

| Register | Description | Typical Use Case |
| :---: | :--- | :--- |
| **`A`** | Primary Accumulator | Arithmetic, main calculations, returns |
| **`B`** | Secondary Register | Second operands, loop limits |
| **`C`** | Counter Register | Loop indexes, loop counters |
| **`D`** | Data / Pointer Register | Base memory addresses, pointer offsets |

### System Registers & Flags
- **`PC` (Program Counter)**: Holds the line index of the next instruction to execute.
- **`Cycles`**: Tracks the total clock cycles executed since last reset.
- **`Z` (Zero Flag)**:
  - Set to `1` (`true`) if the result of an arithmetic, increment, decrement, or compare operation equals `0`.
  - Cleared to `0` (`false`) if the result is non-zero.
- **`S` (Sign Flag)**:
  - Set to `1` (`true`) if the result of an arithmetic or compare operation is negative (`< 0`).
  - Cleared to `0` (`false`) if non-negative.

### Memory Organization
- **RAM Capacity**: Configured by the RAM module installed in the SoC (typically 16 to 4096 addresses).
- **RAM Addressing**:
  - Direct literal address: `[0]`, `[15]`, `[0x20]`
  - Indirect register address: `[A]`, `[B]`, `[C]`, `[D]`
  - Reading or writing outside the configured RAM boundaries raises an `IndexOutOfBoundsException` CPU halt.

---

## 2. Complete Instruction Set

> **Hardware Rule**: Every instruction executed must be included on the CPU chip during fabrication in the **Chip Forge**. If an instruction is encountered that was not included on the chip, the CPU halts with `[CPU EXCEPTION] Illegal Opcode`.

### 1. NOP (No Operation)
- **Syntax**: `NOP`
- **Description**: Advances the Program Counter and consumes 1 clock cycle without altering registers, flags, or memory.
- **Flags Affected**: None.
- **Example**:
  ```assembly
  NOP
  ```

---

### 2. MOV (Move / Copy Data)
- **Syntax**: `MOV dest, src`
- **Description**: Copies the value from `src` into `dest`.
- **Supported Formats**:
  - Register to Register: `MOV A, B`
  - Immediate to Register: `MOV A, 42` or `MOV A, 0xFF`
  - Memory to Register: `MOV A, [10]` or `MOV A, [B]`
  - Register to Memory: `MOV [10], A` or `MOV [B], A`
  - Immediate to Memory: `MOV [0], 100`
- **Flags Affected**: None.
- **Example**:
  ```assembly
  MOV A, 10      ; Load 10 into A
  MOV B, A       ; Copy A into B
  MOV [0], B     ; Store B into RAM address 0
  MOV C, [0]     ; Load value from RAM address 0 into C
  ```

---

### 3. ADD (Integer Addition)
- **Syntax**: `ADD dest, src`
- **Description**: Adds `src` to `dest` and stores the sum in `dest` (`dest = dest + src`).
- **Supported Formats**:
  - `ADD reg, imm` (e.g., `ADD A, 5`)
  - `ADD reg, reg` (e.g., `ADD A, B`)
  - `ADD reg, [mem]` (e.g., `ADD A, [0]`)
  - `ADD [mem], reg` (e.g., `ADD [10], A`)
- **Flags Affected**: `Z` (Zero), `S` (Sign).
- **Example**:
  ```assembly
  MOV A, 15
  ADD A, 25      ; A becomes 40 (Z=0, S=0)
  ```

---

### 4. SUB (Integer Subtraction)
- **Syntax**: `SUB dest, src`
- **Description**: Subtracts `src` from `dest` and stores the difference in `dest` (`dest = dest - src`).
- **Supported Formats**:
  - `SUB reg, imm` (e.g., `SUB A, 10`)
  - `SUB reg, reg` (e.g., `SUB A, B`)
  - `SUB reg, [mem]` (e.g., `SUB A, [1]`)
- **Flags Affected**: `Z` (Zero), `S` (Sign).
- **Example**:
  ```assembly
  MOV A, 10
  SUB A, 10      ; A becomes 0 (Z=1, S=0)
  ```

---

### 5. INC (Increment)
- **Syntax**: `INC dest`
- **Description**: Increments `dest` by 1 (`dest = dest + 1`).
- **Supported Formats**:
  - `INC reg` (e.g., `INC A`, `INC C`)
  - `INC [mem]` (e.g., `INC [0]`)
- **Flags Affected**: `Z` (Zero), `S` (Sign).
- **Example**:
  ```assembly
  MOV C, 0
  INC C          ; C becomes 1
  ```

---

### 6. DEC (Decrement)
- **Syntax**: `DEC dest`
- **Description**: Decrements `dest` by 1 (`dest = dest - 1`).
- **Supported Formats**:
  - `DEC reg` (e.g., `DEC A`, `DEC C`)
  - `DEC [mem]` (e.g., `DEC [0]`)
- **Flags Affected**: `Z` (Zero), `S` (Sign).
- **Example**:
  ```assembly
  MOV C, 5
  DEC C          ; C becomes 4
  ```

---

### 7. CMP (Compare)
- **Syntax**: `CMP reg, val`
- **Description**: Compares `reg` with `val` by internally computing `(reg - val)`. Updates status flags `Z` and `S` without modifying `reg`.
  - If `reg == val`: `Z = 1`, `S = 0`
  - If `reg < val`: `Z = 0`, `S = 1`
  - If `reg > val`: `Z = 0`, `S = 0`
- **Flags Affected**: `Z` (Zero), `S` (Sign).
- **Example**:
  ```assembly
  MOV A, 10
  CMP A, 10      ; Z=1, S=0 (Equal)
  CMP A, 15      ; Z=0, S=1 (Less than)
  CMP A, 5       ; Z=0, S=0 (Greater than)
  ```

---

### 8. JMP (Unconditional Jump)
- **Syntax**: `JMP target`
- **Description**: Unconditionally branches to `target`. `target` can be a declared label (`loop:`) or an absolute line number.
- **Flags Affected**: None.
- **Example**:
  ```assembly
  start:
      INC A
      JMP start  ; Infinite loop
  ```

---

### 9. JZ (Jump if Zero / Equal)
- **Syntax**: `JZ target`
- **Description**: Jumps to `target` if the Zero Flag `Z` is `1` (true). Used after arithmetic or `CMP` to detect equality or zero result.
- **Flags Affected**: None.
- **Example**:
  ```assembly
  CMP A, 100
  JZ max_reached ; Jumps if A == 100
  ```

---

### 10. JNZ (Jump if Not Zero / Not Equal)
- **Syntax**: `JNZ target`
- **Description**: Jumps to `target` if the Zero Flag `Z` is `0` (false). Used for loop continuation until a counter reaches zero.
- **Flags Affected**: None.
- **Example**:
  ```assembly
  MOV C, 5
  loop:
      OUT C
      DEC C
      JNZ loop   ; Continues until C reaches 0
  ```

---

### 11. OUT (Terminal Output)
- **Syntax**:
  - String literal: `OUT "Your text here"` or `OUT 'Your text here'`
  - Value / Register / Memory: `OUT reg`, `OUT [mem]`, `OUT imm`
- **Description**: Appends the text or formatted numeric value (`NAME = VAL`) to the computer terminal window.
- **Flags Affected**: None.
- **Example**:
  ```assembly
  OUT "Hello from SiliconCraft!"
  MOV A, 42
  OUT A          ; Terminal displays: A = 42
  ```

---

### 12. HLT (Halt Execution)
- **Syntax**: `HLT`
- **Description**: Halts the CPU clock. The computer enters the `HALTED` state and stops processing subsequent ticks until reset or power cycled.
- **Flags Affected**: None.
- **Example**:
  ```assembly
  OUT "Computation complete."
  HLT
  ```
