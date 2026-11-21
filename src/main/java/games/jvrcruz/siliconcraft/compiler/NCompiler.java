package games.jvrcruz.siliconcraft.compiler;

import games.jvrcruz.siliconcraft.compiler.NConditionCompiler.FuncContext;
import games.jvrcruz.siliconcraft.compiler.NConditionCompiler.IfContext;
import games.jvrcruz.siliconcraft.compiler.NConditionCompiler.LoopContext;
import games.jvrcruz.siliconcraft.compiler.NType.FieldInfo;
import games.jvrcruz.siliconcraft.compiler.NType.TableInfo;
import games.jvrcruz.siliconcraft.compiler.NType.Type;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class NCompiler {

    public record CompileResult(boolean success, List<String> assembly, List<String> errors, Set<String> requiredInstructions) {}

    public static class SourceLine {
        public final String file;
        public final int lineNum;
        public final String text;

        public SourceLine(String file, int lineNum, String text) {
            this.file = file;
            this.lineNum = lineNum;
            this.text = text;
        }

        public String formatLoc() {
            return (file != null && !file.isEmpty()) ? (file + ":" + lineNum) : ("Line " + lineNum);
        }
    }

    public static CompileResult compile(List<String> sourceLines) {
        List<SourceLine> lines = new ArrayList<>();
        for (int i = 0; i < sourceLines.size(); i++) {
            lines.add(new SourceLine(null, i + 1, sourceLines.get(i)));
        }
        return compileInternal(lines, false);
    }

    public static CompileResult compileProject(Map<String, List<String>> files, String entrypoint) {
        if (files == null || files.isEmpty()) {
            return new CompileResult(false, List.of(), List.of("Project contains no files!"), Set.of());
        }

        Map<String, List<String>> normalizedMap = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : files.entrySet()) {
            normalizedMap.put(normalizePath(entry.getKey()), entry.getValue());
        }

        String selectedEntrypoint = null;
        if (entrypoint != null && !entrypoint.trim().isEmpty()) {
            String norm = normalizePath(entrypoint.trim());
            if (normalizedMap.containsKey(norm)) {
                selectedEntrypoint = norm;
            }
        }

        if (selectedEntrypoint == null) {
            if (normalizedMap.containsKey("main.n")) {
                selectedEntrypoint = "main.n";
            } else {
                for (String path : normalizedMap.keySet()) {
                    if (path.equalsIgnoreCase("main.n") || path.toLowerCase(Locale.ROOT).endsWith("/main.n")) {
                        selectedEntrypoint = path;
                        break;
                    }
                }
            }
        }

        if (selectedEntrypoint == null) {
            return new CompileResult(false, List.of(), List.of("Project must contain a 'main.n' file as entrypoint!"), Set.of());
        }

        List<SourceLine> expandedLines = new ArrayList<>();
        List<String> expansionErrors = new ArrayList<>();
        Set<String> alreadyImported = new HashSet<>();
        Set<String> currentlyImporting = new LinkedHashSet<>();

        NProjectResolver.expandProjectFiles(selectedEntrypoint, normalizedMap, alreadyImported, currentlyImporting, expandedLines, expansionErrors);

        if (!expansionErrors.isEmpty()) {
            return new CompileResult(false, List.of(), expansionErrors, Set.of());
        }

        return compileInternal(expandedLines, true);
    }

    public static String normalizePath(String path) {
        return NProjectResolver.normalizePath(path);
    }

    public static String cleanImportString(String raw) {
        return NProjectResolver.cleanImportString(raw);
    }

    public static String resolveImportPath(String currentFile, String importStr, Set<String> availableFiles) {
        return NProjectResolver.resolveImportPath(currentFile, importStr, availableFiles);
    }

    public static String getAsmNameFromZip(String zipFileName) {
        return NProjectResolver.getAsmNameFromZip(zipFileName);
    }

    public static boolean isNumeric(String token) {
        return NNumberParser.isNumeric(token);
    }

    public static int parseNumber(String token) {
        return NNumberParser.parseNumber(token);
    }

    public static String unescapeString(String s) {
        return NConditionCompiler.unescapeString(s);
    }

    private static CompileResult compileInternal(List<SourceLine> sourceLines, boolean allowImports) {
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

        for (int i = 0; i < sourceLines.size(); i++) {
            SourceLine src = sourceLines.get(i);
            int lineNum = src.lineNum;
            String lineLoc = src.formatLoc();
            String rawLine = src.text;
            String line = InstructionValidator.stripComments(rawLine).trim();
            if (line.isEmpty()) continue;

            try {
                // Import check
                if (line.startsWith("import ") || line.startsWith("import\t")) {
                    if (!allowImports) {
                        errors.add(lineLoc + ": Imports are not supported in the default Code Compiler. Use a Project Compiler block instead.");
                    }
                    continue;
                }
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
                        errors.add(lineLoc + ": Unexpected closing brace '}'");
                    }
                    continue;
                }

                // Check for rejected print_char
                if (line.startsWith("print_char ") || line.startsWith("print_char(") ||
                    line.startsWith("printChar ") || line.startsWith("printChar(") ||
                    line.startsWith("println_char ") || line.startsWith("println_char(") ||
                    line.startsWith("printlnChar ") || line.startsWith("printlnChar(")) {
                    errors.add(lineLoc + ": 'print_char' has been removed. Use 'print' or 'println' instead.");
                    continue;
                }

                // Check for rejected type declarations: int, char, boolean, etc.
                if (line.startsWith("int ") || line.startsWith("int\t") ||
                    line.startsWith("char ") || line.startsWith("char\t") ||
                    line.startsWith("char[] ") || line.startsWith("char[]\t") ||
                    line.startsWith("boolean ") || line.startsWith("boolean\t") ||
                    line.startsWith("string ") || line.startsWith("string\t") ||
                    line.startsWith("float ") || line.startsWith("float\t")) {
                    boolean isIntInstruction = false;
                    if (line.startsWith("int ") || line.startsWith("int\t") ||
                        line.startsWith("INT ") || line.startsWith("INT\t")) {
                        String rest = line.substring(3).trim();
                        if (!rest.contains("=") && (NNumberParser.isNumeric(rest) || isRegister(rest) ||
                                rest.equalsIgnoreCase("DIV_ZERO") || rest.equalsIgnoreCase("ILLEGAL_OPCODE") ||
                                rest.equalsIgnoreCase("OUT_OF_BOUNDS") || rest.equalsIgnoreCase("STACK_FAULT") ||
                                rest.equalsIgnoreCase("GENERAL_EXCEPTION") || rest.equalsIgnoreCase("KEYBOARD") ||
                                rest.equalsIgnoreCase("MOUSE_MOVE") || rest.equalsIgnoreCase("MOUSE_CLICK") ||
                                rest.equalsIgnoreCase("TIMER"))) {
                            isIntInstruction = true;
                        }
                    }
                    if (!isIntInstruction) {
                        String typeName = getFirstWord(line);
                        errors.add(lineLoc + ": Explicit type declaration '" + typeName + "' is not allowed. Use 'var' or 'const' instead.");
                        continue;
                    }
                }

                // Label definition (inline assembly label)
                if (line.endsWith(":") && !line.contains(" ") && line.length() > 1) {
                    assembly.add(line);
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

                // Storage drive select: storage.select(d)
                if ((line.startsWith("storage.select(") || line.startsWith("storage.select ")) && line.endsWith(")")) {
                    String dExpr = extractParenthesesOrRest(line);
                    String dLoc = resolveLocation(dExpr, varMap, tableMap, assembly);
                    assembly.add("SSEL " + dLoc);
                    continue;
                }

                // Storage drive indexing statements: storage[d].write(addr, val), storage[d].set, storage[d].write_bytes, etc.
                if (line.startsWith("storage[") && line.contains("].") && line.endsWith(")")) {
                    int closeBracket = line.indexOf("].");
                    String driveExpr = line.substring("storage[".length(), closeBracket).trim();
                    String methodPart = line.substring(closeBracket + 2).trim();
                    String driveLoc = resolveLocation(driveExpr, varMap, tableMap, assembly);

                    if (methodPart.startsWith("write(") || methodPart.startsWith("set(")) {
                        String inner = extractParenthesesOrRest(methodPart);
                        List<String> sArgs = splitArgs(inner);
                        if (sArgs.size() == 2) {
                            String aLoc = resolveLocation(sArgs.get(0).trim(), varMap, tableMap, assembly);
                            String vLoc = resolveLocation(sArgs.get(1).trim(), varMap, tableMap, assembly);
                            assembly.add("SWRITE " + driveLoc + ", " + aLoc + ", " + vLoc);
                            continue;
                        }
                    } else if (methodPart.startsWith("write_int(") || methodPart.startsWith("writeInt(")) {
                        String inner = extractParenthesesOrRest(methodPart);
                        List<String> sArgs = splitArgs(inner);
                        if (sArgs.size() == 2) {
                            String aLoc = resolveLocation(sArgs.get(0).trim(), varMap, tableMap, assembly);
                            String vLoc = resolveLocation(sArgs.get(1).trim(), varMap, tableMap, assembly);
                            assembly.add("MOV A, " + vLoc);
                            assembly.add("MOV B, " + aLoc);
                            assembly.add("SWRITE " + driveLoc + ", B, A");
                            assembly.add("INC B");
                            assembly.add("SHR A, 8");
                            assembly.add("SWRITE " + driveLoc + ", B, A");
                            assembly.add("INC B");
                            assembly.add("SHR A, 8");
                            assembly.add("SWRITE " + driveLoc + ", B, A");
                            assembly.add("INC B");
                            assembly.add("SHR A, 8");
                            assembly.add("SWRITE " + driveLoc + ", B, A");
                            continue;
                        }
                    } else if (methodPart.startsWith("write_bytes(") || methodPart.startsWith("writeBytes(")) {
                        String inner = extractParenthesesOrRest(methodPart);
                        List<String> sArgs = splitArgs(inner);
                        if (sArgs.size() == 3) {
                            String sLoc = resolveLocation(sArgs.get(0).trim(), varMap, tableMap, assembly);
                            String rLoc = resolveLocation(sArgs.get(1).trim(), varMap, tableMap, assembly);
                            String cLoc = resolveLocation(sArgs.get(2).trim(), varMap, tableMap, assembly);
                            assembly.add("SBLKW " + driveLoc + ", " + sLoc + ", " + rLoc + ", " + cLoc);
                            continue;
                        }
                    } else if (methodPart.startsWith("read_bytes(") || methodPart.startsWith("readBytes(")) {
                        String inner = extractParenthesesOrRest(methodPart);
                        List<String> sArgs = splitArgs(inner);
                        if (sArgs.size() == 3) {
                            String sLoc = resolveLocation(sArgs.get(0).trim(), varMap, tableMap, assembly);
                            String rLoc = resolveLocation(sArgs.get(1).trim(), varMap, tableMap, assembly);
                            String cLoc = resolveLocation(sArgs.get(2).trim(), varMap, tableMap, assembly);
                            assembly.add("SBLKR " + rLoc + ", " + driveLoc + ", " + sLoc + ", " + cLoc);
                            continue;
                        }
                    }
                }

                // Storage byte write: storage.write([d,] addr, val) / storage.set([d,] addr, val)
                if ((line.startsWith("storage.write(") || line.startsWith("storage.set(") ||
                     line.startsWith("storage.write ") || line.startsWith("storage.set ")) && line.endsWith(")")) {
                    String inner = extractParenthesesOrRest(line);
                    List<String> sArgs = splitArgs(inner);
                    if (sArgs.size() == 3) {
                        String dLoc = resolveLocation(sArgs.get(0).trim(), varMap, tableMap, assembly);
                        String aLoc = resolveLocation(sArgs.get(1).trim(), varMap, tableMap, assembly);
                        String vLoc = resolveLocation(sArgs.get(2).trim(), varMap, tableMap, assembly);
                        assembly.add("SWRITE " + dLoc + ", " + aLoc + ", " + vLoc);
                        continue;
                    } else if (sArgs.size() == 2) {
                        String aLoc = resolveLocation(sArgs.get(0).trim(), varMap, tableMap, assembly);
                        String vLoc = resolveLocation(sArgs.get(1).trim(), varMap, tableMap, assembly);
                        assembly.add("SWRITE " + aLoc + ", " + vLoc);
                        continue;
                    }
                }

                // Storage 32-bit int write: storage.write_int([d,] addr, val) / storage.writeInt([d,] addr, val)
                if ((line.startsWith("storage.write_int(") || line.startsWith("storage.writeInt(") ||
                     line.startsWith("storage.write_int ") || line.startsWith("storage.writeInt ")) && line.endsWith(")")) {
                    String inner = extractParenthesesOrRest(line);
                    List<String> sArgs = splitArgs(inner);
                    if (sArgs.size() == 3) {
                        String dLoc = resolveLocation(sArgs.get(0).trim(), varMap, tableMap, assembly);
                        String aLoc = resolveLocation(sArgs.get(1).trim(), varMap, tableMap, assembly);
                        String vLoc = resolveLocation(sArgs.get(2).trim(), varMap, tableMap, assembly);
                        assembly.add("MOV A, " + vLoc);
                        assembly.add("MOV B, " + aLoc);
                        assembly.add("SWRITE " + dLoc + ", B, A");
                        assembly.add("INC B");
                        assembly.add("SHR A, 8");
                        assembly.add("SWRITE " + dLoc + ", B, A");
                        assembly.add("INC B");
                        assembly.add("SHR A, 8");
                        assembly.add("SWRITE " + dLoc + ", B, A");
                        assembly.add("INC B");
                        assembly.add("SHR A, 8");
                        assembly.add("SWRITE " + dLoc + ", B, A");
                        continue;
                    } else if (sArgs.size() == 2) {
                        String aLoc = resolveLocation(sArgs.get(0).trim(), varMap, tableMap, assembly);
                        String vLoc = resolveLocation(sArgs.get(1).trim(), varMap, tableMap, assembly);
                        assembly.add("MOV A, " + vLoc);
                        assembly.add("MOV B, " + aLoc);
                        assembly.add("SWRITE B, A");
                        assembly.add("INC B");
                        assembly.add("SHR A, 8");
                        assembly.add("SWRITE B, A");
                        assembly.add("INC B");
                        assembly.add("SHR A, 8");
                        assembly.add("SWRITE B, A");
                        assembly.add("INC B");
                        assembly.add("SHR A, 8");
                        assembly.add("SWRITE B, A");
                        continue;
                    }
                }

                // Storage string write: storage.write_string(addr, str) / storage.writeString(addr, str)
                if ((line.startsWith("storage.write_string(") || line.startsWith("storage.writeString(") ||
                     line.startsWith("storage.write_string ") || line.startsWith("storage.writeString ")) && line.endsWith(")")) {
                    String inner = extractParenthesesOrRest(line);
                    List<String> sArgs = splitArgs(inner);
                    if (sArgs.size() == 2) {
                        String aLoc = resolveLocation(sArgs.get(0).trim(), varMap, tableMap, assembly);
                        String strExpr = sArgs.get(1).trim();
                        if (strExpr.startsWith("\"") && strExpr.endsWith("\"") && strExpr.length() >= 2) {
                            String content = unescapeString(strExpr.substring(1, strExpr.length() - 1));
                            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                            assembly.add("MOV B, " + aLoc);
                            for (byte b : bytes) {
                                assembly.add("SWRITE B, " + (b & 0xFF));
                                assembly.add("INC B");
                            }
                            assembly.add("SWRITE B, 0");
                        } else {
                            String strLoc = resolveLocation(strExpr, varMap, tableMap, assembly);
                            int loopId = labelCounter++;
                            String loopStart = "_sstr_w_" + loopId;
                            String loopEnd = "_sstr_wend_" + loopId;
                            assembly.add("MOV B, " + aLoc);
                            assembly.add("MOV C, " + strLoc);
                            assembly.add(loopStart + ":");
                            assembly.add("MOV A, [C]");
                            assembly.add("CMP A, 0");
                            assembly.add("JZ " + loopEnd);
                            assembly.add("SWRITE B, A");
                            assembly.add("INC B");
                            assembly.add("INC C");
                            assembly.add("JMP " + loopStart);
                            assembly.add(loopEnd + ":");
                            assembly.add("SWRITE B, 0");
                        }
                        continue;
                    }
                }

                // Storage block read: storage.read_bytes([d,] sAddr, rAddr, count) / storage.readBytes(...)
                if ((line.startsWith("storage.read_bytes(") || line.startsWith("storage.readBytes(") ||
                     line.startsWith("storage.read_bytes ") || line.startsWith("storage.readBytes ")) && line.endsWith(")")) {
                    String inner = extractParenthesesOrRest(line);
                    List<String> sArgs = splitArgs(inner);
                    if (sArgs.size() == 4) {
                        String dLoc = resolveLocation(sArgs.get(0).trim(), varMap, tableMap, assembly);
                        String sLoc = resolveLocation(sArgs.get(1).trim(), varMap, tableMap, assembly);
                        String rLoc = resolveLocation(sArgs.get(2).trim(), varMap, tableMap, assembly);
                        String cLoc = resolveLocation(sArgs.get(3).trim(), varMap, tableMap, assembly);
                        assembly.add("SBLKR " + rLoc + ", " + dLoc + ", " + sLoc + ", " + cLoc);
                        continue;
                    } else if (sArgs.size() == 3) {
                        String sLoc = resolveLocation(sArgs.get(0).trim(), varMap, tableMap, assembly);
                        String rLoc = resolveLocation(sArgs.get(1).trim(), varMap, tableMap, assembly);
                        String cLoc = resolveLocation(sArgs.get(2).trim(), varMap, tableMap, assembly);
                        assembly.add("SBLKR " + rLoc + ", " + sLoc + ", " + cLoc);
                        continue;
                    }
                }

                // Storage block write: storage.write_bytes([d,] sAddr, rAddr, count) / storage.writeBytes(...)
                if ((line.startsWith("storage.write_bytes(") || line.startsWith("storage.writeBytes(") ||
                     line.startsWith("storage.write_bytes ") || line.startsWith("storage.writeBytes ")) && line.endsWith(")")) {
                    String inner = extractParenthesesOrRest(line);
                    List<String> sArgs = splitArgs(inner);
                    if (sArgs.size() == 4) {
                        String dLoc = resolveLocation(sArgs.get(0).trim(), varMap, tableMap, assembly);
                        String sLoc = resolveLocation(sArgs.get(1).trim(), varMap, tableMap, assembly);
                        String rLoc = resolveLocation(sArgs.get(2).trim(), varMap, tableMap, assembly);
                        String cLoc = resolveLocation(sArgs.get(3).trim(), varMap, tableMap, assembly);
                        assembly.add("SBLKW " + dLoc + ", " + sLoc + ", " + rLoc + ", " + cLoc);
                        continue;
                    } else if (sArgs.size() == 3) {
                        String sLoc = resolveLocation(sArgs.get(0).trim(), varMap, tableMap, assembly);
                        String rLoc = resolveLocation(sArgs.get(1).trim(), varMap, tableMap, assembly);
                        String cLoc = resolveLocation(sArgs.get(2).trim(), varMap, tableMap, assembly);
                        assembly.add("SBLKW " + sLoc + ", " + rLoc + ", " + cLoc);
                        continue;
                    }
                }

                // Framebuffer mode: display.mode(m) / fb.mode(m)
                if ((line.startsWith("display.mode(") || line.startsWith("fb.mode(")) && line.endsWith(")")) {
                    String mExpr = extractParenthesesOrRest(line);
                    String mLoc = resolveLocation(mExpr, varMap, tableMap, assembly);
                    assembly.add("FBMODE " + mLoc);
                    continue;
                }

                // Framebuffer set pixel: display.set(x, y, color) / display.pixel(x, y, color) / fb.set(...) / fb.pixel(...)
                if ((line.startsWith("display.set(") || line.startsWith("display.pixel(") ||
                     line.startsWith("fb.set(") || line.startsWith("fb.pixel(")) && line.endsWith(")")) {
                    String inner = extractParenthesesOrRest(line);
                    List<String> args = splitArgs(inner);
                    if (args.size() == 3) {
                        String xLoc = resolveLocation(args.get(0).trim(), varMap, tableMap, assembly);
                        String yLoc = resolveLocation(args.get(1).trim(), varMap, tableMap, assembly);
                        String cLoc = resolveLocation(args.get(2).trim(), varMap, tableMap, assembly);
                        assembly.add("FBSET " + xLoc + ", " + yLoc + ", " + cLoc);
                        continue;
                    }
                }

                // Framebuffer clear: display.clear(color) / fb.clear(color)
                if ((line.startsWith("display.clear(") || line.startsWith("fb.clear(") ||
                     line.equals("display.clear()") || line.equals("fb.clear()")) && line.endsWith(")")) {
                    String cExpr = extractParenthesesOrRest(line);
                    String cLoc = cExpr.isEmpty() ? "0" : resolveLocation(cExpr, varMap, tableMap, assembly);
                    assembly.add("FBCLR " + cLoc);
                    continue;
                }

                // Framebuffer rect: display.rect(x, y, w, h, color) / fb.rect(...)
                if ((line.startsWith("display.rect(") || line.startsWith("fb.rect(")) && line.endsWith(")")) {
                    String inner = extractParenthesesOrRest(line);
                    List<String> args = splitArgs(inner);
                    if (args.size() == 5) {
                        String xLoc = resolveLocation(args.get(0).trim(), varMap, tableMap, assembly);
                        String yLoc = resolveLocation(args.get(1).trim(), varMap, tableMap, assembly);
                        String wLoc = resolveLocation(args.get(2).trim(), varMap, tableMap, assembly);
                        String hLoc = resolveLocation(args.get(3).trim(), varMap, tableMap, assembly);
                        String cLoc = resolveLocation(args.get(4).trim(), varMap, tableMap, assembly);
                        assembly.add("FBRECT " + xLoc + ", " + yLoc + ", " + wLoc + ", " + hLoc + ", " + cLoc);
                        continue;
                    }
                }

                // Framebuffer line: display.line(x1, y1, x2, y2, color) / fb.line(...)
                if ((line.startsWith("display.line(") || line.startsWith("fb.line(")) && line.endsWith(")")) {
                    String inner = extractParenthesesOrRest(line);
                    List<String> args = splitArgs(inner);
                    if (args.size() == 5) {
                        String x0Loc = resolveLocation(args.get(0).trim(), varMap, tableMap, assembly);
                        String y0Loc = resolveLocation(args.get(1).trim(), varMap, tableMap, assembly);
                        String x1Loc = resolveLocation(args.get(2).trim(), varMap, tableMap, assembly);
                        String y1Loc = resolveLocation(args.get(3).trim(), varMap, tableMap, assembly);
                        String cLoc = resolveLocation(args.get(4).trim(), varMap, tableMap, assembly);
                        assembly.add("FBLINE " + x0Loc + ", " + y0Loc + ", " + x1Loc + ", " + y1Loc + ", " + cLoc);
                        continue;
                    }
                }

                // Framebuffer blit: display.blit(x, y, ram_src, w, h) / fb.blit(...)
                if ((line.startsWith("display.blit(") || line.startsWith("fb.blit(")) && line.endsWith(")")) {
                    String inner = extractParenthesesOrRest(line);
                    List<String> args = splitArgs(inner);
                    if (args.size() == 5) {
                        String xLoc = resolveLocation(args.get(0).trim(), varMap, tableMap, assembly);
                        String yLoc = resolveLocation(args.get(1).trim(), varMap, tableMap, assembly);
                        String rLoc = resolveLocation(args.get(2).trim(), varMap, tableMap, assembly);
                        String wLoc = resolveLocation(args.get(3).trim(), varMap, tableMap, assembly);
                        String hLoc = resolveLocation(args.get(4).trim(), varMap, tableMap, assembly);
                        assembly.add("FBLIT " + xLoc + ", " + yLoc + ", " + rLoc + ", " + wLoc + ", " + hLoc);
                        continue;
                    }
                }

                // Framebuffer flip / swap: display.flip() / display.swap() / fb.flip() / fb.swap()
                if (line.equalsIgnoreCase("display.flip()") || line.equalsIgnoreCase("display.swap()") ||
                    line.equalsIgnoreCase("fb.flip()") || line.equalsIgnoreCase("fb.swap()")) {
                    assembly.add("FBSWAP");
                    continue;
                }

                // Audio sample / push: audio.sample(val) / audio.push(val)
                if ((line.startsWith("audio.sample(") || line.startsWith("audio.push(")) && line.endsWith(")")) {
                    String vExpr = extractParenthesesOrRest(line);
                    String vLoc = resolveLocation(vExpr, varMap, tableMap, assembly);
                    assembly.add("APUSH " + vLoc);
                    continue;
                }

                // Audio block write: audio.write(ram_src, count) / audio.write_samples(ram_src, count)
                if ((line.startsWith("audio.write(") || line.startsWith("audio.write_samples(") || line.startsWith("audio.writeSamples(")) && line.endsWith(")")) {
                    String inner = extractParenthesesOrRest(line);
                    List<String> args = splitArgs(inner);
                    if (args.size() == 2) {
                        String rLoc = resolveLocation(args.get(0).trim(), varMap, tableMap, assembly);
                        String cLoc = resolveLocation(args.get(1).trim(), varMap, tableMap, assembly);
                        assembly.add("ABUFW " + rLoc + ", " + cLoc);
                        continue;
                    }
                }

                // Audio tone: audio.tone(freq, ms)
                if (line.startsWith("audio.tone(") && line.endsWith(")")) {
                    String inner = extractParenthesesOrRest(line);
                    List<String> args = splitArgs(inner);
                    if (args.size() == 2) {
                        String fLoc = resolveLocation(args.get(0).trim(), varMap, tableMap, assembly);
                        String dLoc = resolveLocation(args.get(1).trim(), varMap, tableMap, assembly);
                        assembly.add("ATONE " + fLoc + ", " + dLoc);
                        continue;
                    }
                }

                // Audio clear: audio.clear() / audio.flush()
                if (line.equalsIgnoreCase("audio.clear()") || line.equalsIgnoreCase("audio.flush()")) {
                    assembly.add("ACLR");
                    continue;
                }

                // Audio play: audio.play()
                if (line.equalsIgnoreCase("audio.play()")) {
                    assembly.add("APLAY");
                    continue;
                }

                // Direct RAM write: mem.write(addr, val) / ram.write(...) / memory.write(...) / poke(addr, val)
                if ((line.startsWith("mem.write(") || line.startsWith("ram.write(") ||
                     line.startsWith("memory.write(") || line.startsWith("poke(")) && line.endsWith(")")) {
                    String inner = extractParenthesesOrRest(line);
                    List<String> mArgs = splitArgs(inner);
                    if (mArgs.size() == 2) {
                        String addr = mArgs.get(0).trim();
                        String val = mArgs.get(1).trim();
                        if (isNumeric(addr)) {
                            compileAssignment("[" + addr + "]", val, varMap, tableMap, assembly);
                        } else {
                            String addrLoc = resolveLocation(addr, varMap, tableMap, assembly);
                            assembly.add("MOV B, " + addrLoc);
                            String valLoc = resolveLocation(val, varMap, tableMap, assembly);
                            assembly.add("MOV A, " + valLoc);
                            assembly.add("MOV [B], A");
                        }
                        continue;
                    }
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

                // High-level interrupt / exception handlers
                if ((line.startsWith("on_key(") || line.startsWith("on_key ")) && line.endsWith(")")) {
                    String handler = extractParenthesesOrRest(line).trim();
                    assembly.add("IVEC 10, " + handler);
                    continue;
                }
                if ((line.startsWith("on_mouse_move(") || line.startsWith("on_mouse_move ")) && line.endsWith(")")) {
                    String handler = extractParenthesesOrRest(line).trim();
                    assembly.add("IVEC 11, " + handler);
                    continue;
                }
                if ((line.startsWith("on_mouse_click(") || line.startsWith("on_mouse_click ")) && line.endsWith(")")) {
                    String handler = extractParenthesesOrRest(line).trim();
                    assembly.add("IVEC 12, " + handler);
                    continue;
                }
                if ((line.startsWith("catch(") || line.startsWith("catch ")) && line.endsWith(")")) {
                    String inner = extractParenthesesOrRest(line);
                    List<String> cArgs = splitArgs(inner);
                    if (cArgs.size() == 2) {
                        assembly.add("IVEC " + cArgs.get(0).trim() + ", " + cArgs.get(1).trim());
                        continue;
                    }
                }

                // Println hex statement: prints expression as hexadecimal (0x...) followed by newline
                if (line.startsWith("println_hex ") || line.startsWith("println_hex(") || line.equals("println_hex") || line.equals("println_hex()")) {
                    String arg = extractParenthesesOrRest(line);
                    compilePrintHex(arg, true, labelCounter++, varMap, typeMap, tableMap, assembly);
                    continue;
                }

                // Print hex statement: prints expression as hexadecimal (0x...) without newline
                if (line.startsWith("print_hex ") || line.startsWith("print_hex(") || line.equals("print_hex") || line.equals("print_hex()")) {
                    String arg = extractParenthesesOrRest(line);
                    compilePrintHex(arg, false, labelCounter++, varMap, typeMap, tableMap, assembly);
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
                        errors.add(lineLoc + ": Cannot modify constant '" + var + "'");
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
                        errors.add(lineLoc + ": Cannot modify constant '" + var + "'");
                        continue;
                    }
                    String loc = resolveLocation(var, varMap, tableMap, assembly);
                    assembly.add("DEC " + loc);
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

                    // Check for +=, -=, *=, /=, %=, &=, |=, ^=, <<=, >>=
                    boolean isShlEq = target.endsWith("<<");
                    boolean isShrEq = target.endsWith(">>");
                    boolean isAndEq = !isShlEq && !isShrEq && target.endsWith("&");
                    boolean isOrEq = !isShlEq && !isShrEq && target.endsWith("|");
                    boolean isXorEq = !isShlEq && !isShrEq && target.endsWith("^");
                    boolean isPlusEq = !isShlEq && !isShrEq && target.endsWith("+");
                    boolean isMinusEq = !isShlEq && !isShrEq && target.endsWith("-");
                    boolean isMulEq = !isShlEq && !isShrEq && target.endsWith("*");
                    boolean isDivEq = !isShlEq && !isShrEq && target.endsWith("/");
                    boolean isModEq = !isShlEq && !isShrEq && target.endsWith("%");
                    if (isShlEq || isShrEq) {
                        target = target.substring(0, target.length() - 2).trim();
                    } else if (isPlusEq || isMinusEq || isMulEq || isDivEq || isModEq || isAndEq || isOrEq || isXorEq) {
                        target = target.substring(0, target.length() - 1).trim();
                    }

                    String lowerTarget = target.toLowerCase(Locale.ROOT);

                    // Check constant immutability
                    if (!isDecl && constSet.contains(lowerTarget)) {
                        errors.add(lineLoc + ": Cannot assign to constant '" + target + "'");
                        continue;
                    }

                    // Pointer dereference assignment: *ptr = expr or *0x10 = expr
                    if (target.startsWith("*")) {
                        String ptrExpr = target.substring(1).trim();
                        if (isNumeric(ptrExpr)) {
                            String targetLoc = "[" + ptrExpr + "]";
                            if (isPlusEq) compileAdd(targetLoc, expr, varMap, tableMap, assembly);
                            else if (isMinusEq) compileSub(targetLoc, expr, varMap, tableMap, assembly);
                            else if (isMulEq) compileBinaryOp(targetLoc, targetLoc, expr, "MUL", varMap, tableMap, assembly);
                            else if (isDivEq) compileBinaryOp(targetLoc, targetLoc, expr, "DIV", varMap, tableMap, assembly);
                            else if (isModEq) compileBinaryOp(targetLoc, targetLoc, expr, "MOD", varMap, tableMap, assembly);
                            else if (isAndEq) compileBinaryOp(targetLoc, targetLoc, expr, "AND", varMap, tableMap, assembly);
                            else if (isOrEq) compileBinaryOp(targetLoc, targetLoc, expr, "OR", varMap, tableMap, assembly);
                            else if (isXorEq) compileBinaryOp(targetLoc, targetLoc, expr, "XOR", varMap, tableMap, assembly);
                            else if (isShlEq) compileBinaryOp(targetLoc, targetLoc, expr, "SHL", varMap, tableMap, assembly);
                            else if (isShrEq) compileBinaryOp(targetLoc, targetLoc, expr, "SHR", varMap, tableMap, assembly);
                            else compileAssignment(targetLoc, expr, varMap, tableMap, assembly);
                        } else {
                            String ptrLoc = resolveLocation(ptrExpr, varMap, tableMap, assembly);
                            if (isPlusEq || isMinusEq || isMulEq || isDivEq || isModEq || isAndEq || isOrEq || isXorEq || isShlEq || isShrEq) {
                                assembly.add("MOV B, " + ptrLoc);
                                assembly.add("MOV D, [B]");
                                String op = isPlusEq ? "ADD" : isMinusEq ? "SUB" : isMulEq ? "MUL" : isDivEq ? "DIV" : isModEq ? "MOD" : isAndEq ? "AND" : isOrEq ? "OR" : isXorEq ? "XOR" : isShlEq ? "SHL" : "SHR";
                                String srcLoc = resolveLocation(expr, varMap, tableMap, assembly);
                                assembly.add(op + " D, " + srcLoc);
                                assembly.add("MOV [B], D");
                            } else {
                                String srcLoc = resolveLocation(expr, varMap, tableMap, assembly);
                                assembly.add("MOV B, " + ptrLoc);
                                assembly.add("MOV A, " + srcLoc);
                                assembly.add("MOV [B], A");
                            }
                        }
                        continue;
                    }

                    // Direct RAM bracket assignment: mem[addr] = expr, ram[addr] = expr, memory[addr] = expr
                    if ((target.startsWith("mem[") || target.startsWith("ram[") || target.startsWith("memory[")) && target.endsWith("]")) {
                        int openBracket = target.indexOf('[');
                        String addrExpr = target.substring(openBracket + 1, target.length() - 1).trim();
                        if (isNumeric(addrExpr)) {
                            String targetLoc = "[" + addrExpr + "]";
                            if (isPlusEq) compileAdd(targetLoc, expr, varMap, tableMap, assembly);
                            else if (isMinusEq) compileSub(targetLoc, expr, varMap, tableMap, assembly);
                            else if (isMulEq) compileBinaryOp(targetLoc, targetLoc, expr, "MUL", varMap, tableMap, assembly);
                            else if (isDivEq) compileBinaryOp(targetLoc, targetLoc, expr, "DIV", varMap, tableMap, assembly);
                            else if (isModEq) compileBinaryOp(targetLoc, targetLoc, expr, "MOD", varMap, tableMap, assembly);
                            else if (isAndEq) compileBinaryOp(targetLoc, targetLoc, expr, "AND", varMap, tableMap, assembly);
                            else if (isOrEq) compileBinaryOp(targetLoc, targetLoc, expr, "OR", varMap, tableMap, assembly);
                            else if (isXorEq) compileBinaryOp(targetLoc, targetLoc, expr, "XOR", varMap, tableMap, assembly);
                            else if (isShlEq) compileBinaryOp(targetLoc, targetLoc, expr, "SHL", varMap, tableMap, assembly);
                            else if (isShrEq) compileBinaryOp(targetLoc, targetLoc, expr, "SHR", varMap, tableMap, assembly);
                            else compileAssignment(targetLoc, expr, varMap, tableMap, assembly);
                        } else {
                            String addrLoc = resolveLocation(addrExpr, varMap, tableMap, assembly);
                            if (isPlusEq || isMinusEq || isMulEq || isDivEq || isModEq || isAndEq || isOrEq || isXorEq || isShlEq || isShrEq) {
                                assembly.add("MOV B, " + addrLoc);
                                assembly.add("MOV D, [B]");
                                String op = isPlusEq ? "ADD" : isMinusEq ? "SUB" : isMulEq ? "MUL" : isDivEq ? "DIV" : isModEq ? "MOD" : isAndEq ? "AND" : isOrEq ? "OR" : isXorEq ? "XOR" : isShlEq ? "SHL" : "SHR";
                                String srcLoc = resolveLocation(expr, varMap, tableMap, assembly);
                                assembly.add(op + " D, " + srcLoc);
                                assembly.add("MOV [B], D");
                            } else {
                                String srcLoc = resolveLocation(expr, varMap, tableMap, assembly);
                                assembly.add("MOV B, " + addrLoc);
                                assembly.add("MOV A, " + srcLoc);
                                assembly.add("MOV [B], A");
                            }
                        }
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

                    // Storage string read declaration: var s = storage.read_string(addr, len)
                    if (isDecl && (expr.startsWith("storage.read_string(") || expr.startsWith("storage.readString(")) && expr.endsWith(")")) {
                        String inner = extractParenthesesOrRest(expr);
                        List<String> sArgs = splitArgs(inner);
                        if (sArgs.size() == 2) {
                            String sAddrLoc = resolveLocation(sArgs.get(0).trim(), varMap, tableMap, assembly);
                            int len = 32;
                            try {
                                len = parseNumber(sArgs.get(1).trim());
                            } catch (NumberFormatException ignored) {}
                            int strBase = nextRamAddress;
                            nextRamAddress += len + 1;
                            varMap.put(lowerTarget, String.valueOf(strBase));
                            typeMap.put(lowerTarget, Type.STRING);
                            assembly.add("SBLKR " + strBase + ", " + sAddrLoc + ", " + len);
                            assembly.add("MOV [" + (strBase + len) + "], 0");
                            if (isConst) constSet.add(lowerTarget);
                            continue;
                        }
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
                    } else if (isAndEq) {
                        compileBinaryOp(targetLoc, targetLoc, expr, "AND", varMap, tableMap, assembly);
                    } else if (isOrEq) {
                        compileBinaryOp(targetLoc, targetLoc, expr, "OR", varMap, tableMap, assembly);
                    } else if (isXorEq) {
                        compileBinaryOp(targetLoc, targetLoc, expr, "XOR", varMap, tableMap, assembly);
                    } else if (isShlEq) {
                        compileBinaryOp(targetLoc, targetLoc, expr, "SHL", varMap, tableMap, assembly);
                    } else if (isShrEq) {
                        compileBinaryOp(targetLoc, targetLoc, expr, "SHR", varMap, tableMap, assembly);
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

                errors.add(lineLoc + ": Unrecognized syntax: " + line);
            } catch (Exception e) {
                errors.add(lineLoc + ": " + e.getMessage());
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

    public static String getFirstWord(String line) {
        return NExpressionCompiler.getFirstWord(line);
    }

    public static String extractParenthesesOrRest(String line) {
        return NExpressionCompiler.extractParenthesesOrRest(line);
    }

    public static List<String> splitArgs(String s) {
        return NExpressionCompiler.splitArgs(s);
    }

    public static boolean isRegister(String token) {
        return NExpressionCompiler.isRegister(token);
    }

    public static Type inferType(String expr, Map<String, Type> typeMap) {
        return NExpressionCompiler.inferType(expr, typeMap);
    }

    public static String resolveLocation(String token, Map<String, String> varMap, Map<String, TableInfo> tableMap, List<String> assembly) {
        return NExpressionCompiler.resolveLocation(token, varMap, tableMap, assembly);
    }

    public static void compileAssignment(String targetLoc, String expr, Map<String, String> varMap,
                                         Map<String, TableInfo> tableMap, List<String> assembly) {
        NExpressionCompiler.compileAssignment(targetLoc, expr, varMap, tableMap, assembly);
    }

    public static void compileBinaryOp(String targetLoc, String left, String right, String op,
                                       Map<String, String> varMap, Map<String, TableInfo> tableMap, List<String> assembly) {
        NExpressionCompiler.compileBinaryOp(targetLoc, left, right, op, varMap, tableMap, assembly);
    }

    public static void compileAdd(String targetLoc, String expr, Map<String, String> varMap,
                                  Map<String, TableInfo> tableMap, List<String> assembly) {
        NExpressionCompiler.compileAdd(targetLoc, expr, varMap, tableMap, assembly);
    }

    public static void compileSub(String targetLoc, String expr, Map<String, String> varMap,
                                  Map<String, TableInfo> tableMap, List<String> assembly) {
        NExpressionCompiler.compileSub(targetLoc, expr, varMap, tableMap, assembly);
    }

    public static int compileTableDeclaration(String name, String expr, int nextRam,
                                              Map<String, String> varMap, Map<String, Type> typeMap,
                                              Map<String, TableInfo> tableMap, List<String> assembly) {
        return NTableCompiler.compileTableDeclaration(name, expr, nextRam, varMap, typeMap, tableMap, assembly);
    }

    public static void compileTableOrArrayWrite(String target, String expr, Map<String, String> varMap,
                                                Map<String, Type> typeMap, Map<String, TableInfo> tableMap, List<String> assembly) {
        NTableCompiler.compileTableOrArrayWrite(target, expr, varMap, typeMap, tableMap, assembly);
    }

    public static int allocateStringLiteral(String name, String expr, int nextRam, Map<String, String> varMap,
                                            Map<String, Type> typeMap, List<String> assembly) {
        return NTableCompiler.allocateStringLiteral(name, expr, nextRam, varMap, typeMap, assembly);
    }

    public static void compilePrint(String arg, boolean isPrintln, int id, Map<String, String> varMap,
                                    Map<String, Type> typeMap, Map<String, TableInfo> tableMap, List<String> assembly) {
        NPrintCompiler.compilePrint(arg, isPrintln, id, varMap, typeMap, tableMap, assembly);
    }

    public static void compilePrintHex(String arg, boolean isPrintln, int id,
                                       Map<String, String> varMap, Map<String, Type> typeMap,
                                       Map<String, TableInfo> tableMap, List<String> assembly) {
        NPrintCompiler.compilePrintHex(arg, isPrintln, id, varMap, typeMap, tableMap, assembly);
    }

    public static void compileCondition(String cond, String branchLabel, boolean jumpOnTrue,
                                        Map<String, String> varMap, Map<String, TableInfo> tableMap, List<String> assembly) {
        NConditionCompiler.compileCondition(cond, branchLabel, jumpOnTrue, varMap, tableMap, assembly);
    }
}
