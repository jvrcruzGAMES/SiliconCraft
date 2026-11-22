package games.jvrcruz.siliconcraft.compiler.n1;

import java.util.List;
import java.util.Map;

public class NConditionCompiler {

    public static class LoopContext {
        public final String startLabel;
        public final String endLabel;
        public final int blockDepth;
        public LoopContext(String startLabel, String endLabel, int blockDepth) {
            this.startLabel = startLabel;
            this.endLabel = endLabel;
            this.blockDepth = blockDepth;
        }
    }

    public static class IfContext {
        public final String endLabel;
        public final int blockDepth;
        public IfContext(String endLabel, int blockDepth) {
            this.endLabel = endLabel;
            this.blockDepth = blockDepth;
        }
    }

    public static class FuncContext {
        public final String name;
        public final String endLabel;
        public final int blockDepth;
        public final boolean isInterrupt;

        public FuncContext(String name, String endLabel, int blockDepth) {
            this(name, endLabel, blockDepth, false);
        }

        public FuncContext(String name, String endLabel, int blockDepth, boolean isInterrupt) {
            this.name = name;
            this.endLabel = endLabel;
            this.blockDepth = blockDepth;
            this.isInterrupt = isInterrupt;
        }
    }

    public static void compileCondition(String cond, String branchLabel, boolean jumpOnTrue,
                                        Map<String, String> varMap, Map<String, NType.TableInfo> tableMap, List<String> assembly) {
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
            String loc = NExpressionCompiler.resolveLocation(inner, varMap, tableMap, assembly);
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
            String loc = NExpressionCompiler.resolveLocation(cond, varMap, tableMap, assembly);
            assembly.add("CMP " + loc + ", 0");
            assembly.add(jumpOnTrue ? ("JNZ " + branchLabel) : ("JZ " + branchLabel));
            return;
        }

        String[] parts = cond.split(op, 2);
        String leftRaw = parts[0].trim();
        String rightRaw = parts[1].trim();

        String leftLoc;
        if (!NExpressionCompiler.isSimpleLocation(leftRaw, varMap, tableMap)) {
            NExpressionCompiler.compileAssignment("C", leftRaw, varMap, tableMap, assembly);
            leftLoc = "C";
        } else {
            leftLoc = NExpressionCompiler.resolveLocation(leftRaw, varMap, tableMap, assembly);
        }

        String rightLoc;
        if (!NExpressionCompiler.isSimpleLocation(rightRaw, varMap, tableMap)) {
            NExpressionCompiler.compileAssignment("A", rightRaw, varMap, tableMap, assembly);
            rightLoc = "A";
        } else {
            rightLoc = NExpressionCompiler.resolveLocation(rightRaw, varMap, tableMap, assembly);
        }

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
        if (s == null) return "";
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
}
