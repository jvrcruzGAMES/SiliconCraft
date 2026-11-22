# Getting Started with SiliconCraft

This guide walks you through building, programming, and expanding your first computer from raw materials in Minecraft.

---

## 1. Raw Materials & Gathering

1. **Silicon Ore & Deepslate Silicon Ore**:
   - Naturally generated throughout the Overworld:
     - **Silicon Ore**: Generates in upper stone layers between Y: -16 and Y: 80 (trapezoidal distribution, replacing stone).
     - **Deepslate Silicon Ore**: Generates in deepslate layers between Y: -64 and Y: 0 (uniform distribution, replacing deepslate).
   - Mineable with an **Iron Pickaxe** or better. Drops **Silicon Powder** (or the ore block itself with Silk Touch).
   - Can also be smelted or blasted in a Furnace/Blast Furnace into Silicon Powder.
2. **Gold Ingots**: Needed for traces, pin headers, and bus logic in memory modules and SoCs.
3. **Redstone Dust**: Used as the semiconductor doping agent in chip forging and audio circuits.
4. **Coal / Charcoal or FE**: All SiliconCraft machines accept furnace fuel (Coal, Charcoal, etc.) or Forge Energy (FE).
5. **Wood Slabs & Planks**: Used as the substrate base in the SoC Manufacturer and speaker enclosures.
6. **Diamond**: Required as the NVMe controller when forging ultra-fast Storage drives, and for crafting the Project Compiler.
7. **Note Block**: Required for crafting the Speaker block.

---

## 2. Architecture Writer (Designing CPU Architecture)

Before forging a microchip, you design its Instruction Set Architecture (ISA) at the **Architecture Writer** drafting desk.

1. Craft and place an **Architecture Writer** block.
2. Open its interface.
3. Enter your custom **Architecture Name** (e.g. `RISC-1`, `Alpha-8`, `JVRC-CPU`).
4. Select which instructions your architecture will support from the scrollable opcode list with checkmarks (e.g. `NOP`, `MOV`, `ADD`, `SUB`, `INC`, `DEC`, `CMP`, `JMP`, `JZ`, `JNZ`, `OUT`, `OUTH`, `HLT`, `FBSET`, `APUSH`, etc.).
5. Insert a piece of **Paper** (or a blank writeup) into the top-left input slot.
6. Click **Write Architecture**.
7. Retrieve your newly drafted **Architecture Writeup** item! Hovering over it displays the architecture name and all supported instructions.

---

## 3. Chip Forge (Fabricating the CPU)

The **Chip Forge** manufactures custom microchips according to an Architecture Writeup blueprint.

1. Open the Chip Forge GUI.
2. Insert **Silicon Powder** (top-left) and **Redstone Dust** (top-mid). Add fuel or connect FE.
3. Place your **Architecture Writeup** into the blueprint slot (bottom-mid, below Redstone).
4. The right side of the screen displays your architecture name and instructions.
5. Click **Forge**.
6. Retrieve the forged **Chip**.
   > **Note**: The **Architecture Writeup is NEVER consumed** during fabrication! You can reuse it indefinitely to forge as many identical CPU chips as needed.
   > **Resource Efficiency**: Material costs scale with instruction set size, capped at a maximum of **16 Redstone Dust** and **12 Silicon Powder** per chip operation.

---

## 4. Memory Forge (RAM, ROM & Storage)

The **Memory Forge** manufactures system memory modules:

1. Insert **Silicon Powder** and **Gold Ingots** into the input slots.
2. Select your memory type:
   - **RAM**: Provides linear execution workspace for variables and stack memory.
   - **ROM**: Read-Only Memory designed to be flashed with your assembly boot program.
   - **Drive**: Persistent binary storage module. Toggle **NVMe: ON** and insert a **Diamond** to build an ultra-fast NVMe storage drive, or leave it off for a standard Flash storage drive.
3. Click **Forge** to produce the memory module.

---

## 5. Writing, Compiling & Flashing Programs

SiliconCraft separates program writing and compilation from physical ROM/Storage burning, ensuring your CPU hardware matches your software instructions before spending resources.

