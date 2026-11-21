# The .n Programming Language Guide

The **.n** language is SiliconCraft's custom high-level programming language designed specifically for custom CPU architectures fabricated in Minecraft.

The **Code Compiler** block compiles `.n` source files directly into SiliconCraft assembly, checks the emitted opcodes against your **Architecture Writeup**, and adds the compiled program to your global Program Library.

---

## 1. Syntax Overview

`.n` code is structured with statements, blocks wrapped in curly braces `{ ... }`, and variables.

### Variables & Declarations
Variables are stored in RAM addresses or mapped directly to CPU registers (`A`, `B`, `C`, `D`):
```text
var x = 0
let total = 100
var counter = 1
```

### Assignment & Arithmetic
You can assign expressions, perform increment/decrement, and accumulate values:
```text
x = 5
x += 2
x -= 1
x++
x--
```

### Print Statement
Outputs values directly to the computer terminal via the `OUT` instruction:
```text
print x
print A
```

### Conditions & Loops
#### While Loop:
```text
var i = 0
while i < 10 {
    print i
    i++
}
```

#### If Statement:
```text
if x == 10 {
    print x
}
```

Comparison operators supported: `==`, `!=`, `<`, `<=`, `>`, `>=`.

### Inline Assembly
If you need direct low-level control or special instructions:
```text
asm {
    MOV B, 42
    OUT B
}
```

### Halt & Nop
```text
halt    // Halts execution (emits HLT)
nop     // No-operation (emits NOP)
```

---

## 2. Example Programs

### Fibonacci Sequence in `.n`
```text
var a = 0
var b = 1
var count = 10

while count > 0 {
    print a
    var next = a + b
    a = b
    b = next
    count--
}
halt
```

### Counter with Limit
```text
var count = 0
while count <= 20 {
    print count
    count += 2
}
halt
```

---

## 3. Compilation & Architecture Verification

When compiling a `.n` file in the **Code Compiler**:
1. Place an **Architecture Writeup** (from the **Architecture Writer**) into the machine's blueprint slot.
2. Click **📁 Choose .n File** to select your script.
3. The Code Compiler parses and compiles the script into SiliconCraft CPU instructions.
4. **Instruction Check**:
   - The compiler identifies all opcodes required (e.g. `MOV`, `ADD`, `CMP`, `JNZ`, `OUT`, `HLT`).
   - If the installed architecture does not support all required opcodes, the compile button is **disabled** with a red status message detailing which instructions are missing.
   - If all opcodes are supported, the status shows `All opcodes valid!` and you can click **[+ Compile & Add]**.
5. Once added, the program is permanently registered in your world's program library, ready to be burned onto physical ROM chips using the **ROM Programmer**.
