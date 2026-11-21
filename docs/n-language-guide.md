# The .n Programming Language Guide

The **.n** language is SiliconCraft's custom high-level programming language designed specifically for custom CPU architectures fabricated in Minecraft.

The **Code Compiler** block compiles `.n` source files directly into SiliconCraft assembly, checks the emitted opcodes against your **Architecture Writeup**, and adds the compiled program to your global Program Library.

---

## 1. Variables & Automatic Type Detection

Variables and constants are declared using **`var`** (mutable) and **`const`** (immutable). Explicit type keywords (such as `int`, `char`, `char[]`) are not used; types are inferred automatically from initial values:

```text
var counter = 0                   // Integer (32-bit)
const MAX_LIMIT = 100             // Integer constant (cannot be reassigned)
var isOnline = true               // Boolean (true / false)
var symbol = '★'                  // Character
var message = "Olá Mundo!"        // String (null-terminated UTF-8 char array)
```

### Constant Immutability
Attempting to reassign to a `const` produces a compile-time error:
```text
const PI = 3
PI = 4       // Error: Cannot assign to constant 'PI'
```

---

## 2. Boolean Type

Booleans support `true` and `false` literals and can be evaluated directly in conditionals:
```text
var isRunning = true
const debugMode = false

if isRunning {
    println "System is active"
}

if !debugMode {
    println "Production build"
}
```

---

## 3. Lua-Style Tables (`{}`)

In `.n`, the curly braces `{}` are used for Lua-style tables, functioning as both indexed arrays and key-value maps:

### Array Tables (Indexed Lists)
```text
var numbers = { 10, 20, 30 }
var fruits = { "apple", "banana", "cherry" }

println numbers[0]      // 10
numbers[0] = 99
println numbers[0]      // 99
```

### Map Tables (Key-Value Dictionaries)
Tables support Lua syntax (`key = value`), colon syntax (`key: value`), and bracket syntax (`["key"] = value`):
```text
var player = {
    name = "Steve",
    health = 100,
    isAlive = true
}

// Accessing fields via dot notation
println player.name        // Steve
println player.health      // 100
println player.isAlive     // true

// Accessing fields via bracket notation
println player["name"]     // Steve

// Modifying fields
player.health = 85
player.isAlive = false
println player.health      // 85
println player.isAlive     // false
```

---

## 4. Output Functions: `print` and `println`

The `.n` language provides two output functions: **`print`** and **`println`** (the legacy `print_char` has been removed). Both functions automatically convert whatever argument type is passed into a string (character array):

- **`print <expr>`**: Appends the string representation of `<expr>` directly to the **current line** on the terminal screen without a trailing newline.
- **`println <expr>`**: Appends the string representation of `<expr>` and advances to the **next line** (appends `\n`). Calling `println()` or `println` with no argument outputs an empty line.

### Supported Types
Both `print` and `println` handle all types seamlessly:
- **Strings**: Streams characters sequentially until the null terminator (`0`).
- **Characters**: Emits the glyph (ASCII or UTF-8).
- **Booleans**: Converts to `"true"` or `"false"`.
- **Numbers / Integers**: Converts the numerical value into its decimal digit string.
- **Table Fields**: Evaluates the table element and converts it to string according to its inferred type.

### Example: Combining `print` and `println`
```text
var user = { name = "Steve", level = 50 }

print "User: "
print user.name
print " (Level "
print user.level
println ")"

// Displays on the CRT terminal:
// User: Steve (Level 50)
```

---

## 5. Control Flow

### While Loop
```text
var i = 0
while i < 5 {
    println i
    i++
}
```

### While Loop over Table Array
```text
var items = { 10, 20, 30 }
var i = 0
while i < 3 {
    println items[i]
    i++
}
```

### If Statement
```text
var score = 100
if score >= 100 {
    println "Victory!"
}
```

---

## 6. BIOS & System Functions

Since you implement your own BIOS in `.n` (or assembly), `.n` provides built-in system tools:

### Screen Control: `cls()`
Clears the CRT terminal screen:
```text
cls()
println "=== BIOS v1.0 ==="
```

### Time: `time.now()`
Returns the current Minecraft world time in ticks:
```text
var start = time.now()
println start
```

