package games.jvrcruz.siliconcraft.compiler;

import games.jvrcruz.siliconcraft.vm.VirtualCpu;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class NTableCompiler {

    // Compile Lua-style Table: var t = { 10, "apple", x = 5, [2] = "ok" }
    public static int compileTableDeclaration(String name, String expr, int nextRam,
                                              Map<String, String> varMap, Map<String, NType.Type> typeMap,
                                              Map<String, NType.TableInfo> tableMap, List<String> assembly) {
        String inner = expr.substring(1, expr.length() - 1).trim();
        List<String> items = parseTableElements(inner);

        int baseAddr = nextRam;
        int capacity = Math.max(16, items.size() + 4);
        nextRam += capacity;

        NType.TableInfo table = new NType.TableInfo(baseAddr, capacity);
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
                offset = NNumberParser.parseNumber(key);
            } catch (NumberFormatException e) {
                offset = table.nextOffset++;
            }

            // Store value into table slot
            int slotAddr = baseAddr + offset;
            NType.Type fieldType = NType.Type.INT;

            if (valStr.startsWith("\"") && valStr.endsWith("\"")) {
                // String literal stored as null-terminated UTF-8
                String content = NConditionCompiler.unescapeString(valStr.substring(1, valStr.length() - 1));
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                int strAddr = nextRam;
                nextRam += bytes.length + 1;

                for (int i = 0; i < bytes.length; i++) {
                    assembly.add("MOV [" + (strAddr + i) + "], " + (bytes[i] & 0xFF));
                }
                assembly.add("MOV [" + (strAddr + bytes.length) + "], 0");

                assembly.add("MOV [" + slotAddr + "], " + strAddr);
                fieldType = NType.Type.STRING;
            } else if (valStr.startsWith("'") && valStr.endsWith("'")) {
                int charVal = VirtualCpu.parseCharLiteral(valStr.substring(1, valStr.length() - 1));
                assembly.add("MOV [" + slotAddr + "], " + charVal);
                fieldType = NType.Type.CHAR;
            } else if (valStr.equalsIgnoreCase("true") || valStr.equalsIgnoreCase("false")) {
                int boolVal = valStr.equalsIgnoreCase("true") ? 1 : 0;
                assembly.add("MOV [" + slotAddr + "], " + boolVal);
                fieldType = NType.Type.BOOLEAN;
            } else {
                try {
                    int num = NNumberParser.parseNumber(valStr);
                    assembly.add("MOV [" + slotAddr + "], " + num);
                    fieldType = NType.Type.INT;
                } catch (NumberFormatException e) {
                    // Variable reference
                    String loc = NExpressionCompiler.resolveLocation(valStr, varMap, tableMap, assembly);
                    assembly.add("MOV A, " + loc);
                    assembly.add("MOV [" + slotAddr + "], A");
                    fieldType = typeMap.getOrDefault(valStr.toLowerCase(Locale.ROOT), NType.Type.INT);
                }
            }

            table.fields.put(key.toLowerCase(Locale.ROOT), new NType.FieldInfo(offset, fieldType));
        }

        tableMap.put(name.toLowerCase(Locale.ROOT), table);
        typeMap.put(name.toLowerCase(Locale.ROOT), NType.Type.TABLE);
        varMap.put(name.toLowerCase(Locale.ROOT), String.valueOf(baseAddr));
        return nextRam;
    }

    public static List<String> parseTableElements(String s) {
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

    public static int allocateStringLiteral(String name, String expr, int nextRam, Map<String, String> varMap,
                                            Map<String, NType.Type> typeMap, List<String> assembly) {
        String content = NConditionCompiler.unescapeString(expr.substring(1, expr.length() - 1));
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
        typeMap.put(lowerName, NType.Type.STRING);
        return nextRam;
    }

    public static void compileTableOrArrayWrite(String target, String expr, Map<String, String> varMap,
                                                Map<String, NType.Type> typeMap, Map<String, NType.TableInfo> tableMap, List<String> assembly) {
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
        NType.TableInfo table = tableMap.get(tblName);

        // Constant integer offset or named field
        int offset = -1;
        try {
            offset = NNumberParser.parseNumber(key);
        } catch (NumberFormatException ignored) {}

        if (offset == -1 && table.fields.containsKey(key)) {
            offset = table.fields.get(key).offset;
        }

        if (offset != -1) {
            int targetAddr = table.baseAddr + offset;
            NExpressionCompiler.compileAssignment("[" + targetAddr + "]", expr, varMap, tableMap, assembly);
        } else {
            // Dynamic variable index: tbl[i] = expr
            String idxLoc = NExpressionCompiler.resolveLocation(key, varMap, tableMap, assembly);
            assembly.add("MOV B, " + table.baseAddr);
            assembly.add("ADD B, " + idxLoc);
            String srcLoc = NExpressionCompiler.resolveLocation(expr, varMap, tableMap, assembly);
            assembly.add("MOV A, " + srcLoc);
            assembly.add("MOV [B], A");
        }
    }
}
