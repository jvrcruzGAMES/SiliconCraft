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
| `PLEN dst` | `peripheral_length()` | Reads the response length in UTF-16 cells without consuming it. |
| `PRESULTS dst, ptr, capacity` | `peripheral_result_text(ptr, capacity)` | Copies and consumes the response, returning the number of characters copied. |
| `PRESULT dst` | `peripheral_result()` | Reads the signed 32-bit result and clears the completed request. Returns 0 if idle or pending. |

Request instructions write 1 to `dst` if the request was accepted, or 0 if the mailbox was busy. The server processes accepted requests on its tick. Read the status before consuming the result, and consume failed responses too so another request can be accepted. A main program and its interrupt handlers share the same mailbox. `PCALLS` copies up to 256 UTF-16 code units from guest RAM at submission time, one character per RAM cell with a zero terminator. Text responses are limited to 8192 UTF-16 cells. Reserve one extra cell for the zero terminator. A short buffer consumes the response and copies only what fits. N2’s `peripheral_text()` allocates a managed string with enough space and consumes the response. To parse it in N2, read `peripheral_length()` before consuming it, then use `peripheral_text_unit(text, index)` for each UTF-16 code unit at a zero-based index below that length.

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

The assembly forms are `PCALL A, side, 0x202, note, 0` and `PCALLS A, side, 0x203, 0x200, note`. Place `ORG 0x200` and `DB "flute", 0` in the data section.


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

Document your method numbers, arguments, and return values so programs can use `peripheral_call` or `PCALL`. Return `OptionalInt.empty()` for an unsupported operation. Implement `callString` for methods that accept a string and return a number. Override `invoke` or `invokeString` and return `Result.text(value)` for text results. Return a stable capability instance while the same manual peripheral is connected. If your device should be available immediately, implement `autoWrap()` to return true.

Calls run on the Minecraft server thread. Keep them short and avoid loading chunks. A runtime exception during a call produces `PERIPHERAL_FAILED`.

## SiliconCraft peripheral blocks

These blocks are in the SiliconCraft creative tab and have crafting recipes. Their recipes use iron, copper, Serial Cables, a SiliconCraft Chip, and the device-specific ingredient shown below. Wrap each block once before calling it. The blocks use their own numbered DeviceKit methods and can also be connected through a serial network.

In the tables, names are the suffixes of N2 constants. For example, use `PERIPHERAL_ENV_BIOME` with `peripheral_call`, or `0x420` with `PCALL`. Leave unused arguments at zero. Rows marked **string input** use `PCALLS` or `peripheral_call_string`. Use `PRESULTS` for text in Assembly and `peripheral_text()` for a managed N2 string. JSON results are text. An empty event result means no event is available.

The [Assembly example](examples/environment-detector.asm) and [N2 example](n-language/n2-examples/environment-detector.n2) read and print the biome ID and world position from a detector to the computer's north.

### Chat Box

Recipe ingredient is a Note Block. Messages identify the Chat Box as their source. Each box can send once per 20 game ticks. Polling receives public player chat from within 64 blocks in the same dimension. Each box has one event cursor shared by attached computers. Recent chat events include the player's name, UUID, dimension, and message.

| ID | Constant suffix | Arguments and result |
|---|---|---|
| `0x400` | `CHAT_SEND` | **String input**. Broadcast plain text. |
| `0x401` | `CHAT_SEND_TO` | **String input**. Send `player\|message` to one online player. |
| `0x402` | `CHAT_POLL` | Read the next nearby chat event as JSON. |
| `0x403` | `CHAT_FORMATTED` | **String input**. Broadcast a JSON text component. Keeps visible text and the root color. |
| `0x404` | `CHAT_TOAST` | **String input**. Show `player\|message` in a toast. |
| `0x405` | `CHAT_NARRATE` | **String input**. Narrate `player\|message` using the player's narrator settings. |

Send methods return the number of recipients, or zero if the request was throttled or the target or message was invalid.

### Distance Detector

Recipe ingredient is an Observer. The detector points out of its marked face. Distances are in thousandths of a block from that face. A reading of −1 means there was no hit in range. The ray stops before any unloaded chunk.

