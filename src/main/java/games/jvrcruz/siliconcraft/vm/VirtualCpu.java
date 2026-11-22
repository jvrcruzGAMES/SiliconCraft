package games.jvrcruz.siliconcraft.vm;

import games.jvrcruz.siliconcraft.compiler.InstructionValidator;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

public class VirtualCpu {
    public enum State {
        OFF,
        RUNNING,
        PAUSED,
        HALTED,
        ERROR
    }

    private volatile State state = State.OFF;
    private int pc = 0;
    private int regA = 0;
    private int regB = 0;
    private int regC = 0;
    private int regD = 0;
    private boolean flagZ = false;
    private boolean flagS = false;
    private int cycles = 0;
    private String errorMessage = "";

    private final Object executionLock = new Object();

    private final Set<String> supportedInstructions = new HashSet<>();
    private final List<String> program = new ArrayList<>();
    private final Map<String, Integer> labels = new HashMap<>();
    private final Map<Integer, List<String>> drivePrograms = new HashMap<>();
    private final List<String> terminalOutput = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final StringBuilder lineBuffer = new StringBuilder();
    private final ByteArrayOutputStream utf8ByteBuf = new ByteArrayOutputStream();
    private final Deque<Integer> callStack = new ArrayDeque<>();
    private final Deque<Integer> dataStack = new ArrayDeque<>();
    private int sleepTicksRemaining = 0;
    private volatile LongSupplier timeSupplier = System::currentTimeMillis;
    private final Random random = new Random();
    private int[] ram = new int[256];
    private boolean hasNvme = false;
    private int storageSize = 0;

    public static final int MAX_DRIVES = 4;
    private final byte[][] storageDrives = new byte[MAX_DRIVES][];
    private final int[] driveCapacities = new int[MAX_DRIVES];
    private int selectedDrive = 0;
    private BiConsumer<Integer, byte[]> driveWriteListener;

    public static final int FB_WIDTH = 240;
    public static final int FB_HEIGHT = 135;
    private volatile int displayMode = 0; // 0 = text mode, 1 = framebuffer mode
    private final int[] framebuffer = new int[FB_WIDTH * FB_HEIGHT];
    private volatile boolean fbDirty = false;

    // Bare-bones audio buffer (8000 Hz, 8-bit unsigned PCM samples)
    private byte[] audioQueue = new byte[65536];
    private int audioHead = 0;
    private int audioTail = 0;
    private int audioCount = 0;
    private boolean audioTriggered = false;

    // Interrupt & Exception Vectors
    public static final int VEC_DIV_ZERO = 0;
    public static final int VEC_ILLEGAL_OPCODE = 1;
    public static final int VEC_OUT_OF_BOUNDS = 2;
    public static final int VEC_STACK_FAULT = 3;
    public static final int VEC_GENERAL_EXCEPTION = 4;

    public static final int VEC_KEYBOARD = 10;
    public static final int VEC_MOUSE_MOVE = 11;
    public static final int VEC_MOUSE_CLICK = 12;
    public static final int VEC_TIMER = 13;
    public static final int VEC_VBLANK = 13;
    public static final int VEC_RESET = 14;

    // Gamepad / Held controller state (bitmask)
    public static final int PAD_RIGHT  = 1 << 0;  // 0x0001
    public static final int PAD_LEFT   = 1 << 1;  // 0x0002
    public static final int PAD_DOWN   = 1 << 2;  // 0x0004
    public static final int PAD_UP     = 1 << 3;  // 0x0008
    public static final int PAD_START  = 1 << 4;  // 0x0010
    public static final int PAD_SELECT = 1 << 5;  // 0x0020
    public static final int PAD_B      = 1 << 6;  // 0x0040 (Jump)
    public static final int PAD_A      = 1 << 7;  // 0x0080 (Spin Jump)
    public static final int PAD_Y      = 1 << 8;  // 0x0100 (Run / Dash)
    public static final int PAD_X      = 1 << 9;  // 0x0200 (Action)
    public static final int PAD_L      = 1 << 10; // 0x0400 (Shoulder L)
    public static final int PAD_R      = 1 << 11; // 0x0800 (Shoulder R)

    private final int[] padStates = new int[4];

    // Hardware frame / periodic timer
    private int timerHz = 0;
    private long lastTimerNanos = 0;
    private int timerIntervalCycles = 0;
    private int lastTimerCycle = 0;

    // Assembler Directives
    public static final Set<String> DIRECTIVES = InstructionValidator.ASSEMBLER_DIRECTIVES;
    private final Map<String, Integer> dataLabels = new HashMap<>();

    public record CpuContext(int pc, int regA, int regB, int regC, int regD, boolean flagZ, boolean flagS, boolean restoreRegs) {}
    public record InterruptEvent(int vector, int regA, int regB, int regC) {}

    private final Map<Integer, Integer> ivt = new HashMap<>();
    private final Deque<InterruptEvent> interruptQueue = new ArrayDeque<>();
    private final Deque<CpuContext> interruptStack = new ArrayDeque<>();
    private boolean interruptsEnabled = true;
    private boolean inInterruptHandler = false;

    // Bytecode opcode constants for O(1) integer switch
    private static final int OP_UNKNOWN = 0;
    private static final int OP_NOP = 1;
    private static final int OP_MOV = 2;
    private static final int OP_ADD = 3;
    private static final int OP_SUB = 4;
    private static final int OP_MUL = 5;
    private static final int OP_DIV = 6;
    private static final int OP_MOD = 7;
    private static final int OP_INC = 8;
    private static final int OP_DEC = 9;
    private static final int OP_CMP = 10;
    private static final int OP_AND = 11;
    private static final int OP_OR = 12;
    private static final int OP_XOR = 13;
    private static final int OP_NOT = 14;
    private static final int OP_SHL = 15;
    private static final int OP_SHR = 16;
    private static final int OP_JMP = 17;
    private static final int OP_JZ = 18;
    private static final int OP_JNZ = 19;
    private static final int OP_JS = 20;
    private static final int OP_JNS = 21;
    private static final int OP_JL = 22;
    private static final int OP_JLE = 23;
    private static final int OP_JG = 24;
    private static final int OP_JGE = 25;
    private static final int OP_CALL = 26;
    private static final int OP_RET = 27;
    private static final int OP_PUSH = 28;
    private static final int OP_POP = 29;
    private static final int OP_IVEC = 30;
    private static final int OP_IRET = 31;
    private static final int OP_CLI = 32;
    private static final int OP_STI = 33;
    private static final int OP_INT = 34;
    private static final int OP_OUT = 35;
    private static final int OP_OUTH = 36;
    private static final int OP_OUTC = 37;
    private static final int OP_CLS = 38;
    private static final int OP_SLEEP = 39;
    private static final int OP_TIME = 40;
    private static final int OP_RAND = 41;
    private static final int OP_INPAD = 42;
    private static final int OP_TIMER = 43;
    private static final int OP_VBLANK = 44;
    private static final int OP_SSIZE = 45;
    private static final int OP_SREAD = 46;
    private static final int OP_SWRITE = 47;
    private static final int OP_SBLKR = 48;
    private static final int OP_SBLKW = 49;
    private static final int OP_SCOUNT = 50;
    private static final int OP_SSEL = 51;
    private static final int OP_SDRV = 52;
    private static final int OP_SEXEC = 53;
    private static final int OP_FBMODE = 54;
    private static final int OP_FBSET = 55;
    private static final int OP_FBGET = 56;
    private static final int OP_FBCLR = 57;
    private static final int OP_FBRECT = 58;
    private static final int OP_FBLINE = 59;
    private static final int OP_FBLIT = 60;
    private static final int OP_FBSWAP = 61;
    private static final int OP_FBWIDTH = 62;
    private static final int OP_FBHEIGHT = 63;
    private static final int OP_APUSH = 64;
    private static final int OP_ABUFW = 65;
    private static final int OP_ACLR = 66;
    private static final int OP_ASTAT = 67;
    private static final int OP_ATONE = 68;
    private static final int OP_APLAY = 69;
    private static final int OP_HLT = 70;
    private static final int OP_DIRECTIVE = 71;

    private static final Map<String, Integer> OPCODE_MAP = new HashMap<>();
    static {
        OPCODE_MAP.put("NOP", OP_NOP);
        OPCODE_MAP.put("MOV", OP_MOV);
        OPCODE_MAP.put("ADD", OP_ADD);
        OPCODE_MAP.put("SUB", OP_SUB);
        OPCODE_MAP.put("MUL", OP_MUL);
        OPCODE_MAP.put("DIV", OP_DIV);
        OPCODE_MAP.put("MOD", OP_MOD);
        OPCODE_MAP.put("INC", OP_INC);
        OPCODE_MAP.put("DEC", OP_DEC);
        OPCODE_MAP.put("CMP", OP_CMP);
        OPCODE_MAP.put("AND", OP_AND);
        OPCODE_MAP.put("OR", OP_OR);
        OPCODE_MAP.put("XOR", OP_XOR);
        OPCODE_MAP.put("NOT", OP_NOT);
        OPCODE_MAP.put("SHL", OP_SHL);
        OPCODE_MAP.put("SHR", OP_SHR);
        OPCODE_MAP.put("JMP", OP_JMP);
        OPCODE_MAP.put("JZ", OP_JZ);
        OPCODE_MAP.put("JNZ", OP_JNZ);
        OPCODE_MAP.put("JS", OP_JS);
        OPCODE_MAP.put("JNS", OP_JNS);
        OPCODE_MAP.put("JL", OP_JL);
        OPCODE_MAP.put("JLE", OP_JLE);
        OPCODE_MAP.put("JG", OP_JG);
        OPCODE_MAP.put("JGE", OP_JGE);
        OPCODE_MAP.put("CALL", OP_CALL);
        OPCODE_MAP.put("RET", OP_RET);
        OPCODE_MAP.put("PUSH", OP_PUSH);
        OPCODE_MAP.put("POP", OP_POP);
        OPCODE_MAP.put("IVEC", OP_IVEC);
        OPCODE_MAP.put("IRET", OP_IRET);
        OPCODE_MAP.put("CLI", OP_CLI);
        OPCODE_MAP.put("STI", OP_STI);
        OPCODE_MAP.put("INT", OP_INT);
        OPCODE_MAP.put("OUT", OP_OUT);
        OPCODE_MAP.put("OUTH", OP_OUTH);
        OPCODE_MAP.put("OUTC", OP_OUTC);
        OPCODE_MAP.put("CLS", OP_CLS);
        OPCODE_MAP.put("SLEEP", OP_SLEEP);
        OPCODE_MAP.put("TIME", OP_TIME);
        OPCODE_MAP.put("RAND", OP_RAND);
        OPCODE_MAP.put("INPAD", OP_INPAD);
        OPCODE_MAP.put("TIMER", OP_TIMER);
        OPCODE_MAP.put("SETTIMER", OP_TIMER);
        OPCODE_MAP.put("VBLANK", OP_VBLANK);
        OPCODE_MAP.put("SSIZE", OP_SSIZE);
        OPCODE_MAP.put("SREAD", OP_SREAD);
        OPCODE_MAP.put("SWRITE", OP_SWRITE);
        OPCODE_MAP.put("SBLKR", OP_SBLKR);
        OPCODE_MAP.put("SBLKW", OP_SBLKW);
        OPCODE_MAP.put("SCOUNT", OP_SCOUNT);
        OPCODE_MAP.put("SSEL", OP_SSEL);
        OPCODE_MAP.put("SDRV", OP_SDRV);
        OPCODE_MAP.put("SEXEC", OP_SEXEC);
        OPCODE_MAP.put("SBOOT", OP_SEXEC);
        OPCODE_MAP.put("FBMODE", OP_FBMODE);
        OPCODE_MAP.put("FBSET", OP_FBSET);
        OPCODE_MAP.put("FBGET", OP_FBGET);
        OPCODE_MAP.put("FBCLR", OP_FBCLR);
        OPCODE_MAP.put("FBRECT", OP_FBRECT);
        OPCODE_MAP.put("FBLINE", OP_FBLINE);
        OPCODE_MAP.put("FBLIT", OP_FBLIT);
        OPCODE_MAP.put("FBSWAP", OP_FBSWAP);
        OPCODE_MAP.put("FBWIDTH", OP_FBWIDTH);
        OPCODE_MAP.put("FBHEIGHT", OP_FBHEIGHT);
        OPCODE_MAP.put("APUSH", OP_APUSH);
        OPCODE_MAP.put("ABUFW", OP_ABUFW);
        OPCODE_MAP.put("ACLR", OP_ACLR);
        OPCODE_MAP.put("ASTAT", OP_ASTAT);
        OPCODE_MAP.put("ATONE", OP_ATONE);
        OPCODE_MAP.put("APLAY", OP_APLAY);
        OPCODE_MAP.put("HLT", OP_HLT);
    }

