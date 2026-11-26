# Airship Sensor and Digital Redstone Link

SiliconCraft registers the Airship Sensor when Create: Aeronautics and Sable are installed. The Digital Redstone Link requires Create. Install the matching dependencies on the client and server. The Gradle development runs include all three mods.

Both blocks appear in the SiliconCraft creative tab when available. Connect either block directly or through a [serial network](peripherals.md#serial-networks), then wrap its port. Direct port numbers are relative to the world or the airship's Sable sub-level.

When upgrading an existing world, remove the separate AeroSilicon JAR. With the matching dependencies installed, SiliconCraft aliases the old block and item IDs and the controller's block entity ID to the merged peripherals.

## Airship Sensor

The crafting recipe uses iron ingots (A), a Gimbal Sensor (B), an Altitude Sensor (C), Silicon Powder (D), and a Velocity Sensor (E). The bundled Create: Aeronautics release includes Simulated, which supplies the three sensors.

```text
ABA
CDE
AAA
```

Pass zero for both arguments. Every reading is a signed integer scaled by 1000, so a reading of 125500 from the altitude method means 125.5 blocks.

| Method | Reading |
| --- | --- |
| `0x300` | World Y coordinate of the sensor's center, in blocks |
| `0x301` | Air pressure ratio at the sensor, with 1000 representing 100% |
| `0x302` | Gimbal X angle in degrees |
| `0x303` | Gimbal Z angle in degrees |
| `0x304` | Signed velocity along the sensor's facing direction in blocks per second |
| `0x305` | Local X velocity in blocks per second |
| `0x306` | Local Y velocity in blocks per second |
| `0x307` | Local Z velocity in blocks per second |
| `0x308` | Speed in blocks per second |
| `0x309` | World X coordinate of the sensor's center, in blocks |
| `0x30A` | World Z coordinate of the sensor's center, in blocks |
| `0x30B` | Yaw angle of the sensor's facing direction in degrees (−180° to 180°: South = 0°, West = 90°, North = ±180°, East = −90°) |

World coordinates (X, Y, Z projected out of the Sable sub-level into the base world), yaw, and pressure also work on stationary world blocks. Gimbal and velocity readings return zero outside a Sable sub-level. The facing-direction reading also returns zero when the magnitude of the projected speed is at most 0.05 blocks per second.

## Digital Redstone Link

The crafting recipe uses iron ingots (A), Redstone Links (B), and Silicon Powder (C).

```text
ABA
BCB
AAA
```

Right-click the controller to open its four channels. Enter a unique nickname and click **Save**. Pick up an item with the cursor and left-click a frequency slot to copy it without spending the item. Right-click a slot to clear it. Put a Create Redstone Link in receive mode and set the same two items in the same order. The receiver must be within Create's configured link range.

Nicknames are case-insensitive and can be up to 32 characters. The saved nicknames and frequency items persist across world saves. Editing a channel or unloading the controller turns off its output.

| Method | Argument after nickname | Result |
| --- | --- | --- |
| `0x310` | Use 0 to turn the channel off or any nonzero integer for strength 15. | 0 for off, 1 for on, −1 for unknown nickname, −2 when turning on a channel with both frequency slots empty. |
| `0x311` | Pass 0. | 0 for off, 1 for on, or −1 for unknown nickname. This reads the controller's output state. |

## Assembly calls

Include the peripheral opcodes in the SoC's architecture and [wrap the device](peripherals.md#assembly-mailbox) before calling it. Pass zero for both numeric sensor arguments.

| Operation | Assembly |
|---|---|
| Wrap | `PWRAP A, port` |
| Read altitude | `PCALL A, port, 0x300, 0, 0` |
| Set a named channel | `PCALLS A, port, 0x310, 0x200, 1` |
| Read a named channel | `PCALLS A, port, 0x311, 0x200, 0` |
| Poll | `PSTATUS B` |
| Consume result | `PRESULT C` |

Use `ORG 0x200` followed by `DB "Engine", 0` to store the channel nickname. `port` is a direct or discovered serial address. Check submission acceptance, poll while status is 1, then consume even failed results. Status 2 means a completed method result; for instance, result −2 means an empty channel frequency, while mailbox status −2 means wrap required.

## Example

Place the sensor north (port 2) and link east (port 5), configure a channel named `Engine`, and provide a matching Create receiver. The [runnable Assembly example](examples/airship_demo.asm) prints altitude, turns `Engine` on for 20 ticks, and turns it off. The [N2 version](../n-language/n2/create-peripherals.md) uses DeviceKit functions.

[Assembly chapter](README.md)
