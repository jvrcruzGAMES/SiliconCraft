package games.jvrcruz.siliconcraft.compiler;

import games.jvrcruz.siliconcraft.vm.BinaryBytecode;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class Assembler {

    public record AssemblerResult(
            boolean success,
            byte[] bytecode,
            Set<String> requiredInstructions,
            List<String> errors
    ) {
        public AssemblerResult {
            bytecode = bytecode == null ? new byte[0] : bytecode;
            requiredInstructions = requiredInstructions == null ? Set.of() : Set.copyOf(requiredInstructions);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        public int instructionCount() {
            return BinaryBytecode.getInstructionCount(bytecode);
        }
    }

    private record ParsedInstruction(int lineNum, String opcode, String args) {}

    private record ParsedDirective(int lineNum, String opcode, String args, int startAddr, byte[] incbinBytes) {}

    public static AssemblerResult assemble(List<String> sourceLines) {
        return assemble(sourceLines, null);
    }

    public static AssemblerResult assemble(List<String> sourceLines, Path baseDir) {
        if (sourceLines == null || sourceLines.isEmpty()) {
            return new AssemblerResult(true, new byte[0], Set.of(), List.of());
        }

        List<ParsedInstruction> instructions = new ArrayList<>();
        List<ParsedDirective> directives = new ArrayList<>();
        Map<String, Integer> labels = new HashMap<>();
        List<String> pendingLabels = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int currentDataAddr = 0;

        // Pass 1: Strip comments, collect code and data labels, measure directives
        for (int i = 0; i < sourceLines.size(); i++) {
            int lineNum = i + 1;
            String raw = sourceLines.get(i);
            String line = stripComments(raw).trim();
            if (line.isEmpty()) {
                continue;
            }

            while (true) {
                int colon = findLabelColon(line);
                if (colon == -1) {
                    break;
                }
                String labelName = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                if (labelName.isEmpty() || labelName.contains(" ") || labelName.contains("\t") || labelName.contains(",")) {
                    errors.add("Line " + lineNum + ": Invalid label declaration");
                    line = "";
                    break;
                }
                pendingLabels.add(labelName);
                line = line.substring(colon + 1).trim();
                if (line.isEmpty()) {
                    break;
                }
            }
            if (line.isEmpty()) {
                continue;
            }

            int space = findFirstWhitespace(line);
            String opcode = (space == -1 ? line : line.substring(0, space)).toUpperCase(Locale.ROOT).trim();
            String args = space == -1 ? "" : line.substring(space + 1).trim();

            if (InstructionValidator.ASSEMBLER_DIRECTIVES.contains(opcode)) {
                try {
                    if (opcode.equals("ORG")) {
                        currentDataAddr = (int) evalConstExpr(args, labels);
                        for (String lbl : pendingLabels) {
                            labels.put(lbl, currentDataAddr);
                        }
                        pendingLabels.clear();
                    } else if (opcode.equals("DB") || opcode.equals("DW") || opcode.equals("DL")) {
                        for (String lbl : pendingLabels) {
                            labels.put(lbl, currentDataAddr);
                        }
                        pendingLabels.clear();
                        int byteLen = measureDataDirective(opcode, args);
                        directives.add(new ParsedDirective(lineNum, opcode, args, currentDataAddr, null));
                        currentDataAddr += byteLen;
                    } else if (opcode.equals("INCBIN")) {
                        for (String lbl : pendingLabels) {
                            labels.put(lbl, currentDataAddr);
                        }
                        pendingLabels.clear();
                        byte[] incbinBytes = readIncbinSlice(args, labels, baseDir);
                        directives.add(new ParsedDirective(lineNum, opcode, args, currentDataAddr, incbinBytes));
                        currentDataAddr += incbinBytes.length;
                    }
                } catch (RuntimeException e) {
                    errors.add("Line " + lineNum + ": " + e.getMessage());
                    pendingLabels.clear();
                }
            } else {
                int pc = instructions.size();
                for (String lbl : pendingLabels) {
                    labels.put(lbl, pc);
                }
                pendingLabels.clear();
                instructions.add(new ParsedInstruction(lineNum, opcode, args));
            }
        }

        if (!pendingLabels.isEmpty()) {
            int endPc = instructions.size();
            for (String lbl : pendingLabels) {
                labels.put(lbl, endPc);
            }
            pendingLabels.clear();
        }

        if (!errors.isEmpty()) {
            return new AssemblerResult(false, new byte[0], Set.of(), errors);
        }

        // Pass 2a: Resolve static data directives (.data)
        List<BinaryBytecode.DataSegment> rawSegments = new ArrayList<>();
        for (ParsedDirective dir : directives) {
            try {
                if (dir.opcode().equals("INCBIN")) {
                    if (dir.incbinBytes() != null && dir.incbinBytes().length > 0) {
                        rawSegments.add(new BinaryBytecode.DataSegment(dir.startAddr(), dir.incbinBytes()));
                    }
                } else {
                    byte[] bytes = encodeDataDirective(dir.opcode(), dir.args(), labels);
                    if (bytes.length > 0) {
                        rawSegments.add(new BinaryBytecode.DataSegment(dir.startAddr(), bytes));
                    }
                }
            } catch (RuntimeException e) {
                errors.add("Line " + dir.lineNum() + ": " + e.getMessage());
            }
        }

        // Pass 2b: Encode instructions into 64-bit binary words
        long[] code = new long[instructions.size()];
        List<String> rodataList = new ArrayList<>();
        Map<String, Integer> rodataPool = new HashMap<>();
        List<BinaryBytecode.ExtOperand> extList = new ArrayList<>();
        Set<String> required = new LinkedHashSet<>();

        for (int i = 0; i < instructions.size(); i++) {
            ParsedInstruction instr = instructions.get(i);
            Integer opcodeIdObj = BinaryBytecode.OPCODE_MAP.get(instr.opcode());
            if (opcodeIdObj == null) {
                errors.add("Line " + instr.lineNum() + ": Unknown instruction '" + instr.opcode() + "'");
                continue;
            }
            int opcodeId = opcodeIdObj;
            String canonicalName = BinaryBytecode.getOpcodeName(opcodeId);
            if (canonicalName != null) {
                required.add(canonicalName);
            }

            try {
                code[i] = encodeSingleInstruction(opcodeId, instr.args(), labels, rodataList, rodataPool, extList);
            } catch (RuntimeException e) {
                errors.add("Line " + instr.lineNum() + ": " + e.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            return new AssemblerResult(false, new byte[0], Set.of(), errors);
        }

        List<BinaryBytecode.DataSegment> dataSegments = BinaryBytecode.coalesceDataSegments(rawSegments);
        if (code.length == 0 && rodataList.isEmpty() && extList.isEmpty() && dataSegments.isEmpty()) {
            return new AssemblerResult(true, new byte[0], Set.of(), List.of());
        }

        BinaryBytecode.ProgramImage image = new BinaryBytecode.ProgramImage(
                code,
                rodataList.toArray(new String[0]),
                extList.toArray(new BinaryBytecode.ExtOperand[0]),
                dataSegments
        );
        byte[] binary = BinaryBytecode.encodeScbc(image);
        return new AssemblerResult(true, binary, required, List.of());
    }

    private static long encodeSingleInstruction(
            int opcodeId,
            String rawArgs,
            Map<String, Integer> labels,
            List<String> rodataList,
            Map<String, Integer> rodataPool,
            List<BinaryBytecode.ExtOperand> extList
    ) {
        if (opcodeId == BinaryBytecode.OP_OUT
                || opcodeId == BinaryBytecode.OP_OUTH
                || opcodeId == BinaryBytecode.OP_OUTC) {
            return encodeOutInstruction(opcodeId, rawArgs, labels, rodataList, rodataPool, extList);
        }

        if (rawArgs == null || rawArgs.isBlank()) {
            return BinaryBytecode.encodeInstruction(opcodeId, List.of(), 0, extList);
        }

        List<String> parts = splitArgsList(rawArgs);
        if (opcodeId == BinaryBytecode.OP_VBLANK && parts.size() == 1) {
            String trimmed = parts.get(0).trim();
            if (trimmed.equalsIgnoreCase("ON") || trimmed.equalsIgnoreCase("true")) {
                return BinaryBytecode.encodeInstruction(opcodeId,
                        List.of(new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_IMM, 0, 1)), 0, extList);
            }
            if (trimmed.equalsIgnoreCase("OFF") || trimmed.equalsIgnoreCase("false")) {
                return BinaryBytecode.encodeInstruction(opcodeId,
                        List.of(new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_IMM, 0, 0)), 0, extList);
            }
        }

        List<BinaryBytecode.ExtOperand> operands = new ArrayList<>(parts.size());
        for (int p = 0; p < parts.size(); p++) {
            String part = parts.get(p).trim();
            if (opcodeId == BinaryBytecode.OP_IVEC && p == 0) {
                Integer vec = parseVectorName(part);
                if (vec != null) {
                    operands.add(new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_IMM, 0, vec));
                    continue;
                }
            }
            operands.add(parseOperand(part, labels));
        }

        return BinaryBytecode.encodeInstruction(opcodeId, operands, 0, extList);
    }

    private static long encodeOutInstruction(
            int opcodeId,
            String rawArgs,
            Map<String, Integer> labels,
            List<String> rodataList,
            Map<String, Integer> rodataPool,
            List<BinaryBytecode.ExtOperand> extList
    ) {
        if (rawArgs == null || rawArgs.isBlank()) {
            return BinaryBytecode.encodeInstruction(opcodeId, List.of(), 1, extList);
        }
        List<String> parts = splitArgsList(rawArgs);
        if (parts.isEmpty()) {
            return BinaryBytecode.encodeInstruction(opcodeId, List.of(), 1, extList);
        }

        String valPart = parts.get(0).trim();
        int newline = 1;
        if (parts.size() == 2) {
            String flag = parts.get(1).trim().toLowerCase(Locale.ROOT);
            if (flag.equals("0") || flag.equals("cont") || flag.equals("no_nl") || flag.equals("false")) {
                newline = 0;
            }
        } else if (parts.size() > 2) {
            List<BinaryBytecode.ExtOperand> operands = new ArrayList<>(parts.size());
            for (String part : parts) {
                operands.add(parseOperand(part.trim(), labels));
            }
            return BinaryBytecode.encodeInstruction(opcodeId, operands, 1, extList);
        }

        if (isQuotedStringLiteral(valPart)) {
            String str = unescapeQuotedString(valPart);
            Integer idx = rodataPool.get(str);
            if (idx == null) {
                idx = rodataList.size();
                rodataList.add(str);
                rodataPool.put(str, idx);
            }
            return BinaryBytecode.encodeFmt2(opcodeId, newline, idx);
        }

        BinaryBytecode.ExtOperand op0 = parseOperand(valPart, labels);
        return BinaryBytecode.encodeInstruction(opcodeId, List.of(op0), newline, extList);
    }

    private static Integer parseVectorName(String token) {
        return switch (token.trim().toUpperCase(Locale.ROOT)) {
            case "DIV_ZERO", "DIV0", "ZERO" -> VirtualCpu.VEC_DIV_ZERO;
            case "ILLEGAL_OPCODE", "OPCODE", "ILL" -> VirtualCpu.VEC_ILLEGAL_OPCODE;
            case "OUT_OF_BOUNDS", "BOUNDS", "MEM_FAULT" -> VirtualCpu.VEC_OUT_OF_BOUNDS;
            case "STACK_FAULT", "STACK" -> VirtualCpu.VEC_STACK_FAULT;
            case "GENERAL_EXCEPTION", "EXCEPTION", "ERROR" -> VirtualCpu.VEC_GENERAL_EXCEPTION;
            case "KEYBOARD", "KEY", "CHAR" -> VirtualCpu.VEC_KEYBOARD;
            case "MOUSE_MOVE", "MOUSE_POS" -> VirtualCpu.VEC_MOUSE_MOVE;
            case "MOUSE_CLICK", "CLICK" -> VirtualCpu.VEC_MOUSE_CLICK;
            case "TIMER", "VBLANK", "VBL", "NMI", "FRAME" -> VirtualCpu.VEC_TIMER;
            case "RESET", "RST" -> VirtualCpu.VEC_RESET;
            default -> null;
        };
    }

    public static BinaryBytecode.ExtOperand parseOperand(String token, Map<String, Integer> labels) {
        String s = stripOuterParens(token.trim());
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Empty operand");
        }

        int reg = parseRegister(s);
        if (reg != -1) {
            return new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_REG, reg, 0);
        }

        if (s.startsWith("[") && s.endsWith("]")) {
            String inner = stripOuterParens(s.substring(1, s.length() - 1).trim());
            int innerReg = parseRegister(inner);
            if (innerReg >= 0 && innerReg <= BinaryBytecode.REG_D) {
                return new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_RAM_REG, innerReg, 0);
            }
            try {
                int addr = (int) evalConstExpr(inner, labels);
                return new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_RAM_IMM, 0, addr);
            } catch (RuntimeException ignored) {
                BinaryBytecode.ExtOperand regOff = tryParseRegOffset(inner, labels);
                if (regOff != null) {
                    return regOff;
                }
                throw new IllegalArgumentException("Invalid memory operand: " + token);
            }
        }

        int imm = (int) evalConstExpr(s, labels);
        return new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_IMM, 0, imm);
    }

    private static BinaryBytecode.ExtOperand tryParseRegOffset(String inner, Map<String, Integer> labels) {
        int plusIdx = findTopLevelOp(inner, '+');
        if (plusIdx > 0) {
            String left = stripOuterParens(inner.substring(0, plusIdx).trim());
            String right = stripOuterParens(inner.substring(plusIdx + 1).trim());
            int rL = parseRegister(left);
            if (rL >= 0 && rL <= BinaryBytecode.REG_D) {
                int off = (int) evalConstExpr(right, labels);
                return new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_RAM_REG_OFF, rL, off);
            }
            int rR = parseRegister(right);
            if (rR >= 0 && rR <= BinaryBytecode.REG_D) {
                int off = (int) evalConstExpr(left, labels);
                return new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_RAM_REG_OFF, rR, off);
            }
        }
        int minusIdx = findTopLevelOp(inner, '-');
        if (minusIdx > 0) {
            String left = stripOuterParens(inner.substring(0, minusIdx).trim());
            String right = stripOuterParens(inner.substring(minusIdx + 1).trim());
            int rL = parseRegister(left);
            if (rL >= 0 && rL <= BinaryBytecode.REG_D) {
                int off = (int) evalConstExpr(right, labels);
                return new BinaryBytecode.ExtOperand(BinaryBytecode.MODE_RAM_REG_OFF, rL, -off);
            }
        }
        return null;
    }

    private static int parseRegister(String s) {
        if (s == null) return -1;
        return switch (s.trim().toUpperCase(Locale.ROOT)) {
            case "A" -> BinaryBytecode.REG_A;
            case "B" -> BinaryBytecode.REG_B;
            case "C" -> BinaryBytecode.REG_C;
            case "D" -> BinaryBytecode.REG_D;
            case "PC" -> BinaryBytecode.REG_PC;
            case "FLAGS" -> BinaryBytecode.REG_FLAGS;
            default -> -1;
        };
    }

    private static int measureDataDirective(String opcode, String args) {
        List<String> tokens = splitArgsList(args);
        int total = 0;
        for (String rawTok : tokens) {
            String token = rawTok.trim();
            if (token.isEmpty()) continue;
            if (opcode.equals("DB")) {
                if (token.startsWith("\"") && token.endsWith("\"") && isQuotedStringLiteral(token)) {
                    total += unescapeQuotedString(token).getBytes(StandardCharsets.UTF_8).length;
                } else {
                    total += 1;
                }
            } else if (opcode.equals("DW")) {
                total += 2;
            } else if (opcode.equals("DL")) {
                total += 3;
            }
        }
        return total;
    }

    private static byte[] encodeDataDirective(String opcode, String args, Map<String, Integer> labels) {
        List<String> tokens = splitArgsList(args);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (String rawTok : tokens) {
            String token = rawTok.trim();
            if (token.isEmpty()) continue;
            if (opcode.equals("DB") && token.startsWith("\"") && token.endsWith("\"") && isQuotedStringLiteral(token)) {
                byte[] utf8 = unescapeQuotedString(token).getBytes(StandardCharsets.UTF_8);
                out.writeBytes(utf8);
                continue;
            }
            int val = (int) evalConstExpr(token, labels);
            if (opcode.equals("DB")) {
                out.write(val & 0xFF);
            } else if (opcode.equals("DW")) {
                out.write(val & 0xFF);
                out.write((val >> 8) & 0xFF);
            } else if (opcode.equals("DL")) {
                out.write(val & 0xFF);
                out.write((val >> 8) & 0xFF);
                out.write((val >> 16) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    private static byte[] readIncbinSlice(String args, Map<String, Integer> labels, Path baseDir) {
        List<String> tokens = splitArgsList(args);
        if (tokens.isEmpty()) {
            return new byte[0];
        }
        String pathToken = tokens.get(0).trim();
        if (pathToken.startsWith("\"") && pathToken.endsWith("\"") && pathToken.length() >= 2) {
            pathToken = pathToken.substring(1, pathToken.length() - 1);
        }
        int offset = tokens.size() > 1 ? (int) evalConstExpr(tokens.get(1), labels) : 0;
        int length = tokens.size() > 2 ? (int) evalConstExpr(tokens.get(2), labels) : -1;

        try {
            Path p = Path.of(pathToken);
            if (!Files.exists(p) && baseDir != null) {
                Path rel = baseDir.resolve(pathToken);
                if (Files.exists(rel)) {
                    p = rel;
                }
            }
            if (!Files.exists(p)) {
                p = Path.of("").toAbsolutePath().resolve(pathToken);
            }
            if (!Files.exists(p)) {
                throw new IllegalArgumentException("INCBIN file not found: " + pathToken);
            }
            byte[] allBytes = Files.readAllBytes(p);
            int start = Math.max(0, Math.min(offset, allBytes.length));
            int count = length >= 0 ? Math.min(length, allBytes.length - start) : (allBytes.length - start);
            return Arrays.copyOfRange(allBytes, start, start + Math.max(0, count));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("INCBIN failed: " + e.getMessage(), e);
        }
    }

    public static long evalConstExpr(String raw, Map<String, Integer> labels) {
        String s = stripOuterParens(raw == null ? "" : raw.trim());
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Empty expression");
        }

        if (s.startsWith("'") && s.endsWith("'") && isQuotedStringLiteral(s)) {
            return VirtualCpu.parseCharLiteral(s.substring(1, s.length() - 1));
        }

        int orIdx = findTopLevelBinaryOp(s, "|");
        if (orIdx > 0) {
            return evalConstExpr(s.substring(0, orIdx), labels) | evalConstExpr(s.substring(orIdx + 1), labels);
        }

        int xorIdx = findTopLevelBinaryOp(s, "^");
        if (xorIdx > 0) {
            return evalConstExpr(s.substring(0, xorIdx), labels) ^ evalConstExpr(s.substring(xorIdx + 1), labels);
        }

        int andIdx = findTopLevelBinaryOp(s, "&");
        if (andIdx > 0) {
            return evalConstExpr(s.substring(0, andIdx), labels) & evalConstExpr(s.substring(andIdx + 1), labels);
        }

        int shiftIdx = findTopLevelBinaryOp(s, "<<", ">>");
        if (shiftIdx > 0) {
            if (s.startsWith("<<", shiftIdx)) {
                return evalConstExpr(s.substring(0, shiftIdx), labels) << evalConstExpr(s.substring(shiftIdx + 2), labels);
            }
            return evalConstExpr(s.substring(0, shiftIdx), labels) >> evalConstExpr(s.substring(shiftIdx + 2), labels);
        }

        int addSubIdx = findTopLevelBinaryOp(s, "+", "-");
        if (addSubIdx > 0) {
            char op = s.charAt(addSubIdx);
            long l = evalConstExpr(s.substring(0, addSubIdx), labels);
            long r = evalConstExpr(s.substring(addSubIdx + 1), labels);
            return op == '+' ? (l + r) : (l - r);
        }

        int mulDivIdx = findTopLevelBinaryOp(s, "*", "/", "%");
        if (mulDivIdx > 0) {
            char op = s.charAt(mulDivIdx);
            long l = evalConstExpr(s.substring(0, mulDivIdx), labels);
            long r = evalConstExpr(s.substring(mulDivIdx + 1), labels);
            if (op == '*') return l * r;
            if (r == 0) {
                throw new ArithmeticException((op == '/' ? "Division" : "Modulo") + " by zero in expression: " + s);
            }
            return op == '/' ? (l / r) : (l % r);
        }

        if (s.startsWith("-")) {
            return -evalConstExpr(s.substring(1), labels);
        }
        if (s.startsWith("+")) {
            return evalConstExpr(s.substring(1), labels);
        }
        if (s.startsWith("~")) {
            return ~evalConstExpr(s.substring(1), labels);
        }

        if (labels != null) {
            Integer lblVal = labels.get(s.toLowerCase(Locale.ROOT));
            if (lblVal != null) {
                return lblVal;
            }
        }

        try {
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return Long.parseUnsignedLong(s.substring(2), 16);
            }
            if (s.startsWith("$")) {
                return Long.parseUnsignedLong(s.substring(1), 16);
            }
            if (s.startsWith("0b") || s.startsWith("0B")) {
                return Long.parseUnsignedLong(s.substring(2), 2);
            }
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid value or label: " + raw);
        }
    }

    private static int findTopLevelOp(String expr, char op) {
        return findTopLevelBinaryOp(expr, String.valueOf(op));
    }

    private static int findTopLevelBinaryOp(String expr, String... ops) {
        int parenDepth = 0;
        int bracketDepth = 0;
        boolean inQuotes = false;
        char quoteChar = 0;
        for (int i = expr.length() - 1; i >= 0; i--) {
            char c = expr.charAt(i);
            if ((c == '\"' || c == '\'') && !isEscaped(expr, i)) {
                if (inQuotes && c == quoteChar) inQuotes = false;
                else if (!inQuotes) { inQuotes = true; quoteChar = c; }
            }
            if (inQuotes) continue;
            if (c == ')') parenDepth++;
            else if (c == '(') parenDepth--;
            else if (c == ']') bracketDepth++;
            else if (c == '[') bracketDepth--;
            else if (parenDepth == 0 && bracketDepth == 0) {
                for (String op : ops) {
                    if (i + op.length() <= expr.length() && expr.startsWith(op, i)) {
                        if (isValidBinaryOpPosition(expr, i, op)) {
                            return i;
                        }
                    }
                }
            }
        }
        return -1;
    }

    private static boolean isValidBinaryOpPosition(String expr, int i, String op) {
        if (i <= 0 || i + op.length() >= expr.length()) return false;
        String leftPrefix = expr.substring(0, i).trim();
        if (leftPrefix.isEmpty()) return false;
        char lastLeft = leftPrefix.charAt(leftPrefix.length() - 1);
        if (lastLeft == '+' || lastLeft == '-' || lastLeft == '*' || lastLeft == '/' || lastLeft == '%'
                || lastLeft == '&' || lastLeft == '|' || lastLeft == '^' || lastLeft == '<' || lastLeft == '>'
                || lastLeft == '=' || lastLeft == '(' || lastLeft == '[' || lastLeft == '~') {
            return false;
        }
        if (op.equals("|")) {
            return (i == 0 || expr.charAt(i - 1) != '|') && (i + 1 >= expr.length() || expr.charAt(i + 1) != '|');
        }
        if (op.equals("&")) {
            return (i == 0 || expr.charAt(i - 1) != '&') && (i + 1 >= expr.length() || expr.charAt(i + 1) != '&');
        }
        if (op.equals("<")) {
            return (i == 0 || expr.charAt(i - 1) != '<') && (i + 1 >= expr.length() || expr.charAt(i + 1) != '<');
        }
        if (op.equals(">")) {
            return (i == 0 || expr.charAt(i - 1) != '>') && (i + 1 >= expr.length() || expr.charAt(i + 1) != '>');
        }
        return true;
    }

    private static String stripOuterParens(String s) {
        while (s.length() >= 2 && s.startsWith("(") && s.endsWith(")") && VirtualCpu.isMatchingOuterParentheses(s)) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    public static List<String> splitArgsList(String args) {
        List<String> list = new ArrayList<>();
        if (args == null || args.isBlank()) return list;
        int parenDepth = 0;
        int bracketDepth = 0;
        boolean inQuotes = false;
        char quoteChar = 0;
        int start = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if ((c == '\"' || c == '\'') && !isEscaped(args, i)) {
                if (inQuotes && c == quoteChar) inQuotes = false;
                else if (!inQuotes) { inQuotes = true; quoteChar = c; }
            }
            if (inQuotes) continue;
            if (c == '(') parenDepth++;
            else if (c == ')') parenDepth--;
            else if (c == '[') bracketDepth++;
            else if (c == ']') bracketDepth--;
            else if (c == ',' && parenDepth == 0 && bracketDepth == 0) {
                list.add(args.substring(start, i));
                start = i + 1;
            }
        }
        list.add(args.substring(start));
        return list;
    }

    private static boolean isQuotedStringLiteral(String s) {
        if (s == null || s.length() < 2) return false;
        char q = s.charAt(0);
        if ((q != '\"' && q != '\'') || s.charAt(s.length() - 1) != q) return false;
        for (int i = 1; i < s.length() - 1; i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == q) {
                return false;
            }
        }
        return !isEscaped(s, s.length() - 1);
    }

    public static String unescapeQuotedString(String s) {
        if (s.length() < 2) return s;
        String inner = s.substring(1, s.length() - 1);
        StringBuilder sb = new StringBuilder(inner.length());
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '\\' && i + 1 < inner.length()) {
                char next = inner.charAt(++i);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '0' -> sb.append('\0');
                    case '\\' -> sb.append('\\');
                    case '\"' -> sb.append('\"');
                    case '\'' -> sb.append('\'');
                    case 'x', 'X' -> {
                        if (i + 2 < inner.length()) {
                            try {
                                sb.append((char) Integer.parseInt(inner.substring(i + 1, i + 3), 16));
                                i += 2;
                                break;
                            } catch (NumberFormatException ignored) {}
                        }
                        sb.append(next);
                    }
                    case 'u', 'U' -> {
                        if (i + 4 < inner.length()) {
                            try {
                                sb.append((char) Integer.parseInt(inner.substring(i + 1, i + 5), 16));
                                i += 4;
                                break;
                            } catch (NumberFormatException ignored) {}
                        }
                        sb.append(next);
                    }
                    default -> sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int findFirstWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isEscaped(String s, int index) {
        int backslashes = 0;
        for (int i = index - 1; i >= 0 && s.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return (backslashes & 1) == 1;
    }

    public static int findLabelColon(String line) {
        boolean inQuotes = false;
        char quoteChar = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if ((c == '\"' || c == '\'') && !isEscaped(line, i)) {
                if (inQuotes && c == quoteChar) inQuotes = false;
                else if (!inQuotes) { inQuotes = true; quoteChar = c; }
            }
            if (!inQuotes && c == ':') {
                return i;
            }
        }
        return -1;
    }

    private static String stripComments(String raw) {
        if (raw == null) return "";
        boolean inQuotes = false;
        char quoteChar = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c == '\"' || c == '\'') && !isEscaped(raw, i)) {
                if (inQuotes && c == quoteChar) inQuotes = false;
                else if (!inQuotes) { inQuotes = true; quoteChar = c; }
            }
            if (!inQuotes) {
                if (c == ';' || c == '#') {
                    return raw.substring(0, i);
                }
                if (c == '/' && i + 1 < raw.length() && raw.charAt(i + 1) == '/') {
                    return raw.substring(0, i);
                }
            }
        }
        return raw;
    }
}
