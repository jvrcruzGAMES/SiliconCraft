# The .n Programming Language Guide

The **.n** language is SiliconCraft's custom high-level programming language designed specifically for custom CPU architectures fabricated in Minecraft.

The **Code Compiler** compiles single `.n` files, and the **Project Compiler** compiles multi-file `.zip` projects into optimized SiliconCraft assembly, checking emitted opcodes against your **Architecture Writeup**.

---

## 1. Variables & Automatic Type Detection

Variables and constants are declared using **`var`**, **`let`** (mutable), or **`const`** (immutable). Explicit type keywords are not used; types are inferred automatically:

```text
var counter = 0                   // Integer (32-bit)
const MAX_LIMIT = 100             // Constant (cannot be reassigned)
var isOnline = true               // Boolean (true / false)
var symbol = '★'                  // Character
var message = "Olá Mundo!"        // String (null-terminated UTF-8)
```

Attempting to reassign to a `const` produces a compile-time error:
```text
const PI = 3
PI = 4       // Error: Cannot assign to constant 'PI'
```

---

## 2. Numbers: Hexadecimal & Decimal

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

The `.n` language allows low-level memory programming using standard pointer and memory syntax:

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

In `.n`, curly braces `{}` define tables, functioning as indexed arrays and key-value maps:

```text
// Indexed arrays
var numbers = { 10, 20, 30 }
println numbers[0]       // 10
numbers[0] = 99

// Key-value maps
var player = {
    name = "Steve",
    health = 100,
    isAlive = true
}

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

The `.n` language provides complete binary storage control across all installed storage drives (SoC flash and PC NVMe drives):

### Storage Inspection & Drive Selection
```text
var numDrives = storage.count()    // Number of attached storage drives
var curSize = storage.size()       // Capacity of currently selected drive in bytes
storage.select(1)                  // Switch active drive to drive index 1
var active = storage.selected()    // Get active drive index
```

### Reading & Writing Bytes and Integers
```text
storage.write(0, 0xAB)             // Write single byte 0xAB to address 0
var b = storage.read(0)            // Read single byte from address 0

storage.write_int(4, 100000)       // Write 32-bit integer (4 bytes little-endian)
var num = storage.read_int(4)      // Read 32-bit integer

// Direct drive indexing syntax:
storage[0].write(0x10, 42)
var val = storage[1].read(0x10)
```

### Strings & Byte Blocks
```text
storage.write_string(0x20, "Hello Storage!")
var s = storage.read_string(0x20, 14) // Reads null-terminated string into RAM
println s
```

---

## 8. Framebuffer Graphics (Direct Screen Canvas)

The computer monitor supports direct $128 \times 64$ hardware pixel rendering via `fb.*` or `display.*`:

```text
var w = fb.width()                 // 128
var h = fb.height()                // 64

// Clear / fill screen with black (ARGB: 0xFF000000)
fb.fill(0xFF000000)

// Set individual pixels (x, y, 32-bit ARGB color)
fb.set(10, 10, 0xFFFF0000)        // Red pixel
var pix = fb.get(10, 10)          // Read pixel color

// Draw shapes
fb.line(0, 0, 127, 63, 0xFF00FF00) // Green diagonal line
fb.rect(20, 20, 40, 20, 0xFF0000FF)// Blue filled rectangle

// Flip back-buffer to screen
fb.flip()
```

---

## 9. Hardware Audio Buffer & Sound Synthesis

The computer synthesizes audio through an onboard 4-channel audio buffer (`audio.*`), played on the screen or in-world via a **Speaker** block:

```text
// audio.beep(frequencyHz, durationTicks, waveformType, volume)
// Waveform types: 0 = Sine, 1 = Square, 2 = Triangle, 3 = Sawtooth, 4 = Noise

audio.beep(440, 20, 1, 100)       // 440 Hz (A4), 1 second (20 ticks), Square wave, 100% volume
audio.beep(880, 10, 0, 80)        // 880 Hz (A5), 0.5s, Sine wave, 80% volume

audio.play()                       // Start buffer playback
audio.stop()                       // Stop playback
audio.clear()                      // Clear queued audio buffer
var pending = audio.status()       // Remaining audio samples / buffer status
```

---

## 10. Control Flow, Loops & Functions

### Conditionals (`if`, `else`)
```text
var x = 15
if x > 10 {
    println "Greater than 10"
} else {
    println "10 or less"
}
```

### Loops (`while`, `break`, `continue`)
```text
var i = 0
while i < 10 {
    if i == 5 {
        i++
        continue
    }
    println i
    i++
}
```

### Functions (`func`, `return`)
```text
func multiply(x, y) {
    return x * y
}

