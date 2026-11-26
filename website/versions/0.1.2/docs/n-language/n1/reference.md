# N1 language reference (deprecated)

N1 (N standard 1) is deprecated and retained for existing `.n` programs. Use [N2](../n2/README.md) for new programs.

The **N1 standard** (`.n`) is SiliconCraft's classic imperative programming language designed for custom CPU architectures fabricated in Minecraft.

---

## 1. Variables & Automatic Type Detection

Variables and constants are declared using **`var`**, **`let`** (mutable), or **`const`** (immutable). Explicit type keywords are not used; types are inferred automatically:

```text
var counter = 0                   // Integer (32-bit)
const MAX_LIMIT = 100             // Constant (cannot be reassigned)
var isOnline = true               // Boolean (true / false)
var symbol = 'A'                  // Character
var message = "Hello Silicon!"     // String (null-terminated UTF-8)
```

Attempting to reassign to a `const` produces a compile-time error:
```text
const PI = 3
PI = 4       // Error: Cannot assign to constant 'PI'
```

---

## 2. Numbers: Decimal & Hexadecimal

Numbers can be written in standard decimal or hexadecimal notation anywhere in your code (variables, array indexes, pointer addresses, and colors):

```text
var decVal = 255
var hexVal = 0xFF                 // Hexadecimal literal (255)
var address = 0x80
var color = 0xFF00FF00            // 32-bit ARGB Green

var mask = 0x0F
var combined = (0x0F << 4) | 0x0A // 0xFA
```

---

## 3. Bitwise Operators & Precedence

All standard bitwise operations are fully supported with parentheses precedence:
- `&` (Bitwise AND)
- `|` (Bitwise OR)
- `^` (Bitwise XOR)
- `~` (Bitwise NOT)
- `<<` (Bitwise Shift Left)
- `>>` (Bitwise Shift Right)
- Compound bitwise assignments: `&=`, `|=`, `^=`, `<<=`, `>>=`

```text
var reg = 0x00
reg |= 0x80              // Set bit 7
reg &= ~0x80             // Clear bit 7
reg ^= 0xFF              // Toggle all bits
reg <<= 2                // Shift left by 2
```

---

## 4. Pointers & Direct Memory Access

The N1 language allows low-level memory programming using standard pointer and memory syntax:

### Address-of Operator (`&`)
Get the memory address of a variable, table field, or array element:
```text
var x = 42
var ptr = &x             // Address of x in RAM

var tbl = { 10, 20, 30 }
var elemPtr = &tbl[1]    // Address of table element
```

### Pointer Dereferencing (`*`)
Read or write through a pointer address:
```text
var data = 100
var ptr = &data
*ptr = 250               // Writes 250 to memory address pointed by ptr
println *ptr             // 250

*0x10 = 0xAA             // Write directly to absolute RAM address 0x10
```

### Direct RAM Helpers: `mem[...]`, `peek`, `poke`
Access RAM directly without variable binding:
```text
mem[0x20] = 55           // Write to RAM address 0x20
var val = mem[0x20]      // Read from RAM address 0x20

poke(0x30, 0xCC)         // poke(addr, val) helper
var readVal = peek(0x30) // peek(addr) helper
```

---

## 5. Lua-Style Tables (`{}`)

In N1, curly braces `{}` define tables, functioning as indexed arrays and key-value maps:

```text
// Indexed arrays
var numbers = { 10, 20, 30 }
println numbers[0]       // 10
numbers[0] = 99

// Key-value maps
var player = { name = "Steve", health = 100, isAlive = true }

println player.name      // Steve
println player["health"] // 100
player.health -= 15
```

---

## 6. Output Functions: `print`, `println`, and Hex Display

Output functions automatically convert all types (integers, strings, booleans, characters) into text:

