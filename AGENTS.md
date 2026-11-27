# SiliconCraft — Agent Guidelines & Architecture Manual

This document provides critical architectural principles, thread safety rules, and implementation standards for AI agents and developers working on the **SiliconCraft** codebase.

---

## 1. Project Overview & Core Subsystems

SiliconCraft is a NeoForge Minecraft mod (1.21.1) simulating modular personal computers, microcontrollers, and custom silicon architectures inside Minecraft.

- **`vm/` (`VirtualCpu.java`, `CpuJitCompiler.java`, `VirtualGpu.java`, `GpuJitCompiler.java`, `BinaryBytecode.java`, `DisplayStreamSession.java`)**: The 16/32-bit virtual CPU and dedicated GPU executing 64-bit `SCBC` binary bytecode via JVM bytecode JIT compilation (`CpuJitCompiler`/`GpuJitCompiler`) with interpreter fallback, hardware timers, memory-mapped peripherals, and off-tick display streaming.
- **`compiler/` (`n2/N2Compiler.java`, `Assembler.java`, `compiler/` Rust `scc` CLI)**: The toolchain compiling N2 (`.n2`) and raw assembly (`.asm`/`.s`) into 64-bit `SCBC` v1 binary bytecode and 3-section `SCPK` v3 `.sc` packages (`manifest`, optional `metadata`, `program`). `n2/N2Library.java` defines DeviceKit constants. (Legacy N1 `.n` is discontinued as of 0.2.0.)
- **`blockentity/` (`ComputerBlockEntity.java`, etc.)**: Tile entities bridging Minecraft game state, inventories, peripheral servicing, and virtual hardware.
- **`client/` (`ComputerScreen.java`, `ClientDisplayCache.java`, `ComputerDisplayRenderer.java`, `ClientPayloadHandler.java`, `ComputerAudioPlayer.java`)**: Uncapped render-rate double-buffered CRT display rendering (`ClientDisplayCache` updated on `HandlerThread.NETWORK`), OpenAL/JavaSound audio playback, and input polling.

---

## 2. Multi-Threading & Shared Thread Model

CPU execution, GPU rasterization, and display/terminal/audio streaming are **offloaded** from Minecraft's main server tick loop (`ServerLevel.tick()`) to background worker clusters managed by [`ComputerThreadManager.java`](file:///home/jvrcruz/IdeaProjects/SiliconCraft/src/main/java/games/jvrcruz/siliconcraft/vm/ComputerThreadManager.java). Server ticks handle only world-coupled state (`lastGameTime`, `NetworkConfig.policy()`, `POWERED` blockstate, `servicePeripheral`, chunk/speaker/viewer snapshot refreshes, and world NBT dirty flags).

### Threading Model Rules:
1. **Shared Thread Clustering**:
   - **CPU Workers (`WorkerThread`, max `MAX_COMPUTERS_PER_THREAD = 8`)**: Executes `CYCLES_PER_SLICE = 485_000` with `SLICE_PARK_NANOS = 1_000_000L` (~485 MHz GameCube Gekko target via `CpuJitCompiler`), capped by a `1.5 ms` slice wall-time budget in `ComputerBlockEntity.tickAsyncCpu`.
   - **GPU Workers (`GpuWorkerThread`, max `MAX_GPUS_PER_THREAD = 8`)**: Drains `VirtualGpu` command queues (`BASE_OPS_PER_SLICE = 32_400` + `OPS_PER_COMPUTE_UNIT * computeUnits`) with `GPU_SLICE_PARK_NANOS = 200_000L` (162+ MHz GameCube Flipper target via `GpuJitCompiler`), woken via `LockSupport.unpark` on command enqueue.
   - **Async Sync Workers (`SyncWorkerThread`, max `MAX_SYNCS_PER_THREAD = 8`)**: Streams `DisplayFramePacket` (keyframes and row-deltas via `DisplayStreamSession`, `8 ms` / ~125 Hz min interval), `SyncTerminalPacket`, and `PlayAudioPacket` directly over Netty (`connection.send(...)`) with `SYNC_ACTIVE_PARK_NANOS = 4_000_000L`, woken immediately when display, terminal, or audio state changes.
   - Idle worker threads across all pools retire automatically after inactivity timeouts.

