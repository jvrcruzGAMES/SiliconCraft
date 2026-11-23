# The N Programming Language

**N** is SiliconCraft's custom high-level programming language tailored specifically for virtual computer architectures and custom silicon microprocessors fabricated in Minecraft.

The SiliconCraft toolchain provides two official standards for the N language:
- **N1 Standard (`.n`)**: The classic imperative standard featuring Lua-style tables, C-like curly braces, and direct hardware pointer semantics.
- **N2 Standard (`.n2`)**: The modern object-oriented standard combining the best features of Python and Lua, with first-class classes, scoped local/global definitions, dynamic lists & maps, and modular system libraries.

Both standards compile down directly into cycle-accurate SiliconCraft assembly and bytecode images (`.o`).

---

## Language Standards at a Glance

| Feature | N1 Standard (`.n`) | N2 Standard (`.n2`) |
| :--- | :--- | :--- |
| **Primary Paradigm** | Imperative / Procedural | Object-Oriented / Procedural |
| **Syntax Style** | C / Lua hybrid (`{}`) | Python / Lua hybrid (`func ... end`) |
| **Classes & OOP** | Simulated via tables | First-class `class Name { ... }` |
| **Variable Scoping** | `var`, `let`, `const` | `global = ...`, `local name = ...` |
| **Collections** | Tables `{}` | Lists `[]` and Maps `{}` |
| **System Libraries** | Global builtins (`storage.*`, `audio.*`) | Modular `<std>`, `<math>`, `<Glass>`, `<AudioKit>`, `<DeviceKit>`, `<GameController>`, [`<Network>`](../network.md) |
| **Project Imports** | `import "path.n"` | `import "path.n2"` (strict quotes) |
| **Default in Compiler** | Legacy / Compatibility | **Default standard** |

---

## Toolchain & In-Game Compilers

In SiliconCraft, you can compile and flash programs using two dedicated blocks:

1. **Code Compiler**:
   - Compiles single-file N programs (`.n` or `.n2`). Use the Assembly Writer for standalone Assembly.
   - Features an interactive **Standard Switch Button** in the UI:
     - `N2`: Compiles using the modern N2 compiler (default).
     - `N1`: Compiles using the classic N1 compiler.
   - Automatically detects whether an opened file is `.n` or `.n2` and adjusts the switch.
   - Verifies required instruction sets against your synthesized CPU SoC writeup before burning.

2. **Project Compiler**:
   - Compiles complex, multi-file software projects from `.zip` archives or project directories.
   - Use `main.n2` for N2 projects, `main.n` for N1 projects, or `main.asm` for Assembly projects.
   - Resolves modular imports recursively, detects circular dependencies, and links all modules into a single bytecode program.
   - Features the same **Standard Switch Button** (`N2` / `N1`).

---

## Documentation Navigation

- **[N1 Language Guide](n1.md)**: Full guide to the N1 language specification, table structures, memory pointers, and classic hardware APIs.
- **[N2 Language Guide](n2.md)**: Full guide to the N2 language specification, classes, constructors, methods, lists, maps, and system libraries.
- **[N1 Examples](n1-examples/)**: Ready-to-use standalone scripts and modular projects for N1.
- **[N2 Examples](n2-examples/)**: Ready-to-use standalone scripts and OOP projects for N2.
