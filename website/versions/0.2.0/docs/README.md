# SiliconCraft documentation

This manual documents SiliconCraft **0.2.0**. The [documentation website](http://sc.jvrcruz.games/) keeps a separate manual for each published mod version, starting with **0.1.1**. Each version starts at `http://sc.jvrcruz.games/X.X.X/`, for example [0.2.0](http://sc.jvrcruz.games/0.2.0/). Select the version installed in your world before following its guides.

Follow the chapters in order to build a computer, then choose Assembly or N2 to program it. N1 is deprecated and documented for existing programs.

| Chapter | Contents |
|---|---|
| **1. [Getting Started](getting-started/README.md)** | Materials, hardware manufacturing, compiling and flashing programs, peripheral wiring, optional integrations, and server network settings. |
| **2. [Assembly](assembly/README.md)** | Syntax, instruction reference, projects, peripheral methods, networking, and runnable examples. |
| **3. [N1 — deprecated](n-language/n1/README.md)** | N standard 1 syntax, hardware APIs, inline Assembly, and legacy examples. |
| **4. [N2](n-language/n2/README.md)** | N standard 2 syntax, libraries, projects, DeviceKit peripherals, networking, and examples. |

## Version 0.2.0

A dedicated **Graphics Card (`VirtualGpu`)** component brings hardware-accelerated double-buffered rasterization, VRAM sprite storage/blitting (`GPU*` instructions), and automatic `<Glass>` acceleration to SoCs and Personal Computers (maximum 1 GPU per machine). Toggle the **Architecture Writer** between **CPU** and **GPU** modes to draft a reusable **GPU Architecture Writeup**, then fabricate your GPU at the **GPU Assembler** with **Silicon Powder**, **Redstone Dust**, and up to 8 optional RAM modules for VRAM. Programs and users can switch between `AUTO`, `CPU`, and `GPU` graphics handlers at runtime via the PC GUI (`GFX:...`) or `GFXSEL`/`GFXSTAT`.

## Version 0.1.2

Controller input now includes analog triggers, both stick axes, and L3/R3 stick buttons. Use [`INAXIS`](assembly/reference.md#analog-axes-012) in Assembly or the [new N2 GameController helpers](n-language/n2/libraries.md#analog-sticks-triggers-and-stick-buttons-012). Enable `INAXIS` on the SoC and recompile programs that use analog input. Existing digital button and keyboard mappings remain supported; Controlify is optional.

The client publishes one bounded input sample on GUI ticks, clears it when the screen closes or the controller disconnects, and keeps GUI cursor input out of the guest. Client and server must both use the updated payload protocol. The versioned website retains the original 0.1.1 manual separately.

## Choose a language

Use **N2** for new high-level programs, **Assembly** for direct control of instructions and memory, and **N1** to maintain existing `.n` programs. The [N language overview](n-language/README.md) explains compiler selection and file extensions.

SiliconCraft targets Minecraft 1.21.1 with NeoForge and Java 21. Programs compile into `.o` bytecode; their required instructions must be supported by the installed SoC. See the [project README](project.md) and [MIT license](license.md).
