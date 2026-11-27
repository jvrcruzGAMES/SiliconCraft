# Connecting peripherals


A computer has six direct peripheral ports, one per adjacent block. Direct port numbers are directions in the computer’s world or Sable sub-level. Serial networks add addresses starting at 6.

| Port | Direction | DeviceKit constant |
|---|---|---|
| 0 | Down | `PERIPHERAL_DOWN` |
| 1 | Up | `PERIPHERAL_UP` |
| 2 | North | `PERIPHERAL_NORTH` |
| 3 | South | `PERIPHERAL_SOUTH` |
| 4 | West | `PERIPHERAL_WEST` |
| 5 | East | `PERIPHERAL_EAST` |

The computer can access a peripheral when its block is loaded and exposes an API on the face wired to the computer or touching it directly. Speakers are available automatically. Note blocks and third-party peripherals that use the default policy require an explicit wrap. A manual wrap applies to the current program session and the capability on that port. Reset, power-off, program replacement, and chunk unload clear the session. If a program resumes with an idle mailbox after a reload, it can discover and wrap its devices again.

## Serial networks

Serial Cables carry the connection between their two ends. Place a cable against a face to align it with that face. Use a Serial Expansion when you need to turn or branch, or connect several expansions directly. Any expansion face can face the computer. A cable route can span any number of loaded blocks. Scans stay within a Sable structure and leave unloaded chunks alone.

```text
Computer ── Cable ── Expansion ── Expansion ── Cable ── Speaker
                       │             │
                  Note Block      Cable
                                     │
                                 Peripheral
```

- **Serial Cable ×8** uses three copper ingots across the middle row and one redstone dust above and below the center.
- **Serial Expansion** uses iron ingots in the corners, copper ingots above and below the center, serial cables on its left and right, and a SiliconCraft Chip in the center.

Discover wired peripherals with `PSCAN dst, after` or `peripheral_scan(after)`. Start with 5 and consume the result. It gives the next higher wired address, or −1 at the end. Pass that address to the next scan to continue. Use the discovered address with the same info, wrap, and call instructions as a direct port. Existing manually wrapped devices keep their original address while their connection remains visible. When a scan observes a disconnected device, its previous address is retired. If rediscovered, it receives a new address. A computer reset starts a new address session.


## Program the connection

- [Assembly peripherals](../assembly/peripherals.md): numeric ports, request instructions, status codes, and device methods.
- [N2 DeviceKit](../n-language/n2/peripherals.md): named direction constants, helper functions, and managed text results.

For instance, place an Environment Detector north of the computer (port 2), wrap it, then query its biome or world coordinates. World-coordinate queries work without Sable; when the detector is on a Sable sublevel, its center is transformed into world space.

[Getting Started](README.md)