- `print <expr>`: Prints `<expr>` without trailing newline.
- `println <expr>`: Prints `<expr>` with trailing newline (`\n`).
- `print_hex <expr>`: Prints integer `<expr>` formatted as hexadecimal (e.g. `0xFF`).
- `println_hex <expr>`: Prints integer in hexadecimal followed by newline.
- `hex(<expr>)`: Can also be used inside `print()` or `println()`, e.g. `println hex(0x10)`.

```text
var val = 255
println val              // 255
println_hex val          // 0xFF
print "Value: "
println_hex 0xCAFE       // 0xCAFE
```

---

## 7. Multi-Drive Binary Storage

The N1 language provides direct storage control across all installed storage drives:

```text
var numDrives = storage.count()    // Number of attached storage drives
var curSize = storage.size()       // Capacity of currently selected drive in bytes
storage.select(1)                  // Switch active drive to drive index 1
var active = storage.selected()    // Get active drive index

storage.write(0, 0xAB)             // Write single byte 0xAB to address 0
var b = storage.read(0)            // Read single byte from address 0

storage.write_int(4, 100000)       // Write 32-bit integer (4 bytes little-endian)
var num = storage.read_int(4)      // Read 32-bit integer

storage.write_string(0x20, "Hello Storage!")
var s = storage.read_string(0x20, 14) // Reads null-terminated string into RAM
println s
```

---

## 8. Framebuffer Graphics

Direct 16:9 framebuffer pixel rendering via `fb.*` or `display.*`:

```text
// Framebuffer size: 960 x 540
display.mode(1)

// Clear screen with black (ARGB: 0xFF000000)
fb.clear(0xFF000000)

// Set individual pixels (x, y, 32-bit ARGB color)
fb.set(10, 10, 0xFFFF0000)        // Red pixel

// Draw shapes
fb.line(0, 0, 127, 63, 0xFF00FF00) // Green diagonal line
fb.rect(20, 20, 40, 20, 0xFF0000FF)// Blue filled rectangle

// Flip back-buffer to screen
fb.flip()
```

---

## 9. Hardware Audio Synthesis

Synthesizes audio through an onboard audio buffer (`audio.*`):

```text
// Frequency in Hz; duration in milliseconds.
audio.tone(440, 1000)
audio.tone(880, 500)
audio.play()
audio.clear()
```

Use `audio.push(sample)` for unsigned 8-bit PCM samples, `audio.write(address, count)` for a RAM sample buffer, and `audio.pause(milliseconds)` for silence. Use [Assembly audio instructions](../../assembly/reference.md#7-audio-buffer--sound-synthesis) for direct hardware control.

---

## 10. Control Flow & Functions

```text
// Conditionals
var x = 15
if x > 10 {
    println "Greater than 10"
} else {
    println "10 or less"
}

// Loops
var i = 0
while i < 10 {
    if i == 5 {
        i++
        continue
    }
    println i
    i++
}

// Functions
var multiply_x = 7
var multiply_y = 8
func multiply() {
    return multiply_x * multiply_y
}

var result = multiply()
println result                   // 56
```

---

N1 function examples use shared variables for inputs and `return` for a numeric result. Use N2 for functions with scoped parameters.

## 11. Multi-File Projects & Modular Imports

When compiling multi-file N1 projects using the Project Compiler:
```text
import "math/vec.n"
import "drivers/display.n"
```
The project entrypoint must be `main.n`.

---

## 12. Interrupt Vector Table & Event Handlers

```text
on_key(handle_key)              // Vector 10: Keyboard input
on_mouse_move(handle_mouse)     // Vector 11: Mouse movement in CRT canvas
on_mouse_click(handle_click)    // Vector 12: Mouse click in CRT canvas
on_vblank(handle_frame)         // Vector 13: 60 Hz V-Blank / periodic timer
on_reset(handle_reset)          // Vector 14: Executed right after CPU reset

fn handle_key() {
    print "Key: "
    println A
    IRET
}
```

---

## 13. Inline Assembly

Embed raw CPU assembly directly into N1 source code:
```text
asm {
    MOV A, 0x1234
    PUSH A
}
asm("OUT \"From ASM\"");
```


[Chapter contents](README.md)
