# SiliconCraft .n Language Examples

This document provides complete, working high-level programs written in the **.n** programming language that you can copy, save to `.n` files, compile using the **Code Compiler** or **Project Compiler**, and flash onto your ROM chips or Storage Drives.

The `.n` language compiles into optimized SiliconCraft assembly instructions while providing automatic type inference, structured loops, functions, tables/arrays, direct RAM pointer access, multi-drive storage, 16:9 framebuffer pixel rendering, hardware audio synthesis, and interrupt exception handlers.

---

## Example 1: Basic Counter & Banner (`counter.n`)

A simple countdown program that demonstrates structured `while` loops, decrements, and string/integer console output.

```text
// ============================================
// Program: counter.n
// Description: Counts down from 5 to 1 and halts
// ============================================

println "=========================="
println "    COUNTDOWN DEMO       "
println "=========================="

var c = 5

while c > 0 {
    println c
    c--
}

println "Liftoff! SiliconCraft online."
```

---

## Example 2: Fibonacci Sequence (`fibonacci.n`)

Calculates the first 8 numbers of the Fibonacci sequence, storing each number into RAM addresses `[0x20]` through `[0x27]` using direct memory brackets (`mem[...]`) while displaying them on the terminal.

```text
// ============================================
// Program: fibonacci.n
// Description: Computes Fibonacci series into RAM
// ============================================

println "Computing Fibonacci Series..."

var termA = 0
var termB = 1
var ptr = 0x20

// Store initial values into RAM
mem[ptr] = termA
ptr++
mem[ptr] = termB
ptr++

// Loop to compute the remaining 6 terms
var count = 6
while count > 0 {
    var next = termA + termB
    mem[ptr] = next
    println next

    // Shift terms
    termA = termB
    termB = next

    ptr++
    count--
}

println "Fibonacci computation complete!"
```

---

## Example 3: Software Multiplication (`multiply.n`)

Computes $12 \times 7 = 84$ using repeated addition in a high-level loop.

```text
// ============================================
// Program: multiply.n
// Description: Computes multiplicand * multiplier via addition
// ============================================

println "Multiplication Routine"

var a = 12        // Multiplicand
var b = 7         // Multiplier
var result = 0    // Result accumulator

while b > 0 {
    result += a
    b--
}

println "Result (12 * 7):"
println result
```

---

## Example 4: Memory Buffer & Array Sum (`array_sum.n`)

Initializes a 5-element array using a Lua-style table (`{ 10, 20, 30, 40, 50 }`), then iterates over the array using dynamic index indexing to compute the total sum.

```text
// ============================================
// Program: array_sum.n
// Description: Populates table array and sums elements
// ============================================

println "Initializing array in RAM..."

var numbers = { 10, 20, 30, 40, 50 }
var sum = 0
var i = 0

while i < 5 {
    sum += numbers[i]
    i++
}

println "Array Sum:"
println sum
```

---

## Example 5: BIOS Boot Routine (`bios_boot.n`)

Simulates a computer firmware POST (Power-On Self-Test) routine suitable for burning into the ROM of an integrated System-on-Chip (SoC).

```text
// ============================================
// Program: bios_boot.n
// Description: Silicon-OS POST & System Boot
// ============================================

println "[POST] Silicon BIOS 1.0"
println "[POST] Checking CPU Registers..."

var regA = 0xAA
var regB = 0x55
println "Reg A Test OK"
println "Reg B Test OK"

println "[POST] Testing RAM Banks..."
mem[0x80] = 0xFF
if mem[0x80] != 0xFF {
    println "[FATAL] Memory verification failed!"
    halt
}
println "RAM Bank 0 Passed."

println "[SYSTEM] Loading OS Kernel..."
var step = 0
while step < 3 {
    step++
}

println "[SYSTEM] SiliconCraft Personal Computer Ready."
```

---

## Example 6: Framebuffer Direct Pixel Graphics (`graphics_demo.n`)

