package games.jvrcruz.siliconcraft.compiler;

import java.util.*;

public class Assembler {

    public record AssemblerResult(
            boolean success,
            List<String> bytecode,
            Set<String> requiredInstructions,
            List<String> errors
    ) {}

    public static AssemblerResult assemble(List<String> sourceLines) {
        if (sourceLines == null || sourceLines.isEmpty()) {
            return new AssemblerResult(true, List.of(), Set.of(), List.of());
        }

        List<String> cleanedLines = new ArrayList<>();
        Map<String, Integer> labels = new HashMap<>();
        List<String> errors = new ArrayList<>();

        // Pass 1: Strip comments, collect label positions, and prepare instruction list
        for (int i = 0; i < sourceLines.size(); i++) {
            String raw = sourceLines.get(i);
            String line = stripComments(raw).trim();
            if (line.isEmpty()) {
                continue;
            }

            int colon = findLabelColon(line);
            if (colon != -1) {
                String labelName = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                if (labelName.isEmpty()) {
                    errors.add("Line " + (i + 1) + ": Empty label declaration");
                    continue;
                }
                String rest = line.substring(colon + 1).trim();
                labels.put(labelName, cleanedLines.size());
                if (!rest.isEmpty()) {
                    cleanedLines.add(rest);
                }
            } else {
                cleanedLines.add(line);
            }
        }

        if (!errors.isEmpty()) {
            return new AssemblerResult(false, List.of(), Set.of(), errors);
        }

        // Pass 2: Resolve label references into direct numeric addresses
        List<String> bytecode = new ArrayList<>();
        Set<String> jumpOpcodes = Set.of(
                "JMP", "JZ", "JNZ", "JS", "JNS", "JL", "JLE", "JG", "JGE", "CALL"
        );

        for (int i = 0; i < cleanedLines.size(); i++) {
            String line = cleanedLines.get(i);
            int space = line.indexOf(' ');
            String opcode = (space == -1 ? line : line.substring(0, space)).toUpperCase(Locale.ROOT).trim();
            String args = space == -1 ? "" : line.substring(space + 1).trim();

            if (jumpOpcodes.contains(opcode) && !args.isEmpty()) {
                String targetLower = args.toLowerCase(Locale.ROOT);
                if (labels.containsKey(targetLower)) {
                    int targetPc = labels.get(targetLower);
                    bytecode.add(opcode + " " + targetPc);
                } else {
                    bytecode.add(line);
                }
            } else if (opcode.equals("IVEC") && !args.isEmpty()) {
                String[] parts = args.split(",", 2);
                if (parts.length == 2) {
                    String vec = parts[0].trim();
                    String handler = parts[1].trim();
                    String handlerLower = handler.toLowerCase(Locale.ROOT);
                    if (labels.containsKey(handlerLower)) {
                        int targetPc = labels.get(handlerLower);
                        bytecode.add("IVEC " + vec + ", " + targetPc);
                    } else {
                        bytecode.add(line);
                    }
                } else {
                    bytecode.add(line);
                }
            } else if (!args.isEmpty()) {
                // Check if any argument references a label (e.g. MOV A, label or MOV [label], val)
                String resolvedArgs = resolveLabelsInArgs(args, labels);
                bytecode.add(opcode + " " + resolvedArgs);
            } else {
                bytecode.add(line);
            }
        }

        Set<String> required = InstructionValidator.getRequiredInstructions(bytecode);
        return new AssemblerResult(true, bytecode, required, List.of());
    }

    private static String resolveLabelsInArgs(String args, Map<String, Integer> labels) {
        if (labels.isEmpty() || args == null || args.isEmpty()) {
            return args;
        }

        // If args is inside quotes, don't substitute
        if (args.startsWith("\"") && args.endsWith("\"")) {
            return args;
        }

        String[] parts = args.split(",");
        StringBuilder sb = new StringBuilder();
        for (int p = 0; p < parts.length; p++) {
            if (p > 0) sb.append(", ");
            String token = parts[p].trim();
            String tokenLower = token.toLowerCase(Locale.ROOT);

            // Direct label reference
            if (labels.containsKey(tokenLower)) {
                sb.append(labels.get(tokenLower));
                continue;
            }

            // Memory bracket reference: [label]
            if (token.startsWith("[") && token.endsWith("]")) {
                String inside = token.substring(1, token.length() - 1).trim();
                String insideLower = inside.toLowerCase(Locale.ROOT);
                if (labels.containsKey(insideLower)) {
                    sb.append("[").append(labels.get(insideLower)).append("]");
                    continue;
                }
            }

            sb.append(token);
        }
        return sb.toString();
    }

    public static int findLabelColon(String line) {
        boolean inQuotes = false;
        char quoteChar = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if ((c == '\"' || c == '\'') && (i == 0 || line.charAt(i - 1) != '\\')) {
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
            if ((c == '\"' || c == '\'') && (i == 0 || raw.charAt(i - 1) != '\\')) {
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
