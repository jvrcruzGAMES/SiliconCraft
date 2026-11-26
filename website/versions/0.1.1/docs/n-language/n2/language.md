# The N2 Programming Language Guide

The **N2 standard** (`.n2`) is SiliconCraft's next-generation programming language, recreated from scratch to deliver clean, modern **Object-Oriented Programming (OOP)**.

N2 blends the expressiveness of **Python** with the elegance and lightweight footprint of **Lua**, tailored for SiliconCraft's virtual computing platform.

---

## 1. Syntax Overview & Philosophy

N2 replaces legacy C-style braces with clean, Lua-style `func ... end` and block delimiters:

- **Comments**: Lua comments (`--`), Python comments (`#`), and standard C-style (`//`) are all supported.
- **Automatic Variable Typing**: Variable types are dynamic and inferred automatically.
- **First-Class Classes**: Native OOP with constructors (`init`), methods, and properties.
- **Lists & Maps**: Clean Python-like syntax using `[...]` for dynamic lists and `{...}` for associative maps.
- **System Libraries**: Globals and hardware drivers are organized into standardized libraries (`<std>`, `<math>`, `<Glass>`, `<AudioKit>`, `<DeviceKit>`, `<GameController>`, and [`<Network>`](network.md)).

### Automatic Memory Management

N2 allocates objects, lists, maps, and string literals in guest RAM. Its collector traces values reachable from program variables and the CPU stack, then reuses memory from unreachable allocations, including objects that only reference each other. Live addresses stay fixed. A numeric value that matches a live heap address can conservatively keep the corresponding allocation alive.

Collection runs during allocation when RAM is tight or when the program has allocated roughly another live heap's worth of memory. Each collection pauses that virtual CPU for a mark-and-sweep pass. This allocation pacing follows the [Lua garbage collector's pause setting](https://www.lua.org/manual/5.4/manual.html#2.5.1).

Programs that allocate require the `GALLOC` instruction on their SoC. Recompile existing N2 source to enable collection. Keep inline Assembly scratch space below address `0x4000` and outside N2's compiler-managed variable slots.

---

## 2. Variables & Scope

Variables in N2 are either **globally scoped** or **locally scoped**:

### Global Definitions
Assigning to a variable at top level or explicitly using `global` binds the variable globally:
```lua
global = "contents"              -- Global variable assignment
global score = 100               -- Explicit global declaration
```

### Local Scoped Definitions
Local variables are scoped to their enclosing function, method, or block using the `local` keyword:
```lua
local scoped = "contents"
local count = 0
local isActive = true
```

---

## 3. Functions

Functions are defined with `func name(args)` and closed with `end`:

```lua
func hello()
    print("hello")
end

func add(a, b)
    return a + b
end

local sum = add(10, 20)
print(sum)                       -- 30
```

---

## 4. Object-Oriented Programming (Classes)

N2 introduces first-class **classes** and object instantiation:

### Class Definition & Constructor
Classes are defined with `class Name { ... }`. The constructor is defined as `func init(...) ... end`:

```lua
class Player {
    func init(name, health)
        this.name = name
        this.health = health
    end

    func take_damage(amount)
        this.health = this.health - amount
        if this.health < 0 then
            this.health = 0
        end
    end

    func is_alive()
        return this.health > 0
    end

    func status()
        print(this.name)
        print(this.health)
    end
}
```

### Instantiation & Method Calls
Objects are instantiated like functions: `ClassName(args)`:

```lua
local p = Player("Steve", 100)
p.take_damage(25)
p.status()
```

---

## 5. Lists & Maps

N2 clearly separates lists from key-value dictionaries:

### Dynamic Lists (`[...]`)
Lists store ordered collections indexed from `0`:

```lua
local inventory = ["Sword", "Shield", "Apple"]
print(inventory[0])              -- "Sword"

-- List length
print(inventory.length())                -- 3

-- Append to list
inventory.push("Bow")
```

### Maps / Dictionaries (`{...}`)
Maps store key-value pairs:

```lua
local stats = {
    "strength": 18,
    "agility": 14,
    "intelligence": 12
}

print(stats["strength"])         -- 18
stats["strength"] = 20
```

---

## 6. Control Flow

### Conditionals (`if`, `elseif`, `else`)
```lua
if score > 90 then
    print("Grade A")
elseif score > 75 then
    print("Grade B")
else
    print("Grade C")
end
```
*(The `then` keyword is optional)*

### While Loops
```lua
local i = 0
while i < 10 do
    print(i)
    i = i + 1
end
```

### For Range Loops
```lua
for i = 1, 10 do
    print(i)
end
```

Continue with the [library reference](libraries.md), [projects](projects.md), [peripherals](peripherals.md), and [networking](network.md).