Draw geometric primitives (filled rectangles, diagonal lines, and pixels) directly onto the $240 \times 135$ (16:9) CRT pixel canvas and renders the frame in an active display loop.

```text
// ============================================
// Program: graphics_demo.n
// Description: Renders 2D geometric shapes on CRT (240x135 16:9)
// ============================================

// Switch CRT display to graphics framebuffer mode (1 = graphics, 0 = text)
display.mode(1)

// Clear canvas to dark slate (ARGB: 0xFF181824)
display.clear(0xFF181824)

// Draw a blue title banner rectangle (x=10, y=10, w=220, h=16)
display.rect(10, 10, 220, 16, 0xFF2A4494)

// Draw diagonal crosshairs across the canvas
display.line(0, 0, 239, 134, 0xFF44AA44)
display.line(0, 134, 239, 0, 0xFF44AA44)

// Draw an accent gold square in the center
display.rect(110, 57, 20, 20, 0xFFFFCC00)

// Plot a single white pixel in the center of the gold box
display.set(120, 67, 0xFFFFFFFF)

// Flip buffer to display on screen
display.swap()

// Keep display running in an active loop
while true {
}
```

---

## Example 7: Audio Buffer & Melody Playback (`audio_tune.n`)

Queues musical note frequencies into the hardware audio buffer and initiates playback on the local computer and adjacent Speaker blocks.

```text
// ============================================
// Program: audio_tune.n
// Description: Plays an arpeggiated chime melody
// ============================================

println "Synthesizing chime chord..."

// Queue musical notes: frequency (Hz), duration (ticks)
audio.tone(440, 8)    // A4
audio.tone(554, 8)    // C#5
audio.tone(659, 8)    // E5
audio.tone(880, 16)   // A5

// Fire audio playback
audio.play()

println "Playback dispatched to speaker!"
```

---

## Example 8: Interrupt & Fault Exception Handling (`interrupt_demo.n`)

Demonstrates registering handlers in the Interrupt Vector Table (IVT) using `catch` (for hardware exceptions like `DIV_ZERO`) and `on_key` (for real-time keyboard events).

```text
// ============================================
// Program: interrupt_demo.n
// Description: Recovers from divide-by-zero & handles keys
// ============================================

// Register IVT handlers:
// Vector 0 = DIV_ZERO exception
// Vector 10 = KEYBOARD interactive interrupt
catch(0, handle_div_zero)
on_key(handle_key)

// Enable hardware interrupts
STI

println "Testing exception handler..."

// Intentional fault: divide 100 by 0
A = 100
B = 0
DIV A, B              // Triggers Vector 0 fault

// Execution resumes here after handler returns
print "Division result after recovery: "
println A
println "Now waiting for keyboard input..."

while true {
}

// --- Handlers ---
fn handle_div_zero() {
    println "[INTERRUPT] Caught DIV_ZERO exception!"
    B = 2             // Fix divisor
    A = 50            // Set safe quotient
    IRET              // Resume execution after fault
}

fn handle_key() {
    // Register A holds the typed character code
    print "Key pressed: "
    OUTC A
    OUTC 10
    IRET
}
```

---

## Example 9: Storage Drive Persistence (`storage_io.n`)

Demonstrates writing binary data to persistent Storage Drives (Flash / NVMe) that remain stored in NBT even when the drive is detached.

```text
// ============================================
// Program: storage_io.n
// Description: Writes persistent bytes to Storage Drive
// ============================================

var drives = storage.count()
if drives == 0 {
    println "[ERROR] No storage drive inserted!"
    halt
}

print "Found installed Storage Drive(s): "
println drives

// Select Drive 0
storage.select(0)

// Write magic byte signature 'SC' (0x53, 0x43) at address 0x00
storage.write(0x00, 0x53)
storage.write(0x01, 0x43)

// Read bytes back from drive into registers
B = storage.read(0x00)
C = storage.read(0x01)

print "Read magic signature: "
OUTC B
OUTC C
OUTC 10
println "Data persisted successfully."
```