---

## 3. Critical Thread Safety Rules

> [!CAUTION]
> **Minecraft's engine (`Level`, `BlockState`, `BlockEntity`, `ChunkAccess`) is NOT thread-safe.**
> Calling world methods from worker threads or Netty threads causes crashes and world corruption.

Agents must adhere to these thread-safety requirements:

1. **Never Touch `Level` from Background or Network Threads**:
   - `VirtualCpu`, `VirtualGpu`, `SyncWorkerThread`, and `HandlerThread.NETWORK` handlers must never hold references to or call methods on server `Level` / `ChunkAccess`.
   - Game time is fed via `volatile long lastGameTime` on `ComputerBlockEntity.tick()`.
   - `ComputerBlockEntity.refreshSyncSnapshots()` refreshes immutable/safe viewer, chunk-tracker, speaker position, and speaker-listener snapshots on the server tick for `SyncWorkerThread` to read safely off-tick.
2. **Volatile State Flags & Separate Display Locks**:
   - `VirtualCpu.state`, `VirtualCpu.displayMode`, `VirtualCpu.fbDirty`, `VirtualGpu.fbDirty`, and `VirtualCpu.terminalVersion` (`AtomicLong`) coordinate cross-thread visibility.
   - Software framebuffer fills and `snapshotActiveDisplay()` synchronize on dedicated `VirtualCpu.fbLock` — **never** on `executionLock` — so `SyncWorkerThread` is never blocked by CPU execution slices.
   - `VirtualGpu` preserves strict acyclic lock ordering: `renderLock` -> `queueLock` / `frontBufferLock`.
3. **Execution Lock Barrier (`cpu.getExecutionLock()`)**:
   - CPU lifecycle & execution (`tick`, `step`, `reset`, `powerOn`, `powerOff`) synchronize on `cpu.getExecutionLock()`.
   - **World Save Synchronization (`saveAdditional`)**: `ComputerBlockEntity.saveAdditional` acquires `synchronized (cpu.getExecutionLock())` to prevent capturing inconsistent register or RAM states mid-instruction.
4. **Terminal Output**:
   - `VirtualCpu.terminalOutput` uses `CopyOnWriteArrayList` and `lineBuffer` updates are synchronized on `lineBuffer`, so server ticks, `SyncWorkerThread`, Jade tooltips, and network handlers never race.
5. **Client Display Cache (`ClientDisplayCache`)**:
   - `DisplayFramePacket`, `SyncTerminalPacket`, screen `PlayAudioPacket`, and `StopAudioPacket` run on `HandlerThread.NETWORK`. Multi-chunk frames reassemble in `stagingPixels` and publish atomically into double-buffered `frontPixels` under `frontLock`; stale/out-of-order `frameId`s are rejected. Clear on screen close and client disconnect.
6. **Interrupt & Audio Queues**:
   - `interruptQueue` and `audioQueue` are synchronized internally. Writers and readers must use the designated queue methods (`queueTimerInterrupt`, `queueKeyboardInterrupt`, `pollAudioSamples`).
7. **Host Unregistration**:
   - Any `BlockEntity` hosting a CPU must implement `ComputerHost` and call `ComputerThreadManager.unregister(this)` inside `setRemoved()` and `onChunkUnloaded()`. When stopped with open GUI viewers (`ComputerMenu`), `unregisterExecution(this)` stops CPU/GPU workers while `SyncWorkerThread` stays active until the screen closes (`ComputerMenu.removed` -> `onViewerClosed`).
