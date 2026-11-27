# Chapter 1: Getting Started

This guide walks you through building, programming, and expanding your first computer from raw materials in Minecraft.

## Chapter contents

- This guide: materials, manufacturing, programming tools, ROM, SoCs, computers, and expansion.
- [Peripheral wiring](peripherals.md): direct ports, serial cables, and expansions.
- [Optional integrations](integrations.md): supported blocks and behavior without integration mods.
- [Server network settings](network-settings.md): transports, destination policy, TLS, and listeners.

When your computer is ready, continue with [Assembly](../assembly/README.md) or [N2](../n-language/n2/README.md).

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
6. **Diamond**: Required as the NVMe controller when forging ultra-fast Storage drives.
7. **Note Block**: Required for crafting the Speaker block.

---

## 2. Architecture Writer (Designing CPU & GPU Architectures)

Before forging a CPU microchip or assembling a GPU, you design its supported instruction or graphics-operation blueprint at the **Architecture Writer** drafting desk.

1. Craft and place an **Architecture Writer** block.
2. Open its interface.
3. Click **`Mode: CPU` / `Mode: GPU`** to choose whether you are drafting a **CPU Architecture Writeup** (for the Chip Forge) or a **GPU Architecture Writeup** (for the GPU Assembler).
4. Enter your custom **Architecture Name** (e.g. `RISC-1`, `GPU-1`).
5. Select which instructions (`NOP`, `MOV`, `ADD`, `OUT`, `HLT`, etc. in CPU mode) or graphics operations (`FBCLR`, `GPUCLR`, `GPURECT`, `GPUCIRC`, `GPUTRI`, `GPUVRAM`, `GPUBLIT`, `GPUSWAP`, etc. in GPU mode) your design will support from the scrollable list.
6. Insert a piece of **Paper** (or a blank writeup) into the top-left input slot.
7. Click **Write Arch**.
8. Retrieve your newly drafted **Architecture Writeup** item! Hovering over it displays its mode (`CPU` or `GPU`), architecture name, and supported operation count.

---

## 3. Chip Forge (Fabricating the CPU)

The **Chip Forge** manufactures custom CPU microchips according to a **CPU Architecture Writeup** blueprint.

1. Open the Chip Forge GUI.
2. Insert **Silicon Powder** (top-left) and **Redstone Dust** (top-mid). Add fuel or connect FE.
3. Place your **CPU Architecture Writeup** into the blueprint slot (bottom-mid, below Redstone).
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

## 5. GPU Assembler (Dedicated Graphics Processor)

The **GPU Assembler** fabricates a dedicated **GPU** expansion card from a reusable **GPU Architecture Writeup**, **Silicon Powder**, **Redstone Dust**, and optional RAM modules:

1. Draft a **GPU Architecture Writeup** at the **Architecture Writer** in **`Mode: GPU`**.
2. Open the **GPU Assembler** GUI.
3. Insert **Silicon Powder** (top-left) and **Redstone Dust** (top-right of the 2×2 input grid), and place your **GPU Architecture Writeup** in the blueprint slot (bottom-right of the 2×2 grid). Material requirements scale with the number of selected GPU operations (`min(12, 1 + ops / 4)` Silicon Powder and `min(16, 1 + ops / 3)` Redstone Dust).
4. Insert **0 to 8 RAM modules** into the VRAM slots. Each RAM module adds **2048 KB (2 MB)** of dedicated GPU VRAM (up to **16 MB**) and scales GPU compute throughput.
5. Connect **Forge Energy (FE)** or insert furnace fuel into the fuel slot (bottom-left of the 2×2 grid). Required energy (`200 + opCount * 60 + ramCount * 250` FE) and assembly time (`40 + opCount * 8 + ramCount * 20` ticks) scale with the GPU architecture size and installed RAM modules.
6. Click **Assemble** and retrieve your **GPU** from the output slot.
   > **Note**: Just like in the Chip Forge, the **GPU Architecture Writeup is NEVER consumed** during assembly and can be reused indefinitely.

---

## 6. Managing & Flashing Programs

Starting in **SiliconCraft 0.2.0**, code compilation happens externally so large N2 and Assembly projects can be built with standard developer tooling without stalling the game server.

