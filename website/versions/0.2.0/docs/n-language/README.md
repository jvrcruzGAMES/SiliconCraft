# N language (N2)

**N2** (`.n2`, project entrypoint `main.n2`) is SiliconCraft's sole high-level programming language. It compiles to SiliconCraft Assembly and `.sc` bytecode packages compatible with any SoC supporting the generated instructions.

> **N1 Discontinued:** The legacy N1 standard (`.n`) was discontinued in SiliconCraft **0.2.0** and is no longer supported by the compiler. Use **N2** for all high-level programs.

## Compile and install

1. Build your `.n2` file or project folder into a `.sc` package outside Minecraft using **`scc`** (see [External N2 Compiler (`scc`), Make & CMake](n2/compiler.md)).
2. Upload the `.sc` file into your world through the **Project Manager** block (supports chunked transfer for large projects, renaming, and removal).
3. Write the program to a **ROM Chip** or **Storage Drive** using the **Programmer**.

N2 provides classes, lists, maps, exception trapping (`pcall`), and bundled libraries such as `<std>`, `<Glass>`, `<AudioKit>`, `<DeviceKit>`, `<GameController>`, and `<Network>`. See the [N2 chapter](n2/README.md) to get started.

[Hardware setup](../getting-started/README.md) · [Assembly chapter](../assembly/README.md) · [All chapters](../README.md)