### Delay: `sleep(seconds)` and `sleep_ticks(ticks)`
Pauses CPU execution for the specified duration:
```text
println "Starting POST..."
sleep(1)            // Sleep 1 second (20 Minecraft ticks)
println "Checking memory..."
sleep(0.5)          // Sleep 10 ticks
sleep_ticks(5)      // Sleep raw 5 Minecraft ticks
```

### Random Numbers: `rand(max)`
Generates a random integer in the range `[0, max - 1]`:
```text
var dice = rand(6) + 1
println dice
```

---

## 7. Functions (`fn`)

You can define and call subroutines and functions:

```text
fn boot() {
    cls()
    println "SiliconCraft BIOS Initialized"
    println "Time: " + time.now()
}

fn add(x, y) {
    return x + y
}

boot()
var sum = add(10, 25)
println sum             // 35
```

---

## 8. Arithmetic & Math Operations

All standard arithmetic operators are supported with operator precedence:
- `+` (Addition), `-` (Subtraction)
- `*` (Multiplication)
- `/` (Integer division)
- `%` (Modulo / remainder)
- Compound assignments: `+=`, `-=`, `*=`, `/=`, `%=`

```text
var a = 20
var b = 6
println a * b           // 120
println a / b           // 3
println a % b           // 2

a += 10                 // 30
a *= 2                  // 60
```

---

## 9. Full Architecture Instruction Set (ISA)

When writing an **Architecture Writeup** at the Architecture Writer, the following hardware instructions are available:

| Opcode | Description |
|---|---|
| `NOP` | No operation |
| `HLT` | Halts CPU execution |
| `MOV <dest>, <src>` | Move value or register |
| `PUSH <src>` | Push onto data stack |
| `POP <dest>` | Pop from data stack |
| `ADD <dest>, <src>` | Addition |
| `SUB <dest>, <src>` | Subtraction |
| `MUL <dest>, <src>` | Multiplication |
| `DIV <dest>, <src>` | Integer division |
| `MOD <dest>, <src>` | Modulo / remainder |
| `INC <reg/mem>` | Increment by 1 |
| `DEC <reg/mem>` | Decrement by 1 |
| `CMP <reg>, <val>` | Compare (sets Z and S flags) |
| `AND <dest>, <src>` | Bitwise AND |
| `OR <dest>, <src>` | Bitwise OR |
| `XOR <dest>, <src>` | Bitwise XOR |
| `NOT <dest>` | Bitwise NOT |
| `SHL <dest>, <count>` | Bitwise Shift Left |
| `SHR <dest>, <count>` | Bitwise Shift Right |
| `JMP <target>` | Unconditional jump |
| `JZ <target>` | Jump if Zero (`Z == 1`) |
| `JNZ <target>` | Jump if Not Zero (`Z == 0`) |
| `JS <target>` | Jump if Sign / Negative (`S == 1`) |
| `JNS <target>` | Jump if Not Sign / Positive (`S == 0`) |
| `JL <target>` | Jump if Less (`S == 1 && Z == 0`) |
| `JLE <target>` | Jump if Less or Equal (`S == 1 || Z == 1`) |
| `JG <target>` | Jump if Greater (`S == 0 && Z == 0`) |
| `JGE <target>` | Jump if Greater or Equal (`S == 0`) |
| `CALL <target>` | Call subroutine (pushes return address) |
| `RET` | Return from subroutine |
| `OUT <val>[, mode]` | Output to CRT terminal |
| `OUTC <char/byte>` | Output character byte (ASCII / UTF-8) |
| `CLS` | Clear CRT terminal display |
| `SLEEP <ticks>` | Pause execution for Minecraft ticks |
| `TIME <dest>` | Store Minecraft game time in ticks |
| `RAND <dest>, <max>` | Generate random integer `[0, max - 1]` |

---

## 10. Full BIOS Example Program

```text
cls()
println "=== JTech Computers ==="
println "BIOS v1.0 Release"
println "Boot tick: " + time.now()
println ""

sleep(1)
println "Testing CPU instructions..."

var points = 0
while points < 5 {
    print "Point: "
    println points
    points++
}

println "All diagnostics passed!"
sleep(0.5)
println "System Ready."
halt
```
