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
    private final Map<Integer, List<String>> drivePrograms = new HashMap<>();
    private final List<String> terminalOutput = new ArrayList<>();
    private final StringBuilder lineBuffer = new StringBuilder();
    private final ByteArrayOutputStream utf8ByteBuf = new ByteArrayOutputStream();
    private final Deque<Integer> callStack = new ArrayDeque<>();
    private final Deque<Integer> dataStack = new ArrayDeque<>();
    private int sleepTicksRemaining = 0;
    private LongSupplier timeSupplier = System::currentTimeMillis;
    private final Random random = new Random();
    private int[] ram = new int[256];
    private boolean hasNvme = false;
    private int storageSize = 0;

    public static final int MAX_DRIVES = 4;
    private final byte[][] storageDrives = new byte[MAX_DRIVES][];
    private int selectedDrive = 0;
    private BiConsumer<Integer, byte[]> driveWriteListener;

    public static final int FB_WIDTH = 240;
    public static final int FB_HEIGHT = 135;
    private int displayMode = 0; // 0 = text mode, 1 = framebuffer mode
    private final int[] framebuffer = new int[FB_WIDTH * FB_HEIGHT];
    private boolean fbDirty = false;

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
        int targetStorageBytes = (int) Math.min(16 * 1024 * 1024L, (long) Math.max(0, storageSize) * 1024L);
        if (targetStorageBytes > 0) {
            if (this.storageDrives[0] == null || this.storageDrives[0].length != targetStorageBytes) {
                byte[] newStorage = new byte[targetStorageBytes];
                if (this.storageDrives[0] != null) {
                    System.arraycopy(this.storageDrives[0], 0, newStorage, 0, Math.min(this.storageDrives[0].length, targetStorageBytes));
                }
                this.storageDrives[0] = newStorage;
            }
        }
        int effectiveRam = Math.max(0, Math.min(ramSize, 65536));
        this.ram = new int[effectiveRam];
        this.program.clear();
        this.program.addAll(code);
        this.labels.clear();
        preprocessLabels();
        reset();
    }

    public void updateHardware(int totalRam, boolean hasNvme, int storageSize) {
        this.hasNvme = hasNvme;
        this.storageSize = storageSize;
        int targetStorageBytes = (int) Math.min(16 * 1024 * 1024L, (long) Math.max(0, storageSize) * 1024L);
        if (targetStorageBytes > 0) {
            if (this.storageDrives[0] == null || this.storageDrives[0].length != targetStorageBytes) {
                byte[] newStorage = new byte[targetStorageBytes];
                if (this.storageDrives[0] != null) {
                    System.arraycopy(this.storageDrives[0], 0, newStorage, 0, Math.min(this.storageDrives[0].length, targetStorageBytes));
                }
                this.storageDrives[0] = newStorage;
            }
        }
        int effectiveRam = Math.max(0, Math.min(totalRam, 65536));
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

            int colon = line.indexOf(':');
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

            int colon = line.indexOf(':');
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
        Arrays.fill(ram, 0);
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
            if (storageDrives[i] != null && storageDrives[i].length > 0) {
                this.selectedDrive = i;
                break;
            }
        }
        this.ivt.clear();
        this.interruptQueue.clear();
        this.interruptStack.clear();
        this.interruptsEnabled = true;
        this.inInterruptHandler = false;
        if (state != State.OFF) {
            this.state = State.RUNNING;
        }
    }

    public void powerOn() {
        reset();
        this.state = State.RUNNING;
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
        checkTimerTrigger();
        if (sleepTicksRemaining > 0) {
            checkAndDispatchInterrupt();
            if (sleepTicksRemaining > 0) {
                sleepTicksRemaining--;
                return;
            }
        }
        int count = 0;
        while (state == State.RUNNING && count < maxCycles) {
            executeNextInstruction();
            count++;
            checkTimerTrigger();
            if (sleepTicksRemaining > 0) {
                break;
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
        if (!interruptsEnabled || inInterruptHandler) {
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

        int currentLineNum = pc;
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

        // Directives (ORG, DB, DW, DL, INCBIN) are no-ops during execution
        if (DIRECTIVES.contains(opcode)) {
            return;
        }

        // Check if instruction is supported by this CPU's synthesized hardware
        if (!supportedInstructions.contains(opcode)) {
            triggerException(VEC_ILLEGAL_OPCODE, "Illegal instruction: " + opcode, currentLineNum, line);
            return;
        }

        try {
            switch (opcode) {
                case "NOP" -> {}
                case "MOV" -> execMov(args);
                case "ADD" -> execAdd(args);
                case "SUB" -> execSub(args);
                case "MUL" -> execMul(args);
                case "DIV" -> execDiv(args);
                case "MOD" -> execMod(args);
                case "INC" -> execInc(args);
                case "DEC" -> execDec(args);
                case "CMP" -> execCmp(args);
                case "AND" -> execAnd(args);
                case "OR" -> execOr(args);
                case "XOR" -> execXor(args);
                case "NOT" -> execNot(args);
                case "SHL" -> execShl(args);
                case "SHR" -> execShr(args);
                case "JMP" -> execJmp(args, true);
                case "JZ" -> execJmp(args, flagZ);
                case "JNZ" -> execJmp(args, !flagZ);
                case "JS" -> execJmp(args, flagS);
                case "JNS" -> execJmp(args, !flagS);
                case "JL" -> execJmp(args, flagS && !flagZ);
                case "JLE" -> execJmp(args, flagS || flagZ);
                case "JG" -> execJmp(args, !flagS && !flagZ);
                case "JGE" -> execJmp(args, !flagS);
                case "CALL" -> execCall(args);
                case "RET" -> execRet();
                case "PUSH" -> execPush(args);
                case "POP" -> execPop(args);
                case "IVEC" -> execIvec(args);
                case "IRET" -> execIret();
                case "CLI" -> interruptsEnabled = false;
                case "STI" -> interruptsEnabled = true;
                case "INT" -> execInt(args);
                case "OUT" -> execOut(args);
                case "OUTH" -> execOuth(args);
                case "OUTC" -> execOutc(args);
                case "CLS" -> execCls();
                case "SLEEP" -> execSleep(args);
                case "TIME" -> execTime(args);
                case "RAND" -> execRand(args);
                case "INPAD" -> execInpad(args);
                case "TIMER", "SETTIMER" -> execTimer(args);
                case "VBLANK" -> execVblank(args);
                case "SSIZE" -> execSSize(args);
                case "SREAD" -> execSRead(args);
                case "SWRITE" -> execSWrite(args);
                case "SBLKR" -> execSBlkr(args);
                case "SBLKW" -> execSBlkw(args);
                case "SCOUNT" -> execSCount(args);
                case "SSEL" -> execSSel(args);
                case "SDRV" -> execSDrv(args);
                case "SEXEC", "SBOOT" -> execSExec(args);
                case "FBMODE" -> execFbMode(args);
                case "FBSET" -> execFbSet(args);
                case "FBGET" -> execFbGet(args);
                case "FBCLR" -> execFbClr(args);
                case "FBRECT" -> execFbRect(args);
                case "FBLINE" -> execFbLine(args);
                case "FBLIT" -> execFbLit(args);
                case "FBSWAP" -> {
                    displayMode = 1;
                    fbDirty = true;
                }
                case "FBWIDTH" -> setDestination(args.trim(), FB_WIDTH);
                case "FBHEIGHT" -> setDestination(args.trim(), FB_HEIGHT);
                case "APUSH" -> execAPush(args);
                case "ABUFW" -> execABufw(args);
                case "ACLR" -> execAClr();
                case "ASTAT" -> setDestination(args.trim(), audioCount);
                case "ATONE" -> execATone(args);
                case "APLAY" -> audioTriggered = true;
                case "HLT" -> {
                    state = State.HALTED;
                    flushLineBuffer();
                    terminalOutput.add("[EXECUTION HALTED]");
                }
                default -> triggerException(VEC_ILLEGAL_OPCODE, "Unknown opcode: " + opcode, currentLineNum, rawLine);
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
            triggerException(vec, desc, currentLineNum, rawLine);
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
            default -> resolveValue(token);
        };
    }

    public void queueTimerInterrupt() {
        synchronized (interruptQueue) {
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
        int sz = (d >= 0 && d < MAX_DRIVES && storageDrives[d] != null) ? storageDrives[d].length : 0;
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
        if (drv != null && addr >= 0 && addr < drv.length) {
            int val = drv[addr] & 0xFF;
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
        byte[] drv = (d >= 0 && d < MAX_DRIVES) ? storageDrives[d] : null;
        if (drv != null && addr >= 0 && addr < drv.length) {
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
        byte[] drv = (d >= 0 && d < MAX_DRIVES) ? storageDrives[d] : null;
        if (drv == null || count <= 0) {
            flagZ = true;
            return;
        }
        int copied = 0;
        for (int i = 0; i < count; i++) {
            int curS = sAddr + i;
            int curR = rAddr + i;
            if (curS >= 0 && curS < drv.length && curR >= 0 && curR < ram.length) {
                ram[curR] = drv[curS] & 0xFF;
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
        byte[] drv = (d >= 0 && d < MAX_DRIVES) ? storageDrives[d] : null;
        if (drv == null || count <= 0) {
            flagZ = true;
            return;
        }
        int copied = 0;
        for (int i = 0; i < count; i++) {
            int curS = sAddr + i;
            int curR = rAddr + i;
            if (curS >= 0 && curS < drv.length && curR >= 0 && curR < ram.length) {
                drv[curS] = (byte) (ram[curR] & 0xFF);
                copied++;
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
            byte[] drv = storageDrives[d];
            flagZ = (drv == null || drv.length == 0);
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

    public void setStorageDrive(int drive, byte[] data) {
        if (drive >= 0 && drive < MAX_DRIVES) {
            this.storageDrives[drive] = (data != null) ? data : new byte[0];
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
            if (storageDrives[i] != null && storageDrives[i].length > 0) {
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
