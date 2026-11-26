# N2 projects and compiler selection

When organizing multi-file projects in the **Project Compiler**:

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
The project entrypoint file must be named `main.n2` (or `main.n` for N1 projects).

---

## 9. Switching Between N1 and N2 in the Compilers

Both the **Code Compiler** and **Project Compiler** blocks support N2 and N1 seamlessly:

- The top-right of the compiler interface features a clickable **Standard Toggle Button**:
  - `[§aN2]`: Compiles using the N2 standard (default).
  - `[§eN1]`: Compiles using the N1 standard.
- When loading a file with a `.n2` extension, the compiler automatically activates N2 mode.
- When loading a file with a `.n` extension, the compiler automatically switches to N1 mode.


[Chapter contents](README.md)
