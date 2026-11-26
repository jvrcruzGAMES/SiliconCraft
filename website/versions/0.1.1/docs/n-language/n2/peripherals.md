# N2 DeviceKit peripherals

Import `<DeviceKit>` for the peripheral functions and constants below. Include the generated peripheral instructions in your SoC's architecture. Connect devices directly or through [serial wiring](../../getting-started/peripherals.md). Calls run on the server through a request mailbox; submitting a call does not return the device's result immediately.

## Ports and discovery

Direct direction constants are `PERIPHERAL_DOWN` (0), `PERIPHERAL_UP` (1), `PERIPHERAL_NORTH` (2), `PERIPHERAL_SOUTH` (3), `PERIPHERAL_WEST` (4), and `PERIPHERAL_EAST` (5). Directions are relative to the world or the current Sable sublevel. Serial addresses start at 6.

Call `peripheral_scan(after)` with 5 to begin wired discovery. Wait and consume its numeric result, then pass the returned address to the next scan. A result of −1 ends discovery. Wrapped devices retain addresses while connected; a reset starts a new session. Unloaded chunks and disconnected devices are not accessible.

## Request functions

| Function | Purpose |
|---|---|
| `peripheral_info(port)` | Queue an information request. |
| `peripheral_wrap(port)` | Queue a manual wrap for the current session. |
| `peripheral_scan(after)` | Queue discovery of the next wired address. |
| `peripheral_call(port, method, arg0, arg1)` | Queue a numeric method with two signed arguments. |
| `peripheral_call_string(port, method, text, arg1)` | Queue a method with an N2 string and a signed argument. |
| `peripheral_status()` | Read mailbox status without consuming it. |
| `peripheral_result()` | Consume a numeric result. |
| `peripheral_length()` | Read the response length in UTF-16 cells before consuming. |
| `peripheral_text_unit(text, index)` | Read a UTF-16 unit in managed response text; use the length saved before consuming. |
| `peripheral_text()` | Consume a response as a managed N2 string. |
| `peripheral_result_text(address, capacity)` | Copy and consume text into a RAM buffer; return copied character count. |

Submission functions return 1 when queued and 0 when busy. Poll while status is `PERIPHERAL_PENDING`, yielding with `sleep(1)`. Read status before consuming results, and consume failures too. Do not issue another request while a result is waiting. `peripheral_text()` consumes the same mailbox result as `peripheral_result()`, so choose one. For RAM text buffers, capacity includes the terminating zero.

| Constant | Value | Meaning |
|---|---:|---|
| `PERIPHERAL_IDLE` | 0 | No request or completed result. |
| `PERIPHERAL_PENDING` | 1 | Waiting for the server. |
| `PERIPHERAL_OK` | 2 | Completed successfully. |
| `PERIPHERAL_MISSING` | −1 | No accessible device. |
| `PERIPHERAL_UNWRAPPED` | −2 | Manual wrap required. |
| `PERIPHERAL_UNSUPPORTED` | −3 | Method not supported. |
| `PERIPHERAL_FAILED` | −4 | Invalid port or host-side failure. |

Information results are bitmasks: `PERIPHERAL_PRESENT` = 1, `PERIPHERAL_WRAPPED` = 2, `PERIPHERAL_AUTOMATIC` = 4. Speakers are automatic; manually wrap the other documented SiliconCraft device blocks before calling them. Wraps are cleared on reset, power-off, program replacement, and unload.

## Read world coordinates

The [Environment Detector example](examples/environment-detector.n2) wraps the detector north of the computer, reads its biome, and prints its position. The query is:

```lua
peripheral_call(port, PERIPHERAL_ENV_POSITION, 0, 0)
```

After success, `peripheral_text()` returns JSON such as `{"x":12.5,"y":64.5,"z":-3.5}`. These are the detector center's coordinates, not the computer's. Sable is optional: ordinary blocks return their center, and Sable sublevel positions are transformed to current world space on each request. Negative and fractional values are preserved.

## Speaker and note block helpers