8. **Fast Unloading & Forced Shutdown**:
   - `ComputerThreadManager.shutdown()` interrupts all CPU, GPU, and Sync workers, clears host collections, and fast-joins threads (<250 ms total).
   - `VirtualCpu.tick()` checks `Thread.currentThread().isInterrupted()` and `jitSliceYielded` to abort slices cleanly without livelocking.
   - Client and server register shutdown listeners on `GameShuttingDownEvent`, `ServerStoppingEvent`, `ServerStoppedEvent`, client `LoggingOut`, and a JVM shutdown hook.

---

## 4. Bytecode, `.sc` Packages & Instruction Registration

- **Binary Object & Package Format**: Programs assemble to 64-bit **`SCBC` v1** binary images (`BinaryBytecode.java`: 8-byte words + `.rodata`, `.ext`, and `.data` static segments). `scc` packages programs into little-endian **`SCPK` v3** `.sc` files with 3 sections (`manifest` mandatory first at offset 0, `metadata` optional, `program` mandatory).
- **Instruction Registration**: When introducing a new instruction, register it in:
  1. `InstructionValidator.ALL_INSTRUCTIONS`
  2. `BinaryBytecode` (`OP_*` constant & metadata tables)
  3. `Assembler.java` and Rust `compiler/` (`scc`)
  4. `VirtualCpu.java` (`executeNextInstruction`) and `CpuJitCompiler.java` (or delegate cleanly)
- **Expression Lowering & Label Safety**:
  - Compilers must lower all arithmetic and composite expressions to registers/temporaries before emitting assembly operands.
  - Colons inside string literals (e.g. `OUT "Value: ", 0`) must never be treated as label declarations (`findLabelColon(line)`).

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
- When adding a peripheral method, update its host implementation, the DeviceKit constants in `compiler/n2/N2Library.java`, the [Assembly peripheral reference](docs/assembly/peripherals.md), the [N2 peripheral reference](docs/n-language/n2/peripherals.md), and relevant examples and tests. Peripheral method IDs are separate from CPU instruction opcodes.
- **Environment Detector position**: `0x42D` / `PERIPHERAL_ENV_POSITION` returns JSON text containing numeric `x`, `y`, and `z` for the detector's center. Arguments are zero; consume text with `PRESULTS` or `peripheral_text()` after the mailbox reports success.
- Use `SubLevelCoordinates.worldPosition` for world-space positions. Without Sable, the position is unchanged; the detector center is `(blockX + 0.5, blockY + 0.5, blockZ + 0.5)`. With Sable, transform sublevel positions into world space. Preserve fractional and negative coordinates and compute the position for each request so moving structures report current coordinates.
- Keep optional Sable API access inside the guarded `SubLevelCoordinates.SableIntegration` class. Basic coordinate queries must work without the Sable mod.
- [Assembly](docs/assembly/examples/environment-detector.asm) and [N2](docs/n-language/n2/examples/environment-detector.n2) examples print biome and position. `PeripheralInstructionsTest` executes both through the VM with host responses, including negative and fractional position values; this does not replace an in-game Sable integration test.

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

## 13. Documentation Chapters

- [docs/README.md](docs/README.md) indexes four chapters: [Getting Started](docs/getting-started/README.md), [Assembly](docs/assembly/README.md), [deprecated N1](docs/n-language/n1/README.md), and [N2](docs/n-language/n2/README.md). Each chapter owns its guides, references, and examples.
- Keep hardware/wiring and server settings under `docs/getting-started/`. Document Assembly peripheral/network instructions under `docs/assembly/`, and N2 DeviceKit/Network functions under `docs/n-language/n2/`. N1 compatibility examples belong under `docs/n-language/n1/`; clearly mark N1 deprecated and direct new programs to N2.
- When changing a shared peripheral or networking API, update both language references and examples. Keep relative links, the root README, these guidelines, and test fixture paths synchronized when moving documentation. Do not recreate the retired top-level peripheral/network guides or the old `n1-examples` / `n2-examples` folders.
- `DocumentationChapterExamplesTest` compiles and assembles the chapter source files, including multi-file projects. Gradle tracks example sources as test inputs so edits invalidate cached test results. `PeripheralInstructionsTest` executes the Assembly and N2 Environment Detector examples against simulated host responses.

