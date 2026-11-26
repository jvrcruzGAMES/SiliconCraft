# N2 library reference

All hardware APIs, mathematical routines, and standard utilities in N2 are imported via standardized system libraries using angle brackets `<...>`.

### Import Syntax
1. **Import entire library**:
   ```lua
   import <std>
   import <math>
   import <GameController>
   ```
2. **Selective import**:
   ```lua
   from <math> import rand, sqrt
   from <std> import print, cls, time
   from <DeviceKit> import read_key, KEY_SPACE
   from <GameController> import gc_poll, gc_button_south
   ```

---

### Library Catalog

#### 1. `<std>` (Standard Library: I/O, Time & Core Utilities)
The merged standard library providing console text output, system timers, and core assertions:

- **I/O & Console**:
  - `print(...)` / `println(...)`: Prints text/numbers followed by an automatic newline.
  - `write(...)`: Prints text/numbers without a trailing newline.
  - `cls()`: Clears the terminal screen.
- **Time & Timers**:
  - `time()`: Returns the current system/game timestamp (in milliseconds).
  - `sleep(ticks)`: Pauses execution for the specified number of ticks.
  - `set_timer(hz)`: Configures the hardware timer interrupt frequency.
- **Core Utilities & Error Handling**:
  - `abs(n)`: Returns the absolute value of `n`.
  - `min(a, b)` / `max(a, b)`: Returns the minimum/maximum of two numbers.
  - `clamp(val, min, max)`: Clamps `val` between `min` and `max`.
  - `assert(condition)`: Asserts that `condition` is non-zero, halting execution with an error if false.
  - `pcall(fn, ...)`: Calls function `fn` in protected mode with the given arguments. Supports Lua-style multi-variable unpacking (`local ok, res = pcall(fn, arg1, arg2)`), returning `1` (`true`) and the function result on success, or `0` (`false`) and the exception code/value if a runtime exception or `error(code)` occurs.
  - `error(code)`: Raises a catchable runtime exception with the specified error value.

#### 2. `<math>` (Mathematical Functions & Constants)
- `sqrt(n)`: Returns the integer square root of `n`.
- `rand(max)`: Generates a pseudo-random integer between `0` and `max - 1`.
- `abs(n)`, `min(a, b)`, `max(a, b)`, `clamp(val, min, max)`.
- **Constants**: `PI` (3), `E` (2).

#### 3. `<Glass>` (High-Resolution Framebuffer Display Canvas)
Hardware graphics library controlling the CRT double-buffered ARGB canvas:

- `set_mode(mode)` / `fb_mode(mode)`: Sets display mode (`0` = Text Console, `1` = 32-bit ARGB Framebuffer).
- `clear_screen(color)` / `fb_clear(color)`: Clears the framebuffer canvas with the specified color.
- `set_pixel(x, y, color)` / `fb_pixel(x, y, color)`: Sets the color of the pixel at `(x, y)`.
- `get_pixel(x, y)` / `fb_get(x, y)`: Reads the color of the pixel at `(x, y)`.
- `draw_rect(x, y, w, h, color)` / `fb_rect(...)`: Draws a filled rectangle.
- `draw_line(x1, y1, x2, y2, color)` / `fb_line(...)`: Draws a line between two points.
- `blit(x, y, ram_src, w, h)` / `fb_blit(...)`: Blits an image from RAM to the screen.
- `swap()` / `flip()` / `fb_swap()`: Swaps front and back buffers to display the rendered frame.
- `width()` / `fb_width()`: Returns canvas width (960).
- `height()` / `fb_height()`: Returns canvas height (540).
- **Color Constants**: `COLOR_BLACK`, `COLOR_WHITE`, `COLOR_RED`, `COLOR_GREEN`, `COLOR_BLUE`, `COLOR_YELLOW`, `COLOR_CYAN`, `COLOR_MAGENTA`, `COLOR_TRANSPARENT`.

#### 4. `<AudioKit>` (Hardware Sound Synthesis & PCM Audio)
- `tone(freq, ms)` / `play_tone(freq, ms)`: Generates a square-wave audio tone.
- `play_audio()` / `audio_play()`: Triggers playback of the hardware PCM audio sample queue.
- `audio_clear()`: Flushes the audio sample buffer.
- `audio_status()`: Returns the number of queued audio samples remaining.
- `audio_push(sample)`: Appends an 8-bit unsigned PCM sample (8000 Hz) to the audio queue.

#### 5. `<DeviceKit>` (Peripherals, Storage & Keyboard Input)
`DeviceKit` handles hardware expansion peripherals, multi-drive binary storage, and keyboard input capture:

