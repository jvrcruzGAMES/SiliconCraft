# SiliconCraft — Agent Guidelines & Architecture Manual

This document provides critical architectural principles, thread safety rules, and implementation standards for AI agents and developers working on the **SiliconCraft** codebase.

---

## 1. Project Overview & Core Subsystems

SiliconCraft is a NeoForge Minecraft mod (1.21.1) simulating modular personal computers, microcontrollers, and custom silicon architectures inside Minecraft.

- **`vm/` (`VirtualCpu.java`)**: The 16/32-bit virtual CPU executing pre-decoded bytecode instructions, hardware timers, and memory-mapped peripherals.
- **`compiler/` (`n1/NCompiler.java`, `n2/N2Compiler.java`, `Assembler.java`)**: The toolchain compiling N1, N2, and raw assembly down into `.o` bytecode images. `n2/N2Library.java` defines DeviceKit constants.
- **`blockentity/` (`ComputerBlockEntity.java`, etc.)**: Tile entities bridging Minecraft game state, inventories, and virtual hardware.
- **`client/` (`ComputerScreen.java`, `ComputerAudioPlayer.java`)**: CRT display rendering, audio playback (OpenAL/JavaSound), and user input.

---

## 2. Multi-Threading & Shared Thread Model

CPU execution is **completely offloaded** from Minecraft's main server tick loop (`ServerLevel.tick()`) to background worker threads managed by [`ComputerThreadManager.java`](file:///home/jvrcruz/IdeaProjects/SiliconCraft/src/main/java/games/jvrcruz/siliconcraft/vm/ComputerThreadManager.java).

### Threading Model Rules:
1. **Shared Thread Clustering**:
   - A single worker thread executes multiple computers cooperatively in round-robin time slices.
   - **Strict Capacity Limit**: `ComputerThreadManager.MAX_COMPUTERS_PER_THREAD = 8`.
   - If more than 8 computers are active, a new worker thread is dynamically spawned to prevent machine slowdown.
   - Idle worker threads retire automatically after an inactivity timeout.
2. **Cooperative Slice Execution**:
   - Each computer executes a batch of cycles per slice (`CYCLES_PER_SLICE = 2500`).
   - Slices pause with a micro-park (`LockSupport.parkNanos(1_000_000L)`) yielding ~2.5 MHz clock speeds per machine with minimal host CPU usage (<5% of a core).

---

## 3. Critical Thread Safety Rules

> [!CAUTION]
> **Minecraft's engine (`Level`, `BlockState`, `BlockEntity`, `ChunkAccess`) is NOT thread-safe.**
> Calling world methods from worker threads causes crashes and world corruption.

Agents must adhere to these thread-safety requirements:

1. **Never Touch `Level` from Background Threads**:
   - The virtual CPU must never hold references to or call methods on `Level`.
   - Game time is fed to the CPU using a `volatile long lastGameTime` updated during `ComputerBlockEntity.tick()`.
2. **Volatile State Flags**:
   - `VirtualCpu.state`, `VirtualCpu.displayMode`, and `VirtualCpu.fbDirty` are `volatile`.
   - Any new cross-thread flags must be marked `volatile` or wrapped in atomic primitives.
3. **Execution Lock Barrier (`cpu.getExecutionLock()`)**:
   - All CPU state modifications (`tick`, `step`, `reset`, `powerOn`, `powerOff`) synchronize on `cpu.getExecutionLock()`.
   - **World Save Synchronization (`saveAdditional`)**: When Minecraft saves the world, `ComputerBlockEntity.saveAdditional` acquires `synchronized (cpu.getExecutionLock())` to prevent capturing inconsistent register or RAM states mid-instruction.
4. **Terminal Output**:
   - `VirtualCpu.terminalOutput` uses `CopyOnWriteArrayList` so server threads, Jade block tooltips, and network packet handlers can safely iterate over lines without locks or `ConcurrentModificationException`.
5. **Interrupt & Audio Queues**:
   - `interruptQueue` and `audioQueue` are synchronized internally. Writers and readers must use the designated queue methods (`queueTimerInterrupt`, `queueKeyboardInterrupt`, `pollAudioSamples`).
6. **Host Unregistration**:
   - Any `BlockEntity` hosting a CPU must implement `ComputerHost` and call `ComputerThreadManager.unregister(this)` inside `setRemoved()` and `onChunkUnloaded()`.