### Option A: High-Level Programming with the Code Compiler
1. Write a single `.n` file (e.g. `main.n`) on your computer (see [n-language-guide.md](n-language-guide.md)).
2. Place and open the **Code Compiler** block.
3. Insert your **Architecture Writeup** into the slot.
4. Click **📁 Choose .n File** to select your `.n` source.
5. The compiler verifies syntax and validates every required opcode against your architecture writeup.
6. Click **[+ Compile & Add]** to compile it to assembly and store it into the world's program library.

### Option B: Multi-File Projects with the Project Compiler
For complex applications spanning multiple files and subdirectories:
1. Craft and place the **Project Compiler** block.
2. Structure your `.n` project with `main.n` at the root as the entrypoint. Files can import other files via `import "path/file.n"` or `import module.name`. You can leave it as a folder or package it into a `.zip` archive.
3. Open the Project Compiler GUI, insert your **Architecture Writeup**, and click either **📁 .zip** or **📂 Folder** to select your project.
4. Click **[+ Compile Project]**. It resolves all dependencies and outputs `<project_name>.asm` directly into your Program Library!

### Option C: Assembly Programming with the Assembly Writer
1. Write your `.s` or `.asm` file on your computer (see [assembly-syntax.md](assembly-syntax.md)).
2. Place and open the **Assembly Writer** block.
3. Insert your **Architecture Writeup** into the slot.
4. Click **📁 Choose .s / .asm** to load the file.
5. The validator verifies that all opcodes in your assembly program are supported by the writeup.
6. Click **[+ Add to Programs]** to register it into the world's program library.

### Flashing with the Programmer
The **Programmer** writes your programs to physical chips and drives:
1. Open the **Programmer** block (formerly ROM Programmer).
2. Insert either a blank **ROM Chip** OR a **Storage Drive** (Flash / NVMe) into the input slot.
3. The right panel displays the scrollable list of all registered programs in your library.
4. Click any program to select it.
5. Click **⚡ Burn to ROM** (or **⚡ Write to Drive**). The flashed item appears in the output slot!
6. **Deleting Programs**: To remove an obsolete program from your library, select it and click the **Delete** button.

---

## 6. SoC Manufacturer (System-on-Chip)

The **SoC Manufacturer** integrates your components into a unified processor package:

- **Wood Slab**: Substrate base
- **Silicon Powder & Gold Ingot**: Traces and bus logic
- **Chip (CPU)**: Your custom-forged microprocessor
- **ROM (BIOS)**: Your flashed ROM chip
- **RAM Slot (Optional)**: Accepts up to **4 RAM modules** at once! The machine consumes all placed modules (1 to 4), scaling the SoC's internal RAM capacity accordingly.
- **Storage Slot (Optional)**: Accepts a Flash Storage drive to integrate built-in storage directly onto the SoC.

Click **Assemble SoC** to generate your integrated **SoC**.

---

## 7. Personal Computer Setup & Hardware Expansion

1. Place a **Computer Case** block in the world.
2. Right-click the Computer Case holding your **SoC** item.
3. The case transforms into a fully functional **Personal Computer**!

### Expanding Hardware in the PC GUI
Opening the Personal Computer interface provides dedicated hardware expansion slots:
- **4 RAM Expansion Slots**: Insert up to 4 RAM modules (1 per slot). Each module's capacity adds directly to the total RAM pool available to the SoC.
- **2 NVMe / Storage Drive Slots**: Insert up to 2 individual Storage Drives (1 per slot).
  - Each drive is treated independently (indexed as drive `0` and drive `1`).
  - Storage drives retain their binary content in item NBT when extracted and moved!

### Framebuffer Graphics (Direct Screen Canvas)
The Personal Computer includes a dedicated **$128 \times 64$ CRT Framebuffer**:
- Programs can draw direct pixels, lines, rectangles, or flip entire color buffers (`FBSET`, `FBLINE`, `FBRECT`, `FBSWAP`, or in N: `fb.set()`, `fb.rect()`, etc.).
- Rendering executes directly on the computer screen canvas without Minecraft GUI engine overhead.