    private static final int TYPE_NONE = 0;
    private static final int TYPE_REG = 1;
    private static final int TYPE_IMM = 2;
    private static final int TYPE_RAM_IMM = 3;
    private static final int TYPE_RAM_REG = 4;
    private static final int TYPE_DYNAMIC = 5;

    private static final class CompiledInstruction {
        final int opCode;
        final boolean isSupported;
        final String rawLine;
        final String rawArgs;
        final int destType;
        final int destReg;
        final int destImm;
        final int srcType;
        final int srcReg;
        final int srcImm;
        final int targetPc;

        CompiledInstruction(int opCode, boolean isSupported, String rawLine, String rawArgs,
                            int destType, int destReg, int destImm,
                            int srcType, int srcReg, int srcImm,
                            int targetPc) {
            this.opCode = opCode;
            this.isSupported = isSupported;
            this.rawLine = rawLine;
            this.rawArgs = rawArgs;
            this.destType = destType;
            this.destReg = destReg;
            this.destImm = destImm;
            this.srcType = srcType;
            this.srcReg = srcReg;
            this.srcImm = srcImm;
            this.targetPc = targetPc;
        }
    }

    private CompiledInstruction[] compiledProgram = new CompiledInstruction[0];

    private int getReg(int reg) {
        return switch (reg) {
            case 0 -> regA;
            case 1 -> regB;
            case 2 -> regC;
            case 3 -> regD;
            default -> 0;
        };
    }

    private void setReg(int reg, int val) {
        switch (reg) {
            case 0 -> regA = val;
            case 1 -> regB = val;
            case 2 -> regC = val;
            case 3 -> regD = val;
        }
    }

    private static int parseRegister(String s) {
        if (s == null) return -1;
        String u = s.trim().toUpperCase(Locale.ROOT);
        return switch (u) {
            case "A" -> 0;
            case "B" -> 1;
            case "C" -> 2;
            case "D" -> 3;
            default -> -1;
        };
    }

    private int[] parseOperand(String token) {
        token = token.trim();
        int reg = parseRegister(token);
        if (reg != -1) {
            return new int[]{TYPE_REG, reg, 0};
        }
        if (token.startsWith("[") && token.endsWith("]")) {
            String inner = token.substring(1, token.length() - 1).trim();
            int innerReg = parseRegister(inner);
            if (innerReg != -1) {
                return new int[]{TYPE_RAM_REG, innerReg, 0};
            }
            try {
                int imm = parseLiteral(inner);
                return new int[]{TYPE_RAM_IMM, -1, imm};
            } catch (Exception e) {
                return new int[]{TYPE_DYNAMIC, -1, 0};
            }
        }
        try {
            int imm = parseLiteral(token);
            return new int[]{TYPE_IMM, -1, imm};
        } catch (Exception e) {
            return new int[]{TYPE_DYNAMIC, -1, 0};
        }
    }

    private int parseLiteral(String s) {
        s = s.trim();
        if (labels.containsKey(s.toLowerCase(Locale.ROOT))) {
            return labels.get(s.toLowerCase(Locale.ROOT));
        }
        if (s.startsWith("0x") || s.startsWith("0X")) {
            return (int) Long.parseLong(s.substring(2), 16);
        }
        if (s.startsWith("-0x") || s.startsWith("-0X")) {
            return (int) -Long.parseLong(s.substring(3), 16);
        }
        if (s.startsWith("$")) {
            return (int) Long.parseLong(s.substring(1), 16);
        }
        if (s.startsWith("-$")) {
            return (int) -Long.parseLong(s.substring(2), 16);
        }
        return Integer.parseInt(s);
    }

    public void compileBytecode() {
        compiledProgram = new CompiledInstruction[program.size()];
        for (int i = 0; i < program.size(); i++) {
            compiledProgram[i] = compileLine(i, program.get(i));
        }
    }

    private static int findLabelColon(String line) {
        int colon = line.indexOf(':');
        if (colon == -1) return -1;
        int space = line.indexOf(' ');
        if (space != -1 && colon > space) return -1;
        int q1 = line.indexOf('\"');
        if (q1 != -1 && colon > q1) return -1;
        int q2 = line.indexOf('\'');
        if (q2 != -1 && colon > q2) return -1;
        return colon;
    }

    private CompiledInstruction compileLine(int lineNum, String rawLine) {
        int commentIndex = rawLine.indexOf(';');
        if (commentIndex == -1) commentIndex = rawLine.indexOf("//");
        if (commentIndex == -1) commentIndex = rawLine.indexOf('#');
        String line = (commentIndex != -1 ? rawLine.substring(0, commentIndex) : rawLine).trim();
        if (line.isEmpty() || line.endsWith(":")) {
            return new CompiledInstruction(OP_NOP, true, rawLine, "", TYPE_NONE, -1, 0, TYPE_NONE, -1, 0, -1);
        }
        int colon = findLabelColon(line);
        String rest = line;
        if (colon != -1) {
            rest = line.substring(colon + 1).trim();
            if (rest.isEmpty()) {
                return new CompiledInstruction(OP_NOP, true, rawLine, "", TYPE_NONE, -1, 0, TYPE_NONE, -1, 0, -1);
            }
        }
        int spaceIndex = rest.indexOf(' ');
        String opcode = (spaceIndex != -1 ? rest.substring(0, spaceIndex) : rest).toUpperCase(Locale.ROOT).trim();
        String args = spaceIndex != -1 ? rest.substring(spaceIndex + 1).trim() : "";

        if (DIRECTIVES.contains(opcode)) {
            return new CompiledInstruction(OP_DIRECTIVE, true, rawLine, args, TYPE_NONE, -1, 0, TYPE_NONE, -1, 0, -1);
        }

        int opId = OPCODE_MAP.getOrDefault(opcode, OP_UNKNOWN);
        boolean supported = supportedInstructions.contains(opcode);

        int destType = TYPE_NONE, destReg = -1, destImm = 0;
        int srcType = TYPE_NONE, srcReg = -1, srcImm = 0;
        int targetPc = -1;

        if (opId >= OP_JMP && opId <= OP_CALL && !args.isEmpty()) {
            try {
                targetPc = parseLiteral(args);
            } catch (Exception ignored) {}
        } else if (!args.isEmpty()) {
            String[] parts = splitArgs(args, 2);
            if (parts.length >= 1) {
                int[] d = parseOperand(parts[0]);
                destType = d[0]; destReg = d[1]; destImm = d[2];
            }
            if (parts.length >= 2) {
                int[] s = parseOperand(parts[1]);
                srcType = s[0]; srcReg = s[1]; srcImm = s[2];
            }
        }

        return new CompiledInstruction(opId, supported, rawLine, args, destType, destReg, destImm, srcType, srcReg, srcImm, targetPc);
    }

    public VirtualCpu() {
        Arrays.fill(framebuffer, 0xFF051009);
    }

    public void configure(Set<String> instructions, int ramSize, List<String> code, boolean hasNvme, int storageSize) {
        this.supportedInstructions.clear();
        for (String instr : instructions) {
            this.supportedInstructions.add(instr.toUpperCase(Locale.ROOT).trim());
        }
        this.hasNvme = hasNvme;
        this.storageSize = storageSize;
        long targetStorageBytes = (long) Math.max(0, storageSize) * 1024L;
        this.driveCapacities[0] = (int) Math.min(Integer.MAX_VALUE, targetStorageBytes);
        int effectiveRam = Math.max(0, Math.min(ramSize, 64 * 1024 * 1024));
        this.ram = new int[effectiveRam];
        this.program.clear();
        this.program.addAll(code);
        this.labels.clear();
        this.ivt.clear();
        preprocessLabels();
        compileBytecode();
        reset();
    }

    public void updateHardware(int totalRam, boolean hasNvme, int storageSize) {
        this.hasNvme = hasNvme;
        this.storageSize = storageSize;
        long targetStorageBytes = (long) Math.max(0, storageSize) * 1024L;
        if (this.driveCapacities[0] == 0 && targetStorageBytes > 0) {
            this.driveCapacities[0] = (int) Math.min(Integer.MAX_VALUE, targetStorageBytes);
        }
        int effectiveRam = Math.max(0, Math.min(totalRam, 64 * 1024 * 1024));
        if (this.ram.length != effectiveRam) {
            int[] newRam = new int[effectiveRam];
            System.arraycopy(this.ram, 0, newRam, 0, Math.min(this.ram.length, effectiveRam));
            this.ram = newRam;
        }
    }

    private void preprocessLabels() {
        labels.clear();
        dataLabels.clear();
        for (int i = 0; i < program.size(); i++) {
            String raw = program.get(i);
            int commentIndex = raw.indexOf(';');
            if (commentIndex == -1) commentIndex = raw.indexOf("//");
            if (commentIndex == -1) commentIndex = raw.indexOf('#');
            String line = (commentIndex != -1 ? raw.substring(0, commentIndex) : raw).trim();
            if (line.isEmpty()) continue;

            int colon = findLabelColon(line);
            if (colon != -1) {
                String labelName = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                if (!labelName.isEmpty()) {
                    labels.put(labelName, i);
                }
            }
        }
        loadStaticData();
    }