7. **Fast Unloading & Forced Shutdown**:
   - `ComputerThreadManager.shutdown()` interrupts all workers, clears host collections to prevent memory leaks, and fast-joins threads (<50ms).
   - `VirtualCpu.tick()` checks `Thread.currentThread().isInterrupted()` to abort slices instantly upon thread interruption.
   - Both client and server register shutdown listeners on `GameShuttingDownEvent`, `ServerStoppingEvent`, `ServerStoppedEvent`, client `LoggingOut`, and a dedicated JVM shutdown hook (`Runtime.getRuntime().addShutdownHook`) to guarantee zero hang times and instant unloading on forced process termination (`SIGTERM`, `kill`, crashes).

---

## 4. Bytecode & Assembler Standard (`.o`)

- **Object Code Format**: All user programs (from `.n` projects or assembly writer) are compiled and assembled into direct numeric bytecode before storage or drive burning. The standard extension is `.o`.
- **Instruction Registration**: When introducing a new instruction, it must be registered in:
  1. `InstructionValidator.ALL_INSTRUCTIONS`
  2. `VirtualCpu.OPCODE_MAP` (assigning a distinct `OP_` constant)
  3. `Assembler.java` (if it involves label targets or static data)
  4. `VirtualCpu.java` (in `compileLine` and `executeNextInstruction` switch)
- **Expression Lowering in N Language**:
  - The N compiler must lower all arithmetic and composite expressions to registers/temporaries.
  - Never emit compound expression strings (e.g. `ADD [86], (tile_ty * LEVEL_COLS)`) directly into assembly operand fields.
- **Label Parsing Safety**:
  - Colons inside string literals (e.g. `OUT "Value: ", 0`) must never be treated as label declarations. Always use `findLabelColon(line)`.

---

## 5. Interrupt Vector Table & Reset Lifecycle

- **Interrupt Vectors**:
  - `0`: `VEC_DIV_ZERO`
  - `1`: `VEC_ILLEGAL_OPCODE`
  - `2`: `VEC_OUT_OF_BOUNDS`
  - `3`: `VEC_STACK_FAULT`
  - `4`: `VEC_GENERAL_EXCEPTION`
  - `10`: `VEC_KEYBOARD`
  - `11`: `VEC_MOUSE_MOVE`
  - `12`: `VEC_MOUSE_CLICK`
  - `13`: `VEC_TIMER` / `VEC_VBLANK`
  - `14`: `VEC_RESET` (invoked right after CPU reset if registered)
- **Reset Lifecycle Rules**:
  - **RAM Preservation**: RAM is **never cleared** automatically on CPU reset (`reset()`); previous RAM contents persist so programs can recover state or inspect previous buffers.
  - **IVT Persistence**: The Interrupt Vector Table (`ivt`) persists across CPU resets, ensuring registered `VEC_RESET` handlers run immediately.
  - **Reset Interrupt Dispatch**: A `VEC_RESET` (14) event is queued immediately upon `reset()`. If a handler was registered (`IVEC RESET, handler` or `on_reset(handler)`), the CPU jumps directly to the handler on the first cycle following the reset, with `pc = 0` saved on the interrupt stack. If unhandled, execution starts at `pc = 0` normally.

---

## 6. Audio Subsystem Rules

- **Sample Format**: 8000 Hz, 8-bit unsigned PCM (`byte[]`).
- **Speaker vs. Screen Routing**:
  - **No Speaker Connected**: Audio plays **only** to players who have the computer screen GUI open (`player.containerMenu instanceof ComputerMenu`).
  - **Speaker Connected** (adjacent `SpeakerBlock`): Audio is broadcast to nearby players within a 32-block radius (`distanceToSqr <= 32 * 32`) and anyone with the screen GUI open.
- **Audio Cutoff on Power Off**:
  - When a computer powers off, is dismantled, its SoC removed, or unloads, all active playback (both screen and world/speaker audio) must immediately stop (`StopAudioPacket`).
  - CPU audio queues are purged immediately on `powerOff()` / `clearAudioQueue()`.

---

## 7. Hardware Specifications

- **RAM Module Capacity**: 2048 KB (2,097,152 Bytes), with eight expansion slots.
- **Storage Drive Capacity** (Flash & NVMe): 2048 MB (2,097,152 KB / 2,147,483,648 Bytes), backed by sparse world files.
- **Framebuffer Resolution**: 960 × 540 pixels, 32-bit ARGB.

---

## 8. Verification & Testing

Before completing any task:
1. Run `./gradlew test` — all test suites must pass 100%.
2. Run `./gradlew build` — ensure all jar artifacts build with 0 compiler errors.

---

## 9. Standalone CPU Emulator (`SiliconCraft-ComputerEmulator`)

A standalone, cycle-accurate Rust implementation of the SiliconCraft Virtual CPU architecture is located at `/home/jvrcruz/Documentos/Projects/SiliconCraft-ComputerEmulator`:

