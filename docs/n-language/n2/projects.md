# N2 projects and imports

When organizing multi-file projects for the external N2 compiler (`scc`):

### The Quoting Rule
### Module aliases

Use `import "library" as Library` in a project to access a file's top-level functions, variables, and classes through one name. For example, with a `library.n2` file that defines `increment` and `count`:

```lua
import "library" as Library

func main()
    Library.increment()
    return Library.count
end
```

Bundled libraries also support aliases in both standalone files and projects:

```lua
import <DeviceKit> as Device
import <std> as Console

Console.print(Device.PERIPHERAL_NORTH)
```

Use the alias to access the library's functions and constants. Add a regular import if you also want to use an unqualified name.

The alias is local to the importing file. Two aliases for the same file share its state, and the file initializes once. Use `Library.Widget(args)` or `new Library.Widget(args)` to construct an exported class. Module aliases are resolved during compilation. Use them with member access.

- **Project Files**: MUST be imported using double quotes:
  ```lua
  import "entities/player.n2"
  from "utils/math.n2" import Vector2D
  ```
- **System Libraries**: MUST use angle brackets:
  ```lua
  import <std>
  import <GameController>
  ```
- **Strict Validation**: Attempting to use `<entities/player.n2>` will trigger a compile error informing you that angle brackets are reserved exclusively for system libraries.

### Project Entrypoint
The project entrypoint file defaults to `main.n2` (or `entry.n2`, `start.n2`, `index.n2`), or can be specified explicitly with `scc <DIR> --entry <file>`.

### Compiling and Importing (`.sc`)
1. Compile your project directory with `scc` (or via **Make** / **CMake** — see [External N2 Compiler (`scc`)](compiler.md)) to produce a `.sc` package.
2. Open the **Project Manager** block in Minecraft and click **+ Add .sc** to upload the `.sc` file.
3. Burn the program onto a ROM Chip or Storage Drive in the **Programmer**.

> N1 (`.n`) was discontinued in SiliconCraft 0.2.0. Existing `.n` files must be migrated to N2 (`.n2`).

[External compiler, Make & CMake](compiler.md) · [Chapter contents](README.md)