    private void loadStaticData() {
        int currentDataAddr = 0;
        String pendingLabel = null;

        for (int lineIdx = 0; lineIdx < program.size(); lineIdx++) {
            String rawLine = program.get(lineIdx);
            int commentIndex = rawLine.indexOf(';');
            if (commentIndex == -1) commentIndex = rawLine.indexOf("//");
            if (commentIndex == -1) commentIndex = rawLine.indexOf('#');
            String line = (commentIndex != -1 ? rawLine.substring(0, commentIndex) : rawLine).trim();
            if (line.isEmpty()) continue;

            int colon = findLabelColon(line);
            String rest = line;
            if (colon != -1) {
                pendingLabel = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                rest = line.substring(colon + 1).trim();
                if (rest.isEmpty()) continue;
            }

            int spaceIndex = rest.indexOf(' ');
            String opcode = (spaceIndex != -1 ? rest.substring(0, spaceIndex) : rest).toUpperCase(Locale.ROOT).trim();
            String args = spaceIndex != -1 ? rest.substring(spaceIndex + 1).trim() : "";

            if (opcode.equals("ORG")) {
                currentDataAddr = resolveValue(args);
                if (pendingLabel != null) {
                    labels.put(pendingLabel, currentDataAddr);
                    dataLabels.put(pendingLabel, currentDataAddr);
                    pendingLabel = null;
                }
            } else if (opcode.equals("DB") || opcode.equals("DW") || opcode.equals("DL")) {
                if (pendingLabel != null) {
                    labels.put(pendingLabel, currentDataAddr);
                    dataLabels.put(pendingLabel, currentDataAddr);
                    pendingLabel = null;
                }
                currentDataAddr = parseAndStoreData(opcode, args, currentDataAddr);
            } else if (opcode.equals("INCBIN")) {
                if (pendingLabel != null) {
                    labels.put(pendingLabel, currentDataAddr);
                    dataLabels.put(pendingLabel, currentDataAddr);
                    pendingLabel = null;
                }
                currentDataAddr = loadIncbin(args, currentDataAddr);
            } else {
                pendingLabel = null;
            }
        }
    }

    private void ensureRamCapacity(int targetAddr) {
        if (targetAddr >= ram.length && targetAddr < 65536) {
            int newCap = Math.min(65536, Math.max(targetAddr + 1024, ram.length * 2));
            int[] newRam = new int[newCap];
            System.arraycopy(ram, 0, newRam, 0, ram.length);
            ram = newRam;
        }
    }