| Function | Arguments and completed result |
|---|---|
| `speaker_sample_rate(port)` | 8000 Hz. |
| `speaker_tone(port, frequency, duration_ms)` | 1–4000 Hz for 1–1000 ms; result is sample count. |
| `note_block_get(port)` | Saved note, 0–24. |
| `note_block_set(port, note)` | Sets and returns note, 0–24. |
| `note_block_play(port, note)` | Plays using the saved instrument; −1 uses the saved note. |
| `note_block_play_instrument(port, instrument, note)` | Plays with an instrument name such as `"flute"`; −1 uses the saved note. |

These helpers return a submission flag; retrieve their result separately. Playback returns 1 when played or 0 when blocked/cancelled, without changing the saved note or instrument. Custom head sounds require a compatible head. See the [runnable tools example](examples/peripheral-tools.n2).

## Device method reference

The following constants are exported by `<DeviceKit>`. Use `peripheral_call` unless a row specifies **string input**, which uses `peripheral_call_string`. Set unused arguments to zero. JSON results are text; consume them with `peripheral_text()` after success. The recipe and block behavior also apply to the [Assembly method catalog](../../assembly/peripherals.md#siliconcraft-peripheral-blocks).

## Chat Box

Recipe ingredient is a Note Block. Messages identify the Chat Box as their source. Each box can send once per 20 game ticks. Polling receives public player chat from within 64 blocks in the same dimension. Each box has one event cursor shared by attached computers. Recent chat events include the player's name, UUID, dimension, and message.

| DeviceKit constant | Method ID | Arguments and result |
|---|---|---|
| `PERIPHERAL_CHAT_SEND` | `0x400` | **String input**. Broadcast plain text. |
| `PERIPHERAL_CHAT_SEND_TO` | `0x401` | **String input**. Send `player\|message` to one online player. |
| `PERIPHERAL_CHAT_POLL` | `0x402` | Read the next nearby chat event as JSON. |
| `PERIPHERAL_CHAT_FORMATTED` | `0x403` | **String input**. Broadcast a JSON text component. Keeps visible text and the root color. |
| `PERIPHERAL_CHAT_TOAST` | `0x404` | **String input**. Show `player\|message` in a toast. |
| `PERIPHERAL_CHAT_NARRATE` | `0x405` | **String input**. Narrate `player\|message` using the player's narrator settings. |

Send methods return the number of recipients, or zero if the request was throttled or the target or message was invalid.

## Distance Detector

Recipe ingredient is an Observer. The detector points out of its marked face. Distances are in thousandths of a block from that face. A reading of −1 means there was no hit in range. The ray stops before any unloaded chunk.

| DeviceKit constant | Method ID | Arguments and result |
|---|---|---|
| `PERIPHERAL_DISTANCE` | `0x410` | Measure immediately. |
| `PERIPHERAL_DISTANCE_MODE` / `PERIPHERAL_DISTANCE_SET_MODE` | `0x411` / `0x412` | Get or set the mode. Values are 0 for blocks, 1 for entities, and 2 for both. |
| `PERIPHERAL_DISTANCE_RANGE` / `PERIPHERAL_DISTANCE_SET_RANGE` | `0x413` / `0x414` | Get or set the maximum distance in blocks, from 1 to 64. |
| `PERIPHERAL_DISTANCE_TRANSPARENT` / `PERIPHERAL_DISTANCE_SET_TRANSPARENT` | `0x415` / `0x416` | Get or set whether the ray ignores non-occluding blocks. |
| `PERIPHERAL_DISTANCE_LASER` / `PERIPHERAL_DISTANCE_SET_LASER` | `0x417` / `0x418` | Get or set whether a reading emits beam particles. |
| `PERIPHERAL_DISTANCE_PERIODIC` / `PERIPHERAL_DISTANCE_SET_PERIODIC` | `0x419` / `0x41A` | Get or set the sampling interval in game ticks. Set 0 to disable or use 5–1200 ticks. |
| `PERIPHERAL_DISTANCE_CACHED` | `0x41B` | Read the latest periodic sample. |

Setters take the first argument and return 1. Boolean settings use 0 for off and 1 for on.

## Environment Detector

Recipe ingredient is a Daylight Detector.

| DeviceKit constant | Method ID | Arguments and result |
|---|---|---|
| `PERIPHERAL_ENV_BIOME` | `0x420` | Biome registry ID. |
| `PERIPHERAL_ENV_DIMENSION` | `0x421` | Dimension ID. |
| `PERIPHERAL_ENV_TIME` | `0x422` | Time of day in game ticks, from 0 to 23999. |
| `PERIPHERAL_ENV_MOON` | `0x423` | Moon phase, from 0 to 7. |
| `PERIPHERAL_ENV_BLOCK_LIGHT`, `PERIPHERAL_ENV_SKY_LIGHT`, `PERIPHERAL_ENV_DAY_LIGHT` | `0x424`–`0x426` | Block, sky, or combined local brightness. |
| `PERIPHERAL_ENV_WEATHER` | `0x427` | 0 for clear, 1 for rain, 2 for thunder. |
| `PERIPHERAL_ENV_SLIME` | `0x428` | Whether the chunk is a slime chunk by the vanilla seed rule. |
| `PERIPHERAL_ENV_DIMENSIONS` | `0x429` | Available dimension IDs as a JSON array. First argument is the offset, with up to 32 IDs per page. |
| `PERIPHERAL_ENV_ENTITIES` | `0x42A` | Up to 24 loaded entities in a box extending 1–32 blocks around the detector. Pass the range in the first argument. Returns a JSON array of type IDs, UUIDs, and positions. |
| `PERIPHERAL_ENV_RADIATION` | `0x42B` | Mekanism radiation in Sv/h as decimal text. An empty result means Mekanism is absent. |
| `PERIPHERAL_ENV_STRUCTURES` | `0x42C` | Sable structures intersecting a 1–32-block scan radius, passed as the first argument. Returns up to 24 structure names and UUIDs as JSON. |
| `PERIPHERAL_ENV_POSITION` | `0x42D` | Detector center in world coordinates as JSON: `{"x":12.5,"y":64.5,"z":-3.5}`. Preserves fractional coordinates and transforms positions on Sable sublevels into world space. Unused arguments are zero. |

`ENV_POSITION` works without Sable installed. In a normal world it returns the block center (`x + 0.5`, `y + 0.5`, `z + 0.5`). Sable's coordinate conversion is only used when Sable is present.

The position is read for each request, so it follows a moving Sable structure. Values are JSON numbers in blocks, including negative and fractional coordinates. They describe the detector, not the connected computer. After wrapping the detector, submit `PCALL A, port, 0x42D, 0, 0` in Assembly or `peripheral_call(port, PERIPHERAL_ENV_POSITION, 0, 0)` in N2. Wait for the mailbox to report success and consume the text with `PRESULTS` or `peripheral_text()`; see the complete examples linked above.

Without Sable, `ENV_STRUCTURES` returns an empty JSON array. Without Mekanism, `ENV_RADIATION` returns empty text; the other Environment Detector queries remain available.

## Player Detector

Recipe ingredient is a Compass. Event polling covers clicks on the detector and login, logout, death, and dimension changes. Server events are retained for at most 1,200 ticks in a shared bounded queue.

| DeviceKit constant | Method ID | Arguments and result |
|---|---|---|
| `PERIPHERAL_PLAYER_ONLINE` | `0x430` | JSON array of online names across dimensions. First argument is the offset, with up to 64 names per page. |
| `PERIPHERAL_PLAYER_RANGE` | `0x431` | JSON array of nearby names in this dimension. First argument is distance in blocks (0–256), second is the offset. |
| `PERIPHERAL_PLAYER_INFO` | `0x432` | **String input**. Online player name. Returns a JSON snapshot with UUID, dimension, position, rotation, health, and food. |
| `PERIPHERAL_PLAYER_AREA` | `0x433` | **String input**. Inclusive block-coordinate box as `x1,y1,z1,x2,y2,z2`. Returns up to 64 names in this dimension. |
| `PERIPHERAL_PLAYER_EVENTS` | `0x434` | Next player event as JSON. |
| `PERIPHERAL_PLAYER_STAT` | `0x435` | **String input**. `player\|minecraft:stat_id` reads a vanilla custom statistic, or returns −1 for an unknown player or statistic. |
| `PERIPHERAL_PLAYER_OWNER` | `0x436` | Name of the player who placed the detector, or empty text. |
| `PERIPHERAL_PLAYER_CUBE` | `0x437` | Same arguments as `PLAYER_RANGE`, with a cube centered on the detector. |

## Block Reader

Recipe ingredient is a Spyglass. Aim the marked face at the target block.

| DeviceKit constant | Method ID | Arguments and result |
|---|---|---|
| `PERIPHERAL_BLOCK_NAME` | `0x440` | Block registry ID. |
| `PERIPHERAL_BLOCK_STATE` | `0x441` | JSON object of block-state properties. |
| `PERIPHERAL_BLOCK_DATA` | `0x442` | The target block entity's client update tag in SNBT, when available. SiliconCraft computers and the devices on this page are excluded. |
| `PERIPHERAL_BLOCK_HAS_ENTITY` | `0x443` | 1 if the target has a block entity, otherwise 0. |
| `PERIPHERAL_BLOCK_EVENTS` | `0x444` | Next queued click on this reader as JSON, including the player's held item ID. |

## Inventory Manager

Recipe ingredient is a Chest. Craft a Memory Card using a SiliconCraft Chip, copper, and iron nuggets. Use the card in your hand to bind it to yourself, then use the bound card on the manager to insert it. Use the manager with an empty hand to retrieve your card. The owner must be online for inventory calls.

| DeviceKit constant | Method ID | Arguments and result |
|---|---|---|
| `PERIPHERAL_INVENTORY_OWNER` | `0x450` | JSON object with the online owner's name and UUID, or empty text. |
| `PERIPHERAL_INVENTORY_SIZE` | `0x451` | Total number of vanilla inventory slots. |
| `PERIPHERAL_INVENTORY_SLOT` | `0x452` | First argument is zero-based slot index. Returns a JSON object with item ID, display name, count, and maximum stack count, or an empty object. |
| `PERIPHERAL_INVENTORY_FREE` | `0x453` | Index of the first empty main-inventory slot, or −1. |
| `PERIPHERAL_INVENTORY_EMPTY` | `0x454` | 1 if the entire inventory is empty. |
| `PERIPHERAL_INVENTORY_HAND` / `PERIPHERAL_INVENTORY_OFFHAND` | `0x455` / `0x456` | Main-hand or offhand stack as JSON. |
| `PERIPHERAL_INVENTORY_IMPORT` / `PERIPHERAL_INVENTORY_EXPORT` | `0x457` / `0x458` | **String input**. Move items into or out of the owner's inventory, including equipment slots. Supply `side,storageSlot,playerSlot` and pass the requested count as the integer argument. Returns the number moved. |
| `PERIPHERAL_INVENTORY_LIST` | `0x459` | JSON array of stacks by slot, including armor and offhand. |
| `PERIPHERAL_INVENTORY_ARMOR` | `0x45A` | JSON array of boots, leggings, chestplate, and helmet stacks in that order. |
| `PERIPHERAL_INVENTORY_HAND_SLOT` | `0x45B` | Selected hotbar slot. |
| `PERIPHERAL_INVENTORY_EMPTY_SLOTS` | `0x45C` | Number of empty main-inventory slots. |
| `PERIPHERAL_INVENTORY_IS_WEARING` | `0x45D` | First argument is armor slot 0–3 in boots-to-helmet order. Returns 1 if occupied. |
| `PERIPHERAL_INVENTORY_CURIO_SIZES` | `0x45E` | JSON object mapping Curios slot types to capacities, or an empty object when unavailable. |
| `PERIPHERAL_INVENTORY_CURIO_LIST` | `0x45F` | **String input**. Curios slot type and an integer offset. Returns up to 32 stacks in that slot type. |
| `PERIPHERAL_INVENTORY_CURIO_IMPORT` / `PERIPHERAL_INVENTORY_CURIO_EXPORT` | `0x480` / `0x481` | **String input**. `curioType\|side,storageSlot,curioSlot` plus a count. Moves items between adjacent storage and Curios. Returns the number moved. |
| `PERIPHERAL_INVENTORY_STORAGE_INFO` | `0x482` | **String input**. `slot` or `curioType\|slot`. Lists item slots and fluid tanks exposed by an item such as a backpack or bucket. Integer argument is the item slot offset. |
| `PERIPHERAL_INVENTORY_STORAGE_IMPORT` / `PERIPHERAL_INVENTORY_STORAGE_EXPORT` | `0x483` / `0x484` | **String input**. `containerSlot,side,storageSlot,innerSlot`, with optional `curioType\|` prefix and integer count. Returns the number of items moved. |
| `PERIPHERAL_INVENTORY_FLUID_IMPORT` / `PERIPHERAL_INVENTORY_FLUID_EXPORT` | `0x485` / `0x486` | **String input**. `containerSlot,side`, with optional `curioType\|` prefix and integer amount in mB. Returns the amount moved. |
| `PERIPHERAL_INVENTORY_ITEM_DATA` | `0x487` | **String input**. `slot` or `curioType\|slot`. Reads the selected item's serialized stack data, including components, as SNBT. |

Use the same direction numbers as direct computer ports for `side`. A transfer requires adjacent storage exposing the relevant sided NeoForge capability. Inventory slot numbers are 0–35 for the main inventory, 36–39 for armor, and 40 for offhand. Curios operations require the optional Curios API mod. The manager grants attached computers access to the owner's inventory while the card is inserted.

## Redstone Integrator

Recipe ingredient is a Repeater. All six faces can be read and controlled independently. Use the direction numbers from the top of this page.

| DeviceKit constant | Method ID | Arguments and result |
|---|---|---|
| `PERIPHERAL_REDSTONE_INPUT` | `0x460` | First argument is side. Reads incoming signal strength from 0 to 15. |
| `PERIPHERAL_REDSTONE_OUTPUT` | `0x461` | First argument is side. Reads the saved output strength. |
| `PERIPHERAL_REDSTONE_SET_OUTPUT` | `0x462` | First argument is side and second is strength. Returns the stored strength, clamped to 0–15. |

## Energy, Fluid, and Gas Detectors

Recipe ingredients are a Redstone Block, Bucket, and Glass Bottle respectively. Place each detector inline so the marked face and rear face connect to the resource network. These blocks proxy transfers between the two sides and share one per-tick transfer limit across both directions. Energy uses Forge Energy and liquids use NeoForge fluid capabilities. Gas uses Mekanism's chemical capability when Mekanism is installed.

Without Mekanism, the Gas Detector remains craftable and visible in the creative tab, but cannot transfer chemicals. `FLOW_MAX` returns 0 and `FLOW_READY_ID` returns empty text. Energy and Fluid Detectors do not require Mekanism.

| DeviceKit constant | Method ID | Arguments and result |
|---|---|---|
| `PERIPHERAL_FLOW_RATE` | `0x470` | Amount transferred in the current tick, or in the previous tick if the current tick has no transfer. |
| `PERIPHERAL_FLOW_LIMIT` | `0x471` | Transfer limit per tick. |
| `PERIPHERAL_FLOW_SET_LIMIT` | `0x472` | Set the limit from the first argument. Returns the new limit. 0 blocks transfers. |
| `PERIPHERAL_FLOW_MAX` | `0x473` | Capacity accessible from the rear endpoint. |
| `PERIPHERAL_FLOW_LAST_ID` | `0x474` | ID of the most recently transferred resource. |
| `PERIPHERAL_FLOW_READY_ID` | `0x475` | ID of the next extractable resource at the rear endpoint, or empty text. |


## Digital compasses

Each compass block requires its corresponding optional mod. Wrap its port, then use `peripheral_call_string(port, PERIPHERAL_COMPASS_LOCATE, target, radius)`. Nature's Compass takes a biome registry ID; Explorer's Compass takes a structure registry ID. Radius 0 uses the device maximum.

After success, a nonzero `peripheral_length()` indicates JSON coordinates. Otherwise use `peripheral_result()`: 0 means no match, −1 invalid/blocked target or disabled coordinates, and −2 incompatible compass API. Searches respect the matching mod's blacklist, coordinate visibility, and sample limit without loading chunks.

## Create and third-party APIs

See [Create and airship peripherals](create-peripherals.md) for sensor methods and named redstone channels. Third-party providers share the same mailbox; use their documented method IDs and arguments. The [capability registration example](../../assembly/peripherals.md#register-an-api-from-another-mod) describes how mod developers expose an API.

[N2 chapter](README.md)
