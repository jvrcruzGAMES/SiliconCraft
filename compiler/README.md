# SiliconCraft N2 Compiler & Assembler (`scc`)

`scc` is the official standalone Rust compiler and assembler for **SiliconCraft 0.2.0+**. It compiles **N2 (`.n2`)** projects and assembles **SiliconCraft Assembly (`.asm` / `.s`)** programs into portable **`.sc`** packages ready to upload into Minecraft via the **Project Manager** block.

> **Note:** N1 (`.n`) was discontinued in SiliconCraft 0.2.0. `scc` compiles N2 and Assembly only and rejects `.n` files.

## Installing or Building

### Download Prebuilt Binaries
Prebuilt binaries (`scc-vX.Y.Z-<target>`) are attached to GitHub Releases when the **Build & Release Compiler (`scc`)** workflow is triggered for a release tag.

### Build from Source (Rust 1.75+)
`compiler/` has zero third-party crate dependencies and builds purely with the Rust standard library:

```bash
cd compiler
cargo build --release
# Binary is at compiler/target/release/scc (or scc.exe on Windows)
```

To install `scc` into `~/.cargo/bin`:

```bash
cargo install --path compiler
```

## Quick Start

Scaffold a new N2 project (complete with `src/main.n2`, `src/helper.n2`, `Makefile`, and `CMakeLists.txt`):

```bash
scc init my_program --name my_program
cd my_program
scc src --entry main.n2 --name my_program.sc -o build/my_program.sc
scc info build/my_program.sc
```

Compile a single `.n2` file or `.asm` file directly:

```bash
scc hello.n2 -o hello.sc
scc asm boot.asm -o boot.sc
```

## CLI Reference

```text
USAGE:
    scc [build] <INPUT> [OPTIONS]
    scc asm <INPUT.asm> [OPTIONS]
    scc init [DIR] [--name <NAME>]
    scc info <FILE.sc>

OPTIONS:
    -o, --output <FILE>     Output .sc file path (defaults to <name>.sc)
    -e, --entry <FILE>      Entrypoint file relative to project root (e.g. main.n2)
    -I, --include <DIR>     Add module/include search directory (repeatable)
    -n, --name <NAME>       Override program display name embedded in the .sc package
    -t, --target <TARGET>   Force target mode: auto (default), n2, or asm
        --emit-asm <FILE>   Write intermediate un-assembled N2 assembly to <FILE>
        --emit-bytecode <F> Write raw 64-bit SCBC binary bytecode (.o) to <F>
        --no-metadata       Omit the optional binary metadata section from .sc
        --check             Compile and validate without writing .sc output
    -q, --quiet             Suppress status output on success
```

## Building with Make

`scc init` creates a ready-to-use `Makefile`:

```makefile
SCC ?= scc
PROJECT ?= my_program
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

Usage:

```bash
make            # Produces build/my_program.sc
make check      # Type-checks and validates without writing output
make clean      # Removes build artifacts
```

## Building with CMake

`scc init` also creates a `CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.16)
project(my_program NONE)

find_program(SCC_EXECUTABLE NAMES scc REQUIRED)

file(GLOB_RECURSE N2_SOURCES CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/src/*.n2"
    "${CMAKE_CURRENT_SOURCE_DIR}/src/*.asm"
    "${CMAKE_CURRENT_SOURCE_DIR}/src/*.s"
)

set(SC_OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/my_program.sc")

add_custom_command(
    OUTPUT "${SC_OUTPUT}"
    COMMAND "${SCC_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/src" --entry main.n2 --name "my_program.sc" -o "${SC_OUTPUT}"
    DEPENDS ${N2_SOURCES}
    COMMENT "Compiling SiliconCraft N2 project -> my_program.sc"
    VERBATIM
)

add_custom_target(my_program_sc ALL DEPENDS "${SC_OUTPUT}")
```

Usage:

```bash
cmake -S . -B build
cmake --build build
```

## `.sc` Binary Format & In-Game Upload

`.sc` files use the little-endian sectioned binary **`SCPK` v3** container with up to three binary sections (two mandatory, one optional):

1. **`manifest` (`0x0001`, mandatory, must be first section at offset `0`)**:
   - 12-byte header (`"SCPK"`, `version = 3` (`u16`), `flags` (`u16`, bit `0x0001` set when `metadata` is present), `section_count` (`u16`: `2` or `3`), `reserved` (`u16`)) followed by `section_count` × 12-byte section descriptors `(id: u16, flags: u16, offset: u32, size: u32)` pointing to every section in the binary.
2. **`metadata` (`0x0002`, optional, omitted with `--no-metadata`)**:
   - Binary metadata header (`meta_version = 1` (`u16`), `meta_flags` (`u16`), `instr_count` (`u32`)), length-prefixed UTF-8 `name`, `target` (`"n2"` / `"asm"`), and `arch` (`"N2"` / `"ASM"`), plus deduplicated `required_instructions` opcode IDs (`u16[]`). When omitted, `ScProjectFile` / `scc info` derive required opcode IDs directly from `program`.
3. **`program` (`0x0003`, mandatory)**:
   - Executable **`SCBC` v1** binary image: magic `"SCBC"` (4B), version `1` (`u16`), 64-bit (`u64`) fixed-width machine instruction words (opcode `[63..56]`, format `[55..52]`, flags `[51..48]`, operands `[47..0]`), `.rodata` string table, `.ext` extended operand table (for 3+ operand or 32-bit multi-immediate instructions), and `.data` static RAM segments (`ORG` / `DB` / `DW` / `DL` / `INCBIN`).

Inspect any `.sc` binary and its section directory from your terminal with `scc info build/my_program.sc`, or emit a raw `SCBC` binary object file (`.o`) directly with `--emit-bytecode out.o`.

In Minecraft, place a **Project Manager** block, click **+ Add .sc** to upload your `.sc` package (chunked upload handles large programs automatically), then insert a **ROM Chip** or **Storage Drive** (Flash / NVMe) into a **Programmer** block to write it onto hardware.

## Running Compiler Tests

```bash
cd compiler
cargo test
```
