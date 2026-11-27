# N2 Create and airship peripherals

The Airship Sensor requires Create: Aeronautics and Sable; the Digital Redstone Link requires Create. Install integrations on client and server. See [block setup, crafting, and channel configuration](../../assembly/create-peripherals.md). Both devices can connect directly or through serial wiring and require a manual wrap.

Import `<DeviceKit>` and use `peripheral_wrap`, `peripheral_call`, and `peripheral_call_string`. Submission returns 1 when queued; wait while status is `PERIPHERAL_PENDING`, then check the final status and consume `peripheral_result()`. See [DeviceKit mailbox rules](peripherals.md).

## Airship readings

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


Use `peripheral_call(port, 0x300, 0, 0)` for altitude, or substitute another method above. After success, read the signed scaled value with `peripheral_result()`. These readings use thousandths of units, unlike Environment Detector position, which returns decimal JSON text.

## Named redstone channels

Nicknames are case-insensitive and can be up to 32 characters. The saved nicknames and frequency items persist across world saves. Editing a channel or unloading the controller turns off its output.

| Method | Argument after nickname | Result |
| --- | --- | --- |
| `0x310` | Use 0 to turn the channel off or any nonzero integer for strength 15. | 0 for off, 1 for on, −1 for unknown nickname, −2 when turning on a channel with both frequency slots empty. |
| `0x311` | Pass 0. | 0 for off, 1 for on, or −1 for unknown nickname. This reads the controller's output state. |


Use `peripheral_call_string(port, 0x310, "Engine", 1)` to turn on `Engine`, `peripheral_call_string(port, 0x310, "Engine", 0)` to turn it off, and method `0x311` to read its output state. A negative method result with status `PERIPHERAL_OK` is distinct from a negative mailbox status.

## Example

The [runnable N2 example](examples/airship_demo.n2) expects the sensor north and the link east. It wraps both devices, prints altitude, and activates `Engine` for 20 game ticks. Configure a frequency and matching receiver first.

[N2 chapter](README.md)
