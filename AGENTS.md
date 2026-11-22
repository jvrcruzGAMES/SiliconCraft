# SiliconCraft — Agent Guidelines & Architecture Manual

This document provides critical architectural principles, thread safety rules, and implementation standards for AI agents and developers working on the **SiliconCraft** codebase.

---

## 1. Project Overview & Core Subsystems

SiliconCraft is a NeoForge Minecraft mod (1.21.1) simulating modular personal computers, microcontrollers, and custom silicon architectures inside Minecraft.

- **`vm/` (`VirtualCpu.java`)**: The 16/32-bit virtual CPU executing pre-decoded bytecode instructions, hardware timers, and memory-mapped peripherals.
- **`compiler/` (`NCompiler.java`, `Assembler.java`)**: The toolchain compiling high-level `N` language files and raw assembly down into `.o` bytecode images.
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

- **RAM Module Capacity**: 1024 KB (1,048,576 Bytes).
- **Storage Drive Capacity** (Flash & NVMe): 1024 MB (1,048,576 KB / 1,073,741,824 Bytes).
- **Framebuffer Resolution**: 240 × 135 pixels, 32-bit ARGB.

---

## 8. Verification & Testing

Before completing any task:
1. Run `./gradlew test` — all test suites must pass 100%.
2. Run `./gradlew build` — ensure all jar artifacts build with 0 compiler errors.

---

## 9. Standalone CPU Emulator (`SiliconCraft-ComputerEmulator`)

A standalone, cycle-accurate Rust implementation of the SiliconCraft Virtual CPU architecture is located at `/home/jvrcruz/Documentos/Projects/SiliconCraft-ComputerEmulator`:

- **Vulkan Window Interface**: Runs full CRT text mode and 240x135 framebuffer graphics inside a hardware-accelerated Vulkan window (`pixels` + `wgpu` + `winit`).
- **Headless Mode**: Supports `--headless` for automated test suites and benchmarks.
- **Toolchain Parity**: Features the same `.n` compiler, assembly project resolver, and `.o` bytecode executor as the Minecraft mod.
- **Standard Preset**: 4 sticks of RAM (4096 KB), NVMe in extension slot 1 (1024 MB), all 71 instructions enabled, and code burned to non-volatile ROM.
