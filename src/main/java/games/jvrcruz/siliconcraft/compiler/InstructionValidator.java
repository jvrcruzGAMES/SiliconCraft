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
            "OUT", "OUTC", "OUTH", "CLS", "SLEEP", "TIME", "RAND", "INPAD",
            // Storage (multi-drive)
            "SSIZE", "SREAD", "SWRITE", "SBLKR", "SBLKW", "SCOUNT", "SSEL", "SDRV", "SEXEC",
            // Framebuffer / Graphics
            "FBMODE", "FBSET", "FBGET", "FBCLR", "FBRECT", "FBLINE", "FBLIT", "FBSWAP", "FBWIDTH", "FBHEIGHT",
            // Audio Buffer & Sound
            "APUSH", "ABUFW", "ACLR", "ASTAT", "ATONE", "APLAY",
            // Adjacent peripherals
            "PINFO", "PWRAP", "PCALL", "PCALLS", "PSTATUS", "PRESULT", "PLEN", "PRESULTS", "PSCAN", "GALLOC",
            // Server network
            "NOPEN", "NSEND", "NRECV", "NCLOSE", "NHTTP", "NSTATUS", "NLEN", "NRESULT", "NWSOPEN", "NWSTEXT", "NTLS", "NHTTP3", "NLISTEN", "NACCEPT", "NRESPOND", "NREADY", "NUTF8", "NRESPONDT",
            // Interrupts & Exceptions
            "IVEC", "IRET", "CLI", "STI", "INT", "TIMER", "VBLANK"
    );
    public static final Set<String> ALL_INSTRUCTIONS_SET = new HashSet<>(ALL_INSTRUCTIONS);

    // Static Assembler Directives (processed by assembler/loader, do not require CPU silicon opcodes)
    public static final Set<String> ASSEMBLER_DIRECTIVES = Set.of(
            "ORG", "DB", "DW", "DL", "INCBIN"
    );
    public static final Set<String> ALL_DIRECTIVES_SET = new HashSet<>(ASSEMBLER_DIRECTIVES);

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
            if (ASSEMBLER_DIRECTIVES.contains(opcode)) {
                continue;
            }
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