- **Vulkan Window Interface**: Runs full CRT text mode and 480x270 framebuffer graphics inside a hardware-accelerated Vulkan window (`pixels` + `wgpu` + `winit`).
- **Headless Mode**: Supports `--headless` for automated test suites and benchmarks.
- **Toolchain Parity**: Features the same `.n` compiler, assembly project resolver, and `.o` bytecode executor as the Minecraft mod.
- **Standard Preset**: 4 sticks of RAM (4096 KB), NVMe in extension slot 1 (1024 MB), all 71 instructions enabled, and code burned to non-volatile ROM.

---

## 10. Peripheral Methods & World Coordinates

- Peripheral requests cross the synchronized `PeripheralBus` mailbox. World access and `ComputerPeripheral` calls run on the server thread through `ComputerBlockEntity`, never on CPU workers.
- When adding a peripheral method, update its host implementation, the DeviceKit constants in `compiler/n2/N2Library.java`, [the peripheral reference](docs/peripherals.md), and relevant Assembly/N2 examples and tests. Peripheral method IDs are separate from CPU instruction opcodes.
- **Environment Detector position**: `0x42D` / `PERIPHERAL_ENV_POSITION` returns JSON text containing numeric `x`, `y`, and `z` for the detector's center. Arguments are zero; consume text with `PRESULTS` or `peripheral_text()` after the mailbox reports success.
- Use `SubLevelCoordinates.worldPosition` for world-space positions. Without Sable, the position is unchanged; the detector center is `(blockX + 0.5, blockY + 0.5, blockZ + 0.5)`. With Sable, transform sublevel positions into world space. Preserve fractional and negative coordinates and compute the position for each request so moving structures report current coordinates.
- Keep optional Sable API access inside the guarded `SubLevelCoordinates.SableIntegration` class. Basic coordinate queries must work without the Sable mod.
- [Assembly](docs/examples/environment-detector.asm) and [N2](docs/n-language/n2-examples/environment-detector.n2) examples print biome and position. `PeripheralInstructionsTest` executes both through the VM with host responses, including negative and fractional position values; this does not replace an in-game Sable integration test.

## 11. Optional Dependencies & Packaging

- `src/main/templates/META-INF/neoforge.mods.toml` is the source of mod dependency metadata. Gradle expands it into the built JAR. Declare integrations actually used by SiliconCraft; JEI's `mezz_config` and `mezz_config_gui` dependencies do not belong in this file.
- Optional dependency ranges use an inclusive supported minimum and an exclusive next-major upper bound, for example Create `[6.0.10,7)`. Use the version and mod ID from the actual artifact metadata, not a hosting slug. Compass versions include a Minecraft prefix, for example `[1.21.1-3.4.0-neoforge,1.21.1-4.0.0)`.
- Create: Aeronautics uses mod ID `aeronautics`. Its distribution wrapper is `aeronautics_bundled`; target the embedded mod for integration checks and dependency ordering.
- **Sable Companion is bundled**, using `jarJar(api(...))` with a supported version range and preferred version from `sable_companion_version`. Keep its exclusive Maven repository configuration. Do not add a standalone `sablecompanion` dependency to `mods.toml`; verify the library and its jarJar metadata are present in the built mod JAR.
- **Mekanism is optional**: guard `PeripheralGas` access with `ModList` checks. Without Mekanism, the Gas Detector remains registered, craftable, and visible, but has no chemical capability; gas capacity is zero and the ready chemical ID is empty. Environment Detector radiation is empty text. Energy and Fluid Detectors use NeoForge capabilities independently.
- **Curios is used** by Inventory Manager slot listing, item queries, and transfers. Keep its optional dependency and guard calls to `PeripheralCurios` when absent.
- Register the Airship Sensor only with Aeronautics and Sable, the Digital Redstone Link with Create, and each digital compass with its corresponding compass mod. Controlify is a client-side optional dependency.

## 12. License, Branding & Feature Descriptions

- SiliconCraft uses the [MIT License](LICENSE). Keep `mod_license=MIT` and include `LICENSE` in JAR artifacts.
- The shared CurseForge and mod-menu logo is [src/main/resources/siliconcraft.png](src/main/resources/siliconcraft.png), a 256 × 256 PNG with a circuit-board background and centered Minecraft bitmap-font `SC`. The metadata uses `logoFile = "siliconcraft.png"` and `logoBlur = false`. Use FFmpeg for the requested logo workflow.
- Base public feature descriptions on registered content and implementation. The hardware workflow uses Architecture Writer, Chip Forge, Memory Forge, Programmer, and SoC Manufacturer. Silicon Powder is implemented; do not describe a Silicon Inscriber or Silicon Wafer as existing content.
