# Optional integrations

SiliconCraft runs without these integrations. Install compatible versions on the appropriate sides. Supported ranges are declared in `src/main/templates/META-INF/neoforge.mods.toml`; an optional entry does not require installation, but an installed version must satisfy its range.

| Mod | Features and behavior when absent |
|---|---|
| JEI | Recipe categories for Chip Forge, Memory Forge, SoC Manufacturer, and Computer Assembly. |
| Jade | Computer, machine, and speaker information in tooltips. |
| Controlify (client) | Gamepad input in the computer screen; unavailable without the mod. |
| Create | Digital Redstone Link; the block is registered only when Create is installed. |
| Create: Aeronautics + Sable | Airship Sensor; the block is registered only when both are installed. |
| Sable | World-coordinate conversion for moving structures. Without it, coordinates use ordinary world space and the Environment Detector structure query returns an empty list. |
| Mekanism | Chemical handling and radiation queries. Without it, the Gas Detector remains craftable and visible but has no chemical capability; capacity is 0 and the ready chemical ID is empty. Environment radiation returns empty text. Energy and Fluid Detectors remain functional independently. |
| Curios | Inventory Manager accessory slot queries and transfers. Without it, Curios results are empty or report zero items moved. |
| Nature's Compass / Explorer's Compass | Corresponding digital compass blocks, registered only when their source mod is installed. |

Sable Companion is embedded in SiliconCraft's JAR and needs no separate installation. It does not require the Sable mod. JEI's Mezz Config libraries are not direct SiliconCraft integrations.

Use [Assembly peripherals](../assembly/peripherals.md), [N2 DeviceKit](../n-language/n2/peripherals.md), or the language-specific Create guides to program these devices.

[Getting Started](README.md)
