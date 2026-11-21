package games.jvrcruz.siliconcraft.vm;

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
        if (pc >= program.size()) {
            state = State.HALTED;
            terminalOutput.add("[PROGRAM REACHED END]");
            return;
        }

        String rawLine = program.get(pc).trim();
        pc++;
        cycles++;

        // Skip comments or empty lines or label declarations
        if (rawLine.isEmpty() || rawLine.startsWith(";") || rawLine.startsWith("//") || rawLine.startsWith("#") || rawLine.endsWith(":")) {
            return;
        }

        // Parse opcode and arguments
        int firstSpace = rawLine.indexOf(' ');
        String opcode;
        String args = "";
        if (firstSpace == -1) {
            opcode = rawLine.toUpperCase(Locale.ROOT);
        } else {
            opcode = rawLine.substring(0, firstSpace).toUpperCase(Locale.ROOT).trim();
            args = rawLine.substring(firstSpace + 1).trim();
        }

        // Verify architecture hardware support!
        if (!supportedInstructions.contains(opcode)) {
            state = State.ERROR;
            errorMessage = "Instruction '" + opcode + "' not supported by this architecture!";
            terminalOutput.add("[CPU EXCEPTION] Illegal Opcode: " + opcode);
            terminalOutput.add("Error: " + errorMessage);
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
                case "HLT" -> {
                    state = State.HALTED;
                    terminalOutput.add("[EXECUTION HALTED]");
                }
                default -> {
                    state = State.ERROR;
                    errorMessage = "Unknown opcode: " + opcode;
                    terminalOutput.add("[CPU ERROR] " + errorMessage);
                }
            }
        } catch (Exception e) {
            state = State.ERROR;
            errorMessage = e.getMessage() != null ? e.getMessage() : "Execution exception";
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

    private void execOut(String arg) {
        arg = arg.trim();
        if ((arg.startsWith("\"") && arg.endsWith("\"")) || (arg.startsWith("'") && arg.endsWith("'"))) {
            terminalOutput.add(arg.substring(1, arg.length() - 1));
        } else {
            int val = resolveValue(arg);
            terminalOutput.add(arg.toUpperCase(Locale.ROOT) + " = " + val);
        }
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
    public List<String> getTerminalOutput() { return terminalOutput; }
    public Set<String> getSupportedInstructions() { return supportedInstructions; }
    public List<String> getProgram() { return program; }
    public int[] getRam() { return ram; }
}
