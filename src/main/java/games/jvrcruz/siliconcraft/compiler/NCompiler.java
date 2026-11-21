package games.jvrcruz.siliconcraft.compiler;

import games.jvrcruz.siliconcraft.vm.VirtualCpu;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class NCompiler {
    public enum Type {
        INT,
        CHAR,
        STRING,
        BOOLEAN,
        TABLE
    }

    public static class FieldInfo {
        public final int offset;
        public final Type type;

        public FieldInfo(int offset, Type type) {
            this.offset = offset;
            this.type = type;
        }
    }

    public static class TableInfo {
        public final int baseAddr;
        public int capacity;
        public int nextOffset;
        public final Map<String, FieldInfo> fields = new LinkedHashMap<>();

        public TableInfo(int baseAddr, int capacity) {
            this.baseAddr = baseAddr;
            this.capacity = capacity;
            this.nextOffset = 0;
        }
    }

    public record CompileResult(boolean success, List<String> assembly, List<String> errors, Set<String> requiredInstructions) {}

    public static CompileResult compile(List<String> sourceLines) {
        List<String> assembly = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, String> varMap = new LinkedHashMap<>();
        Map<String, Type> typeMap = new LinkedHashMap<>();
        Map<String, TableInfo> tableMap = new LinkedHashMap<>();
        Set<String> constSet = new HashSet<>();

        int nextRamAddress = 0;
        int labelCounter = 1;

        Deque<LoopContext> loopStack = new ArrayDeque<>();
        Deque<IfContext> ifStack = new ArrayDeque<>();
        Deque<FuncContext> funcStack = new ArrayDeque<>();

        assembly.add("; === Compiled from SiliconCraft .n source ===");

        for (int lineNum = 1; lineNum <= sourceLines.size(); lineNum++) {
            String rawLine = sourceLines.get(lineNum - 1);
            String line = InstructionValidator.stripComments(rawLine).trim();
            if (line.isEmpty()) continue;

            try {
                // Closing brace
                if (line.equals("}")) {
                    if (!funcStack.isEmpty() && (loopStack.isEmpty() || funcStack.peek().blockDepth >= loopStack.peek().blockDepth)
                            && (ifStack.isEmpty() || funcStack.peek().blockDepth >= ifStack.peek().blockDepth)) {
                        FuncContext ctx = funcStack.pop();
                        if (assembly.isEmpty() || !assembly.get(assembly.size() - 1).equals("RET")) {
                            assembly.add("RET");
                        }
                        assembly.add(ctx.endLabel + ":");
                    } else if (!ifStack.isEmpty() && (loopStack.isEmpty() || ifStack.peek().blockDepth >= loopStack.peek().blockDepth)) {
                        IfContext ctx = ifStack.pop();
                        assembly.add(ctx.endLabel + ":");
                    } else if (!loopStack.isEmpty()) {
                        LoopContext ctx = loopStack.pop();
                        assembly.add("JMP " + ctx.startLabel);
                        assembly.add(ctx.endLabel + ":");
                    } else {
                        errors.add("Line " + lineNum + ": Unexpected closing brace '}'");
                    }
                    continue;
                }

                // Check for rejected print_char
                if (line.startsWith("print_char ") || line.startsWith("print_char(") ||
                    line.startsWith("printChar ") || line.startsWith("printChar(") ||
                    line.startsWith("println_char ") || line.startsWith("println_char(") ||
                    line.startsWith("printlnChar ") || line.startsWith("printlnChar(")) {
                    errors.add("Line " + lineNum + ": 'print_char' has been removed. Use 'print' or 'println' instead.");
                    continue;
                }

                // Inline assembly
                String firstWord = getFirstWord(line).toUpperCase(Locale.ROOT);
                if (InstructionValidator.ALL_INSTRUCTIONS_SET.contains(firstWord)) {
                    assembly.add(line);
                    continue;
                }

                // Halt
                if (line.equalsIgnoreCase("halt")) {
                    assembly.add("HLT");
                    continue;
                }

                // Nop
                if (line.equalsIgnoreCase("nop")) {
                    assembly.add("NOP");
                    continue;
                }

                // Clear screen (CLS)
                if (line.equalsIgnoreCase("cls") || line.equalsIgnoreCase("cls()") ||
                    line.equalsIgnoreCase("clear") || line.equalsIgnoreCase("clear()")) {
                    assembly.add("CLS");
                    continue;
                }

                // Sleep statements: sleep(seconds) -> seconds * 20 Minecraft ticks
                if (line.startsWith("sleep(") || line.startsWith("sleep ")) {
                    String arg = extractParenthesesOrRest(line);
                    try {
                        double sec = Double.parseDouble(arg);
                        int ticks = (int) Math.round(sec * 20.0);
                        assembly.add("SLEEP " + Math.max(1, ticks));
                    } catch (NumberFormatException e) {
                        String loc = resolveLocation(arg, varMap, tableMap, assembly);
                        assembly.add("MOV A, " + loc);
                        assembly.add("MUL A, 20");
                        assembly.add("SLEEP A");
                    }
                    continue;
                }

                // Sleep raw ticks: sleep_ticks(ticks)
                if (line.startsWith("sleep_ticks(") || line.startsWith("sleep_ticks ") ||
                    line.startsWith("sleepTicks(") || line.startsWith("sleepTicks ")) {
                    String arg = extractParenthesesOrRest(line);
                    String loc = resolveLocation(arg, varMap, tableMap, assembly);
                    assembly.add("SLEEP " + loc);
                    continue;
                }

                // Return statement: return or return expr
                if (line.equals("return") || line.startsWith("return ")) {
                    String retExpr = line.length() > 6 ? line.substring(6).trim() : "";
                    if (!retExpr.isEmpty()) {
                        compileAssignment("A", retExpr, varMap, tableMap, assembly);
                    }
                    assembly.add("RET");
                    continue;
                }

                // Function declaration: fn name(...) { or function name(...) {
                if ((line.startsWith("fn ") || line.startsWith("function ")) && line.endsWith("{")) {
                    int startIdx = line.startsWith("fn ") ? 3 : 9;
                    int openParen = line.indexOf('(');
                    String funcName = (openParen != -1 ? line.substring(startIdx, openParen) : line.substring(startIdx, line.length() - 1)).trim();
                    int funcId = labelCounter++;
                    String endLabel = "_func_end_" + funcId;

                    assembly.add("JMP " + endLabel);
                    assembly.add(funcName + ":");
                    funcStack.push(new FuncContext(funcName, endLabel, loopStack.size() + ifStack.size() + funcStack.size()));
                    continue;
                }

                // Println statement: prints converted expression followed by newline
                if (line.startsWith("println ") || line.startsWith("println(") || line.equals("println") || line.equals("println()")) {
                    String arg = extractParenthesesOrRest(line);
                    compilePrint(arg, true, labelCounter++, varMap, typeMap, tableMap, assembly);
                    continue;
                }

                // Print statement: prints converted expression to current line (no newline)
                if (line.startsWith("print ") || line.startsWith("print(") || line.equals("print") || line.equals("print()")) {
                    String arg = extractParenthesesOrRest(line);
                    compilePrint(arg, false, labelCounter++, varMap, typeMap, tableMap, assembly);
                    continue;
                }

                // While loop: while <cond> {
                if (line.startsWith("while ") && line.endsWith("{")) {
                    String condStr = line.substring(6, line.length() - 1).trim();
                    int loopId = labelCounter++;
                    String startLabel = "_while_start_" + loopId;
                    String endLabel = "_while_end_" + loopId;

                    assembly.add(startLabel + ":");
                    compileCondition(condStr, endLabel, false, varMap, tableMap, assembly);
                    loopStack.push(new LoopContext(startLabel, endLabel, loopStack.size() + ifStack.size() + funcStack.size()));
                    continue;
                }

                // If statement: if <cond> {
                if (line.startsWith("if ") && line.endsWith("{")) {
                    String condStr = line.substring(3, line.length() - 1).trim();
                    int ifId = labelCounter++;
                    String endLabel = "_if_end_" + ifId;

                    compileCondition(condStr, endLabel, false, varMap, tableMap, assembly);
                    ifStack.push(new IfContext(endLabel, loopStack.size() + ifStack.size() + funcStack.size()));
                    continue;
                }

                // Standalone function call: myFunc()
                if (line.endsWith(")") && line.contains("(") && !line.startsWith("print") && !line.startsWith("sleep")
                        && !line.startsWith("cls") && !line.startsWith("clear")) {
                    String callName = line.substring(0, line.indexOf('(')).trim();
                    if (!callName.isEmpty() && !callName.contains(" ") && !callName.contains(".")) {
                        assembly.add("CALL " + callName);
                        continue;
                    }
                }

                // Increment: x++
                if (line.endsWith("++")) {
                    String var = line.substring(0, line.length() - 2).trim();
                    if (constSet.contains(var.toLowerCase(Locale.ROOT))) {
                        errors.add("Line " + lineNum + ": Cannot modify constant '" + var + "'");
                        continue;
                    }
                    String loc = resolveLocation(var, varMap, tableMap, assembly);
                    assembly.add("INC " + loc);
                    continue;
                }

                // Decrement: x--
                if (line.endsWith("--")) {
                    String var = line.substring(0, line.length() - 2).trim();
                    if (constSet.contains(var.toLowerCase(Locale.ROOT))) {
                        errors.add("Line " + lineNum + ": Cannot modify constant '" + var + "'");
                        continue;
                    }
                    String loc = resolveLocation(var, varMap, tableMap, assembly);
                    assembly.add("DEC " + loc);
                    continue;
                }

                // Check for rejected type declarations: int, char, boolean, etc.
                if (line.startsWith("int ") || line.startsWith("char ") || line.startsWith("char[] ") ||
                    line.startsWith("boolean ") || line.startsWith("string ") || line.startsWith("float ")) {
                    String typeName = getFirstWord(line);
                    errors.add("Line " + lineNum + ": Explicit type declaration '" + typeName + "' is not allowed. Use 'var' or 'const' instead.");
                    continue;
                }

                // Declaration with 'var' or 'const' (or 'let' as alias for var)
                boolean isConst = false;
                boolean isDecl = false;
                if (line.startsWith("const ")) {
                    line = line.substring(6).trim();
                    isConst = true;
                    isDecl = true;
                } else if (line.startsWith("var ")) {
                    line = line.substring(4).trim();
                    isDecl = true;
                } else if (line.startsWith("let ")) {
                    line = line.substring(4).trim();
                    isDecl = true;
                }

                // Assignment: target = expr or target += expr or target -= expr
                if (line.contains("=")) {
                    int eqIndex = line.indexOf('=');
                    String target = line.substring(0, eqIndex).trim();
                    String expr = line.substring(eqIndex + 1).trim();

                    // Check for +=, -=, *=, /=, %=
                    boolean isPlusEq = target.endsWith("+");
                    boolean isMinusEq = target.endsWith("-");
                    boolean isMulEq = target.endsWith("*");
                    boolean isDivEq = target.endsWith("/");
                    boolean isModEq = target.endsWith("%");
                    if (isPlusEq || isMinusEq || isMulEq || isDivEq || isModEq) {
                        target = target.substring(0, target.length() - 1).trim();
                    }

                    String lowerTarget = target.toLowerCase(Locale.ROOT);

                    // Check constant immutability
                    if (!isDecl && constSet.contains(lowerTarget)) {
                        errors.add("Line " + lineNum + ": Cannot assign to constant '" + target + "'");
                        continue;
                    }

                    // Lua table declaration: var t = { ... }
                    if (isDecl && expr.startsWith("{") && expr.endsWith("}")) {
                        nextRamAddress = compileTableDeclaration(lowerTarget, expr, nextRamAddress, varMap, typeMap, tableMap, assembly);
                        if (isConst) constSet.add(lowerTarget);
                        continue;
                    }

                    // String literal declaration: var s = "..."
                    if (isDecl && expr.startsWith("\"") && expr.endsWith("\"")) {
                        nextRamAddress = allocateStringLiteral(lowerTarget, expr, nextRamAddress, varMap, typeMap, assembly);
                        if (isConst) constSet.add(lowerTarget);
                        continue;
                    }

                    // Char literal declaration: var c = 'x'
                    if (isDecl && expr.startsWith("'") && expr.endsWith("'")) {
                        int charVal = VirtualCpu.parseCharLiteral(expr.substring(1, expr.length() - 1));
                        int addr = nextRamAddress++;
                        varMap.put(lowerTarget, "[" + addr + "]");
                        typeMap.put(lowerTarget, Type.CHAR);
                        assembly.add("MOV [" + addr + "], " + charVal);
                        if (isConst) constSet.add(lowerTarget);
                        continue;
                    }

                    // Boolean literal declaration: var b = true / false
                    if (isDecl && (expr.equalsIgnoreCase("true") || expr.equalsIgnoreCase("false"))) {
                        int boolVal = expr.equalsIgnoreCase("true") ? 1 : 0;
                        int addr = nextRamAddress++;
                        varMap.put(lowerTarget, "[" + addr + "]");
                        typeMap.put(lowerTarget, Type.BOOLEAN);
                        assembly.add("MOV [" + addr + "], " + boolVal);
                        if (isConst) constSet.add(lowerTarget);
                        continue;
                    }

                    // Table element write: tbl.field = expr or tbl["field"] = expr or tbl[i] = expr
                    if (target.contains(".") || (target.contains("[") && target.endsWith("]"))) {
                        compileTableOrArrayWrite(target, expr, varMap, typeMap, tableMap, assembly);
                        continue;
                    }

                    // Regular variable allocation
                    if (isDecl && isConst) {
                        constSet.add(lowerTarget);
                    }

                    if (!varMap.containsKey(lowerTarget) && !isRegister(target)) {
                        varMap.put(lowerTarget, "[" + nextRamAddress++ + "]");
                        // Infer type from expr or default to INT
                        typeMap.put(lowerTarget, inferType(expr, typeMap));
                    }
                    String targetLoc = resolveLocation(target, varMap, tableMap, assembly);

                    if (isPlusEq) {
                        compileAdd(targetLoc, expr, varMap, tableMap, assembly);
                    } else if (isMinusEq) {
                        compileSub(targetLoc, expr, varMap, tableMap, assembly);
                    } else if (isMulEq) {
                        compileBinaryOp(targetLoc, targetLoc, expr, "MUL", varMap, tableMap, assembly);
                    } else if (isDivEq) {
                        compileBinaryOp(targetLoc, targetLoc, expr, "DIV", varMap, tableMap, assembly);
                    } else if (isModEq) {
                        compileBinaryOp(targetLoc, targetLoc, expr, "MOD", varMap, tableMap, assembly);
                    } else {
                        compileAssignment(targetLoc, expr, varMap, tableMap, assembly);
                    }
                    continue;
                }

                // Uninitialized variable declaration: var x
                if (isDecl) {
                    String lowerTarget = line.trim().toLowerCase(Locale.ROOT);
                    if (!varMap.containsKey(lowerTarget) && !isRegister(lowerTarget)) {
                        varMap.put(lowerTarget, "[" + nextRamAddress++ + "]");
                        typeMap.put(lowerTarget, Type.INT);
                        assembly.add("MOV " + varMap.get(lowerTarget) + ", 0");
                    }
                    continue;
                }

                errors.add("Line " + lineNum + ": Unrecognized syntax: " + line);
            } catch (Exception e) {
                errors.add("Line " + lineNum + ": " + e.getMessage());
            }
        }

        if (!loopStack.isEmpty() || !ifStack.isEmpty()) {
            errors.add("Syntax Error: Unclosed block! Missing '}'");
        }

        // Ensure program ends with HLT
        if (assembly.isEmpty() || !assembly.get(assembly.size() - 1).equals("HLT")) {
            assembly.add("HLT");
        }

        Set<String> requiredInstructions = InstructionValidator.getRequiredInstructions(assembly);
        return new CompileResult(errors.isEmpty(), assembly, errors, requiredInstructions);
    }

    private static String getFirstWord(String line) {
        int space = line.indexOf(' ');
        return (space == -1 ? line : line.substring(0, space)).trim();
    }

    private static String extractParenthesesOrRest(String line) {
        int openParen = line.indexOf('(');
        if (openParen != -1 && line.endsWith(")")) {
            return line.substring(openParen + 1, line.length() - 1).trim();
        }
        int space = line.indexOf(' ');
        return (space != -1) ? line.substring(space + 1).trim() : "";
    }

    private static boolean isRegister(String token) {
        String u = token.toUpperCase(Locale.ROOT);
        return u.equals("A") || u.equals("B") || u.equals("C") || u.equals("D");
    }

    private static Type inferType(String expr, Map<String, Type> typeMap) {
        expr = expr.trim();
        if (expr.startsWith("\"") && expr.endsWith("\"")) return Type.STRING;
        if (expr.startsWith("'") && expr.endsWith("'")) return Type.CHAR;
        if (expr.equalsIgnoreCase("true") || expr.equalsIgnoreCase("false")) return Type.BOOLEAN;
        if (expr.startsWith("{") && expr.endsWith("}")) return Type.TABLE;
        return typeMap.getOrDefault(expr.toLowerCase(Locale.ROOT), Type.INT);
    }

    private static String resolveLocation(String token, Map<String, String> varMap, Map<String, TableInfo> tableMap, List<String> assembly) {
        token = token.trim();
        if (isRegister(token)) {
            return token.toUpperCase(Locale.ROOT);
        }

        // Table dot access: tbl.field
        if (token.contains(".")) {
            String[] parts = token.split("\\.", 2);
            String tblName = parts[0].trim().toLowerCase(Locale.ROOT);
            String fieldName = parts[1].trim().toLowerCase(Locale.ROOT);
            if (tableMap.containsKey(tblName)) {
                TableInfo table = tableMap.get(tblName);
                if (table.fields.containsKey(fieldName)) {
                    FieldInfo f = table.fields.get(fieldName);
                    return "[" + (table.baseAddr + f.offset) + "]";
                }
            }
        }

        // Table bracket access: tbl["field"] or tbl[0]
        if (token.contains("[") && token.endsWith("]")) {
            int openBracket = token.indexOf('[');
            String tblName = token.substring(0, openBracket).trim().toLowerCase(Locale.ROOT);
            String idxExpr = token.substring(openBracket + 1, token.length() - 1).trim();
            if (tableMap.containsKey(tblName)) {
                TableInfo table = tableMap.get(tblName);
                // String key: tbl["name"]
                if ((idxExpr.startsWith("\"") && idxExpr.endsWith("\"")) || (idxExpr.startsWith("'") && idxExpr.endsWith("'"))) {
                    String key = idxExpr.substring(1, idxExpr.length() - 1).toLowerCase(Locale.ROOT);
                    if (table.fields.containsKey(key)) {
                        FieldInfo f = table.fields.get(key);
                        return "[" + (table.baseAddr + f.offset) + "]";
                    }
                }
                // Constant integer index: tbl[0]
                try {
                    int constIdx = Integer.parseInt(idxExpr);
                    return "[" + (table.baseAddr + constIdx) + "]";
                } catch (NumberFormatException ignored) {}

                // Dynamic variable index: tbl[i]
                if (assembly != null) {
                    String idxLoc = resolveLocation(idxExpr, varMap, tableMap, null);
                    assembly.add("MOV B, " + table.baseAddr);
                    assembly.add("ADD B, " + idxLoc);
                    assembly.add("MOV A, [B]");
                    return "A";
                }
            }
        }

        String lower = token.toLowerCase(Locale.ROOT);
        if (varMap.containsKey(lower)) {
            return varMap.get(lower);
        }

        // Char literal: 'x'
        if (token.startsWith("'") && token.endsWith("'") && token.length() >= 2) {
            int charVal = VirtualCpu.parseCharLiteral(token.substring(1, token.length() - 1));
            return String.valueOf(charVal);
        }

        // Minecraft game time in ticks
        if (token.equalsIgnoreCase("time.now()") || token.equalsIgnoreCase("time.now")) {
            if (assembly != null) {
                assembly.add("TIME A");
            }
            return "A";
        }

        // Boolean literal: true / false
        if (token.equalsIgnoreCase("true")) return "1";
        if (token.equalsIgnoreCase("false")) return "0";

        return token;
    }

    // Compile Lua-style Table: var t = { 10, "apple", x = 5, [2] = "ok" }
    private static int compileTableDeclaration(String name, String expr, int nextRam,
                                               Map<String, String> varMap, Map<String, Type> typeMap,
                                               Map<String, TableInfo> tableMap, List<String> assembly) {
        String inner = expr.substring(1, expr.length() - 1).trim();
        List<String> items = parseTableElements(inner);

        int baseAddr = nextRam;
        int capacity = Math.max(16, items.size() + 4);
        nextRam += capacity;

        TableInfo table = new TableInfo(baseAddr, capacity);
        assembly.add("; Initialize Lua-style table '" + name + "' at RAM [" + baseAddr + "] (capacity: " + capacity + ")");

        int autoIndex = 0;
        for (String item : items) {
            item = item.trim();
            if (item.isEmpty()) continue;

            String key;
            String valStr;

            int eqIdx = item.indexOf('=');
            int colonIdx = item.indexOf(':');

            if (eqIdx != -1) {
                key = item.substring(0, eqIdx).trim();
                valStr = item.substring(eqIdx + 1).trim();
            } else if (colonIdx != -1) {
                key = item.substring(0, colonIdx).trim();
                valStr = item.substring(colonIdx + 1).trim();
            } else {
                // Positional array element
                key = String.valueOf(autoIndex++);
                valStr = item;
            }

            // Strip brackets from key: ["name"] -> name
            if (key.startsWith("[") && key.endsWith("]")) {
                key = key.substring(1, key.length() - 1).trim();
            }
            if ((key.startsWith("\"") && key.endsWith("\"")) || (key.startsWith("'") && key.endsWith("'"))) {
                key = key.substring(1, key.length() - 1).trim();
            }

            int offset;
            try {
                offset = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                offset = table.nextOffset++;
            }

            // Store value into table slot
            int slotAddr = baseAddr + offset;
            Type fieldType = Type.INT;

            if (valStr.startsWith("\"") && valStr.endsWith("\"")) {
                // String literal stored as null-terminated UTF-8
                String content = unescapeString(valStr.substring(1, valStr.length() - 1));
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                int strAddr = nextRam;
                nextRam += bytes.length + 1;

                for (int i = 0; i < bytes.length; i++) {
                    assembly.add("MOV [" + (strAddr + i) + "], " + (bytes[i] & 0xFF));
                }
                assembly.add("MOV [" + (strAddr + bytes.length) + "], 0");

                assembly.add("MOV [" + slotAddr + "], " + strAddr);
                fieldType = Type.STRING;
            } else if (valStr.startsWith("'") && valStr.endsWith("'")) {
                int charVal = VirtualCpu.parseCharLiteral(valStr.substring(1, valStr.length() - 1));
                assembly.add("MOV [" + slotAddr + "], " + charVal);
                fieldType = Type.CHAR;
            } else if (valStr.equalsIgnoreCase("true") || valStr.equalsIgnoreCase("false")) {
                int boolVal = valStr.equalsIgnoreCase("true") ? 1 : 0;
                assembly.add("MOV [" + slotAddr + "], " + boolVal);
                fieldType = Type.BOOLEAN;
            } else {
                try {
                    int num = Integer.parseInt(valStr);
                    assembly.add("MOV [" + slotAddr + "], " + num);
                    fieldType = Type.INT;
                } catch (NumberFormatException e) {
                    // Variable reference
                    String loc = resolveLocation(valStr, varMap, tableMap, assembly);
                    assembly.add("MOV A, " + loc);
                    assembly.add("MOV [" + slotAddr + "], A");
                    fieldType = typeMap.getOrDefault(valStr.toLowerCase(Locale.ROOT), Type.INT);
                }
            }

            table.fields.put(key.toLowerCase(Locale.ROOT), new FieldInfo(offset, fieldType));
        }

        tableMap.put(name.toLowerCase(Locale.ROOT), table);
        typeMap.put(name.toLowerCase(Locale.ROOT), Type.TABLE);
        varMap.put(name.toLowerCase(Locale.ROOT), String.valueOf(baseAddr));
        return nextRam;
    }

    private static List<String> parseTableElements(String s) {
        List<String> items = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;
        int depth = 0;

        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ((ch == '\"' || ch == '\'') && (i == 0 || s.charAt(i - 1) != '\\')) {
                if (inQuotes && ch == quoteChar) {
                    inQuotes = false;
                } else if (!inQuotes) {
                    inQuotes = true;
                    quoteChar = ch;
                }
            } else if (!inQuotes) {
                if (ch == '{' || ch == '[') depth++;
                else if (ch == '}' || ch == ']') depth--;
                else if (ch == ',' && depth == 0) {
                    items.add(sb.toString().trim());
                    sb.setLength(0);
                    continue;
                }
            }
            sb.append(ch);
        }
        if (sb.length() > 0 && !sb.toString().trim().isEmpty()) {
            items.add(sb.toString().trim());
        }
        return items;
    }

    private static int allocateStringLiteral(String name, String expr, int nextRam, Map<String, String> varMap,
                                             Map<String, Type> typeMap, List<String> assembly) {
        String content = unescapeString(expr.substring(1, expr.length() - 1));
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        int baseAddr = nextRam;
        int allocSize = bytes.length + 1;

        assembly.add("; Initialize string '" + name + "' at RAM [" + baseAddr + "]");
        for (int i = 0; i < bytes.length; i++) {
            assembly.add("MOV [" + (baseAddr + i) + "], " + (bytes[i] & 0xFF));
        }
        assembly.add("MOV [" + (baseAddr + bytes.length) + "], 0");

        nextRam += allocSize;
        String lowerName = name.toLowerCase(Locale.ROOT);
        varMap.put(lowerName, String.valueOf(baseAddr));
        typeMap.put(lowerName, Type.STRING);
        return nextRam;
    }

    private static void compileTableOrArrayWrite(String target, String expr, Map<String, String> varMap,
                                                 Map<String, Type> typeMap, Map<String, TableInfo> tableMap, List<String> assembly) {
        target = target.trim();
        String tblName;
        String key;

        if (target.contains(".")) {
            String[] parts = target.split("\\.", 2);
            tblName = parts[0].trim().toLowerCase(Locale.ROOT);
            key = parts[1].trim().toLowerCase(Locale.ROOT);
        } else {
            int open = target.indexOf('[');
            tblName = target.substring(0, open).trim().toLowerCase(Locale.ROOT);
            key = target.substring(open + 1, target.length() - 1).trim();
            if ((key.startsWith("\"") && key.endsWith("\"")) || (key.startsWith("'") && key.endsWith("'"))) {
                key = key.substring(1, key.length() - 1).trim().toLowerCase(Locale.ROOT);
            }
        }

        if (!tableMap.containsKey(tblName)) return;
        TableInfo table = tableMap.get(tblName);

        // Constant integer offset or named field
        int offset = -1;
        try {
            offset = Integer.parseInt(key);
        } catch (NumberFormatException ignored) {}

        if (offset == -1 && table.fields.containsKey(key)) {
            offset = table.fields.get(key).offset;
        }

        if (offset != -1) {
            int targetAddr = table.baseAddr + offset;
            compileAssignment("[" + targetAddr + "]", expr, varMap, tableMap, assembly);
        } else {
            // Dynamic variable index: tbl[i] = expr
            String idxLoc = resolveLocation(key, varMap, tableMap, assembly);
            assembly.add("MOV B, " + table.baseAddr);
            assembly.add("ADD B, " + idxLoc);
            String srcLoc = resolveLocation(expr, varMap, tableMap, assembly);
            assembly.add("MOV A, " + srcLoc);
            assembly.add("MOV [B], A");
        }
    }

    private static void compileAssignment(String targetLoc, String expr, Map<String, String> varMap,
                                          Map<String, TableInfo> tableMap, List<String> assembly) {
        expr = expr.trim();

        // Check for binary operations: +, -, *, /, % (respecting precedence: + and - lower than *, /, %)
        int addSubIdx = -1;
        String addSubOp = null;
        for (int i = expr.length() - 1; i > 0; i--) {
            char c = expr.charAt(i);
            if (c == '+' || c == '-') {
                addSubIdx = i;
                addSubOp = (c == '+') ? "ADD" : "SUB";
                break;
            }
        }

        if (addSubIdx > 0) {
            String left = expr.substring(0, addSubIdx).trim();
            String right = expr.substring(addSubIdx + 1).trim();
            compileAssignment(targetLoc, left, varMap, tableMap, assembly);
            String rightLoc = resolveLocation(right, varMap, tableMap, assembly);
            if (targetLoc.startsWith("[") && rightLoc.startsWith("[")) {
                assembly.add("MOV A, " + rightLoc);
                assembly.add(addSubOp + " " + targetLoc + ", A");
            } else {
                assembly.add(addSubOp + " " + targetLoc + ", " + rightLoc);
            }
            return;
        }

        int mulDivIdx = -1;
        String mulDivOp = null;
        for (int i = expr.length() - 1; i > 0; i--) {
            char c = expr.charAt(i);
            if (c == '*' || c == '/' || c == '%') {
                mulDivIdx = i;
                mulDivOp = (c == '*') ? "MUL" : (c == '/' ? "DIV" : "MOD");
                break;
            }
        }

        if (mulDivIdx > 0) {
            String left = expr.substring(0, mulDivIdx).trim();
            String right = expr.substring(mulDivIdx + 1).trim();
            compileAssignment(targetLoc, left, varMap, tableMap, assembly);
            String rightLoc = resolveLocation(right, varMap, tableMap, assembly);
            if (targetLoc.startsWith("[") && rightLoc.startsWith("[")) {
                assembly.add("MOV A, " + rightLoc);
                assembly.add(mulDivOp + " " + targetLoc + ", A");
            } else {
                assembly.add(mulDivOp + " " + targetLoc + ", " + rightLoc);
            }
            return;
        }

        if (expr.startsWith("rand(") && expr.endsWith(")")) {
            String inner = expr.substring(5, expr.length() - 1).trim();
            String maxLoc = resolveLocation(inner, varMap, tableMap, assembly);
            assembly.add("RAND " + targetLoc + ", " + maxLoc);
        } else if (expr.endsWith(")") && expr.contains("(") && !expr.startsWith("{") && !expr.equalsIgnoreCase("time.now()")) {
            String funcName = expr.substring(0, expr.indexOf('(')).trim();
            assembly.add("CALL " + funcName);
            if (!targetLoc.equalsIgnoreCase("A")) {
                assembly.add("MOV " + targetLoc + ", A");
            }
        } else {
            String srcLoc = resolveLocation(expr, varMap, tableMap, assembly);
            if (targetLoc.startsWith("[") && srcLoc.startsWith("[")) {
                assembly.add("MOV A, " + srcLoc);
                assembly.add("MOV " + targetLoc + ", A");
            } else {
                assembly.add("MOV " + targetLoc + ", " + srcLoc);
            }
        }
    }

    private static void compileBinaryOp(String targetLoc, String left, String right, String op,
                                        Map<String, String> varMap, Map<String, TableInfo> tableMap, List<String> assembly) {
        String leftLoc = resolveLocation(left, varMap, tableMap, assembly);
        String rightLoc = resolveLocation(right, varMap, tableMap, assembly);

        if (targetLoc.equalsIgnoreCase(leftLoc)) {
            if (targetLoc.startsWith("[") && rightLoc.startsWith("[")) {
                assembly.add("MOV A, " + rightLoc);
                assembly.add(op + " " + targetLoc + ", A");
            } else {
                assembly.add(op + " " + targetLoc + ", " + rightLoc);
            }
        } else {
            if (targetLoc.startsWith("[") && leftLoc.startsWith("[")) {
                assembly.add("MOV A, " + leftLoc);
                assembly.add("MOV " + targetLoc + ", A");
            } else {
                assembly.add("MOV " + targetLoc + ", " + leftLoc);
            }
            if (targetLoc.startsWith("[") && rightLoc.startsWith("[")) {
                assembly.add("MOV A, " + rightLoc);
                assembly.add(op + " " + targetLoc + ", A");
            } else {
                assembly.add(op + " " + targetLoc + ", " + rightLoc);
            }
        }
    }

    private static void compileAdd(String targetLoc, String expr, Map<String, String> varMap,
                                   Map<String, TableInfo> tableMap, List<String> assembly) {
        String srcLoc = resolveLocation(expr, varMap, tableMap, assembly);
        if (targetLoc.startsWith("[") && srcLoc.startsWith("[")) {
            assembly.add("MOV A, " + srcLoc);
            assembly.add("ADD " + targetLoc + ", A");
        } else {
            assembly.add("ADD " + targetLoc + ", " + srcLoc);
        }
    }

    private static void compileSub(String targetLoc, String expr, Map<String, String> varMap,
                                   Map<String, TableInfo> tableMap, List<String> assembly) {
        String srcLoc = resolveLocation(expr, varMap, tableMap, assembly);
        if (targetLoc.startsWith("[") && srcLoc.startsWith("[")) {
            assembly.add("MOV A, " + srcLoc);
            assembly.add("SUB " + targetLoc + ", A");
        } else {
            assembly.add("SUB " + targetLoc + ", " + srcLoc);
        }
    }

    // Unified print / println converting all types to string / char array output
    private static void compilePrint(String arg, boolean isPrintln, int id, Map<String, String> varMap,
                                     Map<String, Type> typeMap, Map<String, TableInfo> tableMap, List<String> assembly) {
        arg = arg.trim();

        if (arg.isEmpty()) {
            if (isPrintln) {
                assembly.add("OUTC 10");
            }
            return;
        }

        // 1. String literal: "hello"
        if (arg.startsWith("\"") && arg.endsWith("\"")) {
            if (isPrintln) {
                assembly.add("OUT " + arg);
            } else {
                assembly.add("OUT " + arg + ", 0");
            }
            return;
        }

        // 2. Char literal: 'A'
        if (arg.startsWith("'") && arg.endsWith("'")) {
            assembly.add("OUTC " + arg);
            if (isPrintln) {
                assembly.add("OUTC 10");
            }
            return;
        }

        // 3. Boolean literal: true / false
        if (arg.equalsIgnoreCase("true")) {
            if (isPrintln) {
                assembly.add("OUT \"true\"");
            } else {
                assembly.add("OUT \"true\", 0");
            }
            return;
        }
        if (arg.equalsIgnoreCase("false")) {
            if (isPrintln) {
                assembly.add("OUT \"false\"");
            } else {
                assembly.add("OUT \"false\", 0");
            }
            return;
        }

        // time.now()
        if (arg.equalsIgnoreCase("time.now()") || arg.equalsIgnoreCase("time.now")) {
            assembly.add("TIME A");
            if (isPrintln) {
                assembly.add("OUT A");
            } else {
                assembly.add("OUT A, 0");
            }
            return;
        }

        // rand(max)
        if (arg.startsWith("rand(") && arg.endsWith(")")) {
            String inner = arg.substring(5, arg.length() - 1).trim();
            String maxLoc = resolveLocation(inner, varMap, tableMap, assembly);
            assembly.add("RAND A, " + maxLoc);
            if (isPrintln) {
                assembly.add("OUT A");
            } else {
                assembly.add("OUT A, 0");
            }
            return;
        }

        // 4. Arithmetic expression: a + b, x - 1, a * b, a / b, a % b
        if (!arg.startsWith("\"") && !arg.startsWith("'") && (arg.contains("+") || arg.contains("-") || arg.contains("*") || arg.contains("/") || arg.contains("%"))) {
            compileAssignment("A", arg, varMap, tableMap, assembly);
            if (isPrintln) {
                assembly.add("OUT A");
            } else {
                assembly.add("OUT A, 0");
            }
            return;
        }

        // 5. Table field access: user.name or user["age"] or nums[0] or nums[i]
        if (arg.contains(".") || (arg.contains("[") && arg.endsWith("]"))) {
            String tblName;
            String fieldKey;

            if (arg.contains(".")) {
                String[] parts = arg.split("\\.", 2);
                tblName = parts[0].trim().toLowerCase(Locale.ROOT);
                fieldKey = parts[1].trim().toLowerCase(Locale.ROOT);
            } else {
                int open = arg.indexOf('[');
                tblName = arg.substring(0, open).trim().toLowerCase(Locale.ROOT);
                fieldKey = arg.substring(open + 1, arg.length() - 1).trim();
                if ((fieldKey.startsWith("\"") && fieldKey.endsWith("\"")) || (fieldKey.startsWith("'") && fieldKey.endsWith("'"))) {
                    fieldKey = fieldKey.substring(1, fieldKey.length() - 1).trim().toLowerCase(Locale.ROOT);
                }
            }

            if (tableMap.containsKey(tblName)) {
                TableInfo table = tableMap.get(tblName);
                if (table.fields.containsKey(fieldKey)) {
                    FieldInfo f = table.fields.get(fieldKey);
                    int addr = table.baseAddr + f.offset;

                    if (f.type == Type.STRING) {
                        emitPrintStringFromPointer("[" + addr + "]", id, isPrintln, assembly);
                        return;
                    } else if (f.type == Type.BOOLEAN) {
                        emitPrintBoolean("[" + addr + "]", id, isPrintln, assembly);
                        return;
                    } else if (f.type == Type.CHAR) {
                        assembly.add("OUTC [" + addr + "]");
                        if (isPrintln) {
                            assembly.add("OUTC 10");
                        }
                        return;
                    } else {
                        if (isPrintln) {
                            assembly.add("OUT [" + addr + "]");
                        } else {
                            assembly.add("OUT [" + addr + "], 0");
                        }
                        return;
                    }
                } else {
                    // Constant integer offset or variable offset
                    try {
                        int idx = Integer.parseInt(fieldKey);
                        if (isPrintln) {
                            assembly.add("OUT [" + (table.baseAddr + idx) + "]");
                        } else {
                            assembly.add("OUT [" + (table.baseAddr + idx) + "], 0");
                        }
                        return;
                    } catch (NumberFormatException ignored) {
                        // Dynamic index: tbl[i]
                        String idxLoc = resolveLocation(fieldKey, varMap, tableMap, assembly);
                        assembly.add("MOV B, " + table.baseAddr);
                        assembly.add("ADD B, " + idxLoc);
                        if (isPrintln) {
                            assembly.add("OUT [B]");
                        } else {
                            assembly.add("OUT [B], 0");
                        }
                        return;
                    }
                }
            }
        }

        // 6. Variable print by Type
        String lowerArg = arg.toLowerCase(Locale.ROOT);
        Type t = typeMap.getOrDefault(lowerArg, Type.INT);

        if (t == Type.STRING) {
            String baseAddr = varMap.get(lowerArg);
            emitPrintStringDirect(baseAddr, id, isPrintln, assembly);
        } else if (t == Type.BOOLEAN) {
            String loc = resolveLocation(arg, varMap, tableMap, assembly);
            emitPrintBoolean(loc, id, isPrintln, assembly);
        } else if (t == Type.CHAR) {
            String loc = resolveLocation(arg, varMap, tableMap, assembly);
            assembly.add("OUTC " + loc);
            if (isPrintln) {
                assembly.add("OUTC 10");
            }
        } else {
            // Integer, register, or general value
            String loc = resolveLocation(arg, varMap, tableMap, assembly);
            if (isPrintln) {
                assembly.add("OUT " + loc);
            } else {
                assembly.add("OUT " + loc + ", 0");
            }
        }
    }

    private static void emitPrintStringDirect(String baseAddr, int id, boolean isPrintln, List<String> assembly) {
        String loopLabel = "_print_str_" + id;
        String endLabel = "_print_str_end_" + id;

        assembly.add("MOV B, " + baseAddr);
        assembly.add(loopLabel + ":");
        assembly.add("MOV A, [B]");
        assembly.add("CMP A, 0");
        assembly.add("JZ " + endLabel);
        assembly.add("OUTC A");
        assembly.add("INC B");
        assembly.add("JMP " + loopLabel);
        assembly.add(endLabel + ":");
        if (isPrintln) {
            assembly.add("OUTC 10");
        }
    }

    private static void emitPrintStringFromPointer(String ptrLoc, int id, boolean isPrintln, List<String> assembly) {
        String loopLabel = "_print_str_ptr_" + id;
        String endLabel = "_print_str_ptr_end_" + id;

        assembly.add("MOV B, " + ptrLoc);
        assembly.add(loopLabel + ":");
        assembly.add("MOV A, [B]");
        assembly.add("CMP A, 0");
        assembly.add("JZ " + endLabel);
        assembly.add("OUTC A");
        assembly.add("INC B");
        assembly.add("JMP " + loopLabel);
        assembly.add(endLabel + ":");
        if (isPrintln) {
            assembly.add("OUTC 10");
        }
    }

    private static void emitPrintBoolean(String loc, int id, boolean isPrintln, List<String> assembly) {
        String falseLabel = "_print_bool_false_" + id;
        String endLabel = "_print_bool_end_" + id;

        assembly.add("MOV A, " + loc);
        assembly.add("CMP A, 0");
        assembly.add("JZ " + falseLabel);
        if (isPrintln) {
            assembly.add("OUT \"true\"");
        } else {
            assembly.add("OUT \"true\", 0");
        }
        assembly.add("JMP " + endLabel);
        assembly.add(falseLabel + ":");
        if (isPrintln) {
            assembly.add("OUT \"false\"");
        } else {
            assembly.add("OUT \"false\", 0");
        }
        assembly.add(endLabel + ":");
    }

    private static void compileCondition(String cond, String branchLabel, boolean jumpOnTrue,
                                         Map<String, String> varMap, Map<String, TableInfo> tableMap, List<String> assembly) {
        cond = cond.trim();

        // Boolean literal condition: while true { ... }
        if (cond.equalsIgnoreCase("true")) {
            if (jumpOnTrue) {
                assembly.add("JMP " + branchLabel);
            }
            return;
        }
        if (cond.equalsIgnoreCase("false")) {
            if (!jumpOnTrue) {
                assembly.add("JMP " + branchLabel);
            }
            return;
        }

        // Negated condition: if !flag { ... }
        if (cond.startsWith("!")) {
            String inner = cond.substring(1).trim();
            String loc = resolveLocation(inner, varMap, tableMap, assembly);
            assembly.add("CMP " + loc + ", 0");
            assembly.add(jumpOnTrue ? ("JZ " + branchLabel) : ("JNZ " + branchLabel));
            return;
        }

        String op = null;
        if (cond.contains("==")) op = "==";
        else if (cond.contains("!=")) op = "!=";
        else if (cond.contains(">=")) op = ">=";
        else if (cond.contains("<=")) op = "<=";
        else if (cond.contains(">")) op = ">";
        else if (cond.contains("<")) op = "<";

        if (op == null) {
            // Truthy check: if flag != 0
            String loc = resolveLocation(cond, varMap, tableMap, assembly);
            assembly.add("CMP " + loc + ", 0");
            assembly.add(jumpOnTrue ? ("JNZ " + branchLabel) : ("JZ " + branchLabel));
            return;
        }

        String[] parts = cond.split(op, 2);
        String leftRaw = parts[0].trim();
        String rightRaw = parts[1].trim();

        String leftLoc = resolveLocation(leftRaw, varMap, tableMap, assembly);
        String rightLoc = resolveLocation(rightRaw, varMap, tableMap, assembly);

        if (leftLoc.startsWith("[") && rightLoc.startsWith("[")) {
            assembly.add("MOV A, " + leftLoc);
            leftLoc = "A";
        }

        assembly.add("CMP " + leftLoc + ", " + rightLoc);

        switch (op) {
            case "==" -> assembly.add(jumpOnTrue ? ("JZ " + branchLabel) : ("JNZ " + branchLabel));
            case "!=" -> assembly.add(jumpOnTrue ? ("JNZ " + branchLabel) : ("JZ " + branchLabel));
            case ">"  -> assembly.add(jumpOnTrue ? ("JG " + branchLabel) : ("JLE " + branchLabel));
            case ">=" -> assembly.add(jumpOnTrue ? ("JGE " + branchLabel) : ("JL " + branchLabel));
            case "<"  -> assembly.add(jumpOnTrue ? ("JL " + branchLabel) : ("JGE " + branchLabel));
            case "<=" -> assembly.add(jumpOnTrue ? ("JLE " + branchLabel) : ("JG " + branchLabel));
        }
    }

    public static String unescapeString(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n' -> { sb.append('\n'); i++; }
                    case 't' -> { sb.append('\t'); i++; }
                    case 'r' -> { sb.append('\r'); i++; }
                    case '0' -> { sb.append('\0'); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    case '\'' -> { sb.append('\''); i++; }
                    case '\"' -> { sb.append('\"'); i++; }
                    default -> sb.append(ch);
                }
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private record LoopContext(String startLabel, String endLabel, int blockDepth) {}
    private record IfContext(String endLabel, int blockDepth) {}
    private record FuncContext(String name, String endLabel, int blockDepth) {}
}
