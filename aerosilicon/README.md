# AeroSilicon

See the [AeroSilicon guide](../docs/addons/aerosilicon/README.md) for runnable Assembly and N2 examples.

A separate SiliconCraft addon for Minecraft 1.21.1 and NeoForge. It requires SiliconCraft, Create, Create: Aeronautics, and Sable. The bundled Aeronautics release includes Simulated, which supplies the sensor ingredients.

Build from the project root with `./gradlew :aerosilicon:build`. The addon JAR is under `aerosilicon/build/libs`. The root IntelliJ client run and `./gradlew runClient` load both mods and their development dependencies. The dedicated server run also loads the addon stack.

## Crafting

**Airship Sensor**

```text
ABA
CDE
AAA
```

A is an iron ingot, B a Gimbal Sensor, C an Altitude Sensor, D Silicon Powder, and E a Velocity Sensor.

**Airship Controller**

```text
ABA
BCB
AAA
```

A is an iron ingot, B a Redstone Link, and C Silicon Powder.

## Programming

Place each peripheral adjacent to a computer on the airship. Wrap its side with `PWRAP` in assembly or `peripheral_wrap(side)` in DeviceKit. Wait for the request to complete and consume the result before calling another method. See the [peripheral guide](../docs/peripherals.md).

### Airship Sensor

Use `PCALL` or `peripheral_call(side, method, 0, 0)`. Results are signed fixed-point integers. Divide by 1000 to obtain the values in the units below.

| Method | Reading |
|---|---|
| `0x300` | World Y coordinate (altitude) in blocks |
| `0x301` | Air pressure ratio, where 1000 means 100% |
| `0x302` | Gimbal X angle in degrees |
| `0x303` | Gimbal Z angle in degrees |
| `0x304` | Signed velocity along the sensor face in blocks per second |
| `0x305` | Local X velocity in blocks per second |
| `0x306` | Local Y velocity in blocks per second |
| `0x307` | Local Z velocity in blocks per second |
| `0x308` | Speed in blocks per second |
| `0x309` | World X coordinate in blocks |
| `0x30A` | World Z coordinate in blocks |
| `0x30B` | Yaw angle of the sensor face in degrees (−180° to 180°: South = 0°, West = 90°, North = ±180°, East = −90°) |

The sensor reads world position (X, Y, Z projected out of the sub-level into the base world), sensor-face yaw, and air pressure in the world. Its gimbal and velocity readings are zero when it is outside a Sable sub-level.

### Airship Controller

Right-click the block to open its configuration. For each of the four channels, enter a unique nickname and click **Save**. Pick up an inventory item and left-click a frequency slot to copy it without spending the item. Right-click clears the slot. Configure the receiver with the same two items in the same order.

Use `PCALLS` or `peripheral_call_string(side, method, nickname, value)`. Channel names are case-insensitive. Settings survive saves. The signal switches off when a channel is edited or the controller unloads. Receivers outside the moving transmitter’s range are refreshed using any other transmitters that still reach them.

| Method | Effect |
|---|---|
| `0x310` | Set the channel to strength 15 for a nonzero value, or off for 0. |
| `0x311` | Read the channel's output state. |

Both methods return 1 for on, 0 for off, or −1 for an unknown nickname. Turning on a channel with both frequency slots empty returns −2. The following N2 call queues an on command for an already wrapped controller to the east.

```lua
peripheral_call_string(PERIPHERAL_EAST, 0x310, "Engine", 1)
```

The equivalent assembly is `PCALLS A, 5, 0x310, 0x200, 1`. Use `ORG 0x200` followed by `DB "Engine", 0` to store the nickname at that RAM address.