| ID | Constant suffix | Arguments and result |
|---|---|---|
| `0x410` | `DISTANCE` | Measure immediately. |
| `0x411` / `0x412` | `DISTANCE_MODE` / `DISTANCE_SET_MODE` | Get or set the mode. Values are 0 for blocks, 1 for entities, and 2 for both. |
| `0x413` / `0x414` | `DISTANCE_RANGE` / `DISTANCE_SET_RANGE` | Get or set the maximum distance in blocks, from 1 to 64. |
| `0x415` / `0x416` | `DISTANCE_TRANSPARENT` / `DISTANCE_SET_TRANSPARENT` | Get or set whether the ray ignores non-occluding blocks. |
| `0x417` / `0x418` | `DISTANCE_LASER` / `DISTANCE_SET_LASER` | Get or set whether a reading emits beam particles. |
| `0x419` / `0x41A` | `DISTANCE_PERIODIC` / `DISTANCE_SET_PERIODIC` | Get or set the sampling interval in game ticks. Set 0 to disable or use 5–1200 ticks. |
| `0x41B` | `DISTANCE_CACHED` | Read the latest periodic sample. |

Setters take the first argument and return 1. Boolean settings use 0 for off and 1 for on.

### Environment Detector

Recipe ingredient is a Daylight Detector.

| ID | Constant suffix | Arguments and result |
|---|---|---|
| `0x420` | `ENV_BIOME` | Biome registry ID. |
| `0x421` | `ENV_DIMENSION` | Dimension ID. |
| `0x422` | `ENV_TIME` | Time of day in game ticks, from 0 to 23999. |
| `0x423` | `ENV_MOON` | Moon phase, from 0 to 7. |
| `0x424`–`0x426` | `ENV_BLOCK_LIGHT`, `ENV_SKY_LIGHT`, `ENV_DAY_LIGHT` | Block, sky, or combined local brightness. |
| `0x427` | `ENV_WEATHER` | 0 for clear, 1 for rain, 2 for thunder. |
| `0x428` | `ENV_SLIME` | Whether the chunk is a slime chunk by the vanilla seed rule. |
| `0x429` | `ENV_DIMENSIONS` | Available dimension IDs as a JSON array. First argument is the offset, with up to 32 IDs per page. |
| `0x42A` | `ENV_ENTITIES` | Up to 24 loaded entities in a box extending 1–32 blocks around the detector. Pass the range in the first argument. Returns a JSON array of type IDs, UUIDs, and positions. |
| `0x42B` | `ENV_RADIATION` | Mekanism radiation in Sv/h as decimal text. An empty result means Mekanism is absent. |
| `0x42C` | `ENV_STRUCTURES` | Sable structures intersecting a 1–32-block scan radius, passed as the first argument. Returns up to 24 structure names and UUIDs as JSON. |
| `0x42D` | `ENV_POSITION` | Detector center in world coordinates as JSON: `{"x":12.5,"y":64.5,"z":-3.5}`. Preserves fractional coordinates and transforms positions on Sable sublevels into world space. Unused arguments are zero. |

`ENV_POSITION` works without Sable installed. In a normal world it returns the block center (`x + 0.5`, `y + 0.5`, `z + 0.5`). Sable's coordinate conversion is only used when Sable is present.

### Player Detector

Recipe ingredient is a Compass. Event polling covers clicks on the detector and login, logout, death, and dimension changes. Server events are retained for at most 1,200 ticks in a shared bounded queue.

| ID | Constant suffix | Arguments and result |
|---|---|---|
| `0x430` | `PLAYER_ONLINE` | JSON array of online names across dimensions. First argument is the offset, with up to 64 names per page. |
| `0x431` | `PLAYER_RANGE` | JSON array of nearby names in this dimension. First argument is distance in blocks (0–256), second is the offset. |
| `0x432` | `PLAYER_INFO` | **String input**. Online player name. Returns a JSON snapshot with UUID, dimension, position, rotation, health, and food. |
| `0x433` | `PLAYER_AREA` | **String input**. Inclusive block-coordinate box as `x1,y1,z1,x2,y2,z2`. Returns up to 64 names in this dimension. |
| `0x434` | `PLAYER_EVENTS` | Next player event as JSON. |
| `0x435` | `PLAYER_STAT` | **String input**. `player\|minecraft:stat_id` reads a vanilla custom statistic, or returns −1 for an unknown player or statistic. |
| `0x436` | `PLAYER_OWNER` | Name of the player who placed the detector, or empty text. |
| `0x437` | `PLAYER_CUBE` | Same arguments as `PLAYER_RANGE`, with a cube centered on the detector. |

### Block Reader

Recipe ingredient is a Spyglass. Aim the marked face at the target block.

