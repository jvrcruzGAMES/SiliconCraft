# Airship Sensor and Digital Redstone Link

SiliconCraft registers the Airship Sensor when Create: Aeronautics and Sable are installed. The Digital Redstone Link requires Create. Install the matching dependencies on the client and server. The Gradle development runs include all three mods.

Both blocks appear in the SiliconCraft creative tab when available. Connect either block directly or through a [serial network](../peripherals.md#serial-networks), then wrap its port. Direct port numbers are relative to the world or the airship's Sable sub-level.

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

## Programming

The SoC must support the instructions used by the assembled program. N2 uses `import <DeviceKit>` and compiles its peripheral functions to the same instructions available in Assembly.

| Operation | Assembly | N2 |
| --- | --- | --- |
| Request a wrap | `PWRAP A, port` | `peripheral_wrap(port)` |
| Request an altitude reading | `PCALL A, port, 0x300, 0, 0` | `peripheral_call(port, 0x300, 0, 0)` |
| Set a channel | `PCALLS A, port, 0x310, 0x200, 1` | `peripheral_call_string(port, 0x310, "Engine", 1)` |
| Read a channel | `PCALLS A, port, 0x311, 0x200, 0` | `peripheral_call_string(port, 0x311, "Engine", 0)` |
| Poll request status | `PSTATUS B` | `peripheral_status()` |
| Consume the result | `PRESULT C` | `peripheral_result()` |

In the Assembly snippets, `port` stands for a port number or a register holding an address. Use `ORG 0x200` followed by `DB "Engine", 0` to store the zero-terminated nickname at the RAM address passed to `PCALLS`. A submission returns 1 when queued or 0 when busy. Poll while status is 1 (pending), then consume the result even if the request failed. Status 2 means the result contains the method's return value. For example, a controller result of −2 with status 2 means the frequency is empty. A mailbox *status* of −2 means the peripheral requires a wrap. See the [peripheral mailbox reference](../peripherals.md#assembly-and-devicekit).

## Runnable examples

Place the Airship Sensor north of the computer (port 2) and the Digital Redstone Link east (port 5). Configure a channel named `Engine` with a matching receiver. Both examples wrap the sensor and print its raw altitude in thousandths of a block. They then wrap the controller, turn on `Engine`, wait 20 game ticks, and turn it off. Change the port numbers or direction constants to match your build.

- [Assembly source](airship_demo.asm)
- [N2 source](airship_demo.n2)
