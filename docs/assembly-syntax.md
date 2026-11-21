# Assembly Language Syntax Guide

This guide describes the syntax, file structure, formatting conventions, and error-handling mechanisms for SiliconCraft assembly files (`.s` and `.asm`).

---

## 1. File Format & Extensions

SiliconCraft accepts assembly source code written as plain UTF-8 text files with any of the following extensions:
- `.s` / `.S`
- `.asm` / `.ASM`

Files can be authored in any external text editor (VS Code, Notepad++, Vim, nano, IntelliJ, etc.) and flashed into ROM chips using the **Programmer** block in Minecraft.

---

## 2. Comments

Comments are non-executable text lines used to document code. SiliconCraft supports three standard comment prefixes:

```assembly
; Semicolon comments (traditional assembly style)
// Double-slash comments (C / C++ style)
# Hash comments (Python / shell style)
```

Comments can appear on their own lines or at the start of a line. Empty lines are automatically ignored by the CPU loader.

---

## 3. Labels and Jumps

Labels mark points in code that jump instructions (`JMP`, `JZ`, `JNZ`) can target.

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
- SiliconCraft's CPU preprocesses all labels upon ROM loading, allowing forward references (jumping to a label defined later in the file).
- You can also jump to a direct line index (e.g. `JMP 0` resets to the first instruction), but labels are recommended for safety and clarity.

---

## 4. Number Literals & Constants

Numbers can be specified in either decimal or hexadecimal:

- **Decimal**: Standard base-10 integers (`0`, `1`, `42`, `-10`).
- **Hexadecimal**: Prefixed with `0x` or `0X` (`0x0`, `0x0F`, `0xFF`, `0x1000`).

```assembly
MOV A, 16      ; Loads decimal 16 into A
MOV B, 0x10    ; Loads hex 0x10 (decimal 16) into B
```

---

## 5. Memory Addressing (`[address]`)

Brackets `[...]` represent a memory dereference into the system's linear RAM:

### Direct Memory Addressing
```assembly
MOV [0], 42       ; Store immediate 42 into RAM address 0
MOV A, [0]        ; Load value at RAM address 0 into register A
```

### Indirect (Register) Addressing
You can use a register as a pointer into RAM:

```assembly
MOV D, 0          ; D holds base RAM address 0
MOV [D], 100      ; Write 100 into RAM address pointed to by D
INC D             ; Advance pointer to address 1
MOV [D], 200      ; Write 200 into RAM address 1
```

> **Safety Warning**: The system's linear RAM bounds depend on the RAM module installed in the SoC. If your program reads or writes to an address $\ge \text{ram.length}$ or $< 0$, the CPU immediately halts with `[CPU EXCEPTION] RAM address out of bounds`.

---

## 6. String Literals (`OUT`)

String literals are enclosed in either double quotes `"` or single quotes `'` and are passed to the `OUT` opcode:

```assembly
OUT "Initializing system..."
OUT 'Terminal ready.'
```

To print register values or RAM contents instead of raw text, pass the register name or bracketed memory location without quotes:

```assembly
MOV A, 99
OUT A             ; Displays: A = 99
OUT [0]           ; Displays: [0] = <value>
```

---

## 7. Common CPU Errors & Troubleshooting

| Error in Terminal | Cause | Solution |
| :--- | :--- | :--- |
| `[CPU EXCEPTION] Illegal Opcode: XYZ` | The instruction `XYZ` was used in the program, but the CPU chip was forged **without** selecting `XYZ`. | Re-forge your CPU chip in the **Chip Forge** and ensure the checkmark for `XYZ` is selected. |
| `[CPU EXCEPTION] RAM address X out of bounds` | The code attempted to access memory beyond the size of the installed RAM module. | Check loop boundaries, or install a higher-capacity RAM module in the SoC. |
| `[CPU EXCEPTION] Label not found: XYZ` | A `JMP`, `JZ`, or `JNZ` targeted a label `XYZ` that does not exist in the source file. | Verify label spelling and ensure the target has a trailing colon (`XYZ:`). |
| `[CPU EXCEPTION] MOV requires dest, src` | Missing comma or operand in instruction syntax. | Ensure instructions have both operands separated by a comma (e.g. `MOV A, 5`). |
| `[CPU EXCEPTION] Cannot write to destination: XYZ` | Attempted to write a value into an invalid destination (such as an immediate constant). | Ensure the first operand is a register (`A`, `B`, `C`, `D`) or memory bracket `[...]`. |