    private List<String> splitDataTokens(String s) {
        List<String> tokens = new ArrayList<>();
        if (s == null || s.trim().isEmpty()) return tokens;
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '\"' || c == '\'') && (i == 0 || s.charAt(i - 1) != '\\')) {
                if (inQuotes && c == quoteChar) {
                    inQuotes = false;
                } else if (!inQuotes) {
                    inQuotes = true;
                    quoteChar = c;
                }
            }
            if (!inQuotes && c == ',') {
                tokens.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0) {
            tokens.add(sb.toString().trim());
        }
        return tokens;
    }

    private int parseAndStoreData(String opcode, String args, int currentAddr) {
        List<String> tokens = splitDataTokens(args);
        for (String token : tokens) {
            if (token.isEmpty()) continue;

            if (opcode.equals("DB") && token.startsWith("\"") && token.endsWith("\"") && token.length() >= 2) {
                String str = stripQuotes(token);
                byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    ensureRamCapacity(currentAddr);
                    if (currentAddr < ram.length) {
                        ram[currentAddr++] = (b & 0xFF);
                    }
                }
                continue;
            }

            int val = resolveValue(token);

            if (opcode.equals("DB")) {
                ensureRamCapacity(currentAddr);
                if (currentAddr < ram.length) {
                    ram[currentAddr++] = (val & 0xFF);
                }
            } else if (opcode.equals("DW")) {
                ensureRamCapacity(currentAddr + 1);
                if (currentAddr < ram.length) ram[currentAddr++] = (val & 0xFF);
                if (currentAddr < ram.length) ram[currentAddr++] = ((val >> 8) & 0xFF);
            } else if (opcode.equals("DL")) {
                ensureRamCapacity(currentAddr + 2);
                if (currentAddr < ram.length) ram[currentAddr++] = (val & 0xFF);
                if (currentAddr < ram.length) ram[currentAddr++] = ((val >> 8) & 0xFF);
                if (currentAddr < ram.length) ram[currentAddr++] = ((val >> 16) & 0xFF);
            }
        }
        return currentAddr;
    }

    private int loadIncbin(String args, int currentAddr) {
        List<String> tokens = splitDataTokens(args);
        if (tokens.isEmpty()) return currentAddr;
        String pathToken = tokens.get(0);
        if (pathToken.startsWith("\"") && pathToken.endsWith("\"") && pathToken.length() >= 2) {
            pathToken = pathToken.substring(1, pathToken.length() - 1);
        }
        int offset = 0;
        int length = -1;
        if (tokens.size() > 1) {
            offset = resolveValue(tokens.get(1));
        }
        if (tokens.size() > 2) {
            length = resolveValue(tokens.get(2));
        }

        try {
            java.nio.file.Path p = java.nio.file.Path.of(pathToken);
            if (!java.nio.file.Files.exists(p)) {
                p = java.nio.file.Path.of("").toAbsolutePath().resolve(pathToken);
            }
            if (java.nio.file.Files.exists(p)) {
                byte[] allBytes = java.nio.file.Files.readAllBytes(p);
                int start = Math.max(0, Math.min(offset, allBytes.length));
                int count = (length >= 0) ? Math.min(length, allBytes.length - start) : (allBytes.length - start);
                for (int i = 0; i < count; i++) {
                    ensureRamCapacity(currentAddr);
                    if (currentAddr < ram.length) {
                        ram[currentAddr++] = (allBytes[start + i] & 0xFF);
                    }
                }
            }
        } catch (Exception e) {
            terminalOutput.add("[ASSEMBLER ERROR] INCBIN failed: " + e.getMessage());
        }
        return currentAddr;
    }

    public void setTimeSupplier(LongSupplier supplier) {
        this.timeSupplier = supplier != null ? supplier : System::currentTimeMillis;
    }

    public int getSleepTicksRemaining() {
        return sleepTicksRemaining;
    }

    public void setSleepTicksRemaining(int ticks) {
        this.sleepTicksRemaining = ticks;
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
        this.callStack.clear();
        this.dataStack.clear();
        this.sleepTicksRemaining = 0;
        this.timerHz = 0;
        this.timerIntervalCycles = 0;
        Arrays.fill(this.padStates, 0);
        loadStaticData();
        this.displayMode = 0;
        Arrays.fill(this.framebuffer, 0xFF051009);
        this.fbDirty = true;
        this.audioHead = 0;
        this.audioTail = 0;
        this.audioCount = 0;
        this.audioTriggered = false;
        this.terminalOutput.clear();
        this.lineBuffer.setLength(0);
        this.utf8ByteBuf.reset();
        this.selectedDrive = 0;
        for (int i = 0; i < MAX_DRIVES; i++) {
            if (getDriveCapacity(i) > 0 || (storageDrives[i] != null && storageDrives[i].length > 0)) {
                this.selectedDrive = i;
                break;
            }
        }
        this.interruptQueue.clear();
        this.interruptStack.clear();
        this.interruptsEnabled = true;
        this.inInterruptHandler = false;
        if (state != State.OFF) {
            this.state = State.RUNNING;
        }
        queueResetInterrupt();
    }

    public Object getExecutionLock() {
        return executionLock;
    }

    public void powerOn() {
        synchronized (executionLock) {
            reset();
            this.state = State.RUNNING;
        }
    }

    public void powerOff() {
        synchronized (executionLock) {
            flushLineBuffer();
            this.state = State.OFF;
            this.terminalOutput.add("[POWER OFF]");
            clearAudioQueue();
        }
    }

    public void clearAudioQueue() {
        synchronized (audioQueue) {
            this.audioHead = 0;
            this.audioTail = 0;
            this.audioCount = 0;
            this.audioTriggered = false;
        }
    }

    public void togglePower() {
        synchronized (executionLock) {
            if (state == State.OFF) {
                powerOn();
            } else {
                powerOff();
            }
        }
    }

    public void step() {
        synchronized (executionLock) {
            if (state != State.RUNNING && state != State.PAUSED) {
                return;
            }
            executeNextInstruction();
        }
    }

    public void tick(int maxCycles) {
        synchronized (executionLock) {
            if (state != State.RUNNING) {
                return;
            }
            checkTimerTrigger();
            if (sleepTicksRemaining > 0) {
                checkAndDispatchInterrupt();
                if (sleepTicksRemaining > 0) {
                    sleepTicksRemaining--;
                    return;
                }
            }
            int count = 0;
            long startNanos = System.nanoTime();
            while (state == State.RUNNING && count < maxCycles) {
                executeNextInstruction();
                count++;
                if ((count & 63) == 0) {
                    checkTimerTrigger();
                    if (System.nanoTime() - startNanos > 10_000_000L) { // 10ms safety threshold
                        break;
                    }
                }
                if (sleepTicksRemaining > 0) {
                    break;
                }
            }
        }
    }

    private void checkTimerTrigger() {
        if (timerHz <= 0) return;
        long now = System.nanoTime();
        long intervalNanos = 1_000_000_000L / timerHz;
        if (now - lastTimerNanos >= intervalNanos || (timerIntervalCycles > 0 && cycles - lastTimerCycle >= timerIntervalCycles)) {
            lastTimerNanos = now;
            lastTimerCycle = cycles;
            queueTimerInterrupt();
        }
    }

    private void checkAndDispatchInterrupt() {
        if (!interruptsEnabled || inInterruptHandler || interruptQueue.isEmpty()) {
            return;
        }
        InterruptEvent event = null;
        synchronized (interruptQueue) {
            if (!interruptQueue.isEmpty()) {
                event = interruptQueue.poll();
            }
        }
        if (event != null && ivt.containsKey(event.vector)) {
            int handlerPc = ivt.get(event.vector);
            interruptStack.push(new CpuContext(pc, regA, regB, regC, regD, flagZ, flagS, true));
            inInterruptHandler = true;
            regA = event.regA;
            regB = event.regB;
            regC = event.regC;
            pc = handlerPc;
            sleepTicksRemaining = 0;
        }
    }

    private void executeNextInstruction() {
        checkAndDispatchInterrupt();

        if (pc < 0 || pc >= program.size()) {
            state = State.HALTED;
            flushLineBuffer();
            terminalOutput.add("[PROGRAM TERMINATED: END OF FILE]");
            return;
        }

        if (compiledProgram == null || compiledProgram.length != program.size()) {
            compileBytecode();
        }

        int currentLineNum = pc;
        CompiledInstruction ci = compiledProgram[pc++];
        cycles++;

        if (ci.opCode == OP_NOP || ci.opCode == OP_DIRECTIVE) {
            return;
        }

        if (!ci.isSupported) {
            triggerException(VEC_ILLEGAL_OPCODE, "Illegal instruction: " + ci.rawLine, currentLineNum, ci.rawLine);
            return;
        }

        try {
            switch (ci.opCode) {
                case OP_MOV -> {
                    if (ci.destType == TYPE_REG) {
                        if (ci.srcType == TYPE_REG) {
                            setReg(ci.destReg, getReg(ci.srcReg));
                        } else if (ci.srcType == TYPE_IMM) {
                            setReg(ci.destReg, ci.srcImm);
                        } else if (ci.srcType == TYPE_RAM_IMM) {
                            int addr = ci.srcImm;
                            if (addr >= 0 && addr < ram.length) {
                                setReg(ci.destReg, ram[addr]);
                            } else {
                                throw new IndexOutOfBoundsException("RAM address " + addr + " out of bounds");
                            }
                        } else if (ci.srcType == TYPE_RAM_REG) {
                            int addr = getReg(ci.srcReg);
                            if (addr >= 0 && addr < ram.length) {
                                setReg(ci.destReg, ram[addr]);
                            } else {
                                throw new IndexOutOfBoundsException("RAM address " + addr + " out of bounds");
                            }
                        } else {
                            execMov(ci.rawArgs);
                        }
                    } else if (ci.destType == TYPE_RAM_IMM) {
                        int addr = ci.destImm;
                        if (addr < 0 || addr >= ram.length) {
                            throw new IndexOutOfBoundsException("RAM address " + addr + " out of bounds");
                        }
                        if (ci.srcType == TYPE_REG) {
                            ram[addr] = getReg(ci.srcReg);
                        } else if (ci.srcType == TYPE_IMM) {
                            ram[addr] = ci.srcImm;
                        } else {
                            execMov(ci.rawArgs);
                        }
                    } else if (ci.destType == TYPE_RAM_REG) {
                        int addr = getReg(ci.destReg);
                        if (addr < 0 || addr >= ram.length) {
                            throw new IndexOutOfBoundsException("RAM address " + addr + " out of bounds");
                        }
                        if (ci.srcType == TYPE_REG) {
                            ram[addr] = getReg(ci.srcReg);
                        } else if (ci.srcType == TYPE_IMM) {
                            ram[addr] = ci.srcImm;
                        } else {
                            execMov(ci.rawArgs);
                        }
                    } else {
                        execMov(ci.rawArgs);
                    }
                }
                case OP_ADD -> {
                    if (ci.destType == TYPE_REG) {
                        int val;
                        if (ci.srcType == TYPE_REG) val = getReg(ci.srcReg);
                        else if (ci.srcType == TYPE_IMM) val = ci.srcImm;
                        else if (ci.srcType == TYPE_RAM_IMM && ci.srcImm >= 0 && ci.srcImm < ram.length) val = ram[ci.srcImm];
                        else if (ci.srcType == TYPE_RAM_REG) {
                            int a = getReg(ci.srcReg);
                            if (a >= 0 && a < ram.length) val = ram[a];
                            else throw new IndexOutOfBoundsException("RAM address " + a + " out of bounds");
                        } else {
                            execAdd(ci.rawArgs);
                            break;
                        }
                        int res = getReg(ci.destReg) + val;
                        setReg(ci.destReg, res);
                        updateFlags(res);
                    } else {
                        execAdd(ci.rawArgs);
                    }
                }
                case OP_SUB -> {
                    if (ci.destType == TYPE_REG) {
                        int val;
                        if (ci.srcType == TYPE_REG) val = getReg(ci.srcReg);
                        else if (ci.srcType == TYPE_IMM) val = ci.srcImm;
                        else if (ci.srcType == TYPE_RAM_IMM && ci.srcImm >= 0 && ci.srcImm < ram.length) val = ram[ci.srcImm];
                        else if (ci.srcType == TYPE_RAM_REG) {
                            int a = getReg(ci.srcReg);
                            if (a >= 0 && a < ram.length) val = ram[a];
                            else throw new IndexOutOfBoundsException("RAM address " + a + " out of bounds");
                        } else {
                            execSub(ci.rawArgs);
                            break;
                        }
                        int res = getReg(ci.destReg) - val;
                        setReg(ci.destReg, res);
                        updateFlags(res);
                    } else {
                        execSub(ci.rawArgs);
                    }
                }
                case OP_INC -> {
                    if (ci.destType == TYPE_REG) {
                        int res = getReg(ci.destReg) + 1;
                        setReg(ci.destReg, res);
                        updateFlags(res);
                    } else {
                        execInc(ci.rawArgs);
                    }
                }
                case OP_DEC -> {
                    if (ci.destType == TYPE_REG) {
                        int res = getReg(ci.destReg) - 1;
                        setReg(ci.destReg, res);
                        updateFlags(res);
                    } else {
                        execDec(ci.rawArgs);
                    }
                }
                case OP_CMP -> {
                    if (ci.destType == TYPE_REG) {
                        int val;
                        if (ci.srcType == TYPE_REG) val = getReg(ci.srcReg);
                        else if (ci.srcType == TYPE_IMM) val = ci.srcImm;
                        else if (ci.srcType == TYPE_RAM_IMM && ci.srcImm >= 0 && ci.srcImm < ram.length) val = ram[ci.srcImm];
                        else if (ci.srcType == TYPE_RAM_REG) {
                            int a = getReg(ci.srcReg);
                            if (a >= 0 && a < ram.length) val = ram[a];
                            else throw new IndexOutOfBoundsException("RAM address " + a + " out of bounds");
                        } else {
                            execCmp(ci.rawArgs);
                            break;
                        }
                        int res = getReg(ci.destReg) - val;
                        updateFlags(res);
                    } else {
                        execCmp(ci.rawArgs);
                    }
                }
                case OP_JMP -> {
                    if (ci.targetPc != -1) pc = Math.max(0, Math.min(ci.targetPc, program.size()));
                    else execJmp(ci.rawArgs, true);
                }
                case OP_JZ -> {
                    if (flagZ) {
                        if (ci.targetPc != -1) pc = Math.max(0, Math.min(ci.targetPc, program.size()));
                        else execJmp(ci.rawArgs, true);
                    }
                }
                case OP_JNZ -> {
                    if (!flagZ) {
                        if (ci.targetPc != -1) pc = Math.max(0, Math.min(ci.targetPc, program.size()));
                        else execJmp(ci.rawArgs, true);
                    }
                }
                case OP_JS -> {
                    if (flagS) {
                        if (ci.targetPc != -1) pc = Math.max(0, Math.min(ci.targetPc, program.size()));
                        else execJmp(ci.rawArgs, true);
                    }
                }
                case OP_JNS -> {
                    if (!flagS) {
                        if (ci.targetPc != -1) pc = Math.max(0, Math.min(ci.targetPc, program.size()));
                        else execJmp(ci.rawArgs, true);
                    }
                }
                case OP_JL -> {
                    if (flagS && !flagZ) {
                        if (ci.targetPc != -1) pc = Math.max(0, Math.min(ci.targetPc, program.size()));
                        else execJmp(ci.rawArgs, true);
                    }
                }
                case OP_JLE -> {
                    if (flagS || flagZ) {
                        if (ci.targetPc != -1) pc = Math.max(0, Math.min(ci.targetPc, program.size()));
                        else execJmp(ci.rawArgs, true);
                    }
                }
                case OP_JG -> {
                    if (!flagS && !flagZ) {
                        if (ci.targetPc != -1) pc = Math.max(0, Math.min(ci.targetPc, program.size()));
                        else execJmp(ci.rawArgs, true);
                    }
                }
                case OP_JGE -> {
                    if (!flagS) {
                        if (ci.targetPc != -1) pc = Math.max(0, Math.min(ci.targetPc, program.size()));
                        else execJmp(ci.rawArgs, true);
                    }
                }
                case OP_CALL -> {
                    if (ci.targetPc != -1) {
                        callStack.push(pc);
                        pc = Math.max(0, Math.min(ci.targetPc, program.size()));
                    } else {
                        execCall(ci.rawArgs);
                    }
                }
                case OP_RET -> execRet();
                case OP_PUSH -> {
                    if (ci.destType == TYPE_REG) {
                        dataStack.push(getReg(ci.destReg));
                    } else if (ci.destType == TYPE_IMM) {
                        dataStack.push(ci.destImm);
                    } else {
                        execPush(ci.rawArgs);
                    }
                }
                case OP_POP -> {
                    if (ci.destType == TYPE_REG) {
                        if (dataStack.isEmpty()) throw new IllegalStateException("Stack underflow");
                        setReg(ci.destReg, dataStack.pop());
                    } else {
                        execPop(ci.rawArgs);
                    }
                }
                case OP_MUL -> execMul(ci.rawArgs);
                case OP_DIV -> execDiv(ci.rawArgs);
                case OP_MOD -> execMod(ci.rawArgs);
                case OP_AND -> execAnd(ci.rawArgs);
                case OP_OR -> execOr(ci.rawArgs);
                case OP_XOR -> execXor(ci.rawArgs);
                case OP_NOT -> execNot(ci.rawArgs);
                case OP_SHL -> execShl(ci.rawArgs);
                case OP_SHR -> execShr(ci.rawArgs);
                case OP_IVEC -> execIvec(ci.rawArgs);
                case OP_IRET -> execIret();
                case OP_CLI -> interruptsEnabled = false;
                case OP_STI -> interruptsEnabled = true;
                case OP_INT -> execInt(ci.rawArgs);
                case OP_OUT -> execOut(ci.rawArgs);
                case OP_OUTH -> execOuth(ci.rawArgs);
                case OP_OUTC -> execOutc(ci.rawArgs);
                case OP_CLS -> execCls();
                case OP_SLEEP -> execSleep(ci.rawArgs);
                case OP_TIME -> execTime(ci.rawArgs);
                case OP_RAND -> execRand(ci.rawArgs);
                case OP_INPAD -> execInpad(ci.rawArgs);
                case OP_TIMER -> execTimer(ci.rawArgs);
                case OP_VBLANK -> execVblank(ci.rawArgs);
                case OP_SSIZE -> execSSize(ci.rawArgs);
                case OP_SREAD -> execSRead(ci.rawArgs);
                case OP_SWRITE -> execSWrite(ci.rawArgs);
                case OP_SBLKR -> execSBlkr(ci.rawArgs);
                case OP_SBLKW -> execSBlkw(ci.rawArgs);
                case OP_SCOUNT -> execSCount(ci.rawArgs);
                case OP_SSEL -> execSSel(ci.rawArgs);
                case OP_SDRV -> execSDrv(ci.rawArgs);
                case OP_SEXEC -> execSExec(ci.rawArgs);
                case OP_FBMODE -> execFbMode(ci.rawArgs);
                case OP_FBSET -> execFbSet(ci.rawArgs);
                case OP_FBGET -> execFbGet(ci.rawArgs);
                case OP_FBCLR -> execFbClr(ci.rawArgs);
                case OP_FBRECT -> execFbRect(ci.rawArgs);
                case OP_FBLINE -> execFbLine(ci.rawArgs);
                case OP_FBLIT -> execFbLit(ci.rawArgs);
                case OP_FBSWAP -> {
                    displayMode = 1;
                    fbDirty = true;
                }
                case OP_FBWIDTH -> setDestination(ci.rawArgs.trim(), FB_WIDTH);
                case OP_FBHEIGHT -> setDestination(ci.rawArgs.trim(), FB_HEIGHT);
                case OP_APUSH -> execAPush(ci.rawArgs);
                case OP_ABUFW -> execABufw(ci.rawArgs);
                case OP_ACLR -> execAClr();
                case OP_ASTAT -> setDestination(ci.rawArgs.trim(), audioCount);
                case OP_ATONE -> execATone(ci.rawArgs);
                case OP_APLAY -> audioTriggered = true;
                case OP_HLT -> {
                    state = State.HALTED;
                    flushLineBuffer();
                    terminalOutput.add("[EXECUTION HALTED]");
                }
                default -> triggerException(VEC_ILLEGAL_OPCODE, "Unknown opcode: " + ci.rawLine, currentLineNum, ci.rawLine);
            }
        } catch (Exception e) {
            int vec = VEC_GENERAL_EXCEPTION;
            String desc = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (e instanceof ArithmeticException) {
                vec = VEC_DIV_ZERO;
            } else if (e instanceof IndexOutOfBoundsException) {
                vec = VEC_OUT_OF_BOUNDS;
            } else if (e instanceof IllegalStateException && desc.toLowerCase(Locale.ROOT).contains("stack")) {
                vec = VEC_STACK_FAULT;
            }
            triggerException(vec, desc, currentLineNum, ci.rawLine);
        }

        if (terminalOutput.size() > 60) {
            terminalOutput.remove(0);
        }
    }

    private void execMov(String args) {
        String[] parts = splitArgs(args, 2);
        if (parts.length != 2) throw new IllegalArgumentException("MOV requires dest, src");
        String dest = parts[0].trim();
        String src = parts[1].trim();
        int val = resolveValue(src);
        setDestination(dest, val);
    }

    private void execAdd(String args) {
        String[] parts = splitArgs(args, 2);
        if (parts.length != 2) throw new IllegalArgumentException("ADD requires dest, src");
        String dest = parts[0].trim();
        int current = resolveValue(dest);
        int addend = resolveValue(parts[1].trim());
        int result = current + addend;
        setDestination(dest, result);
        updateFlags(result);
    }

    private void execSub(String args) {
        String[] parts = splitArgs(args, 2);
        if (parts.length != 2) throw new IllegalArgumentException("SUB requires dest, src");
        String dest = parts[0].trim();
        int current = resolveValue(dest);
        int subtrahend = resolveValue(parts[1].trim());
        int result = current - subtrahend;
        setDestination(dest, result);
        updateFlags(result);
    }

    private void execMul(String args) {
        String[] parts = splitArgs(args, 2);
        if (parts.length != 2) throw new IllegalArgumentException("MUL requires dest, src");
        String dest = parts[0].trim();
        int current = resolveValue(dest);
        int factor = resolveValue(parts[1].trim());
        int result = current * factor;
        setDestination(dest, result);
        updateFlags(result);
    }

    private void execDiv(String args) {
        String[] parts = splitArgs(args, 2);
        if (parts.length != 2) throw new IllegalArgumentException("DIV requires dest, src");
        String dest = parts[0].trim();
        int current = resolveValue(dest);
        int divisor = resolveValue(parts[1].trim());
        if (divisor == 0) throw new ArithmeticException("Division by zero");
        int result = current / divisor;
        setDestination(dest, result);
        updateFlags(result);
    }

    private void execMod(String args) {
        String[] parts = splitArgs(args, 2);
        if (parts.length != 2) throw new IllegalArgumentException("MOD requires dest, src");
        String dest = parts[0].trim();
        int current = resolveValue(dest);
        int divisor = resolveValue(parts[1].trim());
        if (divisor == 0) throw new ArithmeticException("Modulo by zero");
        int result = current % divisor;
        setDestination(dest, result);
        updateFlags(result);
    }

    private void execAnd(String args) {
        String[] parts = splitArgs(args, 2);
        if (parts.length != 2) throw new IllegalArgumentException("AND requires dest, src");
        String dest = parts[0].trim();
        int current = resolveValue(dest);
        int val = resolveValue(parts[1].trim());
        int result = current & val;
        setDestination(dest, result);
        updateFlags(result);
    }

    private void execOr(String args) {
        String[] parts = splitArgs(args, 2);
        if (parts.length != 2) throw new IllegalArgumentException("OR requires dest, src");
        String dest = parts[0].trim();
        int current = resolveValue(dest);
        int val = resolveValue(parts[1].trim());
        int result = current | val;
        setDestination(dest, result);
        updateFlags(result);
    }

    private void execXor(String args) {
        String[] parts = splitArgs(args, 2);
        if (parts.length != 2) throw new IllegalArgumentException("XOR requires dest, src");
        String dest = parts[0].trim();
        int current = resolveValue(dest);
        int val = resolveValue(parts[1].trim());
        int result = current ^ val;
        setDestination(dest, result);
        updateFlags(result);
    }

    private void execNot(String arg) {
        String dest = arg.trim();
        int current = resolveValue(dest);
        int result = ~current;
        setDestination(dest, result);
        updateFlags(result);
    }

    private void execShl(String args) {
        String[] parts = splitArgs(args, 2);
        if (parts.length != 2) throw new IllegalArgumentException("SHL requires dest, count");
        String dest = parts[0].trim();
        int current = resolveValue(dest);
        int count = resolveValue(parts[1].trim());
        int result = current << count;
        setDestination(dest, result);
        updateFlags(result);
    }

    private void execShr(String args) {
        String[] parts = splitArgs(args, 2);
        if (parts.length != 2) throw new IllegalArgumentException("SHR requires dest, count");
        String dest = parts[0].trim();
        int current = resolveValue(dest);
        int count = resolveValue(parts[1].trim());
        int result = current >> count;
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
        String[] parts = splitArgs(args, 2);
        if (parts.length != 2) throw new IllegalArgumentException("CMP requires reg, val");
        int left = resolveValue(parts[0].trim());
        int right = resolveValue(parts[1].trim());
        int diff = left - right;
        updateFlags(diff);
    }

    private void execJmp(String target, boolean condition) {
        if (!condition) return;
        int dest = resolveTarget(target);
        pc = Math.max(0, Math.min(dest, program.size()));
    }

    private void execCall(String target) {
        int destPc = resolveTarget(target);
        callStack.push(pc);
        pc = Math.max(0, Math.min(destPc, program.size()));
    }

    private void execRet() {
        if (callStack.isEmpty()) {
            throw new IllegalStateException("RET without CALL: Call stack underflow");
        }
        pc = callStack.pop();
    }

    private void execPush(String arg) {
        int val = resolveValue(arg.trim());
        dataStack.push(val);
    }

    private void execPop(String dest) {
        if (dataStack.isEmpty()) {
            throw new IllegalStateException("POP on empty stack: Data stack underflow");
        }
        int val = dataStack.pop();
        setDestination(dest.trim(), val);
    }

    private int resolveTarget(String target) {
        target = target.trim();
        String lower = target.toLowerCase(Locale.ROOT);
        if (labels.containsKey(lower)) {
            return labels.get(lower);
        }
        try {
            return resolveValue(target);
        } catch (Exception e) {
            throw new IllegalArgumentException("Label or jump target not found: " + target);
        }
    }

    private void execIvec(String args) {
        String[] parts = args.split(",", 2);
        if (parts.length != 2) throw new IllegalArgumentException("IVEC requires vector, handler");
        int vec = parseVector(parts[0].trim());
        int targetPc = resolveTarget(parts[1].trim());
        ivt.put(vec, targetPc);
    }

    private void execIret() {
        if (interruptStack.isEmpty()) {
            throw new IllegalStateException("IRET with empty interrupt stack: Stack underflow");
        }
        CpuContext ctx = interruptStack.pop();
        this.pc = ctx.pc();
        if (ctx.restoreRegs()) {
            this.regA = ctx.regA();
            this.regB = ctx.regB();
            this.regC = ctx.regC();
            this.regD = ctx.regD();
        }
        this.flagZ = ctx.flagZ();
        this.flagS = ctx.flagS();
        this.inInterruptHandler = !interruptStack.isEmpty();
    }

    private void execInt(String args) {
        int vec = parseVector(args.trim());
        if (ivt.containsKey(vec)) {
            interruptStack.push(new CpuContext(pc, regA, regB, regC, regD, flagZ, flagS, false));
            inInterruptHandler = true;
            regA = vec;
            pc = ivt.get(vec);
        } else {
            triggerException(vec, "Software interrupt " + vec, pc - 1, "INT " + args);
        }
    }

    public void triggerException(int vector, String description, int lineNum, String lineCode) {
        if (ivt.containsKey(vector)) {
            int resumePc = lineNum >= 0 ? lineNum + 1 : pc;
            interruptStack.push(new CpuContext(resumePc, regA, regB, regC, regD, flagZ, flagS, false));
            inInterruptHandler = true;
            regA = vector;
            pc = ivt.get(vector);
            return;
        }

        state = State.ERROR;
        errorMessage = "Exception not handled: " + description + (lineNum >= 0 ? " at line " + lineNum + ": '" + lineCode + "'" : "");
        flushLineBuffer();
        terminalOutput.add("[CPU EXCEPTION] " + errorMessage);
    }

    private int parseVector(String token) {
        token = token.trim();
        String u = token.toUpperCase(Locale.ROOT);
        return switch (u) {
            case "DIV_ZERO", "DIV0", "ZERO" -> VEC_DIV_ZERO;
            case "ILLEGAL_OPCODE", "OPCODE", "ILL" -> VEC_ILLEGAL_OPCODE;
            case "OUT_OF_BOUNDS", "BOUNDS", "MEM_FAULT" -> VEC_OUT_OF_BOUNDS;
            case "STACK_FAULT", "STACK" -> VEC_STACK_FAULT;
            case "GENERAL_EXCEPTION", "EXCEPTION", "ERROR" -> VEC_GENERAL_EXCEPTION;
            case "KEYBOARD", "KEY", "CHAR" -> VEC_KEYBOARD;
            case "MOUSE_MOVE", "MOUSE_POS" -> VEC_MOUSE_MOVE;
            case "MOUSE_CLICK", "CLICK" -> VEC_MOUSE_CLICK;
            case "TIMER", "VBLANK", "VBL", "NMI", "FRAME" -> VEC_TIMER;
            case "RESET", "RST" -> VEC_RESET;
            default -> resolveValue(token);
        };
    }

    public void queueResetInterrupt() {
        synchronized (interruptQueue) {
            for (InterruptEvent e : interruptQueue) {
                if (e.vector == VEC_RESET) {
                    return;
                }
            }
            if (interruptQueue.size() < 64) {
                interruptQueue.addFirst(new InterruptEvent(VEC_RESET, 0, 0, 0));
            }
        }
    }

    public void queueTimerInterrupt() {
        synchronized (interruptQueue) {
            for (InterruptEvent e : interruptQueue) {
                if (e.vector == VEC_TIMER) {
                    return;
                }
            }
            if (interruptQueue.size() < 64) {
                interruptQueue.add(new InterruptEvent(VEC_TIMER, 0, 0, 0));
            }
        }
    }

    public void queueKeyboardInterrupt(int keyChar) {
        synchronized (interruptQueue) {
            if (interruptQueue.size() < 64) {
                interruptQueue.add(new InterruptEvent(VEC_KEYBOARD, keyChar, 0, 0));
            }
        }
    }

    public void queueMouseMoveInterrupt(int x, int y) {
        synchronized (interruptQueue) {
            InterruptEvent last = interruptQueue.peekLast();
            if (last != null && last.vector() == VEC_MOUSE_MOVE) {
                interruptQueue.pollLast();
            }
            if (interruptQueue.size() < 64) {
                interruptQueue.add(new InterruptEvent(VEC_MOUSE_MOVE, x, y, 0));
            }
        }
    }

    public void queueMouseClickInterrupt(int x, int y, int button) {
        synchronized (interruptQueue) {
            if (interruptQueue.size() < 64) {
                interruptQueue.add(new InterruptEvent(VEC_MOUSE_CLICK, x, y, button));
            }
        }
    }

    public Map<Integer, Integer> getIvt() {
        return Collections.unmodifiableMap(ivt);
    }

    public boolean isInterruptsEnabled() {
        return interruptsEnabled;
    }

    public boolean isInInterruptHandler() {
        return inInterruptHandler;
    }

    public int getInterruptStackDepth() {
        return interruptStack.size();
    }

    public int getInterruptQueueSize() {
        return interruptQueue.size();
    }

    private void execCls() {
        terminalOutput.clear();
        lineBuffer.setLength(0);
        utf8ByteBuf.reset();
    }

    private void execSleep(String arg) {
        int ticks = resolveValue(arg.trim());
        if (ticks > 0) {
            this.sleepTicksRemaining = ticks;
        }
    }

    private void execTime(String dest) {
        long t = timeSupplier != null ? timeSupplier.getAsLong() : 0L;
        setDestination(dest.trim(), (int) (t & 0x7FFFFFFF));
    }

    private void execRand(String args) {
        String[] parts = args.split(",", 2);
        if (parts.length != 2) throw new IllegalArgumentException("RAND requires dest, max");
        String dest = parts[0].trim();
        int max = resolveValue(parts[1].trim());
        int val = (max > 0) ? random.nextInt(max) : 0;
        setDestination(dest, val);
    }

    private void execInpad(String args) {
        String[] parts = args.split(",", 2);
        String dest = parts[0].trim();
        int padId = 0;
        if (parts.length > 1) {
            padId = resolveValue(parts[1].trim());
        }
        int padMask = getPadState(padId);
        setDestination(dest, padMask);
    }

    public void setPadState(int padId, int bitmask) {
        if (padId >= 0 && padId < padStates.length) {
            padStates[padId] = bitmask;
        }
    }

    public int getPadState(int padId) {
        if (padId >= 0 && padId < padStates.length) {
            return padStates[padId];
        }
        return 0;
    }

    private void execTimer(String arg) {
        int hz = resolveValue(arg.trim());
        setTimerHz(hz);
    }

    private void execVblank(String arg) {
        String trimmed = arg.trim();
        if (trimmed.isEmpty() || trimmed.equals("1") || trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("ON")) {
            setTimerHz(60);
        } else if (trimmed.equals("0") || trimmed.equalsIgnoreCase("false") || trimmed.equalsIgnoreCase("OFF")) {
            setTimerHz(0);
        } else {
            int val = resolveValue(trimmed);
            setTimerHz(val > 0 ? 60 : 0);
        }
    }

    public void setTimerHz(int hz) {
        this.timerHz = Math.max(0, hz);
        this.lastTimerNanos = System.nanoTime();
        this.lastTimerCycle = this.cycles;
        this.timerIntervalCycles = hz > 0 ? Math.max(1, 1000 / hz) : 0;
    }

    public int getTimerHz() {
        return timerHz;
    }

    public Map<String, Integer> getDataLabels() {
        return Collections.unmodifiableMap(dataLabels);
    }

    private void execSSize(String args) {
        String[] parts = args.split(",", 2);
        int d = selectedDrive;
        String dest;
        if (parts.length == 2) {
            dest = parts[0].trim();
            d = resolveValue(parts[1].trim());
        } else {
            dest = parts[0].trim();
        }
        int sz = getDriveCapacity(d);
        setDestination(dest, sz);
        flagZ = (sz == 0);
        flagS = false;
    }

    private void execSRead(String args) {
        String[] parts = args.split(",");
        String dest;
        int d = selectedDrive;
        int addr;
        if (parts.length == 3) {
            dest = parts[0].trim();
            d = resolveValue(parts[1].trim());
            addr = resolveValue(parts[2].trim());
        } else if (parts.length == 2) {
            dest = parts[0].trim();
            addr = resolveValue(parts[1].trim());
        } else {
            throw new IllegalArgumentException("SREAD requires dest, [drive,] addr");
        }
        byte[] drv = (d >= 0 && d < MAX_DRIVES) ? storageDrives[d] : null;
        int cap = getDriveCapacity(d);
        if (addr >= 0 && addr < cap) {
            int val = (drv != null && addr < drv.length) ? (drv[addr] & 0xFF) : 0;
            setDestination(dest, val);
            flagZ = (val == 0);
            flagS = false;
        } else {
            setDestination(dest, 0);
            flagZ = true;
            flagS = true;
        }
    }

    private void execSWrite(String args) {
        String[] parts = args.split(",");
        int d = selectedDrive;
        int addr;
        int val;
        if (parts.length == 3) {
            d = resolveValue(parts[0].trim());
            addr = resolveValue(parts[1].trim());
            val = resolveValue(parts[2].trim());
        } else if (parts.length == 2) {
            addr = resolveValue(parts[0].trim());
            val = resolveValue(parts[1].trim());
        } else {
            throw new IllegalArgumentException("SWRITE requires [drive,] addr, val");
        }
        int cap = getDriveCapacity(d);
        if (d >= 0 && d < MAX_DRIVES && addr >= 0 && addr < cap) {
            byte[] drv = storageDrives[d];
            if (drv == null || addr >= drv.length) {
                int newLen = Math.max(addr + 1, Math.min(cap, drv == null ? 1024 : Math.max(drv.length * 2, addr + 1)));
                byte[] newDrv = new byte[newLen];
                if (drv != null) {
                    System.arraycopy(drv, 0, newDrv, 0, drv.length);
                }
                storageDrives[d] = newDrv;
                drv = newDrv;
            }
            drv[addr] = (byte) (val & 0xFF);
            flagZ = false;
            flagS = false;
            if (driveWriteListener != null) {
                driveWriteListener.accept(d, drv);
            }
        } else {
            flagZ = true;
            flagS = true;
        }
    }

    private void execSBlkr(String args) {
        String[] parts = args.split(",");
        int rAddr;
        int d = selectedDrive;
        int sAddr;
        int count;
        if (parts.length == 4) {
            rAddr = resolveValue(parts[0].trim());
            d = resolveValue(parts[1].trim());
            sAddr = resolveValue(parts[2].trim());
            count = resolveValue(parts[3].trim());
        } else if (parts.length == 3) {
            rAddr = resolveValue(parts[0].trim());
            sAddr = resolveValue(parts[1].trim());
            count = resolveValue(parts[2].trim());
        } else {
            throw new IllegalArgumentException("SBLKR requires ram_dest, [drive,] storage_addr, count");
        }
        int cap = getDriveCapacity(d);
        if (d < 0 || d >= MAX_DRIVES || cap <= 0 || count <= 0) {
            flagZ = true;
            return;
        }
        byte[] drv = storageDrives[d];
        int copied = 0;
        for (int i = 0; i < count; i++) {
            int curS = sAddr + i;
            int curR = rAddr + i;
            if (curS >= 0 && curS < cap && curR >= 0 && curR < ram.length) {
                int val = (drv != null && curS < drv.length) ? (drv[curS] & 0xFF) : 0;
                ram[curR] = val;
                copied++;
            }
        }
        flagZ = (copied == 0);
    }

    private void execSBlkw(String args) {
        String[] parts = args.split(",");
        int d = selectedDrive;
        int sAddr;
        int rAddr;
        int count;
        if (parts.length == 4) {
            d = resolveValue(parts[0].trim());
            sAddr = resolveValue(parts[1].trim());
            rAddr = resolveValue(parts[2].trim());
            count = resolveValue(parts[3].trim());
        } else if (parts.length == 3) {
            sAddr = resolveValue(parts[0].trim());
            rAddr = resolveValue(parts[1].trim());
            count = resolveValue(parts[2].trim());
        } else {
            throw new IllegalArgumentException("SBLKW requires [drive,] storage_addr, ram_src, count");
        }
        int cap = getDriveCapacity(d);
        if (d < 0 || d >= MAX_DRIVES || cap <= 0 || count <= 0) {
            flagZ = true;
            return;
        }
        int maxTargetAddr = sAddr + count - 1;
        if (maxTargetAddr >= cap) {
            maxTargetAddr = cap - 1;
        }
        byte[] drv = storageDrives[d];
        if (maxTargetAddr >= 0 && (drv == null || maxTargetAddr >= drv.length)) {
            int newLen = Math.max(maxTargetAddr + 1, Math.min(cap, drv == null ? 1024 : Math.max(drv.length * 2, maxTargetAddr + 1)));
            byte[] newDrv = new byte[newLen];
            if (drv != null) {
                System.arraycopy(drv, 0, newDrv, 0, drv.length);
            }
            storageDrives[d] = newDrv;
            drv = newDrv;
        }
        int copied = 0;
        for (int i = 0; i < count; i++) {
            int curS = sAddr + i;
            int curR = rAddr + i;
            if (curS >= 0 && curS < cap && curR >= 0 && curR < ram.length) {
                if (drv != null && curS < drv.length) {
                    drv[curS] = (byte) (ram[curR] & 0xFF);
                    copied++;
                }
            }
        }
        flagZ = (copied == 0);
        if (copied > 0 && driveWriteListener != null) {
            driveWriteListener.accept(d, drv);
        }
    }

    private void execSCount(String dest) {
        int count = getStorageDriveCount();
        setDestination(dest.trim(), count);
        flagZ = (count == 0);
        flagS = false;
    }

    private void execSSel(String args) {
        int d = resolveValue(args.trim());
        if (d >= 0 && d < MAX_DRIVES) {
            selectedDrive = d;
            int cap = getDriveCapacity(d);
            flagZ = (cap == 0);
            flagS = false;
        } else {
            flagZ = true;
            flagS = true;
        }
    }

    private void execSDrv(String dest) {
        setDestination(dest.trim(), selectedDrive);
        flagZ = (selectedDrive == 0);
        flagS = false;
    }

    private void execSExec(String args) {
        int drive = resolveValue(args.trim());
        if (drive < 0 || drive >= MAX_DRIVES) {
            triggerException(VEC_OUT_OF_BOUNDS, "Drive index " + drive + " out of bounds", pc - 1, "SEXEC " + args);
            return;
        }

        List<String> targetProgram = drivePrograms.get(drive);
        if (targetProgram == null || targetProgram.isEmpty()) {
            // Check if binary storage on drive contains plain UTF-8 text lines
            byte[] bytes = storageDrives[drive];
            if (bytes != null && bytes.length > 0) {
                int len = 0;
                while (len < bytes.length && bytes[len] != 0) {
                    len++;
                }
                if (len > 0) {
                    String src = new String(bytes, 0, len, StandardCharsets.UTF_8).trim();
                    if (!src.isEmpty()) {
                        String[] lines = src.split("\\r?\\n");
                        targetProgram = new ArrayList<>();
                        for (String l : lines) {
                            targetProgram.add(l);
                        }
                    }
                }
            }
        }

        if (targetProgram == null || targetProgram.isEmpty()) {
            triggerException(VEC_GENERAL_EXCEPTION, "No executable program on storage drive " + drive, pc - 1, "SEXEC " + args);
            return;
        }

        // Validate that all instructions in target program are supported by this CPU
        Set<String> req = InstructionValidator.getRequiredInstructions(targetProgram);
        List<String> missing = InstructionValidator.getMissingInstructions(req, new ArrayList<>(supportedInstructions));
        if (!missing.isEmpty()) {
            triggerException(VEC_ILLEGAL_OPCODE, "Target drive program uses unsupported instructions: " + missing, pc - 1, "SEXEC " + args);
            return;
        }

        // Load new program image
        this.program.clear();
        this.program.addAll(targetProgram);
        preprocessLabels();
        compileBytecode();
        this.callStack.clear();
        this.pc = 0;
        this.selectedDrive = drive;
        this.flagZ = false;
        this.flagS = false;
        flushLineBuffer();
    }

    // --- Framebuffer Instructions ---
    private void execFbMode(String args) {
        displayMode = resolveValue(args.trim());
        fbDirty = true;
    }

    private void execFbSet(String args) {
        String[] parts = args.split(",", 3);
        if (parts.length != 3) throw new IllegalArgumentException("FBSET requires x, y, color");
        int x = resolveValue(parts[0].trim());
        int y = resolveValue(parts[1].trim());
        int color = resolveValue(parts[2].trim());
        if ((color & 0xFF000000) == 0) color |= 0xFF000000;
        if (x >= 0 && x < FB_WIDTH && y >= 0 && y < FB_HEIGHT) {
            framebuffer[y * FB_WIDTH + x] = color;
            fbDirty = true;
        }
    }

    private void execFbGet(String args) {
        String[] parts = args.split(",", 3);
        if (parts.length != 3) throw new IllegalArgumentException("FBGET requires dest, x, y");
        String dest = parts[0].trim();
        int x = resolveValue(parts[1].trim());
        int y = resolveValue(parts[2].trim());
        int color = 0;
        if (x >= 0 && x < FB_WIDTH && y >= 0 && y < FB_HEIGHT) {
            color = framebuffer[y * FB_WIDTH + x];
        }
        setDestination(dest, color);
    }

    private void execFbClr(String args) {
        int color = resolveValue(args.trim());
        if ((color & 0xFF000000) == 0) color |= 0xFF000000;
        Arrays.fill(framebuffer, color);
        fbDirty = true;
    }

    private void execFbRect(String args) {
        String[] parts = args.split(",", 5);
        if (parts.length != 5) throw new IllegalArgumentException("FBRECT requires x, y, w, h, color");
        int rx = resolveValue(parts[0].trim());
        int ry = resolveValue(parts[1].trim());
        int rw = resolveValue(parts[2].trim());
        int rh = resolveValue(parts[3].trim());
        int color = resolveValue(parts[4].trim());
        if ((color & 0xFF000000) == 0) color |= 0xFF000000;

        int xStart = Math.max(0, rx);
        int xEnd = Math.min(FB_WIDTH, rx + rw);
        int yStart = Math.max(0, ry);
        int yEnd = Math.min(FB_HEIGHT, ry + rh);

        if (xEnd > xStart && yEnd > yStart) {
            for (int y = yStart; y < yEnd; y++) {
                int rowOffset = y * FB_WIDTH;
                Arrays.fill(framebuffer, rowOffset + xStart, rowOffset + xEnd, color);
            }
            fbDirty = true;
        }
    }

    private void execFbLine(String args) {
        String[] parts = args.split(",", 5);
        if (parts.length != 5) throw new IllegalArgumentException("FBLINE requires x1, y1, x2, y2, color");
        int x0 = resolveValue(parts[0].trim());
        int y0 = resolveValue(parts[1].trim());
        int x1 = resolveValue(parts[2].trim());
        int y1 = resolveValue(parts[3].trim());
        int color = resolveValue(parts[4].trim());
        if ((color & 0xFF000000) == 0) color |= 0xFF000000;

        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;

        while (true) {
            if (x0 >= 0 && x0 < FB_WIDTH && y0 >= 0 && y0 < FB_HEIGHT) {
                framebuffer[y0 * FB_WIDTH + x0] = color;
            }
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                y0 += sy;
            }
        }
        fbDirty = true;
    }

    private void execFbLit(String args) {
        String[] parts = args.split(",", 5);
        if (parts.length != 5) throw new IllegalArgumentException("FBLIT requires x, y, ram_src, w, h");
        int dx = resolveValue(parts[0].trim());
        int dy = resolveValue(parts[1].trim());
        int ramSrc = resolveValue(parts[2].trim());
        int bw = resolveValue(parts[3].trim());
        int bh = resolveValue(parts[4].trim());

        for (int py = 0; py < bh; py++) {
            int targetY = dy + py;
            if (targetY < 0 || targetY >= FB_HEIGHT) continue;
            for (int px = 0; px < bw; px++) {
                int targetX = dx + px;
                if (targetX < 0 || targetX >= FB_WIDTH) continue;
                int ramIndex = ramSrc + py * bw + px;
                if (ramIndex >= 0 && ramIndex < ram.length) {
                    int c = ram[ramIndex];
                    if ((c & 0xFF000000) == 0) c |= 0xFF000000;
                    framebuffer[targetY * FB_WIDTH + targetX] = c;
                }
            }
        }
        fbDirty = true;
    }

    // --- Audio Instructions ---
    private void ensureAudioQueueCapacity(int minCapacity) {
        if (minCapacity > audioQueue.length) {
            int newCap = Math.max(audioQueue.length * 2, minCapacity);
            byte[] newQueue = new byte[newCap];
            for (int i = 0; i < audioCount; i++) {
                newQueue[i] = audioQueue[(audioHead + i) % audioQueue.length];
            }
            audioQueue = newQueue;
            audioHead = 0;
            audioTail = audioCount;
        }
    }

    private synchronized void execAPush(String args) {
        int sample = resolveValue(args.trim()) & 0xFF;
        ensureAudioQueueCapacity(audioCount + 1);
        audioQueue[audioTail] = (byte) sample;
        audioTail = (audioTail + 1) % audioQueue.length;
        audioCount++;
    }

    private synchronized void execABufw(String args) {
        String[] parts = args.split(",", 2);
        if (parts.length != 2) throw new IllegalArgumentException("ABUFW requires ram_src, count");
        int ramSrc = resolveValue(parts[0].trim());
        int count = resolveValue(parts[1].trim());
        ensureAudioQueueCapacity(audioCount + count);
        for (int i = 0; i < count; i++) {
            int rAddr = ramSrc + i;
            if (rAddr >= 0 && rAddr < ram.length) {
                audioQueue[audioTail] = (byte) (ram[rAddr] & 0xFF);
                audioTail = (audioTail + 1) % audioQueue.length;
                audioCount++;
            }
        }
    }

    private synchronized void execAClr() {
        audioHead = 0;
        audioTail = 0;
        audioCount = 0;
        audioTriggered = false;
    }

    private synchronized void execATone(String args) {
        String[] parts = args.split(",", 2);
        if (parts.length != 2) throw new IllegalArgumentException("ATONE requires freq, duration_ms");
        int freq = resolveValue(parts[0].trim());
        int ms = resolveValue(parts[1].trim());
        if (ms > 0) {
            int numSamples = (int) ((8000L * ms) / 1000L);
            ensureAudioQueueCapacity(audioCount + numSamples);
            if (freq <= 0) {
                // Pause / silence: 128 is center (silence) in 8-bit unsigned PCM
                for (int i = 0; i < numSamples; i++) {
                    audioQueue[audioTail] = (byte) 128;
                    audioTail = (audioTail + 1) % audioQueue.length;
                    audioCount++;
                }
            } else {
                int period = Math.max(1, 8000 / freq);
                int half = Math.max(1, period / 2);
                for (int i = 0; i < numSamples; i++) {
                    byte s = (byte) ((i % period < half) ? 200 : 50);
                    audioQueue[audioTail] = s;
                    audioTail = (audioTail + 1) % audioQueue.length;
                    audioCount++;
                }
            }
            audioTriggered = true;
        }
    }

    public synchronized byte[] pollAudioSamples() {
        if (!audioTriggered && audioCount < 512) {
            return null;
        }
        if (audioCount == 0) {
            audioTriggered = false;
            return null;
        }
        byte[] chunk = new byte[audioCount];
        for (int i = 0; i < audioCount; i++) {
            chunk[i] = audioQueue[audioHead];
            audioHead = (audioHead + 1) % audioQueue.length;
        }
        audioCount = 0;
        audioTriggered = false;
        return chunk;
    }

    public int[] getFramebuffer() { return framebuffer; }
    public boolean isFbDirty() { return fbDirty; }
    public void setFbDirty(boolean dirty) { this.fbDirty = dirty; }
    public int getDisplayMode() { return displayMode; }
    public void setDisplayMode(int mode) { this.displayMode = mode; }

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

    private void execOuth(String args) {
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
            lineBuffer.append("0x").append(Integer.toHexString(val).toUpperCase(Locale.ROOT));
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

    public static boolean isMatchingOuterParentheses(String s) {
        if (!s.startsWith("(") || !s.endsWith(")")) return false;
        int depth = 0;
        for (int i = 0; i < s.length() - 1; i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return false;
            }
        }
        return depth == 1;
    }

    private int findTopLevelBinaryOp(String expr, String... ops) {
        int parenDepth = 0;
        int bracketDepth = 0;
        boolean inQuotes = false;
        char quoteChar = 0;
        for (int i = expr.length() - 1; i >= 0; i--) {
            char c = expr.charAt(i);
            if ((c == '\"' || c == '\'') && (i == 0 || expr.charAt(i - 1) != '\\')) {
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

    private boolean isValidBinaryOpPosition(String expr, int i, String op) {
        if (i <= 0) return false;
        int opLen = op.length();
        if (i + opLen >= expr.length()) return false;

        String leftPrefix = expr.substring(0, i).trim();
        if (leftPrefix.isEmpty()) return false;
        char lastLeft = leftPrefix.charAt(leftPrefix.length() - 1);
        if (lastLeft == '+' || lastLeft == '-' || lastLeft == '*' || lastLeft == '/' || lastLeft == '%'
                || lastLeft == '&' || lastLeft == '|' || lastLeft == '^' || lastLeft == '<' || lastLeft == '>'
                || lastLeft == '=' || lastLeft == '(' || lastLeft == '[') {
            return false;
        }

        if (op.equals("|")) {
            if (i > 0 && expr.charAt(i - 1) == '|') return false;
            if (i + 1 < expr.length() && expr.charAt(i + 1) == '|') return false;
        } else if (op.equals("&")) {
            if (i > 0 && expr.charAt(i - 1) == '&') return false;
            if (i + 1 < expr.length() && expr.charAt(i + 1) == '&') return false;
        } else if (op.equals("<")) {
            if (i > 0 && expr.charAt(i - 1) == '<') return false;
            if (i + 1 < expr.length() && expr.charAt(i + 1) == '<') return false;
        } else if (op.equals(">")) {
            if (i > 0 && expr.charAt(i - 1) == '>') return false;
            if (i + 1 < expr.length() && expr.charAt(i + 1) == '>') return false;
        }
        return true;
    }

    public static String[] splitArgs(String args, int limit) {
        if (args == null) return new String[0];
        List<String> list = new ArrayList<>();
        int parenDepth = 0;
        int bracketDepth = 0;
        boolean inQuotes = false;
        char quoteChar = 0;
        int start = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if ((c == '\"' || c == '\'') && (i == 0 || args.charAt(i - 1) != '\\')) {
                if (inQuotes && c == quoteChar) inQuotes = false;
                else if (!inQuotes) { inQuotes = true; quoteChar = c; }
            }
            if (inQuotes) continue;
            if (c == '(') parenDepth++;
            else if (c == ')') parenDepth--;
            else if (c == '[') bracketDepth++;
            else if (c == ']') bracketDepth--;
            else if (c == ',' && parenDepth == 0 && bracketDepth == 0) {
                if (limit > 0 && list.size() == limit - 1) {
                    break;
                }
                list.add(args.substring(start, i));
                start = i + 1;
            }
        }
        list.add(args.substring(start));
        return list.toArray(new String[0]);
    }

    private int resolveValue(String token) {
        token = token.trim();
        while (token.startsWith("(") && token.endsWith(")") && isMatchingOuterParentheses(token)) {
            token = token.substring(1, token.length() - 1).trim();
        }

        if (token.equalsIgnoreCase("A")) return regA;
        if (token.equalsIgnoreCase("B")) return regB;
        if (token.equalsIgnoreCase("C")) return regC;
        if (token.equalsIgnoreCase("D")) return regD;
        if (token.equalsIgnoreCase("PC")) return pc;
        if (token.equalsIgnoreCase("FLAGS")) return (flagZ ? 1 : 0) | (flagS ? 2 : 0);

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

        // Binary operations in precedence order (lowest to highest)
        int orIdx = findTopLevelBinaryOp(token, "|");
        if (orIdx > 0) {
            return resolveValue(token.substring(0, orIdx)) | resolveValue(token.substring(orIdx + 1));
        }

        int xorIdx = findTopLevelBinaryOp(token, "^");
        if (xorIdx > 0) {
            return resolveValue(token.substring(0, xorIdx)) ^ resolveValue(token.substring(xorIdx + 1));
        }

        int andIdx = findTopLevelBinaryOp(token, "&");
        if (andIdx > 0) {
            return resolveValue(token.substring(0, andIdx)) & resolveValue(token.substring(andIdx + 1));
        }

        int shiftIdx = findTopLevelBinaryOp(token, "<<", ">>");
        if (shiftIdx > 0) {
            if (token.startsWith("<<", shiftIdx)) {
                return resolveValue(token.substring(0, shiftIdx)) << resolveValue(token.substring(shiftIdx + 2));
            } else {
                return resolveValue(token.substring(0, shiftIdx)) >> resolveValue(token.substring(shiftIdx + 2));
            }
        }

        int addSubIdx = findTopLevelBinaryOp(token, "+", "-");
        if (addSubIdx > 0) {
            char op = token.charAt(addSubIdx);
            int left = resolveValue(token.substring(0, addSubIdx));
            int right = resolveValue(token.substring(addSubIdx + 1));
            return (op == '+') ? (left + right) : (left - right);
        }

        int mulDivIdx = findTopLevelBinaryOp(token, "*", "/", "%");
        if (mulDivIdx > 0) {
            char op = token.charAt(mulDivIdx);
            int left = resolveValue(token.substring(0, mulDivIdx));
            int right = resolveValue(token.substring(mulDivIdx + 1));
            if (op == '*') return left * right;
            if (op == '/') {
                if (right == 0) throw new ArithmeticException("Division by zero in expression: " + token);
                return left / right;
            }
            if (right == 0) throw new ArithmeticException("Modulo by zero in expression: " + token);
            return left % right;
        }

        // Label reference: returns line number (code label) or RAM address (data label)
        String lower = token.toLowerCase(Locale.ROOT);
        if (labels.containsKey(lower)) {
            return labels.get(lower);
        }

        try {
            if (token.startsWith("0x") || token.startsWith("0X")) {
                return (int) Long.parseLong(token.substring(2), 16);
            }
            if (token.startsWith("-0x") || token.startsWith("-0X")) {
                return (int) -Long.parseLong(token.substring(3), 16);
            }
            return (int) Long.parseLong(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid value or register: " + token);
        }
    }

    private void setDestination(String dest, int val) {
        dest = dest.trim();
        while (dest.startsWith("(") && dest.endsWith(")") && isMatchingOuterParentheses(dest)) {
            dest = dest.substring(1, dest.length() - 1).trim();
        }
        if (dest.equalsIgnoreCase("A")) { regA = val; return; }
        if (dest.equalsIgnoreCase("B")) { regB = val; return; }
        if (dest.equalsIgnoreCase("C")) { regC = val; return; }
        if (dest.equalsIgnoreCase("D")) { regD = val; return; }
        if (dest.equalsIgnoreCase("PC")) { pc = val; return; }

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

    public void setTerminalOutput(List<String> lines) {
        this.terminalOutput.clear();
        if (lines != null) {
            this.terminalOutput.addAll(lines);
        }
        this.lineBuffer.setLength(0);
        this.utf8ByteBuf.reset();
    }

    public Set<String> getSupportedInstructions() { return supportedInstructions; }
    public List<String> getProgram() { return program; }
    public int[] getRam() { return ram; }
    public boolean hasNvme() { return hasNvme; }
    public int getStorageSize() { return storageSize; }
    public byte[] getStorage() {
        if (selectedDrive >= 0 && selectedDrive < MAX_DRIVES && storageDrives[selectedDrive] != null && storageDrives[selectedDrive].length > 0) {
            return storageDrives[selectedDrive];
        }
        for (int i = 0; i < MAX_DRIVES; i++) {
            if (storageDrives[i] != null && storageDrives[i].length > 0) {
                return storageDrives[i];
            }
        }
        return storageDrives[0] != null ? storageDrives[0] : new byte[0];
    }

    public void setStorage(byte[] data) {
        int targetDrive = selectedDrive;
        if (targetDrive < 0 || targetDrive >= MAX_DRIVES) targetDrive = 0;
        setStorageDrive(targetDrive, data);
        if (driveWriteListener != null) {
            driveWriteListener.accept(targetDrive, getStorageDrive(targetDrive));
        }
    }

    public void setDriveCapacity(int drive, int capacityBytes) {
        if (drive >= 0 && drive < MAX_DRIVES) {
            this.driveCapacities[drive] = capacityBytes;
        }
    }

    public int getDriveCapacity(int drive) {
        if (drive >= 0 && drive < MAX_DRIVES) {
            if (driveCapacities[drive] > 0) {
                return driveCapacities[drive];
            }
            if (storageDrives[drive] != null) {
                return storageDrives[drive].length;
            }
        }
        return 0;
    }

    public void setStorageDrive(int drive, byte[] data) {
        if (drive >= 0 && drive < MAX_DRIVES) {
            this.storageDrives[drive] = (data != null) ? data : new byte[0];
            if (this.storageDrives[drive].length > this.driveCapacities[drive]) {
                this.driveCapacities[drive] = this.storageDrives[drive].length;
            }
        }
    }

    public byte[] getStorageDrive(int drive) {
        if (drive >= 0 && drive < MAX_DRIVES) {
            return this.storageDrives[drive] != null ? this.storageDrives[drive] : new byte[0];
        }
        return new byte[0];
    }

    public int getStorageDriveCount() {
        int count = 0;
        for (int i = 0; i < MAX_DRIVES; i++) {
            if (getDriveCapacity(i) > 0 || (storageDrives[i] != null && storageDrives[i].length > 0)) {
                count++;
            }
        }
        return count;
    }

    public int getSelectedDrive() {
        return selectedDrive;
    }

    public void setSelectedDrive(int drive) {
        if (drive >= 0 && drive < MAX_DRIVES) {
            this.selectedDrive = drive;
        }
    }

    public void setDriveWriteListener(BiConsumer<Integer, byte[]> listener) {
        this.driveWriteListener = listener;
    }

    public void setDriveProgram(int drive, List<String> lines) {
        if (lines != null && !lines.isEmpty()) {
            this.drivePrograms.put(drive, new ArrayList<>(lines));
        } else {
            this.drivePrograms.remove(drive);
        }
    }

    public List<String> getDriveProgram(int drive) {
        return this.drivePrograms.getOrDefault(drive, Collections.emptyList());
    }
}
