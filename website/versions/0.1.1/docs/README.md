# SiliconCraft documentation

This manual documents SiliconCraft **0.1.1**. The [documentation website](https://jvrcruzGAMES.github.io/SiliconCraft/) keeps a separate manual for each published mod version, starting with **0.1.1**. Select the version installed in your world before following its guides.

Follow the chapters in order to build a computer, then choose Assembly or N2 to program it. N1 is deprecated and documented for existing programs.

| Chapter | Contents |
|---|---|
| **1. [Getting Started](getting-started/README.md)** | Materials, hardware manufacturing, compiling and flashing programs, peripheral wiring, optional integrations, and server network settings. |
| **2. [Assembly](assembly/README.md)** | Syntax, instruction reference, projects, peripheral methods, networking, and runnable examples. |
| **3. [N1 — deprecated](n-language/n1/README.md)** | N standard 1 syntax, hardware APIs, inline Assembly, and legacy examples. |
| **4. [N2](n-language/n2/README.md)** | N standard 2 syntax, libraries, projects, DeviceKit peripherals, networking, and examples. |

## Choose a language

Use **N2** for new high-level programs, **Assembly** for direct control of instructions and memory, and **N1** to maintain existing `.n` programs. The [N language overview](n-language/README.md) explains compiler selection and file extensions.

SiliconCraft targets Minecraft 1.21.1 with NeoForge and Java 21. Programs compile into `.o` bytecode; their required instructions must be supported by the installed SoC. See the [project README](project.md) and [MIT license](license.md).
