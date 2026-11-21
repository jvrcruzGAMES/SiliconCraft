package games.jvrcruz.siliconcraft.compiler;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public class NPrintCompiler {

    // Unified print / println converting all types to string / char array output
    public static void compilePrint(String arg, boolean isPrintln, int id, Map<String, String> varMap,
                                    Map<String, NType.Type> typeMap, Map<String, NType.TableInfo> tableMap, List<String> assembly) {
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
            String maxLoc = NExpressionCompiler.resolveLocation(inner, varMap, tableMap, assembly);
            assembly.add("RAND A, " + maxLoc);
            if (isPrintln) {
                assembly.add("OUT A");
            } else {
                assembly.add("OUT A, 0");
            }
            return;
        }

        // hex(...) inside print(...) or println(...)
        if (arg.startsWith("hex(") && arg.endsWith(")")) {
            String inner = NExpressionCompiler.extractParenthesesOrRest(arg);
            compilePrintHex(inner, isPrintln, id, varMap, typeMap, tableMap, assembly);
            return;
        }

        // 4. Arithmetic or bitwise expression: a + b, x - 1, a * b, a / b, a % b, a & b, a | b, a ^ b, a << b, a >> b
        if (!arg.startsWith("\"") && !arg.startsWith("'") && (arg.contains("+") || arg.contains("-") || arg.contains("*") || arg.contains("/") || arg.contains("%")
                || arg.contains("&") || arg.contains("|") || arg.contains("^") || arg.contains("<<") || arg.contains(">>"))) {
            NExpressionCompiler.compileAssignment("A", arg, varMap, tableMap, assembly);
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
                NType.TableInfo table = tableMap.get(tblName);
                if (table.fields.containsKey(fieldKey)) {
                    NType.FieldInfo f = table.fields.get(fieldKey);
                    int addr = table.baseAddr + f.offset;

                    if (f.type == NType.Type.STRING) {
                        emitPrintStringFromPointer("[" + addr + "]", id, isPrintln, assembly);
                        return;
                    } else if (f.type == NType.Type.BOOLEAN) {
                        emitPrintBoolean("[" + addr + "]", id, isPrintln, assembly);
                        return;
                    } else if (f.type == NType.Type.CHAR) {
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
                    // Constant integer/hex offset or variable offset
                    try {
                        int idx = NNumberParser.parseNumber(fieldKey);
                        if (isPrintln) {
                            assembly.add("OUT [" + (table.baseAddr + idx) + "]");
                        } else {
                            assembly.add("OUT [" + (table.baseAddr + idx) + "], 0");
                        }
                        return;
                    } catch (NumberFormatException ignored) {
                        // Dynamic index: tbl[i]
                        String idxLoc = NExpressionCompiler.resolveLocation(fieldKey, varMap, tableMap, assembly);
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
        NType.Type t = typeMap.getOrDefault(lowerArg, NType.Type.INT);

        if (t == NType.Type.STRING) {
            String baseAddr = varMap.get(lowerArg);
            emitPrintStringDirect(baseAddr, id, isPrintln, assembly);
        } else if (t == NType.Type.BOOLEAN) {
            String loc = NExpressionCompiler.resolveLocation(arg, varMap, tableMap, assembly);
            emitPrintBoolean(loc, id, isPrintln, assembly);
        } else if (t == NType.Type.CHAR) {
            String loc = NExpressionCompiler.resolveLocation(arg, varMap, tableMap, assembly);
            assembly.add("OUTC " + loc);
            if (isPrintln) {
                assembly.add("OUTC 10");
            }
        } else {
            // Integer, register, or general value
            String loc = NExpressionCompiler.resolveLocation(arg, varMap, tableMap, assembly);
            if (isPrintln) {
                assembly.add("OUT " + loc);
            } else {
                assembly.add("OUT " + loc + ", 0");
            }
        }
    }

    public static void compilePrintHex(String arg, boolean isPrintln, int id,
                                       Map<String, String> varMap, Map<String, NType.Type> typeMap,
                                       Map<String, NType.TableInfo> tableMap, List<String> assembly) {
        arg = arg.trim();
        if (arg.isEmpty()) {
            if (isPrintln) assembly.add("OUTC 10");
            return;
        }
        if (NNumberParser.isNumeric(arg)) {
            if (isPrintln) {
                assembly.add("OUTH " + arg);
            } else {
                assembly.add("OUTH " + arg + ", 0");
            }
            return;
        }
        if (!arg.startsWith("\"") && !arg.startsWith("'") && (arg.contains("+") || arg.contains("-") || arg.contains("*") || arg.contains("/") || arg.contains("%")
                || arg.contains("&") || arg.contains("|") || arg.contains("^") || arg.contains("<<") || arg.contains(">>"))) {
            NExpressionCompiler.compileAssignment("A", arg, varMap, tableMap, assembly);
            if (isPrintln) {
                assembly.add("OUTH A");
            } else {
                assembly.add("OUTH A, 0");
            }
            return;
        }
        String loc = NExpressionCompiler.resolveLocation(arg, varMap, tableMap, assembly);
        if (isPrintln) {
            assembly.add("OUTH " + loc);
        } else {
            assembly.add("OUTH " + loc + ", 0");
        }
    }

    public static void emitPrintStringDirect(String baseAddr, int id, boolean isPrintln, List<String> assembly) {
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

    public static void emitPrintStringFromPointer(String ptrLoc, int id, boolean isPrintln, List<String> assembly) {
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

    public static void emitPrintBoolean(String loc, int id, boolean isPrintln, List<String> assembly) {
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
}
