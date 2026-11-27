# External N2 Compiler (`scc`), Make & CMake

Starting in **SiliconCraft 0.2.0**, programs are compiled outside Minecraft into portable **`.sc`** packages using **`scc`** (the standalone Rust compiler under [`compiler/`](../../../compiler/README.md)), then uploaded into your world through the **Project Manager**.

In-game compilation blocks (**Code Compiler**, **Project Compiler**, and **Assembly Writer**) no longer have user interfaces and are kept only so older worlds do not lose placed blocks. **N1 (`.n`) is discontinued** as of 0.2.0; `scc` compiles **N2 (`.n2`)** and **SiliconCraft Assembly (`.asm` / `.s`)** exclusively.

---

## 1. Installing `scc`

### Option A: Prebuilt Release Binary (GitHub Releases)
1. Download the archive for your platform from the mod release page on GitHub:
   - Linux (`x86_64`): `scc-<tag>-linux-x86_64.tar.gz`
   - Windows (`x86_64`): `scc-<tag>-windows-x86_64.zip`
   - macOS (Apple Silicon / Intel): `scc-<tag>-macos-arm64.tar.gz` or `scc-<tag>-macos-x86_64.tar.gz`
2. Extract `scc` (or `scc.exe` on Windows) into a folder on your `PATH`.
3. Verify the installation:
   ```bash
   scc --version
   ```

### Option B: Build from Source (`./compiler`)
`compiler/` uses only the Rust standard library with zero third-party crate dependencies (Rust 1.75+):

```bash
cd compiler
cargo build --release
```

The compiled executable is written to `compiler/target/release/scc` (or `scc.exe` on Windows). To install it globally into `~/.cargo/bin`:

```bash
cargo install --path compiler
```

---

## 2. Setting Up an N2 Project

Create a complete starter workspace with `scc init`:

```bash
scc init my_game --name my_game
cd my_game
```

This generates:

```text
my_game/
├── .gitignore
├── CMakeLists.txt
├── Makefile
└── src/
    ├── helper.n2
    └── main.n2
```

- `src/main.n2` defines `func main()` and imports `<std>` and `"helper.n2" as Helper`.
- `src/helper.n2` shows a local project module.
- Use `-I <dir>` (repeatable) if you keep shared libraries outside `src/`.
- For custom assembly routines or standalone assembly projects, place `.asm` or `.s` files inside `src/` (see [Assembly syntax](../../assembly/syntax.md)).

### Build & Inspect Manually

```bash
# Build a multi-file N2 project into build/my_game.sc
scc src --entry main.n2 --name my_game.sc -o build/my_game.sc

# Type-check and validate opcodes without emitting a file
scc src --entry main.n2 --name my_game.sc --check

# Compile a single N2 file or standalone Assembly file
scc hello.n2 -o hello.sc
scc asm boot.asm -o boot.sc

# Inspect .sc metadata, target architecture, and required CPU opcodes
scc info build/my_game.sc
```

### CLI Options Summary

| Option | Purpose |
|---|---|
| `-o, --output <FILE>` | Destination `.sc` file path |
| `-e, --entry <FILE>` | Entrypoint file relative to project directory (`main.n2`, `main.asm`, etc.) |
| `-I, --include <DIR>` | Additional search directory for quoted imports or assembly includes (repeatable) |
| `-n, --name <NAME>` | Program display name embedded in the `.sc` package |
| `-t, --target <MODE>` | `auto` (default), `n2`, or `asm` |
| `--emit-asm <FILE>` | Write intermediate generated assembly before assembling |
| `--emit-bytecode <FILE>` | Write final resolved bytecode lines to a text file |
| `--check` | Validate syntax, imports, libraries, and opcodes without writing `.sc` |
| `-q, --quiet` | Suppress status message on success |

---

## 3. Building with Make (`Makefile`)

Projects created by `scc init` include a `Makefile` configured for incremental builds:

```makefile
SCC ?= scc
PROJECT ?= my_game
SRC_DIR := src
BUILD_DIR := build
TARGET := $(BUILD_DIR)/$(PROJECT).sc
SOURCES := $(shell find $(SRC_DIR) -type f \( -name '*.n2' -o -name '*.asm' -o -name '*.s' \))

.PHONY: all clean check

all: $(TARGET)

$(TARGET): $(SOURCES)
	@mkdir -p $(BUILD_DIR)
	$(SCC) $(SRC_DIR) --entry main.n2 --name $(PROJECT).sc -o $@

check:
	$(SCC) $(SRC_DIR) --entry main.n2 --name $(PROJECT).sc --check

clean:
	rm -rf $(BUILD_DIR)
```

Common commands:

```bash
make                        # Build build/my_game.sc
make check                  # Validate without writing output
make PROJECT=radar_hud      # Override output program name
make SCC=/path/to/scc       # Use a custom compiler binary path
make clean                  # Remove build/
```

---

## 4. Building with CMake (`CMakeLists.txt`)

`scc init` also generates a `CMakeLists.txt` that discovers `scc` on your `PATH` (or via `-DSCC_EXECUTABLE=...`) and tracks all `.n2`, `.asm`, and `.s` sources with `CONFIGURE_DEPENDS`:

```cmake
cmake_minimum_required(VERSION 3.16)
project(my_game NONE)

find_program(SCC_EXECUTABLE NAMES scc REQUIRED)

file(GLOB_RECURSE N2_SOURCES CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/src/*.n2"
    "${CMAKE_CURRENT_SOURCE_DIR}/src/*.asm"
    "${CMAKE_CURRENT_SOURCE_DIR}/src/*.s"
)

set(SC_OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/my_game.sc")

add_custom_command(
    OUTPUT "${SC_OUTPUT}"
    COMMAND "${SCC_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/src" --entry main.n2 --name "my_game.sc" -o "${SC_OUTPUT}"
    DEPENDS ${N2_SOURCES}
    COMMENT "Compiling SiliconCraft N2 project -> my_game.sc"
    VERBATIM
)

add_custom_target(my_game_sc ALL DEPENDS "${SC_OUTPUT}")
```

Configure and build:

```bash
cmake -S . -B build
cmake --build build
```

If `scc` is not on your `PATH`, pass its path when configuring CMake:

```bash
cmake -S . -B build -DSCC_EXECUTABLE=/path/to/scc
cmake --build build
```

You can also add extra include flags (`-I`, `${CMAKE_CURRENT_SOURCE_DIR}/libs`) directly in the `COMMAND` list when sharing modules across multiple projects.

---

## 5. `.sc` Package Format & Importing into Minecraft

`scc` outputs a UTF-8 JSON package (`.sc`) containing:
- `format`: `"siliconcraft-sc"` and `version`: `1`
- `name`: program display name (normalized to `.sc`)
- `target` (`"n2"` / `"asm"`) and `arch` (`"N2"` / `"ASM"`)
- `requiredInstructions`: deduplicated CPU opcodes needed to execute the program (checked against your SoC chip)
- `bytecode`: assembled virtual CPU instructions

### Loading into Your World
1. Craft and place a **Project Manager** block (see [Getting Started](../../getting-started/README.md)).
2. Click **+ Add .sc** and select your built `.sc` file. Large packages are automatically compressed and transferred in chunks so they do not hit packet size limits.
3. Manage entries with **Rename** or **Remove**.
4. Open a **Programmer** block to write your uploaded `.sc` program onto a **ROM Chip** or **Storage Drive** (Flash / NVMe).

[Projects and imports](projects.md) · [Chapter contents](README.md)
