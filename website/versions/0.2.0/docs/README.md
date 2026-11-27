# SiliconCraft documentation

This manual documents SiliconCraft **0.2.0**. The [documentation website](http://sc.jvrcruz.games/) keeps a separate manual for each published mod version, starting with **0.1.1**. Each version starts at `http://sc.jvrcruz.games/X.X.X/`, for example [0.2.0](http://sc.jvrcruz.games/0.2.0/). Select the version installed in your world before following its guides.

Follow the chapters in order to build a computer, then choose Assembly or N2 to program it.

| Chapter | Contents |
|---|---|
| **1. [Getting Started](getting-started/README.md)** | Materials, hardware manufacturing, GPU assembly, Project Manager (`.sc`), flashing, peripheral wiring, and server settings. |
| **2. [Assembly](assembly/README.md)** | Syntax, instruction reference, projects, peripheral methods, networking, and runnable examples. |
| **3. [N2](n-language/n2/README.md)** | N2 language guide, libraries, projects, external compiler (`scc` / Make / CMake), DeviceKit peripherals, networking, and examples. |

## Version 0.2.0

- **Graphics Card (`VirtualGpu`)**: Dedicated graphics acceleration with double-buffered rasterization, VRAM sprite storage/blitting (`GPU*` instructions), and automatic `<Glass>` acceleration (maximum 1 GPU per SoC or computer). Toggle the **Architecture Writer** between **CPU** and **GPU** modes to draft a reusable **GPU Architecture Writeup**, then fabricate the GPU at the **GPU Assembler** with **Silicon Powder**, **Redstone Dust**, and up to 8 optional RAM modules for VRAM. Switch handlers at runtime between `AUTO`, `CPU`, and `GPU` via the PC GUI (`GFX:...`) or `GFXSEL`/`GFXSTAT`.
- **External N2 Compiler (`scc`) & Project Manager (`.sc`)**: Programs are now compiled externally into `.sc` packages using the standalone Rust compiler (`scc`, see [External Compiler, Make & CMake](n-language/n2/compiler.md)), and imported into worlds using the new **Project Manager** block (chunked upload, rename, and remove) before flashing with the **Programmer**.
- **Legacy Compiler Blocks & N1 Discontinuation**: **Code Compiler**, **Project Compiler**, and **Assembly Writer** are now UI-less legacy blocks retained only for existing worlds. **N1 (`.n`)** support has been completely discontinued as of 0.2.0.

## Version 0.1.2

Controller input now includes analog triggers, both stick axes, and L3/R3 stick buttons. Use [`INAXIS`](assembly/reference.md#analog-axes-012) in Assembly or the [new N2 GameController helpers](n-language/n2/libraries.md#analog-sticks-triggers-and-stick-buttons-012). Enable `INAXIS` on the SoC and recompile programs that use analog input. Existing digital button and keyboard mappings remain supported; Controlify is optional.

The client publishes one bounded input sample on GUI ticks, clears it when the screen closes or the controller disconnects, and keeps GUI cursor input out of the guest. Client and server must both use the updated payload protocol. The versioned website retains the original 0.1.1 manual separately.

## Choose a language

Use **N2** (`.n2`) for high-level programs and **Assembly** (`.asm` / `.s`) for direct control of instructions and memory. Both compile externally via `scc` into `.sc` packages; their required instructions must be supported by the installed SoC. See the [N language overview](n-language/README.md).

SiliconCraft targets Minecraft 1.21.1 with NeoForge and Java 21. See the [project README](project.md) and [MIT license](license.md).