| ID | Constant suffix | Arguments and result |
|---|---|---|
| `0x440` | `BLOCK_NAME` | Block registry ID. |
| `0x441` | `BLOCK_STATE` | JSON object of block-state properties. |
| `0x442` | `BLOCK_DATA` | The target block entity's client update tag in SNBT, when available. SiliconCraft computers and the devices on this page are excluded. |
| `0x443` | `BLOCK_HAS_ENTITY` | 1 if the target has a block entity, otherwise 0. |
| `0x444` | `BLOCK_EVENTS` | Next queued click on this reader as JSON, including the player's held item ID. |

### Inventory Manager

Recipe ingredient is a Chest. Craft a Memory Card using a SiliconCraft Chip, copper, and iron nuggets. Use the card in your hand to bind it to yourself, then use the bound card on the manager to insert it. Use the manager with an empty hand to retrieve your card. The owner must be online for inventory calls.

| ID | Constant suffix | Arguments and result |
|---|---|---|
| `0x450` | `INVENTORY_OWNER` | JSON object with the online owner's name and UUID, or empty text. |
| `0x451` | `INVENTORY_SIZE` | Total number of vanilla inventory slots. |
| `0x452` | `INVENTORY_SLOT` | First argument is zero-based slot index. Returns a JSON object with item ID, display name, count, and maximum stack count, or an empty object. |
| `0x453` | `INVENTORY_FREE` | Index of the first empty main-inventory slot, or −1. |
| `0x454` | `INVENTORY_EMPTY` | 1 if the entire inventory is empty. |
| `0x455` / `0x456` | `INVENTORY_HAND` / `INVENTORY_OFFHAND` | Main-hand or offhand stack as JSON. |
| `0x457` / `0x458` | `INVENTORY_IMPORT` / `INVENTORY_EXPORT` | **String input**. Move items into or out of the owner's inventory, including equipment slots. Supply `side,storageSlot,playerSlot` and pass the requested count as the integer argument. Returns the number moved. |
| `0x459` | `INVENTORY_LIST` | JSON array of stacks by slot, including armor and offhand. |
| `0x45A` | `INVENTORY_ARMOR` | JSON array of boots, leggings, chestplate, and helmet stacks in that order. |
| `0x45B` | `INVENTORY_HAND_SLOT` | Selected hotbar slot. |
| `0x45C` | `INVENTORY_EMPTY_SLOTS` | Number of empty main-inventory slots. |
| `0x45D` | `INVENTORY_IS_WEARING` | First argument is armor slot 0–3 in boots-to-helmet order. Returns 1 if occupied. |
| `0x45E` | `INVENTORY_CURIO_SIZES` | JSON object mapping Curios slot types to capacities, or an empty object when unavailable. |
| `0x45F` | `INVENTORY_CURIO_LIST` | **String input**. Curios slot type and an integer offset. Returns up to 32 stacks in that slot type. |
| `0x480` / `0x481` | `INVENTORY_CURIO_IMPORT` / `INVENTORY_CURIO_EXPORT` | **String input**. `curioType\|side,storageSlot,curioSlot` plus a count. Moves items between adjacent storage and Curios. Returns the number moved. |
| `0x482` | `INVENTORY_STORAGE_INFO` | **String input**. `slot` or `curioType\|slot`. Lists item slots and fluid tanks exposed by an item such as a backpack or bucket. Integer argument is the item slot offset. |
| `0x483` / `0x484` | `INVENTORY_STORAGE_IMPORT` / `INVENTORY_STORAGE_EXPORT` | **String input**. `containerSlot,side,storageSlot,innerSlot`, with optional `curioType\|` prefix and integer count. Returns the number of items moved. |
| `0x485` / `0x486` | `INVENTORY_FLUID_IMPORT` / `INVENTORY_FLUID_EXPORT` | **String input**. `containerSlot,side`, with optional `curioType\|` prefix and integer amount in mB. Returns the amount moved. |
| `0x487` | `INVENTORY_ITEM_DATA` | **String input**. `slot` or `curioType\|slot`. Reads the selected item's serialized stack data, including components, as SNBT. |

Use the same direction numbers as direct computer ports for `side`. A transfer requires adjacent storage exposing the relevant sided NeoForge capability. Inventory slot numbers are 0–35 for the main inventory, 36–39 for armor, and 40 for offhand. Curios operations require the optional Curios API mod. The manager grants attached computers access to the owner's inventory while the card is inserted.

### Redstone Integrator

Recipe ingredient is a Repeater. All six faces can be read and controlled independently. Use the direction numbers from the top of this page.