### Step 1: Compile Externally to a `.sc` Package
Use **`scc`** (see [External N2 Compiler (`scc`), Make & CMake](../n-language/n2/compiler.md)) on your computer:
- **N2 Programs & Projects**: Compile single `.n2` files or multi-file folders (`main.n2`, modules, `-I` library directories, **Make**, or **CMake**) into `<name>.sc`.
- **Assembly Programs**: Assemble `.asm` / `.s` files and multi-file assembly projects (`scc asm main.asm -o boot.sc`) into `<name>.sc`.
- **N1 Discontinued**: Legacy `.n` files are no longer supported in 0.2.0.

> **Legacy Blocks**: Existing **Code Compiler**, **Project Compiler**, and **Assembly Writer** blocks no longer open a UI or compile code; they are kept as decorative facing blocks for world compatibility.

### Step 2: Import, Rename & Remove with the Project Manager
1. Craft and place a **Project Manager** block.
2. Open its interface:
   - **`+ Add .sc`**: Opens a file picker to import a compiled `.sc` package into your world's Program Library. Large projects are compressed and transferred automatically via chunked upload.
   - **`Rename`**: Type a new name in the input box and click **Rename** to rename the selected project.
   - **`Remove`**: Delete the selected project from your Program Library.

### Step 3: Flash Hardware with the Programmer
The **Programmer** writes imported `.sc` programs to physical chips and drives:
1. Open the **Programmer** block.
2. Insert either a blank **ROM Chip** OR a **Storage Drive** (Flash / NVMe) into the input slot.
3. The right panel displays the scrollable list of imported programs in your library.
4. Click any program to select it.
5. Click **⚡ Burn to ROM** (or **⚡ Write to Drive**). The flashed item appears in the output slot!
6. **Deleting Programs**: To remove an obsolete program from your library, select it and click **Delete** (or use **Remove** in the Project Manager).

---

## 7. SoC Manufacturer (System-on-Chip)

The **SoC Manufacturer** integrates your components into a unified processor package:

- **Wood Slab**: Substrate base
- **Silicon Powder & Gold Ingot**: Traces and bus logic
- **Chip (CPU)**: Your custom-forged microprocessor
- **ROM (BIOS)**: Your flashed ROM chip
- **RAM Slot (Optional)**: Accepts up to **4 RAM modules** at once! The machine consumes all placed modules (1 to 4), scaling the SoC's internal RAM capacity accordingly.
- **Storage Slot (Optional)**: Accepts a Flash Storage drive to integrate built-in storage directly onto the SoC.
- **GPU Slot (Optional)**: Accepts **1 GPU** card to integrate an onboard graphics processor directly onto the SoC.

Click **Assemble SoC** to generate your integrated **SoC**.

---

## 8. Personal Computer Setup & Hardware Expansion

1. Place a **Computer Case** block in the world.
2. Right-click the Computer Case holding your **SoC** item.
3. The case transforms into a fully functional **Personal Computer**!

### Expanding Hardware in the PC GUI
Opening the Personal Computer interface provides dedicated hardware expansion slots:
- **8 RAM Expansion Slots**: Insert up to 8 RAM modules (1 per slot). Each module provides **2048 KB**, for up to **16,384 KB** of expansion RAM.
- **2 NVMe / Storage Drive Slots**: Insert up to 2 individual Storage Drives (1 per slot).
  - Both **Flash** and **NVMe** drives provide an expansive **2048 MB** (2,147,483,648 bytes / 2 GiB) capacity per drive!
  - Each drive is treated independently (indexed as drive `0` and drive `1`).
  - Storage drives retain their binary content in item NBT when extracted and moved!
- **1 GPU Expansion Slot**: Insert **1 GPU** card to add hardware-accelerated graphics to the computer.
  - **1 GPU Limit**: Only **1 GPU** is allowed per SoC or computer. If the installed SoC already has an onboard GPU, the PC's GPU expansion slot is locked so a second GPU cannot be inserted.

### Framebuffer Graphics & GPU Acceleration
The Personal Computer includes a dedicated **$960 \times 540$ CRT Framebuffer** (`VirtualGpu` when a GPU is installed, or the CPU software framebuffer otherwise), streamed asynchronously off the 20 TPS server tick for >20 FPS updates:
- **Graphics Handler Selector (`GFX:AUTO` / `GFX:CPU` / `GFX:GPU`)**: Click the `GFX:...` button in the PC GUI (or use `GFXSEL` / `<Glass>` helpers in software) to choose whether framebuffer drawing runs on the CPU or the dedicated `VirtualGpu`.
  - **`AUTO` (Default)**: Automatically routes framebuffer graphics and `<Glass>` calls through `VirtualGpu` when a GPU is installed, falling back to the CPU software framebuffer when no GPU is present.
  - **`CPU`**: Forces standard `FB*` rendering onto the CPU's software framebuffer even when a GPU is installed.
  - **`GPU`**: Targets `VirtualGpu` explicitly (available when a GPU is installed).
