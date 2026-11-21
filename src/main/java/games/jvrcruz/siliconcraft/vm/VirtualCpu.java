package games.jvrcruz.siliconcraft.vm;

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

    public static final int FB_WIDTH = 228;
    public static final int FB_HEIGHT = 82;
    private int displayMode = 0; // 0 = text mode, 1 = framebuffer mode
    private final int[] framebuffer = new int[FB_WIDTH * FB_HEIGHT];
    private boolean fbDirty = false;

    // Bare-bones audio buffer (8000 Hz, 8-bit unsigned PCM samples)
    private final byte[] audioQueue = new byte[8192];
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
        for (int i = 0; i < program.size(); i++) {
            String line = program.get(i).trim();
            if (line.endsWith(":")) {
                String labelName = line.substring(0, line.length() - 1).trim().toLowerCase(Locale.ROOT);
                labels.put(labelName, i);
            }
        }
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
        Arrays.fill(ram, 0);
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
            if (sleepTicksRemaining > 0) {
                break;
            }
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
                case "SSIZE" -> execSSize(args);
                case "SREAD" -> execSRead(args);
                case "SWRITE" -> execSWrite(args);
                case "SBLKR" -> execSBlkr(args);
                case "SBLKW" -> execSBlkw(args);
                case "SCOUNT" -> execSCount(args);
                case "SSEL" -> execSSel(args);
                case "SDRV" -> execSDrv(args);
                case "FBMODE" -> execFbMode(args);
                case "FBSET" -> execFbSet(args);
                case "FBGET" -> execFbGet(args);
                case "FBCLR" -> execFbClr(args);
                case "FBRECT" -> execFbRect(args);
                case "FBLINE" -> execFbLine(args);
                case "FBLIT" -> execFbLit(args);
                case "FBSWAP" -> fbDirty = true;
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

    private void execMul(String args) {
        String[] parts = args.split(",", 2);
        if (parts.length != 2) throw new IllegalArgumentException("MUL requires dest, src");
        String dest = parts[0].trim();
        int current = resolveValue(dest);
        int factor = resolveValue(parts[1].trim());
        int result = current * factor;
        setDestination(dest, result);
        updateFlags(result);
    }

    private void execDiv(String args) {
        String[] parts = args.split(",", 2);
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
        String[] parts = args.split(",", 2);
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
        String[] parts = args.split(",", 2);
        if (parts.length != 2) throw new IllegalArgumentException("AND requires dest, src");
        String dest = parts[0].trim();
        int current = resolveValue(dest);
        int val = resolveValue(parts[1].trim());
        int result = current & val;
        setDestination(dest, result);
        updateFlags(result);
    }

    private void execOr(String args) {
        String[] parts = args.split(",", 2);
        if (parts.length != 2) throw new IllegalArgumentException("OR requires dest, src");
        String dest = parts[0].trim();
        int current = resolveValue(dest);
        int val = resolveValue(parts[1].trim());
        int result = current | val;
        setDestination(dest, result);
        updateFlags(result);
    }

    private void execXor(String args) {
        String[] parts = args.split(",", 2);
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
        String[] parts = args.split(",", 2);
        if (parts.length != 2) throw new IllegalArgumentException("SHL requires dest, count");
        String dest = parts[0].trim();
        int current = resolveValue(dest);
        int count = resolveValue(parts[1].trim());
        int result = current << count;
        setDestination(dest, result);
        updateFlags(result);
    }

    private void execShr(String args) {
        String[] parts = args.split(",", 2);
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

    private void execCall(String target) {
        target = target.trim();
        String lower = target.toLowerCase(Locale.ROOT);
        int destPc;
        if (labels.containsKey(lower)) {
            destPc = labels.get(lower);
        } else {
            try {
                destPc = Integer.parseInt(target);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Label not found for CALL: " + target);
            }
        }
        callStack.push(pc);
        pc = destPc;
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
            return Integer.parseInt(target);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Label not found: " + target);
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
            case "TIMER" -> VEC_TIMER;
            default -> resolveValue(token);
        };
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
    private synchronized void execAPush(String args) {
        int sample = resolveValue(args.trim()) & 0xFF;
        if (audioCount < audioQueue.length) {
            audioQueue[audioTail] = (byte) sample;
            audioTail = (audioTail + 1) % audioQueue.length;
            audioCount++;
        }
    }

    private synchronized void execABufw(String args) {
        String[] parts = args.split(",", 2);
        if (parts.length != 2) throw new IllegalArgumentException("ABUFW requires ram_src, count");
        int ramSrc = resolveValue(parts[0].trim());
        int count = resolveValue(parts[1].trim());
        for (int i = 0; i < count; i++) {
            if (audioCount >= audioQueue.length) break;
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
        if (freq > 0 && ms > 0) {
            int numSamples = Math.min(8000, (int) ((8000L * ms) / 1000L));
            int period = Math.max(1, 8000 / freq);
            int half = Math.max(1, period / 2);
            for (int i = 0; i < numSamples; i++) {
                if (audioCount >= audioQueue.length) break;
                byte s = (byte) ((i % period < half) ? 200 : 50);
                audioQueue[audioTail] = s;
                audioTail = (audioTail + 1) % audioQueue.length;
                audioCount++;
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
}
