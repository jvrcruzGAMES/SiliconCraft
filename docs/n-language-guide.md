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

## 4. Unified Print Statement: `println`

The only print instruction in `.n` is **`println`** (the legacy `print_char` has been removed). `println` automatically converts whatever type is passed into a string (character array) and outputs it followed by a newline:

- **Strings**: Prints characters sequentially from memory until the null terminator (`0`), then appends newline (`\n`).
- **Characters**: Prints character glyph, followed by newline.
- **Booleans**: Evaluates and outputs `"true"` or `"false"`.
- **Numbers / Integers**: Evaluates and outputs the numerical value as a decimal string.
- **Table Fields**: Evaluates the field and converts its type to string (e.g. `println user.name` prints `"Steve"`, `println user.isAlive` prints `"true"`).

```text
println "Starting system..."
println true
println '★'
println 42
println player.name
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

## 6. Full Example Program

```text
const TITLE = "★ SiliconCraft Terminal ★"
println TITLE

var server = {
    host = "127.0.0.1",
    port = 8080,
    online = true
}

println server.host
println server.port
println server.online

var metrics = { 45, 60, 80 }
println metrics[0]
metrics[0] = 50
println metrics[0]

server.online = false
println server.online
halt
```
