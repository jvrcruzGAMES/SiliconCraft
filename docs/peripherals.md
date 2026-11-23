# Adjacent peripherals and DeviceKit

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

## Assembly and DeviceKit

Include the peripheral instructions in the CPU's SoC architecture. N2's DeviceKit functions compile to these instructions and use the same mailbox as assembly programs.

| Assembly | DeviceKit | Effect |
|---|---|---|
| `PSCAN dst, after` | `peripheral_scan(after)` | Requests the next wired address above `after`, or −1. |
| `PINFO dst, side` | `peripheral_info(side)` | Requests the peripheral flags for a port. |
| `PWRAP dst, side` | `peripheral_wrap(side)` | Requests a manual wrap. A successful wrap returns a result of 1. |
| `PCALL dst, side, method, arg0, arg1` | `peripheral_call(side, method, arg0, arg1)` | Calls a numbered API method with two signed 32-bit arguments. |
| `PCALLS dst, side, method, ptr, arg1` | `peripheral_call_string(side, method, string, arg1)` | Calls a numbered method with a string and a signed 32-bit argument. |
| `PSTATUS dst` | `peripheral_status()` | Reads the mailbox status. |
| `PRESULT dst` | `peripheral_result()` | Reads the signed 32-bit result and clears the completed request. Returns 0 if idle or pending. |

Request instructions write 1 to `dst` if the request was accepted, or 0 if the mailbox was busy. The server processes accepted requests on its tick. Read the status before consuming the result, and consume failed responses too so another request can be accepted. A main program and its interrupt handlers share the same mailbox. `PCALLS` copies up to 64 UTF-16 code units from guest RAM at submission time, one character per RAM cell with a zero terminator.

| Status | Value | Meaning |
|---|---:|---|
| `PERIPHERAL_IDLE` | 0 | Ready for another request. |
| `PERIPHERAL_PENDING` | 1 | Waiting for the server. |
| `PERIPHERAL_OK` | 2 | Result is available. |
| `PERIPHERAL_MISSING` | −1 | No accessible peripheral at the port. |
| `PERIPHERAL_UNWRAPPED` | −2 | Call requires a manual wrap. |
| `PERIPHERAL_UNSUPPORTED` | −3 | Peripheral does not implement that method. |
| `PERIPHERAL_FAILED` | −4 | Invalid port or error while handling the request. |

A successful `PINFO` returns a bitmask. `PERIPHERAL_PRESENT` is 1, `PERIPHERAL_WRAPPED` is 2, and `PERIPHERAL_AUTOMATIC` is 4.

## Built-in tools

| Device | Method | DeviceKit tool | Arguments and result |
|---|---:|---|---|
| Speaker | `0x100` | `speaker_sample_rate(side)` | Returns 8000 Hz. |
| Speaker | `0x101` | `speaker_tone(side, frequency, duration_ms)` | Plays a square wave at 1–4000 Hz for 1–1000 ms. Returns the sample count. |
| Note block | `0x200` | `note_block_get(side)` | Returns the current note from 0 to 24. |
| Note block | `0x201` | `note_block_set(side, note)` | Sets and returns the note from 0 to 24. |
| Note block | `0x202` | `note_block_play(side, note)` | Plays a note using the block’s current instrument. |
| Note block | `0x203` | `note_block_play_instrument(side, instrument, note)` | Plays a note using a named instrument for this call. |

Both playback tools accept a note from 0 to 24, or −1 to use the block’s saved note. They return 1 when the note plays and 0 when playback is blocked or cancelled. Playback leaves the block’s saved note and instrument intact. Use names such as `"harp"`, `"bass"`, `"flute"`, `"bell"`, or `"iron_xylophone"`. Custom head sounds require a compatible head above the note block.

The assembly forms are `PCALL A, side, 0x202, note, 0` and `PCALLS A, side, 0x203, instrument_name, note`. For the second form, define a zero-terminated RAM string such as `instrument_name: DB "flute", 0`.


The tool calls return the mailbox acceptance flag. Retrieve the method result separately. This program sets a note block to the north and plays a tone on the speaker to the east.

```text
import <DeviceKit>
import <std>

func finish_request()
    while peripheral_status() == PERIPHERAL_PENDING do
        sleep(1)
    end
    local status = peripheral_status()
    peripheral_result()
    return status
end

func main()
    if peripheral_wrap(PERIPHERAL_NORTH) == 1 then
        if finish_request() == PERIPHERAL_OK then
            if note_block_set(PERIPHERAL_NORTH, 12) == 1 then
                finish_request()
            end
        end
    end

    if speaker_tone(PERIPHERAL_EAST, 440, 150) == 1 then
        finish_request()
    end
end
```

The equivalent assembly call for the speaker is `PCALL A, 5, 0x101, 440, 150`. Existing CPU audio instructions such as `ATONE` and `APLAY` also route audio through an adjacent speaker automatically.

## Register an API from another mod

Register `ComputerPeripheral.CAPABILITY` on your mod bus with NeoForge's `RegisterCapabilitiesEvent`. Plain blocks use `registerBlock`. Block entities can use `registerBlockEntity`. The capability context is the peripheral's face toward the computer. Return `null` for faces that do not expose the API.

```java
import games.jvrcruz.siliconcraft.api.peripheral.ComputerPeripheral;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import java.util.OptionalInt;

private static final ComputerPeripheral MY_API = (context, method, arg0, arg1) -> {
    if (method == 0) {
        return OptionalInt.of(context.level().hasNeighborSignal(context.peripheralPos()) ? 1 : 0);
    }
    return OptionalInt.empty();
};

public static void registerCapabilities(RegisterCapabilitiesEvent event) {
    event.registerBlock(ComputerPeripheral.CAPABILITY,
            (level, pos, state, entity, face) -> MY_API, MY_BLOCK.get());
}
```

Document your method numbers, arguments, and return values so programs can use `peripheral_call` or `PCALL`. Return `OptionalInt.empty()` for an unsupported operation. Implement `callString` for methods that accept a string. Return a stable capability instance while the same manual peripheral is connected. If your device should be available immediately, implement `autoWrap()` to return true.

Calls run on the Minecraft server thread. Keep them short and avoid loading chunks. A runtime exception during a call produces `PERIPHERAL_FAILED`.
