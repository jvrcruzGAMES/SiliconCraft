# SiliconCraft

[![Minecraft Version](https://img.shields.io/badge/Minecraft-1.21.1-brightgreen.svg)](https://neoforged.net/)
[![Mod Loader](https://img.shields.io/badge/Loader-NeoForge-orange.svg)](https://neoforged.net/)
[![Java](https://img.shields.io/badge/Java-21-blue.svg)](https://www.oracle.com/java/)

**SiliconCraft** is a modular personal computing, microcontroller, and custom silicon architecture mod for Minecraft (NeoForge 1.21.1).

Design your own silicon chips from raw wafers, assemble modular personal computers, write programs in the high-level **N language** or native assembly, render hardware-accelerated 960×540 retro graphics, synthesize 8-bit PCM audio, connect physical gamepads, and automate your world with custom virtual hardware—with the virtual CPU running on background workers and world interactions handled by the server tick.

---

## ⚡ Key Features

### 🖥️ Modular Personal Computers
- **Custom Silicon Core**: Assemble a PC by inserting a fabricated **SoC (System-on-Chip)** into a **Computer Case**.
- **Expansion Bays**:
  - **8 RAM Slots**: Expand memory by up to **16,384 KB** (16 MB) using forged RAM modules.
  - **2 Storage Slots**: Support both non-volatile **Flash Storage** and high-speed **NVMe SSDs** (up to 2,048 MB per drive).
- **Dual Display Modes**:
  - **Text Console Mode**: Crisp 40×13 retro terminal output with syntax coloring for logs, BIOS messages, and shells.
  - **Framebuffer Mode**: 960×540 resolution with 32-bit ARGB color for games, raycasters, and animations.
- **Full State Persistence**:
  - CPU registers, flags, call/data stacks, interrupt tables (IVT), and RAM contents are **compressed and saved to disk**.
  - Your programs keep running across world saves, reloads, and dimensional travel without losing RAM contents or resetting.
- **Easy Dismantling**:
  - Sneak + Right-Click with an empty hand to cleanly dismantle a Personal Computer back into a Computer Case, safely dropping the installed SoC and addon modules.

---

### 🔬 Silicon Inscription & Architecture Synthesis
- **Photolithography in Minecraft**: Use the **Silicon Inscriber** with silicon wafers, gold wire, and redstone to synthesize custom instruction sets.
- **Bespoke Instruction Sets**:
  - Choose exactly which of the 71 instructions your CPU supports (Arithmetic, Logic, Stack Operations, Subroutines, Storage IO, Graphics, Audio, Interrupts).
  - Create anything from a minimal 3-instruction microcontroller up to a full-featured 70-instruction computing monster.
- **Integrated BIOS / ROM**: Burn bootloader assembly directly onto the silicon wafer during synthesis.

---

### 💾 Memory Forge & Programmer
- **Memory Forge**: Construct high-capacity RAM sticks (2,048 KB each) and solid-state drives (Flash drives and high-throughput NVMe SSDs).
- **Programmer**:
  - Browse, inspect, and burn software onto removable storage drives.
  - Tooltips clearly show the program's name, description, and required CPU instructions.
  - Hold **SHIFT** to view the full, detailed opcode breakdown.

---

### 🔊 8-Bit PCM Audio & World Speakers
- **8000 Hz, 8-Bit Audio**: Real-time sound synthesis and music playback (`APLAY`, `ATONE`, `APUSH`).
- **Private vs. World Broadcast**:
  - **Screen Audio**: Plays privately to players currently viewing the computer GUI.
  - **Speaker Block**: Place a Speaker block adjacent to a Personal Computer to broadcast audio across a 32-block radius in the world.
- **Instant Cutoff**: Audio stops immediately when machines are powered off or dismantled.

---

### 🕹️ Gamepad & Controller Support
- **12-Button Standard Gamepad (`INPAD`)**:
  - Read input for up to 4 virtual controllers in software (`PAD_UP`, `PAD_DOWN`, `PAD_LEFT`, `PAD_RIGHT`, `A`, `B`, `X`, `Y`, `L`, `R`, `START`, `SELECT`).
- **Keyboard Emulation (Built-in)**:
  - Arrow Keys / WASD, Space / Z (Jump / B), X (Spin / A), C / J (Run / Y), V / K (Action / X), Q / E (Shoulders), Enter (Start), Shift (Select).
- **External Controller Integration**:
  - Seamlessly connect your physical gamepad (Xbox, PlayStation, Nintendo Switch, Steam Deck) when **Controlify** is installed.

---

### ⚡ Threading & Performance Architecture
- **Off-Thread Execution**: CPU execution is offloaded from Minecraft's main tick loop to clustered background worker threads.
- **Zero World Lag**: Computers execute up to 2.5 MHz clock speeds while consuming less than 5% of a CPU core.
- **Clean Unloading**: Zero hang times on game save, chunk unload, or forced server shutdown.

---

## 🔌 Optional Dependencies & Integrations

SiliconCraft works completely out-of-the-box as a standalone mod, but seamlessly unlocks enhanced features when the following optional mods are installed:

| Mod | Platform | Type | What It Unlocks |
|---|---|---|---|
| **[Controlify](https://www.curseforge.com/minecraft/mc-mods/controlify)** | CurseForge / Modrinth | Client | **External Controller Support**: Enables physical gamepad input. Connect Xbox, PlayStation, Switch Pro, Steam Deck, or generic USB/Bluetooth controllers to play computer games directly on the screen. (Disabled if Controlify is not present). |
| **[Jade](https://www.curseforge.com/minecraft/mc-mods/jade)** | CurseForge / Modrinth | Client & Server | **In-World HUD Tooltips**: Look at any Personal Computer, Computer Case, Speaker, or Machine to see CPU state (`[RUNNING]`, `[OFF]`, `[ERROR]`), SoC opcode count, installed RAM, storage capacity, cycle count, and active error messages. |
| **[JEI (Just Enough Items)](https://www.curseforge.com/minecraft/mc-mods/jei)** | CurseForge / Modrinth | Client & Server | **Recipe & Machine Lookup**: Full recipe browsing and integration for the Silicon Inscriber, Memory Forge, and Programmer workstations. |

---

## 🛠️ Quick Start Guide

### 1. Synthesize a System-on-Chip (SoC)
1. Place a **Silicon Inscriber**.
2. Insert a **Silicon Wafer**, **Gold Ingot**, and **Redstone Dust**.
3. Select your desired CPU architecture profile (or customize opcodes and BIOS code).
4. Fabricate your synthesized **SoC**.

### 2. Assemble Your Computer
1. Place a **Computer Case** on the ground.
2. Right-click the case with your synthesized **SoC** in hand. The case will transform into a **Personal Computer**!
3. Right-click the Personal Computer to open the chassis screen.
4. Insert **RAM Modules** into the green RAM bays (up to 8 sticks) and **Storage Drives** into the blue NVMe/Flash bays.

### 3. Power On & Run Programs
1. Click the **⏻ ON** button to boot your computer.
2. The onboard BIOS will initialize hardware, test RAM, mount drives, and launch the primary program.
3. Play games using your keyboard or plug in a controller with **Controlify** installed!

---

## 📋 Technical Specifications

| Component | Specification |
|---|---|
| **Architecture** | 16/32-Bit Register & Stack Architecture |
| **Clock Speed** | ~2.5 MHz (cooperative time-sliced execution) |
| **RAM Module Capacity** | 2,048 KB (2 MB) per module, up to 16,384 KB across eight expansion slots |
| **Storage Capacity** | Up to 2,048 MB (2 GiB) Flash / NVMe per slot |
| **Display (Text)** | 40 Columns × 13 Rows CRT Phosphor Console |
| **Display (Graphics)** | 960 × 540 Pixels, 32-Bit ARGB Color |
| **Audio Format** | 8,000 Hz, 8-Bit Unsigned PCM Mono |
| **Audio Range** | Screen GUI (local) or 32-block radius (Speaker Block) |
| **Controller Support** | 12-button standard gamepad (Keyboard or Controlify) |
| **Interrupts** | 15 Vector Table (Div0, Illegal Op, Bounds, Stack, Timer, Keyboard, Mouse, Reset) |

---

## 📜 License

SiliconCraft is distributed under the project's open-source license. Contributions, issues, and feature requests are welcome!

## Sable compatibility

SiliconCraft computers and machines can be assembled into Sable sub-levels with their inventories intact. Adjacent peripherals stay on the same structure, and positional speaker audio follows the structure. Sable is optional and AeroSilicon is only needed for its airship-specific peripherals.

## N2 networking

N2 supports HTTP request and routing helpers, HTTP/3, WebSocket and secure WebSocket, and raw TCP and UDP sockets. The Minecraft server runs connections and controls whether guest programs can open listening ports. [Network guide](docs/network.md)

## Peripherals

Connect built-in peripherals to computers directly or through Serial Cables and Serial Expansions. Programs use the same peripheral instructions from Assembly and N2 DeviceKit. [Peripheral guide and examples](docs/peripherals.md)

## AeroSilicon

[AeroSilicon](aerosilicon/README.md) adds airship telemetry and named Redstone Link channels as SiliconCraft peripherals.