---

## Example 10: Running Code from Storage Drive (`bootloader.n` & `os_kernel.n`)

Demonstrates probing for installed storage drives (Flash or NVMe) and chainloading/executing a program burned to **Storage Drive X** using `storage.exec(d)`.

Registers and RAM are preserved across chainloading, allowing the bootloader to pass system parameters (such as the boot drive index and handoff signatures) directly into the loaded program.

### Part 1: Bootloader (`bootloader.n`)
*Burned into SoC ROM (BIOS)*

```text
// ============================================
// Program: bootloader.n
// Description: BIOS bootloader that launches OS from Storage Drive
// ============================================

println "[BIOS] SiliconCraft Bootloader 1.0"
println "[BIOS] Searching for bootable Storage Drive..."

// Check if Storage Drive 1 (Expansion Slot 1: Flash or NVMe) is installed
var bootDrive = 1
var cap = storage[1].size()

if cap == 0 {
    // Fallback: Check if Storage Drive 0 (Onboard SoC Flash) is installed
    bootDrive = 0
    cap = storage[0].size()
}

if cap == 0 {
    println "[FATAL] No bootable storage drive detected!"
    halt
}

print "[BIOS] Booting from Storage Drive: "
println bootDrive
print "[BIOS] Drive capacity (bytes): "
println cap

println "[BIOS] Chainloading program..."

// Pass boot parameters to the loaded OS kernel in registers:
// Register A: Boot drive index
// Register B: Handshake magic signature (0x5343 = 'SC')
A = bootDrive
B = 0x5343

// Chainload and execute program burned to boot drive (Flash or NVMe)
storage.exec(A)
```

### Part 2: Operating System Kernel (`os_kernel.n`)
*Burned to Storage Drive 1 (Flash or NVMe) using the Programmer*

```text
// ============================================
// Program: os_kernel.n
// Description: OS Kernel burned onto Storage Drive
// ============================================

println "=========================================="
println "       SILICON-OS KERNEL INITIALIZED      "
println "=========================================="

// Inspect boot parameters passed by the bootloader in registers:
print "Booted from Storage Drive: "
println A

if B != 0x5343 {
    println "Warning: Bootloader signature mismatch!"
}
if B == 0x5343 {
    println "Bootloader handoff signature verified: OK"
}

println "Silicon-OS kernel running from Storage Drive."
println "System ready for commands."
```

---

## Example 11: Audio Buffer – Jingle Bells (`jingle_bells.n`)

Plays the opening melody of *Jingle Bells* ("Jingle bells, jingle bells, jingle all the way") using the hardware audio buffer, outputting sound through the personal computer and adjacent Speaker blocks.

```text
// ============================================
// Program: jingle_bells.n
// Description: Plays the opening of Jingle Bells
// Notes: E4, E4, E4 | E4, E4, E4 | E4, G4, C4, D4, E4
// ============================================

println "=========================================="
println "       SILICON AUDIO: JINGLE BELLS        "
println "=========================================="
println "Synthesizing Jingle Bells melody..."

// Flush any existing audio queue
audio.clear()

// Phrase 1: "Jin-gle bells" (E4 = 330 Hz)
audio.tone(330, 70)
audio.tone(330, 70)
audio.tone(330, 140)

// Phrase 2: "Jin-gle bells" (E4 = 330 Hz)
audio.tone(330, 70)
audio.tone(330, 70)
audio.tone(330, 140)

// Phrase 3: "Jin-gle all the way" (E4, G4, C4, D4, E4)
// E4 (330 Hz), G4 (392 Hz), C4 (262 Hz), D4 (294 Hz), E4 (330 Hz)
audio.tone(330, 70)
audio.tone(392, 70)
audio.tone(262, 70)
audio.tone(294, 70)
audio.tone(330, 160)

// Trigger audio playback through computer/speaker
audio.play()

println "Jingle Bells dispatched to audio buffer!"
```
