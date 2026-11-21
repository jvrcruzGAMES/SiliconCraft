package games.jvrcruz.siliconcraft.compiler;

import java.util.*;

public class InstructionValidator {
    public static final List<String> ALL_INSTRUCTIONS = List.of(
            // Flow & Control
            "NOP", "HLT", "JMP", "JZ", "JNZ", "JS", "JNS", "JL", "JLE", "JG", "JGE", "CALL", "RET",
            // Data movement & Stack
            "MOV", "PUSH", "POP",
            // Arithmetic & Logic
            "ADD", "SUB", "MUL", "DIV", "MOD", "INC", "DEC", "CMP", "AND", "OR", "XOR", "NOT", "SHL", "SHR",
            // I/O & System / BIOS
            "OUT", "OUTC", "CLS", "SLEEP", "TIME", "RAND"
    );
    public static final Set<String> ALL_INSTRUCTIONS_SET = new HashSet<>(ALL_INSTRUCTIONS);

    public static Set<String> getRequiredInstructions(List<String> assemblyLines) {
        Set<String> required = new LinkedHashSet<>();
        if (assemblyLines == null) return required;

        for (String raw : assemblyLines) {
            String line = stripComments(raw).trim();
            if (line.isEmpty() || line.endsWith(":")) {
                continue;
            }
            int space = line.indexOf(' ');
            String opcode = (space == -1 ? line : line.substring(0, space)).toUpperCase(Locale.ROOT).trim();
            if (ALL_INSTRUCTIONS_SET.contains(opcode)) {
                required.add(opcode);
            }
        }
        return required;
    }

    public static List<String> getMissingInstructions(Set<String> required, List<String> supported) {
        List<String> missing = new ArrayList<>();
        Set<String> supportedSet = new HashSet<>();
        if (supported != null) {
            for (String s : supported) {
                supportedSet.add(s.toUpperCase(Locale.ROOT).trim());
            }
        }

        for (String req : required) {
            if (!supportedSet.contains(req.toUpperCase(Locale.ROOT).trim())) {
                missing.add(req);
            }
        }
        return missing;
    }

    public static String stripComments(String line) {
        if (line == null) return "";
        int semi = line.indexOf(';');
        int slash = line.indexOf("//");
        int hash = line.indexOf('#');

        int cutoff = -1;
        if (semi != -1) cutoff = semi;
        if (slash != -1 && (cutoff == -1 || slash < cutoff)) cutoff = slash;
        if (hash != -1 && (cutoff == -1 || hash < cutoff)) cutoff = hash;

        return (cutoff == -1) ? line : line.substring(0, cutoff);
    }
}
