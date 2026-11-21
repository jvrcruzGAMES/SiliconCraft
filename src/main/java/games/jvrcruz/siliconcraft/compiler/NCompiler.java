package games.jvrcruz.siliconcraft.compiler;

import java.util.*;

public class NCompiler {
    public record CompileResult(boolean success, List<String> assembly, List<String> errors, Set<String> requiredInstructions) {}

    public static CompileResult compile(List<String> sourceLines) {
        List<String> assembly = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, String> varMap = new LinkedHashMap<>();
        int nextRamAddress = 0;
        int labelCounter = 1;

        Deque<LoopContext> loopStack = new ArrayDeque<>();
        Deque<IfContext> ifStack = new ArrayDeque<>();

        assembly.add("; === Compiled from SiliconCraft .n source ===");

        for (int lineNum = 1; lineNum <= sourceLines.size(); lineNum++) {
            String rawLine = sourceLines.get(lineNum - 1);
            String line = InstructionValidator.stripComments(rawLine).trim();
            if (line.isEmpty()) continue;

            try {
                // Closing brace
                if (line.equals("}")) {
                    if (!ifStack.isEmpty() && (loopStack.isEmpty() || ifStack.peek().blockDepth >= loopStack.peek().blockDepth)) {
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

                // Check for inline assembly opcodes
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

                // Print statement
                if (line.startsWith("print ") || line.startsWith("println ")) {
                    String arg = line.substring(line.indexOf(' ') + 1).trim();
                    if ((arg.startsWith("\"") && arg.endsWith("\"")) || (arg.startsWith("'") && arg.endsWith("'"))) {
                        assembly.add("OUT " + arg);
                    } else {
                        String loc = resolveLocation(arg, varMap);
                        assembly.add("OUT " + loc);
                    }
                    continue;
                }

                // While loop: while <var> <op> <val> {
                if (line.startsWith("while ") && line.endsWith("{")) {
                    String condStr = line.substring(6, line.length() - 1).trim();
                    int loopId = labelCounter++;
                    String startLabel = "_while_start_" + loopId;
                    String endLabel = "_while_end_" + loopId;

                    assembly.add(startLabel + ":");
                    compileCondition(condStr, endLabel, false, varMap, assembly);
                    loopStack.push(new LoopContext(startLabel, endLabel, loopStack.size() + ifStack.size()));
                    continue;
                }

                // If statement: if <var> <op> <val> {
                if (line.startsWith("if ") && line.endsWith("{")) {
                    String condStr = line.substring(3, line.length() - 1).trim();
                    int ifId = labelCounter++;
                    String endLabel = "_if_end_" + ifId;

                    compileCondition(condStr, endLabel, false, varMap, assembly);
                    ifStack.push(new IfContext(endLabel, loopStack.size() + ifStack.size()));
                    continue;
                }

                // Increment: x++ or ++x
                if (line.endsWith("++")) {
                    String var = line.substring(0, line.length() - 2).trim();
                    String loc = resolveLocation(var, varMap);
                    assembly.add("INC " + loc);
                    continue;
                }

                // Decrement: x-- or --x
                if (line.endsWith("--")) {
                    String var = line.substring(0, line.length() - 2).trim();
                    String loc = resolveLocation(var, varMap);
                    assembly.add("DEC " + loc);
                    continue;
                }

                // Assignment: [var/let] <name> = <expr>
                if (line.startsWith("var ") || line.startsWith("let ")) {
                    line = line.substring(4).trim();
                }

                if (line.contains("=")) {
                    int eqIndex = line.indexOf('=');
                    String target = line.substring(0, eqIndex).trim();
                    String expr = line.substring(eqIndex + 1).trim();

                    // Check for += or -=
                    boolean isPlusEq = target.endsWith("+");
                    boolean isMinusEq = target.endsWith("-");
                    if (isPlusEq || isMinusEq) {
                        target = target.substring(0, target.length() - 1).trim();
                    }

                    if (!varMap.containsKey(target.toLowerCase(Locale.ROOT)) && !isRegister(target)) {
                        varMap.put(target.toLowerCase(Locale.ROOT), "[" + nextRamAddress++ + "]");
                    }
                    String targetLoc = resolveLocation(target, varMap);

                    if (isPlusEq) {
                        compileAdd(targetLoc, expr, varMap, assembly);
                    } else if (isMinusEq) {
                        compileSub(targetLoc, expr, varMap, assembly);
                    } else {
                        compileAssignment(targetLoc, expr, varMap, assembly);
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

    private static boolean isRegister(String token) {
        String u = token.toUpperCase(Locale.ROOT);
        return u.equals("A") || u.equals("B") || u.equals("C") || u.equals("D");
    }

    private static String resolveLocation(String token, Map<String, String> varMap) {
        token = token.trim();
        if (isRegister(token)) {
            return token.toUpperCase(Locale.ROOT);
        }
        String lower = token.toLowerCase(Locale.ROOT);
        if (varMap.containsKey(lower)) {
            return varMap.get(lower);
        }
        // Literal number or brackets
        return token;
    }

    private static void compileAssignment(String targetLoc, String expr, Map<String, String> varMap, List<String> assembly) {
        expr = expr.trim();
        // Check for binary + or -
        int plusIdx = expr.indexOf('+');
        int minusIdx = expr.indexOf('-');

        if (plusIdx > 0 && !expr.startsWith("+")) {
            String left = expr.substring(0, plusIdx).trim();
            String right = expr.substring(plusIdx + 1).trim();
            compileBinaryOp(targetLoc, left, right, "ADD", varMap, assembly);
        } else if (minusIdx > 0 && !expr.startsWith("-")) {
            String left = expr.substring(0, minusIdx).trim();
            String right = expr.substring(minusIdx + 1).trim();
            compileBinaryOp(targetLoc, left, right, "SUB", varMap, assembly);
        } else {
            String srcLoc = resolveLocation(expr, varMap);
            if (targetLoc.startsWith("[") && srcLoc.startsWith("[")) {
                // Cannot directly MOV [x], [y], must use temporary register A
                assembly.add("MOV A, " + srcLoc);
                assembly.add("MOV " + targetLoc + ", A");
            } else {
                assembly.add("MOV " + targetLoc + ", " + srcLoc);
            }
        }
    }

    private static void compileBinaryOp(String targetLoc, String left, String right, String op, Map<String, String> varMap, List<String> assembly) {
        String leftLoc = resolveLocation(left, varMap);
        String rightLoc = resolveLocation(right, varMap);

        if (targetLoc.equalsIgnoreCase(leftLoc)) {
            if (targetLoc.startsWith("[") && rightLoc.startsWith("[")) {
                assembly.add("MOV A, " + rightLoc);
                assembly.add(op + " " + targetLoc + ", A");
            } else {
                assembly.add(op + " " + targetLoc + ", " + rightLoc);
            }
        } else {
            // Need to load left into target first
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

    private static void compileAdd(String targetLoc, String expr, Map<String, String> varMap, List<String> assembly) {
        String srcLoc = resolveLocation(expr, varMap);
        if (targetLoc.startsWith("[") && srcLoc.startsWith("[")) {
            assembly.add("MOV A, " + srcLoc);
            assembly.add("ADD " + targetLoc + ", A");
        } else {
            assembly.add("ADD " + targetLoc + ", " + srcLoc);
        }
    }

    private static void compileSub(String targetLoc, String expr, Map<String, String> varMap, List<String> assembly) {
        String srcLoc = resolveLocation(expr, varMap);
        if (targetLoc.startsWith("[") && srcLoc.startsWith("[")) {
            assembly.add("MOV A, " + srcLoc);
            assembly.add("SUB " + targetLoc + ", A");
        } else {
            assembly.add("SUB " + targetLoc + ", " + srcLoc);
        }
    }

    private static void compileCondition(String cond, String branchLabel, boolean jumpOnTrue, Map<String, String> varMap, List<String> assembly) {
        // Supported operators: ==, !=, >=, <=, >, <
        String op = null;
        if (cond.contains("==")) op = "==";
        else if (cond.contains("!=")) op = "!=";
        else if (cond.contains(">=")) op = ">=";
        else if (cond.contains("<=")) op = "<=";
        else if (cond.contains(">")) op = ">";
        else if (cond.contains("<")) op = "<";

        if (op == null) {
            // Truthy check: if var != 0
            String loc = resolveLocation(cond, varMap);
            assembly.add("CMP " + loc + ", 0");
            assembly.add(jumpOnTrue ? ("JNZ " + branchLabel) : ("JZ " + branchLabel));
            return;
        }

        String[] parts = cond.split(op, 2);
        String left = resolveLocation(parts[0].trim(), varMap);
        String right = resolveLocation(parts[1].trim(), varMap);

        assembly.add("CMP " + left + ", " + right);

        // Map condition to JZ / JNZ
        switch (op) {
            case "==" -> assembly.add(jumpOnTrue ? ("JZ " + branchLabel) : ("JNZ " + branchLabel));
            case "!=" -> assembly.add(jumpOnTrue ? ("JNZ " + branchLabel) : ("JZ " + branchLabel));
            case ">", ">=" -> {
                // If left <= 0, jump
                assembly.add(jumpOnTrue ? ("JNZ " + branchLabel) : ("JZ " + branchLabel));
            }
            case "<", "<=" -> {
                assembly.add(jumpOnTrue ? ("JZ " + branchLabel) : ("JNZ " + branchLabel));
            }
        }
    }

    private record LoopContext(String startLabel, String endLabel, int blockDepth) {}
    private record IfContext(String endLabel, int blockDepth) {}
}
