# Getting Started with SiliconCraft

This guide walks you through building your first computer from raw materials in Minecraft.

---

## 1. Raw Materials & Gathering

1. **Silicon Ore**: Mine Silicon Ore underground (drops **Silicon Powder**).
2. **Gold Ingots**: Needed for traces and pin headers in memories and SoCs.
3. **Redstone Dust**: Used as the semiconductor doping agent in chip forging.
4. **Coal / Charcoal or FE**: All SiliconCraft machines accept furnace fuel (Coal, Charcoal, etc.) or Forge Energy (FE).
5. **Wood Slabs**: Used as the substrate board in the SoC Manufacturer.
6. **Diamond** *(Optional)*: Required as the NVMe controller when forging high-speed Storage drives.

---

## 2. Chip Forge (Fabricating the CPU)

## 2. Architecture Writer (Designing CPU Architecture)

Before forging a microchip, you design its Instruction Set Architecture (ISA) at the **Architecture Writer** drafting desk.

1. Craft and place an **Architecture Writer** block.
2. Open its interface.
3. Enter your custom **Architecture Name** (e.g. `RISC-1`, `Alpha-8`, `JVRC-CPU`).
4. Select which instructions your architecture will support from the scrollable opcode list with checkmarks (e.g. `NOP`, `MOV`, `ADD`, `SUB`, `INC`, `DEC`, `CMP`, `JMP`, `JZ`, `JNZ`, `OUT`, `HLT`).
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

---

## 4. Memory Forge (RAM, ROM & Storage)

The **Memory Forge** manufactures system memory modules:

1. Insert **Silicon Powder** and **Gold Ingots** into the input slots.
2. Select your memory type:
   - **RAM**: Provides execution workspace for variables and stack memory.
   - **ROM**: Read-Only Memory designed to be flashed with your assembly boot program.
   - **Drive**: Persistent storage module. Toggle **NVMe: ON** and insert a **Diamond** to build an ultra-fast NVMe storage drive.
3. Click **Forge** to produce the memory module.

---

## 5. Writing, Compiling & Flashing Programs

SiliconCraft separates program writing and compilation from physical ROM burning, ensuring your CPU hardware matches your software instructions before spending resources.

### Option A: High-Level Programming with the Code Compiler
1. Write a script in the `.n` language (e.g. `main.n`) on your computer (see [n-language-guide.md](n-language-guide.md)).
2. Place and open the **Code Compiler** block.
3. Insert your **Architecture Writeup** into the slot.
4. Click **📁 Choose .n File** to select your `.n` source.
5. The compiler verifies syntax and validates every required opcode against your architecture writeup.
6. Click **[+ Compile & Add]** to compile it to assembly and store it into the world's program library.

### Option B: Assembly Programming with the Assembly Writer
1. Write your `.s` or `.asm` file on your computer (see [assembly-syntax.md](assembly-syntax.md)).
2. Place and open the **Assembly Writer** block.
3. Insert your **Architecture Writeup** into the slot.
4. Click **📁 Choose .s / .asm** to load the file.
5. The validator verifies that all opcodes in your assembly program are supported by the writeup.
6. Click **[+ Add to Programs]** to register it into the world's program library.

### Flashing ROMs with the ROM Programmer
1. Open the **ROM Programmer** block.
2. Insert a blank **ROM** chip and solid fuel or FE.
3. The right panel displays the scrollable list of all registered programs in your library.
4. Click any program to select it.
5. Click **⚡ Burn to ROM**. The flashed ROM chip will appear in the output slot, ready for SoC assembly!

---

## 6. SoC Manufacturer (System-on-Chip)

The **SoC Manufacturer** combines all components onto a single board:

- **Wood Slab**: Substrate base
- **Silicon Powder & Gold Ingot**: Traces and bus logic
- **Chip (CPU)**: Your custom-forged microprocessor
- **ROM (BIOS)**: Your flashed ROM chip
- **RAM**: Memory module
- **Storage** *(Optional)*: NVMe or standard drive

Click **Assemble SoC** to generate your integrated **SoC**.

---

## 7. Personal Computer Setup

1. Place a **Computer Case** block in the world.
2. Right-click the Computer Case holding your **SoC** item.
3. The case will transform into a fully functional **Personal Computer**!

### Operating the Computer

- **Right-Click**: Opens the computer terminal screen.
- **Power Button**: Boots the CPU. The terminal outputs BIOS information, clock cycles, and any strings printed with `OUT`.
- **Reset Button**: Resets registers, flags, and restarts PC from line `0`.
- **Step Button**: Executes a single clock cycle / instruction for interactive debugging.
- **CPU Registers & Flags**: Live displays for `PC`, `A`, `B`, `C`, `D`, `Z` (Zero Flag), and `S` (Sign Flag).

### Upgrading or Removing the SoC

To upgrade or retrieve your SoC:
- **Shift + Right-Click** the Computer block with an **empty main hand**.
- The SoC will be safely ejected back into your inventory, and the block will revert to an empty Computer Case with zero duplicate items.
