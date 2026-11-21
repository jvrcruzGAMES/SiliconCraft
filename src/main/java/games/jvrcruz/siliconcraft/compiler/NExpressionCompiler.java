package games.jvrcruz.siliconcraft.compiler;

import games.jvrcruz.siliconcraft.vm.VirtualCpu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class NExpressionCompiler {

    public static String getFirstWord(String line) {
        int space = line.indexOf(' ');
        return (space == -1 ? line : line.substring(0, space)).trim();
    }

    public static String extractParenthesesOrRest(String line) {
        int openParen = line.indexOf('(');
        if (openParen != -1 && line.endsWith(")")) {
            return line.substring(openParen + 1, line.length() - 1).trim();
        }
        int space = line.indexOf(' ');
        return (space != -1) ? line.substring(space + 1).trim() : "";
    }

    public static List<String> splitArgs(String s) {
        List<String> list = new ArrayList<>();
        if (s == null || s.trim().isEmpty()) return list;
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        int parenDepth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\"') {
                inQuotes = !inQuotes;
                sb.append(c);
            } else if (!inQuotes && c == '(') {
                parenDepth++;
                sb.append(c);
            } else if (!inQuotes && c == ')') {
                parenDepth--;
                sb.append(c);
            } else if (!inQuotes && parenDepth == 0 && c == ',') {
                list.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0) {
            list.add(sb.toString().trim());
        }
        return list;
    }

    public static boolean isRegister(String token) {
        String u = token.toUpperCase(Locale.ROOT);
        return u.equals("A") || u.equals("B") || u.equals("C") || u.equals("D");
    }

    public static NType.Type inferType(String expr, Map<String, NType.Type> typeMap) {
        expr = expr.trim();
        if (expr.startsWith("\"") && expr.endsWith("\"")) return NType.Type.STRING;
        if (expr.startsWith("'") && expr.endsWith("'")) return NType.Type.CHAR;
        if (expr.equalsIgnoreCase("true") || expr.equalsIgnoreCase("false")) return NType.Type.BOOLEAN;
        if (expr.startsWith("{") && expr.endsWith("}")) return NType.Type.TABLE;
        if (expr.startsWith("storage.read_string(") || expr.startsWith("storage.readString(")) return NType.Type.STRING;
        if (expr.startsWith("storage.read(") || expr.startsWith("storage.get(") || expr.startsWith("storage.read_int(") || expr.startsWith("storage.readInt(") || expr.startsWith("storage.size")) return NType.Type.INT;
        if (expr.startsWith("display.get(") || expr.startsWith("fb.get(") ||
            expr.equalsIgnoreCase("display.width()") || expr.equalsIgnoreCase("fb.width()") ||
            expr.equalsIgnoreCase("display.height()") || expr.equalsIgnoreCase("fb.height()") ||
            expr.equalsIgnoreCase("audio.status()") || expr.equalsIgnoreCase("audio.size()")) return NType.Type.INT;
        return typeMap.getOrDefault(expr.toLowerCase(Locale.ROOT), NType.Type.INT);
    }

    public static String resolveLocation(String token, Map<String, String> varMap, Map<String, NType.TableInfo> tableMap, List<String> assembly) {
        token = token.trim();
        if (isRegister(token)) {
            return token.toUpperCase(Locale.ROOT);
        }

        // Address-of operator: &var or &tbl.field or &tbl[0]
        if (token.startsWith("&")) {
            String target = token.substring(1).trim();
            if (target.contains(".")) {
                String[] parts = target.split("\\.", 2);
                String tblName = parts[0].trim().toLowerCase(Locale.ROOT);
                String fieldName = parts[1].trim().toLowerCase(Locale.ROOT);
                if (tableMap.containsKey(tblName)) {
                    NType.TableInfo table = tableMap.get(tblName);
                    if (table.fields.containsKey(fieldName)) {
                        NType.FieldInfo f = table.fields.get(fieldName);
                        return String.valueOf(table.baseAddr + f.offset);
                    }
                }
            } else if (target.contains("[") && target.endsWith("]")) {
                int openBracket = target.indexOf('[');
                String tblName = target.substring(0, openBracket).trim().toLowerCase(Locale.ROOT);
                String idxExpr = target.substring(openBracket + 1, target.length() - 1).trim();
                if (tableMap.containsKey(tblName)) {
                    NType.TableInfo table = tableMap.get(tblName);
                    if ((idxExpr.startsWith("\"") && idxExpr.endsWith("\"")) || (idxExpr.startsWith("'") && idxExpr.endsWith("'"))) {
                        String key = idxExpr.substring(1, idxExpr.length() - 1).toLowerCase(Locale.ROOT);
                        if (table.fields.containsKey(key)) {
                            return String.valueOf(table.baseAddr + table.fields.get(key).offset);
                        }
                    }
                    if (NNumberParser.isNumeric(idxExpr)) {
                        try {
                            int constIdx = NNumberParser.parseNumber(idxExpr);
                            return String.valueOf(table.baseAddr + constIdx);
                        } catch (NumberFormatException ignored) {}
                    }
                    if (assembly != null) {
                        String idxLoc = resolveLocation(idxExpr, varMap, tableMap, assembly);
                        assembly.add("MOV B, " + table.baseAddr);
                        assembly.add("ADD B, " + idxLoc);
                        return "B";
                    }
                }
            }
            String lower = target.toLowerCase(Locale.ROOT);
            if (varMap.containsKey(lower)) {
                String loc = varMap.get(lower);
                if (loc.startsWith("[") && loc.endsWith("]")) {
                    return loc.substring(1, loc.length() - 1);
                }
                return loc;
            }
        }

        // Pointer dereferencing: *ptr or *0x10 or *16
        if (token.startsWith("*")) {
            String inner = token.substring(1).trim();
            if (NNumberParser.isNumeric(inner)) {
                return "[" + inner + "]";
            }
            if (isRegister(inner)) {
                return "[" + inner.toUpperCase(Locale.ROOT) + "]";
            }
            String ptrLoc = resolveLocation(inner, varMap, tableMap, assembly);
            if (assembly != null) {
                assembly.add("MOV B, " + ptrLoc);
                assembly.add("MOV A, [B]");
            }
            return "A";
        }

        // Direct RAM memory bracket read: mem[addr], ram[addr], memory[addr]
        if ((token.startsWith("mem[") || token.startsWith("ram[") || token.startsWith("memory[")) && token.endsWith("]")) {
            int openBracket = token.indexOf('[');
            String aExpr = token.substring(openBracket + 1, token.length() - 1).trim();
            if (NNumberParser.isNumeric(aExpr)) {
                return "[" + aExpr + "]";
            }
            if (assembly != null) {
                String aLoc = resolveLocation(aExpr, varMap, tableMap, assembly);
                assembly.add("MOV B, " + aLoc);
                assembly.add("MOV A, [B]");
            }
            return "A";
        }

        // Direct RAM read helper: mem.read(addr), ram.read(addr), memory.read(addr), peek(addr)
        if ((token.startsWith("mem.read(") || token.startsWith("ram.read(") || token.startsWith("memory.read(") ||
             token.startsWith("peek(") || token.startsWith("mem.get(") || token.startsWith("ram.get(")) && token.endsWith(")")) {
            String aExpr = extractParenthesesOrRest(token);
            if (NNumberParser.isNumeric(aExpr)) {
                return "[" + aExpr + "]";
            }
            if (assembly != null) {
                String aLoc = resolveLocation(aExpr, varMap, tableMap, assembly);
                assembly.add("MOV B, " + aLoc);
                assembly.add("MOV A, [B]");
            }
            return "A";
        }

        // Table dot access: tbl.field
        if (token.contains(".")) {
            String[] parts = token.split("\\.", 2);
            String tblName = parts[0].trim().toLowerCase(Locale.ROOT);
            String fieldName = parts[1].trim().toLowerCase(Locale.ROOT);
            if (tableMap.containsKey(tblName)) {
                NType.TableInfo table = tableMap.get(tblName);
                if (table.fields.containsKey(fieldName)) {
                    NType.FieldInfo f = table.fields.get(fieldName);
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
                NType.TableInfo table = tableMap.get(tblName);
                // String key: tbl["name"]
                if ((idxExpr.startsWith("\"") && idxExpr.endsWith("\"")) || (idxExpr.startsWith("'") && idxExpr.endsWith("'"))) {
                    String key = idxExpr.substring(1, idxExpr.length() - 1).toLowerCase(Locale.ROOT);
                    if (table.fields.containsKey(key)) {
                        NType.FieldInfo f = table.fields.get(key);
                        return "[" + (table.baseAddr + f.offset) + "]";
                    }
                }
                // Constant integer/hex index: tbl[0] or tbl[0x05]
                try {
                    int constIdx = NNumberParser.parseNumber(idxExpr);
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

        // Storage capacity in bytes
        if (token.equalsIgnoreCase("storage.size()") || token.equalsIgnoreCase("storage.size")) {
            if (assembly != null) {
                assembly.add("SSIZE A");
            }
            return "A";
        }

        // Storage byte read: storage.read(addr) or storage.get(addr)
        if ((token.startsWith("storage.read(") || token.startsWith("storage.get(")) && token.endsWith(")")) {
            String addrExpr = extractParenthesesOrRest(token);
            String addrLoc = resolveLocation(addrExpr, varMap, tableMap, assembly);
            if (assembly != null) {
                assembly.add("SREAD A, " + addrLoc);
            }
            return "A";
        }

        // Storage 32-bit int read: storage.read_int(addr) or storage.readInt(addr)
        if ((token.startsWith("storage.read_int(") || token.startsWith("storage.readInt(")) && token.endsWith(")")) {
            String addrExpr = extractParenthesesOrRest(token);
            String addrLoc = resolveLocation(addrExpr, varMap, tableMap, assembly);
            if (assembly != null) {
                assembly.add("MOV B, " + addrLoc);
                assembly.add("SREAD A, B");
                assembly.add("INC B");
                assembly.add("SREAD C, B");
                assembly.add("SHL C, 8");
                assembly.add("OR A, C");
                assembly.add("INC B");
                assembly.add("SREAD C, B");
                assembly.add("SHL C, 16");
                assembly.add("OR A, C");
                assembly.add("INC B");
                assembly.add("SREAD C, B");
                assembly.add("SHL C, 24");
                assembly.add("OR A, C");
            }
            return "A";
        }

        // Framebuffer width / height
        if (token.equalsIgnoreCase("display.width()") || token.equalsIgnoreCase("fb.width()")) {
            if (assembly != null) {
                assembly.add("FBWIDTH A");
            }
            return "A";
        }
        if (token.equalsIgnoreCase("display.height()") || token.equalsIgnoreCase("fb.height()")) {
            if (assembly != null) {
                assembly.add("FBHEIGHT A");
            }
            return "A";
        }

        // Framebuffer get pixel: display.get(x, y) / fb.get(x, y)
        if ((token.startsWith("display.get(") || token.startsWith("fb.get(")) && token.endsWith(")")) {
            String inner = extractParenthesesOrRest(token);
            List<String> args = splitArgs(inner);
            if (args.size() == 2 && assembly != null) {
                String xLoc = resolveLocation(args.get(0).trim(), varMap, tableMap, assembly);
                String yLoc = resolveLocation(args.get(1).trim(), varMap, tableMap, assembly);
                assembly.add("FBGET A, " + xLoc + ", " + yLoc);
            }
            return "A";
        }

        // Audio status: audio.status() / audio.size()
        if (token.equalsIgnoreCase("audio.status()") || token.equalsIgnoreCase("audio.size()")) {
            if (assembly != null) {
                assembly.add("ASTAT A");
            }
            return "A";
        }

        // Boolean literal: true / false
        if (token.equalsIgnoreCase("true")) return "1";
        if (token.equalsIgnoreCase("false")) return "0";

        return token;
    }

    public static void compileAssignment(String targetLoc, String expr, Map<String, String> varMap,
                                         Map<String, NType.TableInfo> tableMap, List<String> assembly) {
        expr = expr.trim();
        while (expr.startsWith("(") && expr.endsWith(")") && isMatchingOuterParentheses(expr)) {
            expr = expr.substring(1, expr.length() - 1).trim();
        }

        // Unary bitwise NOT: ~expr
        if (expr.startsWith("~")) {
            compileAssignment(targetLoc, expr.substring(1).trim(), varMap, tableMap, assembly);
            assembly.add("NOT " + targetLoc);
            return;
        }

        // Check for binary operations: |, ^, &, +, -, *, /, %, <<, >> (respecting precedence: lowest precedence split first)
        int orIdx = findBinaryOp(expr, "|");
        if (orIdx > 0) {
            compileBinaryOpInAssignment(targetLoc, expr, orIdx, 1, "OR", varMap, tableMap, assembly);
            return;
        }

        int xorIdx = findBinaryOp(expr, "^");
        if (xorIdx > 0) {
            compileBinaryOpInAssignment(targetLoc, expr, xorIdx, 1, "XOR", varMap, tableMap, assembly);
            return;
        }

        int andIdx = findBinaryOp(expr, "&");
        if (andIdx > 0) {
            compileBinaryOpInAssignment(targetLoc, expr, andIdx, 1, "AND", varMap, tableMap, assembly);
            return;
        }

        int addSubIdx = findBinaryOp(expr, "+", "-");
        if (addSubIdx > 0) {
            String op = (expr.charAt(addSubIdx) == '+') ? "ADD" : "SUB";
            compileBinaryOpInAssignment(targetLoc, expr, addSubIdx, 1, op, varMap, tableMap, assembly);
            return;
        }

        int mulDivIdx = findBinaryOp(expr, "*", "/", "%");
        if (mulDivIdx > 0) {
            char c = expr.charAt(mulDivIdx);
            String op = (c == '*') ? "MUL" : (c == '/' ? "DIV" : "MOD");
            compileBinaryOpInAssignment(targetLoc, expr, mulDivIdx, 1, op, varMap, tableMap, assembly);
            return;
        }

        int shiftIdx = findBinaryOp(expr, "<<", ">>");
        if (shiftIdx > 0) {
            String op = expr.startsWith("<<", shiftIdx) ? "SHL" : "SHR";
            compileBinaryOpInAssignment(targetLoc, expr, shiftIdx, 2, op, varMap, tableMap, assembly);
            return;
        }

        if (expr.startsWith("rand(") && expr.endsWith(")")) {
            String inner = expr.substring(5, expr.length() - 1).trim();
            String maxLoc = resolveLocation(inner, varMap, tableMap, assembly);
            assembly.add("RAND " + targetLoc + ", " + maxLoc);
        } else if (expr.equalsIgnoreCase("storage.count()") || expr.equalsIgnoreCase("storage.count")) {
            assembly.add("SCOUNT " + targetLoc);
        } else if (expr.equalsIgnoreCase("storage.selected()") || expr.equalsIgnoreCase("storage.current()")) {
            assembly.add("SDRV " + targetLoc);
        } else if (expr.startsWith("storage[") && expr.contains("].") && expr.endsWith(")")) {
            int closeBracket = expr.indexOf("].");
            String driveExpr = expr.substring("storage[".length(), closeBracket).trim();
            String methodPart = expr.substring(closeBracket + 2).trim();
            String driveLoc = resolveLocation(driveExpr, varMap, tableMap, assembly);
            if (methodPart.equalsIgnoreCase("size()")) {
                assembly.add("SSIZE " + targetLoc + ", " + driveLoc);
            } else if (methodPart.startsWith("read(") || methodPart.startsWith("get(")) {
                String aExpr = extractParenthesesOrRest(methodPart);
                String aLoc = resolveLocation(aExpr, varMap, tableMap, assembly);
                assembly.add("SREAD " + targetLoc + ", " + driveLoc + ", " + aLoc);
            } else if (methodPart.startsWith("read_int(") || methodPart.startsWith("readInt(")) {
                String aExpr = extractParenthesesOrRest(methodPart);
                String aLoc = resolveLocation(aExpr, varMap, tableMap, assembly);
                assembly.add("MOV B, " + aLoc);
                assembly.add("SREAD A, " + driveLoc + ", B");
                assembly.add("INC B");
                assembly.add("SREAD C, " + driveLoc + ", B");
                assembly.add("SHL C, 8");
                assembly.add("OR A, C");
                assembly.add("INC B");
                assembly.add("SREAD C, " + driveLoc + ", B");
                assembly.add("SHL C, 16");
                assembly.add("OR A, C");
                assembly.add("INC B");
                assembly.add("SREAD C, " + driveLoc + ", B");
                assembly.add("SHL C, 24");
                assembly.add("OR A, C");
                if (!targetLoc.equalsIgnoreCase("A")) {
                    assembly.add("MOV " + targetLoc + ", A");
                }
            }
        } else if (expr.equalsIgnoreCase("storage.size()") || expr.equalsIgnoreCase("storage.size")) {
            assembly.add("SSIZE " + targetLoc);
        } else if (expr.startsWith("storage.size(") && expr.endsWith(")")) {
            String dExpr = extractParenthesesOrRest(expr);
            String dLoc = resolveLocation(dExpr, varMap, tableMap, assembly);
            assembly.add("SSIZE " + targetLoc + ", " + dLoc);
        } else if ((expr.startsWith("storage.read(") || expr.startsWith("storage.get(")) && expr.endsWith(")")) {
            String inner = extractParenthesesOrRest(expr);
            List<String> sArgs = splitArgs(inner);
            if (sArgs.size() == 2) {
                String dLoc = resolveLocation(sArgs.get(0).trim(), varMap, tableMap, assembly);
                String aLoc = resolveLocation(sArgs.get(1).trim(), varMap, tableMap, assembly);
                assembly.add("SREAD " + targetLoc + ", " + dLoc + ", " + aLoc);
            } else {
                String addrLoc = resolveLocation(inner, varMap, tableMap, assembly);
                assembly.add("SREAD " + targetLoc + ", " + addrLoc);
            }
        } else if ((expr.startsWith("storage.read_int(") || expr.startsWith("storage.readInt(")) && expr.endsWith(")")) {
            String inner = extractParenthesesOrRest(expr);
            List<String> sArgs = splitArgs(inner);
            String dLoc = null;
            String aLoc;
            if (sArgs.size() == 2) {
                dLoc = resolveLocation(sArgs.get(0).trim(), varMap, tableMap, assembly);
                aLoc = resolveLocation(sArgs.get(1).trim(), varMap, tableMap, assembly);
            } else {
                aLoc = resolveLocation(inner, varMap, tableMap, assembly);
            }
            assembly.add("MOV B, " + aLoc);
            if (dLoc != null) {
                assembly.add("SREAD A, " + dLoc + ", B");
                assembly.add("INC B");
                assembly.add("SREAD C, " + dLoc + ", B");
                assembly.add("SHL C, 8");
                assembly.add("OR A, C");
                assembly.add("INC B");
                assembly.add("SREAD C, " + dLoc + ", B");
                assembly.add("SHL C, 16");
                assembly.add("OR A, C");
                assembly.add("INC B");
                assembly.add("SREAD C, " + dLoc + ", B");
                assembly.add("SHL C, 24");
                assembly.add("OR A, C");
            } else {
                assembly.add("SREAD A, B");
                assembly.add("INC B");
                assembly.add("SREAD C, B");
                assembly.add("SHL C, 8");
                assembly.add("OR A, C");
                assembly.add("INC B");
                assembly.add("SREAD C, B");
                assembly.add("SHL C, 16");
                assembly.add("OR A, C");
                assembly.add("INC B");
                assembly.add("SREAD C, B");
                assembly.add("SHL C, 24");
                assembly.add("OR A, C");
            }
            if (!targetLoc.equalsIgnoreCase("A")) {
                assembly.add("MOV " + targetLoc + ", A");
            }
        } else if (expr.equalsIgnoreCase("display.width()") || expr.equalsIgnoreCase("fb.width()")) {
            assembly.add("FBWIDTH " + targetLoc);
        } else if (expr.equalsIgnoreCase("display.height()") || expr.equalsIgnoreCase("fb.height()")) {
            assembly.add("FBHEIGHT " + targetLoc);
        } else if ((expr.startsWith("display.get(") || expr.startsWith("fb.get(")) && expr.endsWith(")")) {
            String inner = extractParenthesesOrRest(expr);
            List<String> args = splitArgs(inner);
            if (args.size() == 2) {
                String xLoc = resolveLocation(args.get(0).trim(), varMap, tableMap, assembly);
                String yLoc = resolveLocation(args.get(1).trim(), varMap, tableMap, assembly);
                assembly.add("FBGET " + targetLoc + ", " + xLoc + ", " + yLoc);
            }
        } else if (expr.equalsIgnoreCase("audio.status()") || expr.equalsIgnoreCase("audio.size()")) {
            assembly.add("ASTAT " + targetLoc);
        } else if ((expr.startsWith("mem.read(") || expr.startsWith("ram.read(") || expr.startsWith("memory.read(") ||
                    expr.startsWith("peek(") || expr.startsWith("mem.get(") || expr.startsWith("ram.get(")) && expr.endsWith(")")) {
            String aExpr = extractParenthesesOrRest(expr);
            if (NNumberParser.isNumeric(aExpr)) {
                if (targetLoc.startsWith("[")) {
                    assembly.add("MOV A, [" + aExpr + "]");
                    assembly.add("MOV " + targetLoc + ", A");
                } else {
                    assembly.add("MOV " + targetLoc + ", [" + aExpr + "]");
                }
            } else {
                String aLoc = resolveLocation(aExpr, varMap, tableMap, assembly);
                assembly.add("MOV B, " + aLoc);
                assembly.add("MOV A, [B]");
                if (!targetLoc.equalsIgnoreCase("A")) {
                    assembly.add("MOV " + targetLoc + ", A");
                }
            }
        } else if ((expr.startsWith("mem[") || expr.startsWith("ram[") || expr.startsWith("memory[")) && expr.endsWith("]")) {
            int openBracket = expr.indexOf('[');
            String aExpr = expr.substring(openBracket + 1, expr.length() - 1).trim();
            if (NNumberParser.isNumeric(aExpr)) {
                if (targetLoc.startsWith("[")) {
                    assembly.add("MOV A, [" + aExpr + "]");
                    assembly.add("MOV " + targetLoc + ", A");
                } else {
                    assembly.add("MOV " + targetLoc + ", [" + aExpr + "]");
                }
            } else {
                String aLoc = resolveLocation(aExpr, varMap, tableMap, assembly);
                assembly.add("MOV B, " + aLoc);
                assembly.add("MOV A, [B]");
                if (!targetLoc.equalsIgnoreCase("A")) {
                    assembly.add("MOV " + targetLoc + ", A");
                }
            }
        } else if (expr.endsWith(")") && expr.contains("(") && !expr.startsWith("{") && !expr.contains(".")
                && !expr.equalsIgnoreCase("time.now()") && !expr.startsWith("peek(") && !expr.startsWith("hex(")) {
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

    public static boolean isMatchingOuterParentheses(String s) {
        if (!s.startsWith("(") || !s.endsWith(")")) return false;
        int depth = 0;
        for (int i = 0; i < s.length() - 1; i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return false;
            }
        }
        return depth == 1;
    }

    public static int findBinaryOp(String expr, String... ops) {
        int depth = 0;
        boolean inQuotes = false;
        char quoteChar = 0;
        for (int i = expr.length() - 1; i >= 0; i--) {
            char c = expr.charAt(i);
            if ((c == '\"' || c == '\'') && (i == 0 || expr.charAt(i - 1) != '\\')) {
                if (inQuotes && c == quoteChar) inQuotes = false;
                else if (!inQuotes) { inQuotes = true; quoteChar = c; }
            }
            if (inQuotes) continue;
            if (c == ')') depth++;
            else if (c == '(') depth--;
            else if (depth == 0) {
                for (String op : ops) {
                    if (i + op.length() <= expr.length() && expr.startsWith(op, i)) {
                        if (isValidOpPosition(expr, i, op)) {
                            return i;
                        }
                    }
                }
            }
        }
        return -1;
    }

    public static boolean isValidOpPosition(String expr, int i, String op) {
        if (i <= 0) return false;
        int opLen = op.length();
        if (i + opLen >= expr.length()) return false;

        String leftPrefix = expr.substring(0, i).trim();
        if (leftPrefix.isEmpty()) return false;
        char lastLeft = leftPrefix.charAt(leftPrefix.length() - 1);
        if (lastLeft == '+' || lastLeft == '-' || lastLeft == '*' || lastLeft == '/' || lastLeft == '%'
                || lastLeft == '&' || lastLeft == '|' || lastLeft == '^' || lastLeft == '<' || lastLeft == '>'
                || lastLeft == '=' || lastLeft == '(') {
            return false;
        }

        if (op.equals("|")) {
            if (i > 0 && expr.charAt(i - 1) == '|') return false;
            if (i + 1 < expr.length() && expr.charAt(i + 1) == '|') return false;
        } else if (op.equals("&")) {
            if (i > 0 && expr.charAt(i - 1) == '&') return false;
            if (i + 1 < expr.length() && expr.charAt(i + 1) == '&') return false;
        } else if (op.equals("<")) {
            if (i > 0 && expr.charAt(i - 1) == '<') return false;
            if (i + 1 < expr.length() && expr.charAt(i + 1) == '<') return false;
        } else if (op.equals(">")) {
            if (i > 0 && expr.charAt(i - 1) == '>') return false;
            if (i + 1 < expr.length() && expr.charAt(i + 1) == '>') return false;
        }
        return true;
    }

    public static void compileBinaryOpInAssignment(String targetLoc, String expr, int opIdx, int opLen, String opInstruction,
                                                   Map<String, String> varMap, Map<String, NType.TableInfo> tableMap, List<String> assembly) {
        String left = expr.substring(0, opIdx).trim();
        String right = expr.substring(opIdx + opLen).trim();
        compileAssignment(targetLoc, left, varMap, tableMap, assembly);
        String rightLoc = resolveLocation(right, varMap, tableMap, assembly);
        if (targetLoc.startsWith("[") && rightLoc.startsWith("[")) {
            assembly.add("MOV A, " + rightLoc);
            assembly.add(opInstruction + " " + targetLoc + ", A");
        } else {
            assembly.add(opInstruction + " " + targetLoc + ", " + rightLoc);
        }
    }

    public static void compileBinaryOp(String targetLoc, String left, String right, String op,
                                       Map<String, String> varMap, Map<String, NType.TableInfo> tableMap, List<String> assembly) {
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

    public static void compileAdd(String targetLoc, String expr, Map<String, String> varMap,
                                  Map<String, NType.TableInfo> tableMap, List<String> assembly) {
        String srcLoc = resolveLocation(expr, varMap, tableMap, assembly);
        if (targetLoc.startsWith("[") && srcLoc.startsWith("[")) {
            assembly.add("MOV A, " + srcLoc);
            assembly.add("ADD " + targetLoc + ", A");
        } else {
            assembly.add("ADD " + targetLoc + ", " + srcLoc);
        }
    }

    public static void compileSub(String targetLoc, String expr, Map<String, String> varMap,
                                  Map<String, NType.TableInfo> tableMap, List<String> assembly) {
        String srcLoc = resolveLocation(expr, varMap, tableMap, assembly);
        if (targetLoc.startsWith("[") && srcLoc.startsWith("[")) {
            assembly.add("MOV A, " + srcLoc);
            assembly.add("SUB " + targetLoc + ", A");
        } else {
            assembly.add("SUB " + targetLoc + ", " + srcLoc);
        }
    }
}