### Audio Buffer & Speaker Block
- The computer features a 4-channel audio synthesis buffer (`APUSH`, `APLAY`, `ASTOP`, `ASTAT`).
- By default, audio plays through the computer screen CRT speaker.
- **Speaker Block**: Place a **Speaker** block adjacent to or connected to your computer to broadcast computer sound effects, music, and beeps into the surrounding Minecraft world!

### Operating the Computer
- **Power Button**: Boots the CPU. The terminal outputs BIOS information, clock cycles, and any strings printed with `OUT`.
- **Reset Button**: Resets registers, flags, audio, and restarts PC from line `0`.
- **Step Button**: Executes a single clock cycle / instruction for interactive debugging.
- **CPU Registers & Flags**: Live displays for `PC`, `A`, `B`, `C`, `D`, `Z` (Zero Flag), and `S` (Sign Flag).

### Upgrading or Removing the SoC
To upgrade or retrieve your SoC:
- **Shift + Right-Click** the Computer block with an **empty main hand**.
- The SoC will be safely ejected back into your inventory, and the block will revert to an empty Computer Case.

---

## 8. Crafting Recipes Reference

### Project Compiler
```
ABA
ACA
ABA
```
- **A**: Iron Ingot
- **B**: Diamond
- **C**: Code Compiler

### Speaker
```
ABA
CDC
ABA
```
- **A**: Iron Ingot
- **B**: Redstone Dust
- **C**: Wood Planks
- **D**: Note Block

---

## 9. Interactive Input, Interrupts & Exceptions

### Interactive CRT Display
When you open the **Personal Computer** interface:
- **Keyboard Input**: Typing characters while focused on the screen fires the `KEYBOARD` interrupt (Vector 10), passing the character code into register `A`.
- **Mouse Movement**: Moving your cursor over the CRT display area (`(0, 0)` to `(227, 81)`) fires `MOUSE_MOVE` (Vector 11) with coordinates in `A` (X) and `B` (Y).
- **Mouse Clicks**: Clicking within the CRT display canvas fires `MOUSE_CLICK` (Vector 12) with coordinates in `A` (X), `B` (Y), and the button in `C` (`0` for left-click, `1` for right-click).

### Exception Handling
Fatal errors that would previously cause an unexplained CPU halt now trigger CPU exceptions:
- **Divide by zero**: Vector 0 (`DIV_ZERO`)
- **Illegal instruction**: Vector 1 (`ILLEGAL_OPCODE`)
- **Memory out of bounds**: Vector 2 (`OUT_OF_BOUNDS`)
- **Stack overflow/underflow**: Vector 3 (`STACK_FAULT`)
- **General exception**: Vector 4 (`GENERAL_EXCEPTION`)

If an exception handler is registered using `IVEC` or `catch()`, the CPU invokes your handler. When the handler finishes with `IRET`, execution resumes safely after the faulting instruction.

If an exception is **unhandled**, the CPU enters the `ERROR` state and prints:
```text
[CPU EXCEPTION] Exception not handled: <exception description> at line <line>: '<lineCode>'
```

---

## 10. Jade HUD Integration

SiliconCraft includes native support for the **Jade** (Just Enough Details) on-screen HUD mod, providing real-time in-world telemetry when looking at blocks:

- **Personal Computer**:
  - Live CPU State (`[OFF]`, `[RUNNING]`, `[PAUSED]`, `[HALTED]`, `[ERROR]`).
  - Active error messages if faulted.
  - Installed SoC details (opcode count, ROM program).
  - Effective RAM capacity and Storage drives installed.
  - Cycle counter and Display mode (Text vs Framebuffer Direct Pixel).
- **Forges & Machines**:
  - Real-time Forge Energy (`FE`) storage and capacity.
  - Solid fuel burn ticks remaining.
  - Interactive progress bars and percentage completion when forging chips, memory, or synthesizing SoCs.
  - Active blueprints, target memory modes, and compiler architecture writeups.
- **Speaker**:
  - Displays whether the speaker is connected to an adjacent Personal Computer along with the computer's coordinates.
