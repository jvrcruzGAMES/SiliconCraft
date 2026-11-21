package games.jvrcruz.siliconcraft.vm;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class VirtualCpu {
    public enum State {
        OFF,
        RUNNING,
        PAUSED,
        HALTED,
        ERROR
    }

    private State state = State.OFF;
    private int pc = 0;
    private int regA = 0;
    private int regB = 0;
    private int regC = 0;
    private int regD = 0;
    private boolean flagZ = false;
    private boolean flagS = false;
    private int cycles = 0;
    private String errorMessage = "";

    private final Set<String> supportedInstructions = new HashSet<>();
    private final List<String> program = new ArrayList<>();
    private final Map<String, Integer> labels = new HashMap<>();
    private final List<String> terminalOutput = new ArrayList<>();
    private final StringBuilder lineBuffer = new StringBuilder();
    private final ByteArrayOutputStream utf8ByteBuf = new ByteArrayOutputStream();
    private int[] ram = new int[256];

    public VirtualCpu() {}

    public void configure(Set<String> instructions, int ramSize, List<String> code, boolean hasNvme, int storageSize) {
        this.supportedInstructions.clear();
        for (String instr : instructions) {
            this.supportedInstructions.add(instr.toUpperCase(Locale.ROOT).trim());
        }
        int effectiveRam = Math.max(16, Math.min(ramSize, 4096));
        this.ram = new int[effectiveRam];
        this.program.clear();
        this.program.addAll(code);
        this.labels.clear();
        preprocessLabels();
        reset();
    }

    private void preprocessLabels() {
        labels.clear();
        for (int i = 0; i < program.size(); i++) {
            String line = program.get(i).trim();
            if (line.endsWith(":")) {
                String labelName = line.substring(0, line.length() - 1).trim().toLowerCase(Locale.ROOT);
                labels.put(labelName, i);
            }
        }
    }

    public void reset() {
        this.pc = 0;
        this.regA = 0;
        this.regB = 0;
        this.regC = 0;
        this.regD = 0;
        this.flagZ = false;
        this.flagS = false;
        this.cycles = 0;
        this.errorMessage = "";
        Arrays.fill(ram, 0);
        this.terminalOutput.clear();
        this.lineBuffer.setLength(0);
        this.utf8ByteBuf.reset();
        if (state != State.OFF) {
            this.state = State.RUNNING;
        }
    }

    public void powerOn() {
        reset();
        this.state = State.RUNNING;
        terminalOutput.add("[SILICON-OS BIOS v1.0]");
        terminalOutput.add("ISA: " + supportedInstructions.size() + " opcodes");
        terminalOutput.add("RAM: " + ram.length + " Bytes OK");
        terminalOutput.add("Booting CPU...");
    }

    public void powerOff() {
        flushLineBuffer();
        this.state = State.OFF;
        this.terminalOutput.add("[POWER OFF]");
    }

    public void togglePower() {
        if (state == State.OFF) {
            powerOn();
        } else {
            powerOff();
        }
    }

    public void step() {
        if (state != State.RUNNING && state != State.PAUSED) {
            return;
        }
        executeNextInstruction();
    }

    public void tick(int maxCycles) {
        if (state != State.RUNNING) {
            return;
        }
        int count = 0;
        while (state == State.RUNNING && count < maxCycles) {
            executeNextInstruction();
            count++;
        }
    }

    private void executeNextInstruction() {
        if (pc < 0 || pc >= program.size()) {
            state = State.HALTED;
            flushLineBuffer();
            terminalOutput.add("[PROGRAM TERMINATED: END OF FILE]");
            return;
        }

        String rawLine = program.get(pc++);
        cycles++;

        // Strip comments
        int commentIndex = rawLine.indexOf(';');
        if (commentIndex == -1) commentIndex = rawLine.indexOf("//");
        if (commentIndex == -1) commentIndex = rawLine.indexOf('#');
        String line = (commentIndex != -1 ? rawLine.substring(0, commentIndex) : rawLine).trim();

        if (line.isEmpty() || line.endsWith(":")) {
            return; // Skip empty lines and labels
        }

        int spaceIndex = line.indexOf(' ');
        String opcode = (spaceIndex != -1 ? line.substring(0, spaceIndex) : line).toUpperCase(Locale.ROOT).trim();
        String args = spaceIndex != -1 ? line.substring(spaceIndex + 1).trim() : "";

        // Check if instruction is supported by this CPU's synthesized hardware
        if (!supportedInstructions.contains(opcode)) {
            state = State.ERROR;
            errorMessage = "Illegal instruction: " + opcode;
            flushLineBuffer();
            terminalOutput.add("[CPU EXCEPTION] " + errorMessage);
            return;
        }

        try {
            switch (opcode) {
                case "NOP" -> {}
                case "MOV" -> execMov(args);
                case "ADD" -> execAdd(args);
                case "SUB" -> execSub(args);
                case "INC" -> execInc(args);
                case "DEC" -> execDec(args);
                case "CMP" -> execCmp(args);
                case "JMP" -> execJmp(args, true);
                case "JZ" -> execJmp(args, flagZ);
                case "JNZ" -> execJmp(args, !flagZ);
                case "OUT" -> execOut(args);
                case "OUTC" -> execOutc(args);
                case "HLT" -> {
                    state = State.HALTED;
                    flushLineBuffer();
                    terminalOutput.add("[EXECUTION HALTED]");
                }
                default -> {
                    state = State.ERROR;
                    errorMessage = "Unknown opcode: " + opcode;
                    flushLineBuffer();
                    terminalOutput.add("[CPU ERROR] " + errorMessage);
                }
            }
        } catch (Exception e) {
            state = State.ERROR;
            errorMessage = e.getMessage() != null ? e.getMessage() : "Execution exception";
            flushLineBuffer();
            terminalOutput.add("[CPU EXCEPTION] " + errorMessage);
        }

        if (terminalOutput.size() > 60) {
            terminalOutput.remove(0);
        }
    }

    private void execMov(String args) {
        String[] parts = args.split(",", 2);
        if (parts.length != 2) throw new IllegalArgumentException("MOV requires dest, src");
        String dest = parts[0].trim();
        String src = parts[1].trim();
        int val = resolveValue(src);
        setDestination(dest, val);
    }

    private void execAdd(String args) {
        String[] parts = args.split(",", 2);
        if (parts.length != 2) throw new IllegalArgumentException("ADD requires dest, src");
        String dest = parts[0].trim();
        int current = resolveValue(dest);
        int addend = resolveValue(parts[1].trim());
        int result = current + addend;
        setDestination(dest, result);
        updateFlags(result);
    }

    private void execSub(String args) {
        String[] parts = args.split(",", 2);
        if (parts.length != 2) throw new IllegalArgumentException("SUB requires dest, src");
        String dest = parts[0].trim();
        int current = resolveValue(dest);
        int subtrahend = resolveValue(parts[1].trim());
        int result = current - subtrahend;
        setDestination(dest, result);
        updateFlags(result);
    }

    private void execInc(String arg) {
        String reg = arg.trim();
        int current = resolveValue(reg);
        int result = current + 1;
        setDestination(reg, result);
        updateFlags(result);
    }

    private void execDec(String arg) {
        String reg = arg.trim();
        int current = resolveValue(reg);
        int result = current - 1;
        setDestination(reg, result);
        updateFlags(result);
    }

    private void execCmp(String args) {
        String[] parts = args.split(",", 2);
        if (parts.length != 2) throw new IllegalArgumentException("CMP requires reg, val");
        int left = resolveValue(parts[0].trim());
        int right = resolveValue(parts[1].trim());
        int diff = left - right;
        updateFlags(diff);
    }

    private void execJmp(String target, boolean condition) {
        if (!condition) return;
        target = target.trim();
        String lower = target.toLowerCase(Locale.ROOT);
        if (labels.containsKey(lower)) {
            pc = labels.get(lower);
        } else {
            try {
                int line = Integer.parseInt(target);
                pc = Math.max(0, Math.min(line, program.size()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Label not found: " + target);
            }
        }
    }

    private void execOut(String args) {
        args = args.trim();
        boolean newline = true;
        String valPart = args;

        int lastComma = args.lastIndexOf(',');
        if (lastComma != -1) {
            boolean insideQuotes = false;
            char quoteChar = 0;
            int commaOutside = -1;
            for (int i = 0; i < args.length(); i++) {
                char c = args.charAt(i);
                if ((c == '\"' || c == '\'') && (i == 0 || args.charAt(i - 1) != '\\')) {
                    if (insideQuotes && c == quoteChar) insideQuotes = false;
                    else if (!insideQuotes) { insideQuotes = true; quoteChar = c; }
                } else if (c == ',' && !insideQuotes) {
                    commaOutside = i;
                }
            }
            if (commaOutside != -1) {
                valPart = args.substring(0, commaOutside).trim();
                String flag = args.substring(commaOutside + 1).trim().toLowerCase(Locale.ROOT);
                if (flag.equals("0") || flag.equals("cont") || flag.equals("no_nl") || flag.equals("false")) {
                    newline = false;
                }
            }
        }

        if ((valPart.startsWith("\"") && valPart.endsWith("\"")) || (valPart.startsWith("'") && valPart.endsWith("'"))) {
            lineBuffer.append(stripQuotes(valPart));
        } else {
            int val = resolveValue(valPart);
            lineBuffer.append(val);
        }

        if (newline) {
            flushLineBuffer();
        }
    }

    private void execOutc(String arg) {
        arg = arg.trim();
        if ((arg.startsWith("\"") && arg.endsWith("\"")) || (arg.startsWith("'") && arg.endsWith("'"))) {
            String s = stripQuotes(arg);
            for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
                emitCharByte(b & 0xFF);
            }
            return;
        }
        int val = resolveValue(arg);
        emitCharByte(val);
    }

    private void emitCharByte(int val) {
        if (val == 10) { // '\n'
            flushUtf8();
            if (lineBuffer.length() == 0) {
                terminalOutput.add("");
                if (terminalOutput.size() > 60) {
                    terminalOutput.remove(0);
                }
            } else {
                flushLineBuffer();
            }
            return;
        }
        if (val == 13) { // '\r'
            return;
        }

        if (val >= 0 && val <= 127) {
            // ASCII Character
            flushUtf8();
            lineBuffer.append((char) val);
        } else if (val > 255) {
            // Full Unicode Code Point
            flushUtf8();
            if (Character.isValidCodePoint(val)) {
                lineBuffer.appendCodePoint(val);
            } else {
                lineBuffer.append('?');
            }
        } else if (val > 127) {
            // UTF-8 stream byte (128..255)
            utf8ByteBuf.write((byte) val);
            byte[] bytes = utf8ByteBuf.toByteArray();
            try {
                CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
                CharBuffer charBuf = decoder.decode(ByteBuffer.wrap(bytes));
                lineBuffer.append(charBuf.toString());
                utf8ByteBuf.reset();
            } catch (CharacterCodingException e) {
                // Continuation bytes needed; flush if buffer exceeds 4 bytes
                if (utf8ByteBuf.size() >= 4) {
                    lineBuffer.append('?');
                    utf8ByteBuf.reset();
                }
            }
        }

        if (lineBuffer.length() >= 42) {
            flushLineBuffer();
        }
    }

    private void flushUtf8() {
        if (utf8ByteBuf.size() > 0) {
            try {
                String decoded = StandardCharsets.UTF_8.newDecoder()
                        .decode(ByteBuffer.wrap(utf8ByteBuf.toByteArray())).toString();
                lineBuffer.append(decoded);
            } catch (Exception e) {
                lineBuffer.append('?');
            }
            utf8ByteBuf.reset();
        }
    }

    private void flushLineBuffer() {
        flushUtf8();
        if (lineBuffer.length() > 0) {
            String content = lineBuffer.toString();
            lineBuffer.setLength(0);
            if (content.contains("\n")) {
                String[] split = content.split("\n", -1);
                for (int i = 0; i < split.length - 1; i++) {
                    terminalOutput.add(split[i]);
                    if (terminalOutput.size() > 60) {
                        terminalOutput.remove(0);
                    }
                }
                if (!split[split.length - 1].isEmpty()) {
                    lineBuffer.append(split[split.length - 1]);
                }
            } else {
                terminalOutput.add(content);
                if (terminalOutput.size() > 60) {
                    terminalOutput.remove(0);
                }
            }
        }
    }

    private String stripQuotes(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            s = s.substring(1, s.length() - 1);
            s = s.replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r").replace("\\\"", "\"").replace("\\\'", "\'");
        }
        return s;
    }

    public static int parseCharLiteral(String s) {
        if (s == null || s.isEmpty()) return 0;
        if (s.startsWith("\\") && s.length() >= 2) {
            char esc = s.charAt(1);
            return switch (esc) {
                case 'n' -> 10;
                case 't' -> 9;
                case 'r' -> 13;
                case '0' -> 0;
                case '\\' -> 92;
                case '\'' -> 39;
                case '\"' -> 34;
                case 'x', 'X' -> {
                    if (s.length() >= 4) {
                        try {
                            yield Integer.parseInt(s.substring(2, 4), 16);
                        } catch (NumberFormatException ignored) {}
                    }
                    yield s.charAt(1);
                }
                case 'u', 'U' -> {
                    if (s.length() >= 6) {
                        try {
                            yield Integer.parseInt(s.substring(2, 6), 16);
                        } catch (NumberFormatException ignored) {}
                    }
                    yield s.charAt(1);
                }
                default -> esc;
            };
        }
        return s.codePointAt(0);
    }

    private int resolveValue(String token) {
        token = token.trim();
        if (token.equalsIgnoreCase("A")) return regA;
        if (token.equalsIgnoreCase("B")) return regB;
        if (token.equalsIgnoreCase("C")) return regC;
        if (token.equalsIgnoreCase("D")) return regD;

        if (token.startsWith("[") && token.endsWith("]")) {
            String inner = token.substring(1, token.length() - 1).trim();
            int addr = resolveValue(inner);
            if (addr >= 0 && addr < ram.length) {
                return ram[addr];
            }
            throw new IndexOutOfBoundsException("RAM address " + addr + " out of bounds");
        }

        // Char literal: 'x'
        if (token.startsWith("'") && token.endsWith("'") && token.length() >= 2) {
            String inner = token.substring(1, token.length() - 1);
            return parseCharLiteral(inner);
        }

        try {
            if (token.startsWith("0x") || token.startsWith("0X")) {
                return Integer.parseInt(token.substring(2), 16);
            }
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid value or register: " + token);
        }
    }

    private void setDestination(String dest, int val) {
        dest = dest.trim();
        if (dest.equalsIgnoreCase("A")) { regA = val; return; }
        if (dest.equalsIgnoreCase("B")) { regB = val; return; }
        if (dest.equalsIgnoreCase("C")) { regC = val; return; }
        if (dest.equalsIgnoreCase("D")) { regD = val; return; }

        if (dest.startsWith("[") && dest.endsWith("]")) {
            String inner = dest.substring(1, dest.length() - 1).trim();
            int addr = resolveValue(inner);
            if (addr >= 0 && addr < ram.length) {
                ram[addr] = val;
                return;
            }
            throw new IndexOutOfBoundsException("RAM write to address " + addr + " out of bounds");
        }

        throw new IllegalArgumentException("Cannot write to destination: " + dest);
    }

    private void updateFlags(int val) {
        flagZ = (val == 0);
        flagS = (val < 0);
    }

    // Getters
    public State getState() { return state; }
    public int getPc() { return pc; }
    public int getRegA() { return regA; }
    public int getRegB() { return regB; }
    public int getRegC() { return regC; }
    public int getRegD() { return regD; }
    public boolean isFlagZ() { return flagZ; }
    public boolean isFlagS() { return flagS; }
    public int getCycles() { return cycles; }
    public String getErrorMessage() { return errorMessage; }

    public List<String> getTerminalOutput() {
        if (lineBuffer.length() > 0) {
            List<String> combined = new ArrayList<>(terminalOutput);
            combined.add(lineBuffer.toString());
            return combined;
        }
        return terminalOutput;
    }

    public Set<String> getSupportedInstructions() { return supportedInstructions; }
    public List<String> getProgram() { return program; }
    public int[] getRam() { return ram; }
}