| ID | Constant suffix | Arguments and result |
|---|---|---|
| `0x460` | `REDSTONE_INPUT` | First argument is side. Reads incoming signal strength from 0 to 15. |
| `0x461` | `REDSTONE_OUTPUT` | First argument is side. Reads the saved output strength. |
| `0x462` | `REDSTONE_SET_OUTPUT` | First argument is side and second is strength. Returns the stored strength, clamped to 0–15. |

### Energy, Fluid, and Gas Detectors

Recipe ingredients are a Redstone Block, Bucket, and Glass Bottle respectively. Place each detector inline so the marked face and rear face connect to the resource network. These blocks proxy transfers between the two sides and share one per-tick transfer limit across both directions. Energy uses Forge Energy and liquids use NeoForge fluid capabilities. Gas uses Mekanism's chemical capability when Mekanism is installed.

| ID | Constant suffix | Arguments and result |
|---|---|---|
| `0x470` | `FLOW_RATE` | Amount transferred in the current tick, or in the previous tick if the current tick has no transfer. |
| `0x471` | `FLOW_LIMIT` | Transfer limit per tick. |
| `0x472` | `FLOW_SET_LIMIT` | Set the limit from the first argument. Returns the new limit. 0 blocks transfers. |
| `0x473` | `FLOW_MAX` | Capacity accessible from the rear endpoint. |
| `0x474` | `FLOW_LAST_ID` | ID of the most recently transferred resource. |
| `0x475` | `FLOW_READY_ID` | ID of the next extractable resource at the rear endpoint, or empty text. |

## Digital compasses

Each digital compass is a separate block in the SiliconCraft creative tab. The Digital Nature's Compass requires Nature's Compass, and the Digital Explorer's Compass requires Explorer's Compass. Only installed integrations register their blocks and recipes.

Each recipe uses its matching original compass in the center, two Serial Cables beside it, a SiliconCraft Chip below it, copper above it, and iron in the side columns. Connect the block directly or through a serial network, then wrap its port before calling it.

Use `PCALLS dst, port, 0x490, ptr, radius` in Assembly or `peripheral_call_string(port, PERIPHERAL_COMPASS_LOCATE, target, radius)` in N2. The target is a biome ID for Nature's Compass (for example, `minecraft:plains`) or a structure ID for Explorer's Compass (for example, `minecraft:village_plains`). The radius is in blocks. Zero uses the device's maximum. The search starts at the digital compass block in its current dimension.

- **Digital Nature's Compass** samples the biome source at the block's elevation on a 32-block grid, up to a 512-block radius.
- **Digital Explorer's Compass** looks for structure starts in loaded chunks, up to a 256-block radius. It returns the center of the nearest matching structure it finds.

Both devices respect the matching mod's target blacklist, coordinate visibility, and sample limit. The searches leave unloaded chunks alone. When the mailbox status is `PERIPHERAL_OK`, a nonzero `PLEN` or `peripheral_length()` indicates a JSON coordinate result such as `{"x":48,"y":74,"z":-16}`. Otherwise consume the numeric result with `PRESULT` or `peripheral_result()`. It is 0 for no match, −1 for an invalid or blocked target or disabled coordinates, or −2 for an incompatible compass API.

```assembly
; With a wrapped Digital Nature's Compass on port 2.
PCALLS A, 2, 0x490, 0x200, 256
; Poll PSTATUS, then use PLEN and PRESULTS (or PRESULT for a numeric result).
HLT

ORG 0x200
DB "minecraft:plains", 0
```

```lua
import <DeviceKit>
import <std>

func wait_for_compass()
    while peripheral_status() == PERIPHERAL_PENDING do
        sleep(1)
    end
    return peripheral_status()
end

func main()
    local port = PERIPHERAL_NORTH
    if peripheral_wrap(port) == 0 then
        return 0
    end
    local status = wait_for_compass()
    peripheral_result()
    if status == PERIPHERAL_OK then
        if peripheral_call_string(port, PERIPHERAL_COMPASS_LOCATE, "minecraft:village_plains", 256) == 1 then
            if wait_for_compass() == PERIPHERAL_OK and peripheral_length() > 0 then
                print(peripheral_text())
            else
                print(peripheral_result())
            end
        end
    end
    return 0
end
```

## Create peripherals

See the [Airship Sensor and Digital Redstone Link guide](create-peripherals/README.md) for requirements, crafting recipes, and Assembly and N2 examples.