- **GPU Hardware Acceleration**: When using a GPU, `VirtualGpu` executes queued drawing commands (`GPUCLR`, `GPURECT`, `GPULINE`, `GPUCIRC`, `GPUTRI`, `GPUVRAM`, `GPUBLIT`, `GPUSWAP`, `GPUSYNC`) on a dedicated JIT-accelerated background worker (`162+ MHz` target) with double-buffered presentation and dedicated VRAM.

### Audio Buffer & Speaker Routing Rules
- The computer features an 8000 Hz, 8-bit unsigned PCM audio synthesis buffer (`APUSH`, `ATONE`, `APLAY`, `ACLR`, `ASTAT`).
- **Speaker vs. Screen Routing**:
  - **No Speaker Connected**: Audio plays **only** to players who currently have the computer screen GUI open (`ComputerMenu`).
  - **Speaker Connected** (adjacent `SpeakerBlock`): Audio is broadcast to nearby players within a **32-block radius** (`distanceToSqr <= 32 * 32`) and anyone with the screen GUI open.

### Operating the Computer & Keystroke Isolation
- **Power Button**: Boots the CPU on background worker threads. The terminal outputs BIOS information, clock cycles, and any strings printed with `OUT`.
- **Reset Button**: Resets registers, flags, audio, and restarts PC from line `0`.
- **Step Button**: Executes a single clock cycle / instruction for interactive debugging.
- **GFX Handler Button**: Cycles graphics output handling between `GFX:AUTO`, `GFX:CPU`, and `GFX:GPU`.
- **Keystroke Isolation**: Buttons automatically lose selection when your cursor moves away. This prevents accidental UI button activation when typing interactive terminal keystrokes into your running program!
- **CPU Registers & Flags**: Live displays for `PC`, `A`, `B`, `C`, `D`, `Z` (Zero Flag), and `S` (Sign Flag).

### Upgrading or Removing the SoC
To upgrade or retrieve your SoC:
- **Shift + Right-Click** the Computer block with an **empty main hand**.
- The SoC will be safely ejected back into your inventory, and the block will revert to an empty Computer Case.

---

## 9. Crafting Recipes Reference

### GPU Assembler
```
ICI
RFR
ISI
```
- **I**: Iron Ingot
- **C**: Copper Ingot
- **R**: Redstone Dust
- **F**: Blast Furnace
- **S**: Silicon Powder

### Project Manager
```
IGI
RCR
ISI
```
- **I**: Iron Ingot
- **G**: Glass Pane
- **R**: Redstone Dust
- **C**: Chest
- **S**: Silicon Powder

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

## 10. Interactive Input, Interrupts & Exceptions

### Interactive CRT Display
When you open the **Personal Computer** interface:
- **Keyboard Input**: Typing characters while focused on the screen fires the `KEYBOARD` interrupt (Vector 10), passing the character code into register `A`.
- **Mouse Movement**: Moving your cursor over the CRT display area (`(0, 0)` to `(959, 539)`) fires `MOUSE_MOVE` (Vector 11) with coordinates in `A` (X) and `B` (Y).
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

## 11. Jade HUD Integration

SiliconCraft includes native support for the **Jade** (Just Enough Details) on-screen HUD mod, providing real-time in-world telemetry when looking at blocks:

- **Personal Computer**:
  - Live CPU State (`[OFF]`, `[RUNNING]`, `[PAUSED]`, `[HALTED]`, `[ERROR]`).
  - Active error messages if faulted.
  - Installed SoC details (opcode count, ROM program).
  - Effective RAM capacity, GPU presence/VRAM/handler mode, and Storage drives installed.
  - Cycle counter and Display mode (Text vs Framebuffer Direct Pixel).
- **Forges & Machines**:
  - Real-time Forge Energy (`FE`) storage and capacity.
  - Solid fuel burn ticks remaining.
  - Interactive progress bars and percentage completion when forging chips, memory, assembling GPUs, or synthesizing SoCs.
  - Active blueprints, target memory modes, and compiler architecture writeups.
- **Speaker**:
  - Displays whether the speaker is connected to an adjacent Personal Computer along with the computer's coordinates.

Drive data is stored in sparse files under `siliconcraft/drives` inside the world directory. Include that folder when backing up or moving a world. Drive items identify their backing file within that world.


[All chapters](../README.md)