- **Keyboard Handling**:
  - `read_key()` / `get_key()`: Reads and dequeues the next key character from the keyboard buffer (returns `-1` if buffer empty).
  - `peek_key()`: Inspects the next key character without consuming it.
  - `key_available()` / `has_key()`: Returns `1` if a key is available in the keyboard buffer, or `0` if empty.
  - `on_key(handler)`: Registers an interrupt callback function for `VEC_KEYBOARD` events.
  - **Key Constants**: `KEY_ENTER` (10), `KEY_SPACE` (32), `KEY_BACKSPACE` (8), `KEY_TAB` (9), `KEY_ESCAPE` (27), `KEY_UP` (257), `KEY_DOWN` (258), `KEY_LEFT` (259), `KEY_RIGHT` (260).
- **Multi-Drive Storage**:
  - `drive_count()`: Returns the total number of connected Flash / NVMe storage drives.
  - `select_drive(drive_id)` / `drive_select(...)`: Selects the active drive for read/write operations.
  - `storage_size(drive_id)` / `drive_size(...)`: Returns capacity of the specified drive in bytes.
  - `storage_read(addr, count)` / `drive_read(...)`: Reads bytes from the active storage drive into RAM.
  - `storage_write(addr, ram_src, count)` / `drive_write(...)`: Writes bytes from RAM to the active storage drive.

- **Peripherals** connect directly or through a serial network. Use `peripheral_info`, `peripheral_wrap`, and `peripheral_call` to submit requests. Check `peripheral_status` and consume the result with `peripheral_result()` for numbers or `peripheral_text()` for a managed string. Use `peripheral_length()` and `peripheral_result_text(address, capacity)` if you manage the buffer yourself. See the [peripheral guide](peripherals.md) for usage and third-party registration.

#### 6. `<Network>`

Use `import <Network>` for server-originated HTTP, HTTPS, TCP, and UDP. See the [Network guide](network.md) for functions and server controls.

#### 7. `<GameController>` (Apple Game Controller Framework API)
The `<GameController>` library provides a unified gamepad API inspired by **Apple's Game Controller framework**, with wide controller compatibility across Xbox, PlayStation, Switch, and generic gamepads via Controlify and SDL3:

- **Controller Discovery & Status**:
  - `gc_controllers()` / `gamepad_count()`: Returns the number of connected gamepads.
  - `gc_current()`: Returns the index of the primary active controller (`0`).
  - `is_gamepad_connected(padId)`: Returns `1` if the specified controller is connected, or `0` otherwise.
- **Input Polling**:
  - `gc_poll(padId)` / `poll_gamepad(padId)` / `read_gamepad(padId)`: Polls and returns the raw button bitmask for `padId`.
- **Button Elements (Boolean Checks)**:
  - `gc_button_south(padId)` / `gc_button_a(padId)`: Primary action button (Bottom: Xbox A / PS Cross / Nintendo B).
  - `gc_button_east(padId)` / `gc_button_b(padId)`: Secondary action button (Right: Xbox B / PS Circle / Nintendo A).
  - `gc_button_west(padId)` / `gc_button_y(padId)`: Action button (Left: Xbox X / PS Square / Nintendo Y).
  - `gc_button_north(padId)` / `gc_button_x(padId)`: Action button (Top: Xbox Y / PS Triangle / Nintendo X).
- **Directional Pad (D-Pad)**:
  - `gc_dpad_up(padId)`: D-pad Up or left analog stick up.
  - `gc_dpad_down(padId)`: D-pad Down or left analog stick down.
  - `gc_dpad_left(padId)`: D-pad Left or left analog stick left.
  - `gc_dpad_right(padId)`: D-pad Right or left analog stick right.
- **Shoulders & System Buttons**:
  - `gc_left_shoulder(padId)`: Left bumper / trigger.
  - `gc_right_shoulder(padId)`: Right bumper / trigger.
  - `gc_button_menu(padId)`: Start / Menu button.
  - `gc_button_options(padId)`: Select / Back / Options button.
- **Constants**:
  - `GC_BUTTON_SOUTH` (0x0040), `GC_BUTTON_EAST` (0x0080), `GC_BUTTON_WEST` (0x0100), `GC_BUTTON_NORTH` (0x0200).
  - `GC_BUTTON_A` (0x0080), `GC_BUTTON_B` (0x0040), `GC_BUTTON_X` (0x0200), `GC_BUTTON_Y` (0x0100).
  - `GC_DPAD_UP` (0x0008), `GC_DPAD_DOWN` (0x0004), `GC_DPAD_LEFT` (0x0002), `GC_DPAD_RIGHT` (0x0001).
  - `GC_LEFT_SHOULDER` (0x0400), `GC_RIGHT_SHOULDER` (0x0800).
  - `GC_BUTTON_MENU` (0x0010), `GC_BUTTON_OPTIONS` (0x0020).
  - Retro SNES aliases: `PAD_UP`, `PAD_DOWN`, `PAD_LEFT`, `PAD_RIGHT`, `PAD_A`, `PAD_B`, `PAD_X`, `PAD_Y`, `PAD_L`, `PAD_R`, `PAD_START`, `PAD_SELECT`.

---



[Chapter contents](README.md)