## 14. Versioned Documentation & GitHub Pages

- The documentation root is `http://sc.jvrcruz.games/`; each version's root is `http://sc.jvrcruz.games/X.X.X/`. Keep the build default, workflow, version selector, canonical URLs, and current documentation links consistent with this root, without a `/SiliconCraft/` prefix. `website/CNAME` contains `sc.jvrcruz.games`. Rewrite legacy hosting URLs only in temporary rendering copies; do not edit archived snapshots to change the hosting domain.

- GitHub Pages publishes preserved documentation by mod version, starting with **0.1.1**. `docs/` remains the working manual. See [website/README.md](website/README.md) for local builds and deployment setup.
- **Required task on every `mod_version` bump**: finish the release's documentation and examples, then run `python3 scripts/docs_site.py snapshot <new-mod-version>`. Commit the new `website/versions/<new-mod-version>/` directory and the updated `website/versions.json` in the same change as `gradle.properties`.
- Keep previous snapshots intact. Each contains the release's chapter navigation, manual, downloadable source examples, license, and logo. The snapshot command refuses duplicate versions, and the validation command rejects missing version entries or changed archive checksums.
- Run `python3 scripts/docs_site.py check`, `python3 -m unittest discover -s scripts -p 'test_docs_site.py'`, and `python3 scripts/docs_site.py build` using an environment with `website/requirements.txt` installed, alongside the required Gradle test/build checks.
- `.github/workflows/docs.yml` validates and builds the site on pull requests, and deploys successful builds from `master` through GitHub Pages actions. Keep the repository's Pages publishing source set to GitHub Actions. Never claim the site is live before a deployment succeeds.

## 15. Controller Input (0.1.2)

- `vm/GamepadState` is an immutable, bounded sample containing the 16-bit button mask and six analog values. Publish complete samples through the CPU's `AtomicReferenceArray`; do not share mutable input arrays between server and CPU worker threads.
- `INAXIS dest, pad, axis` reads axes 0–5: left X/Y, right X/Y, left trigger, right trigger. Sticks are −1000..1000 (positive right/down); triggers are 0..1000. Stick deadzone is 0.15 per axis without rescaling outside it. Invalid IDs return 0 and never dequeue keyboard input.
- Keep opcode 101 assigned to `INAXIS`. N2 `<GameController>` exports `gc_axis`, named stick/trigger helpers, trigger-pressed helpers, and L3/R3 button helpers. Keep their arity, constants, compiler lowering, Assembly/N2 references, and examples synchronized.
- New mask bits 12/13 are independent trigger presses (>500); bits 14/15 are L3/R3. Preserve existing D-pad/left-stick aliases and the legacy trigger-to-shoulder aliases in bits 10/11. Normalize GLFW triggers from −1..1 to 0..1 before quantizing.
- Controlify remains optional. The current physical controller (or the first GLFW fallback) feeds virtual pad 0; keyboard mappings still work independently. Suppress both physical buttons and axes in GUI cursor mode. Clear samples on disconnect, screen close, reset, and power-off.
- `GamepadInputPacket` carries one complete sample, with clamping in `GamepadState`. Accept it only for the sender's open, valid `ComputerMenu`. Client polling runs on render frames (8 ms throttle) and GUI ticks, sends changes immediately, and refreshes once per second via `padSyncTicks`. The payload protocol is `1.5.0` (includes `DisplayFramePacket` off-tick streaming).
- `GameControllerInputsTest` covers axis normalization, independent masks, VM execution/fault/reset behavior, and N2 lowering including runtime pad/axis expressions. JVM unit tests do not replace physical Controlify/GLFW controller testing.