var result = multiply(7, 8)
println result                   // 56
```

---

## 11. Multi-File Projects & Modular Imports (Project Compiler)

When writing large applications, package your project into a `.zip` archive and compile using the **Project Compiler**.

### Import Syntax
Inside any `.n` file (starting with `main.n` as entrypoint):
```text
import "math/vec.n"
import "drivers/display.n"
import utils.string               // Resolves to utils/string.n
```

### Rules for Project Compilation
- The `.zip` root or entrypoint must be `main.n`.
- Imports can reference subdirectories (`"sub/module.n"`).
- Duplicate and circular imports are automatically detected and prevented.
- The output assembly `<zipname>.asm` is compiled and registered in your Program Library.

---

## 12. Interrupts, Events & Exception Handlers

The `.n` language provides native event-hooking and exception handling primitives that map directly to the CPU's Interrupt Vector Table (IVT):

### Input Event Handlers
```text
on_key(handle_key)              // Vector 10: Keyboard input
on_mouse_move(handle_mouse)     // Vector 11: Mouse movement in CRT canvas
on_mouse_click(handle_click)    // Vector 12: Mouse click in CRT canvas
on_vblank(handle_frame)         // Vector 13: 60 Hz V-Blank / periodic timer

fn handle_key() {
    // Register A holds the typed character code
    print "Key: "
    println A
    IRET
}

fn handle_frame() {
    // Executed 60 times per second
    fb.flip()
    IRET
}

fn handle_click() {
    // A = mouse X (0..227), B = mouse Y (0..81), C = button (0: left, 1: right)
    fb.set(A, B, 0xFFFFFFFF)
    fb.flip()
    IRET
}
```

### Frame Synchronization & Timer
```text
vblank.enable()                 // Starts 60 Hz V-Blank interrupt
vblank.disable()                // Disables V-Blank interrupt
timer.start(60)                 // Set arbitrary timer frequency in Hz
timer.stop()                    // Stops timer
```

### Exception Handlers (`catch`)
Register custom handlers for hardware and runtime exceptions:
```text
catch(DIV_ZERO, handle_div_zero)
catch(OUT_OF_BOUNDS, handle_out_of_bounds)

fn handle_div_zero() {
    println "[ERROR] Division by zero caught!"
    A = 0                       // Set fallback result
    IRET                        // Resumes after faulting line
}
```

### Low-Level Control (`CLI`, `STI`, `INT`)
- `CLI`: Disable/mask interrupts. Incoming events are queued safely.
- `STI`: Enable/unmask interrupts. Queued events are immediately dispatched.
- `INT <vector>`: Trigger a software interrupt or system call.

---

## 13. Gamepad & Controller Button State Polling

For continuous player movement, dash mechanics, and variable jump heights, `.n` provides high-level gamepad polling:

```text
var buttons = pad.get()          // Polls controller 0 held bitmask

if (buttons & pad.RIGHT) != 0 {
    playerX += 2
}
if (buttons & pad.B) != 0 {
    // B button held: jump
    playerY -= 5
}
if (buttons & pad.Y) != 0 {
    // Y button held: dash speed boost
    playerX += 2
}
```

#### Available Pad Constants:
- `pad.RIGHT` (`1`), `pad.LEFT` (`2`), `pad.DOWN` (`4`), `pad.UP` (`8`)
- `pad.START` (`16`), `pad.SELECT` (`32`)
- `pad.B` (`64` - Jump), `pad.A` (`128` - Spin Jump)
- `pad.Y` (`256` - Dash/Run), `pad.X` (`512` - Action)
- `pad.L` (`1024` - Shoulder L), `pad.R` (`2048` - Shoulder R)

---

## 14. Dynamic Function Pointers & Indirect Calls

You can dynamically call or jump to functions stored in variables or lookup tables:

```text
var stateHandler = &walk_state
call(stateHandler)               // Dynamic CALL to address in stateHandler

var stateTable = { &idle_state, &walk_state, &jump_state }
var nextFn = stateTable[stateIndex]
call(nextFn)
```

---

## 15. Static Data Directives in `.n`

Low-level static data directives can be placed directly at the top or bottom of `.n` files to define asset ROM tables and preloaded data:

```text
ORG 0x1000
DB 0x01, 0x02, 0x03, 0x04
DW 0x55AA, 0x1234
DL 0x100000
INCBIN "level1_map.bin"
```

