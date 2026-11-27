package games.jvrcruz.siliconcraft.vm;

import games.jvrcruz.siliconcraft.vm.network.NetworkPort;
import games.jvrcruz.siliconcraft.compiler.Assembler;
import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import games.jvrcruz.siliconcraft.data.GpuData;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

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
    private byte[] binaryImage = new byte[0];
    private long[] binaryCode = new long[0];
    private String[] binaryRodata = new String[0];
    private BinaryBytecode.ExtOperand[] binaryExt = new BinaryBytecode.ExtOperand[0];
    private List<BinaryBytecode.DataSegment> staticDataSegments = List.of();
    private String pendingAssemblyError = null;
    private final Map<Integer, byte[]> driveBinaries = new HashMap<>();
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
    private final DriveStorage[] storageDrives = new DriveStorage[MAX_DRIVES];
    private final byte[] storageBuffer = new byte[4096];
    private final long[] driveCapacities = new long[MAX_DRIVES];
    private int selectedDrive = 0;
    private BiConsumer<Integer, byte[]> driveWriteListener;

    public enum GraphicsHandler {
        AUTO,
        CPU,
        GPU
    }

    public static final int FB_WIDTH = 960;
    public static final int FB_HEIGHT = 540;
    private volatile int displayMode = 0; // 0 = text mode, 1 = framebuffer mode
    private final int[] framebuffer = new int[FB_WIDTH * FB_HEIGHT];
    private volatile boolean fbDirty = false;
    private final VirtualGpu gpu = new VirtualGpu();
    private volatile GraphicsHandler graphicsHandler = GraphicsHandler.AUTO;

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

    public static final int PAD_LEFT_TRIGGER = GamepadState.LEFT_TRIGGER_BUTTON;
    public static final int PAD_RIGHT_TRIGGER = GamepadState.RIGHT_TRIGGER_BUTTON;
    public static final int PAD_LEFT_STICK = GamepadState.LEFT_STICK_BUTTON;
    public static final int PAD_RIGHT_STICK = GamepadState.RIGHT_STICK_BUTTON;
    private final AtomicReferenceArray<GamepadState> padStates = new AtomicReferenceArray<>(4);
    private final ConcurrentLinkedQueue<Integer> keyBuffer = new ConcurrentLinkedQueue<>();

    // Hardware frame / periodic timer
    private int timerHz = 0;
    private long lastTimerNanos = 0;
    private int timerIntervalCycles = 0;
    private int lastTimerCycle = 0;

    // Assembler Directives
    public static final Set<String> DIRECTIVES = InstructionValidator.ASSEMBLER_DIRECTIVES;

    public record CpuContext(int pc, int regA, int regB, int regC, int regD, boolean flagZ, boolean flagS, boolean restoreRegs) {}
    public record InterruptEvent(int vector, int regA, int regB, int regC) {}

    private final Map<Integer, Integer> ivt = new HashMap<>();
    private final Deque<InterruptEvent> interruptQueue = new ArrayDeque<>();
    private final Deque<CpuContext> interruptStack = new ArrayDeque<>();
    private boolean interruptsEnabled = true;
    private boolean inInterruptHandler = false;

    private final NetworkPort networkPort = new NetworkPort();

    public NetworkPort getNetworkPort() {
        return networkPort;
    }

    private final PeripheralBus peripheralBus = new PeripheralBus();

    public PeripheralBus getPeripheralBus() {
        return peripheralBus;
    }

    private int getReg(int reg) {
        return switch (reg) {
            case BinaryBytecode.REG_A -> regA;
            case BinaryBytecode.REG_B -> regB;
            case BinaryBytecode.REG_C -> regC;
            case BinaryBytecode.REG_D -> regD;
            case BinaryBytecode.REG_PC -> pc;
            case BinaryBytecode.REG_FLAGS -> (flagZ ? 1 : 0) | (flagS ? 2 : 0);
            default -> 0;
        };
    }

    private void setReg(int reg, int val) {
        switch (reg) {
            case BinaryBytecode.REG_A -> regA = val;
            case BinaryBytecode.REG_B -> regB = val;
            case BinaryBytecode.REG_C -> regC = val;
            case BinaryBytecode.REG_D -> regD = val;
            case BinaryBytecode.REG_PC -> pc = val;
            default -> throw new IllegalArgumentException("Cannot write to register " + reg);
        }
    }

    public VirtualCpu() {
        Arrays.fill(framebuffer, 0xFF051009);
    }

    public void configure(Set<String> instructions, int ramSize, List<String> assemblyLines, boolean hasNvme, int storageSize) {
        if (assemblyLines == null || assemblyLines.isEmpty()) {
            configure(instructions, ramSize, new byte[0], hasNvme, storageSize);
            return;
        }
        var assembled = games.jvrcruz.siliconcraft.compiler.Assembler.assemble(assemblyLines);
        if (!assembled.success()) {
            configure(instructions, ramSize, new byte[0], hasNvme, storageSize);
            this.pendingAssemblyError = String.join("; ", assembled.errors());
            return;
        }
        configure(instructions, ramSize, assembled.bytecode(), hasNvme, storageSize);
    }

    public void configure(Set<String> instructions, int ramSize, byte[] bytecode, boolean hasNvme, int storageSize) {
        this.supportedInstructions.clear();
        if (instructions != null) {
            for (String instr : instructions) {
                this.supportedInstructions.add(instr.toUpperCase(Locale.ROOT).trim());
            }
        }
        this.hasNvme = hasNvme;
        this.storageSize = storageSize;
        long targetStorageBytes = (long) Math.max(0, storageSize) * 1024L;
        this.driveCapacities[0] = Math.min(1L << 31, targetStorageBytes);
        int effectiveRam = Math.max(0, Math.min(ramSize, 64 * 1024 * 1024));
        this.ram = new int[effectiveRam];
        this.ivt.clear();
        applyBinaryImage(bytecode);
        reset();
    }

    private void applyBinaryImage(byte[] bytecode) {
        this.pendingAssemblyError = null;
        if (bytecode == null || bytecode.length == 0) {
            this.binaryImage = new byte[0];
            this.binaryCode = new long[0];
            this.binaryRodata = new String[0];
            this.binaryExt = new BinaryBytecode.ExtOperand[0];
            this.staticDataSegments = List.of();
            return;
        }
        try {
            BinaryBytecode.ProgramImage decoded = BinaryBytecode.decodeScbc(bytecode);
            this.binaryImage = Arrays.copyOf(bytecode, bytecode.length);
            this.binaryCode = decoded.code();
            this.binaryRodata = decoded.rodata();
            this.binaryExt = decoded.ext();
            this.staticDataSegments = decoded.dataSegments();
        } catch (RuntimeException e) {
            this.binaryImage = new byte[0];
            this.binaryCode = new long[0];
            this.binaryRodata = new String[0];
            this.binaryExt = new BinaryBytecode.ExtOperand[0];
            this.staticDataSegments = List.of();
            this.pendingAssemblyError = e.getMessage();
        }
    }

    public void updateHardware(int totalRam, boolean hasNvme, int storageSize) {
        this.hasNvme = hasNvme;
        this.storageSize = storageSize;
        long targetStorageBytes = (long) Math.max(0, storageSize) * 1024L;
        if (this.driveCapacities[0] == 0 && targetStorageBytes > 0) {
            this.driveCapacities[0] = Math.min(1L << 31, targetStorageBytes);
        }
        int effectiveRam = Math.max(0, Math.min(totalRam, 64 * 1024 * 1024));
        if (this.ram.length != effectiveRam) {
            int[] newRam = new int[effectiveRam];
            System.arraycopy(this.ram, 0, newRam, 0, Math.min(this.ram.length, effectiveRam));
            this.ram = newRam;
        }
    }

    private void loadStaticData() {
        if (staticDataSegments == null || staticDataSegments.isEmpty()) {
            return;
        }
        for (BinaryBytecode.DataSegment segment : staticDataSegments) {
            int addr = segment.ramStart();
            byte[] bytes = segment.bytes();
            if (bytes.length == 0 || addr < 0) {
                continue;
            }
            ensureRamCapacity(Math.min(65535, addr + bytes.length - 1));
            for (int i = 0; i < bytes.length; i++) {
                int target = addr + i;
                if (target >= 0 && target < ram.length) {
                    ram[target] = Byte.toUnsignedInt(bytes[i]);
                }
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
        networkPort.reset();
        peripheralBus.reset();
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
        clearPadStates();
        this.keyBuffer.clear();
        loadStaticData();
        this.displayMode = 0;
        Arrays.fill(this.framebuffer, 0xFF051009);
        this.gpu.reset();
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
            if (getDriveCapacity(i) > 0) {
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
            if (this.gpu.isInstalled()) {
                this.gpu.powerOn();
            }
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
            if (this.gpu.isInstalled()) {
                this.gpu.powerOn();
            }
        }
    }

    public void powerOff() {
        synchronized (executionLock) {
            flushLineBuffer();
            this.state = State.OFF;
            this.gpu.powerOff();
            clearPadStates();
            networkPort.reset();
            peripheralBus.reset();
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
            if (gpu.isInstalled()) {
                gpu.tick(gpu.getBatchBudget());
            }
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
                    if (gpu.isInstalled()) {
                        gpu.tick(gpu.getBatchBudget());
                    }
                    return;
                }
            }
            int count = 0;
            long startNanos = System.nanoTime();
            while (state == State.RUNNING && count < maxCycles && !Thread.currentThread().isInterrupted()) {
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
            if (gpu.isInstalled()) {
                gpu.tick(gpu.getBatchBudget());
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

    private int opCount(long word) {
        int fmt = BinaryBytecode.extractFormat(word);
        return switch (fmt) {
            case BinaryBytecode.FMT_0, BinaryBytecode.FMT_3 -> BinaryBytecode.extractFlags(word);
            case BinaryBytecode.FMT_1 -> 2;
            case BinaryBytecode.FMT_2 -> 1;
            default -> 0;
        };
    }

    private int opMode(long word, int idx) {
        int fmt = BinaryBytecode.extractFormat(word);
        return switch (fmt) {
            case BinaryBytecode.FMT_0 -> idx == 0 ? BinaryBytecode.extractFmt0Op0Mode(word)
                    : (idx == 1 ? BinaryBytecode.extractFmt0Op1Mode(word) : BinaryBytecode.MODE_NONE);
            case BinaryBytecode.FMT_1 -> idx == 0 ? BinaryBytecode.extractFmt1Mode0(word)
                    : (idx == 1 ? BinaryBytecode.extractFmt1Mode1(word) : BinaryBytecode.MODE_NONE);
            case BinaryBytecode.FMT_3 -> {
                int extOffset = BinaryBytecode.extractImm32(word);
                int pos = extOffset + idx;
                yield (pos >= 0 && pos < binaryExt.length) ? binaryExt[pos].mode() : BinaryBytecode.MODE_NONE;
            }
            default -> BinaryBytecode.MODE_NONE;
        };
    }

    private int opReg(long word, int idx) {
        int fmt = BinaryBytecode.extractFormat(word);
        return switch (fmt) {
            case BinaryBytecode.FMT_0 -> idx == 0 ? BinaryBytecode.extractFmt0Op0Reg(word)
                    : (idx == 1 ? BinaryBytecode.extractFmt0Op1Reg(word) : 0);
            case BinaryBytecode.FMT_1 -> {
                int mode = idx == 0 ? BinaryBytecode.extractFmt1Mode0(word) : BinaryBytecode.extractFmt1Mode1(word);
                if (mode == BinaryBytecode.MODE_REG || mode == BinaryBytecode.MODE_RAM_REG) {
                    yield idx == 0 ? BinaryBytecode.extractFmt1Val0(word) : BinaryBytecode.extractFmt1Val1(word);
                }
                yield 0;
            }
            case BinaryBytecode.FMT_3 -> {
                int extOffset = BinaryBytecode.extractImm32(word);
                int pos = extOffset + idx;
                yield (pos >= 0 && pos < binaryExt.length) ? binaryExt[pos].reg() : 0;
            }
            default -> 0;
        };
    }

    private int opImm(long word, int idx) {
        int fmt = BinaryBytecode.extractFormat(word);
        return switch (fmt) {
            case BinaryBytecode.FMT_0 -> BinaryBytecode.extractImm32(word);
            case BinaryBytecode.FMT_1 -> idx == 0 ? BinaryBytecode.extractFmt1Val0(word) : BinaryBytecode.extractFmt1Val1(word);
            case BinaryBytecode.FMT_3 -> {
                int extOffset = BinaryBytecode.extractImm32(word);
                int pos = extOffset + idx;
                yield (pos >= 0 && pos < binaryExt.length) ? binaryExt[pos].imm() : 0;
            }
            default -> 0;
        };
    }

    private int readRam(int addr) {
        if (addr >= 0 && addr < ram.length) {
            return ram[addr];
        }
        throw new IndexOutOfBoundsException("RAM address " + addr + " out of bounds");
    }

    private void writeRam(int addr, int val) {
        if (addr >= 0 && addr < ram.length) {
            ram[addr] = val;
            return;
        }
        throw new IndexOutOfBoundsException("RAM write to address " + addr + " out of bounds");
    }

    private int readOperand(long word, int idx) {
        if (idx >= opCount(word)) {
            throw new IllegalArgumentException("Missing operand " + idx);
        }
        int mode = opMode(word, idx);
        int reg = opReg(word, idx);
        int imm = opImm(word, idx);
        return switch (mode) {
            case BinaryBytecode.MODE_REG -> getReg(reg);
            case BinaryBytecode.MODE_IMM -> imm;
            case BinaryBytecode.MODE_RAM_IMM -> readRam(imm);
            case BinaryBytecode.MODE_RAM_REG -> readRam(getReg(reg));
            case BinaryBytecode.MODE_RAM_REG_OFF -> readRam(getReg(reg) + imm);
            default -> throw new IllegalArgumentException("Invalid source operand mode: " + mode);
        };
    }

    private void writeOperand(long word, int idx, int value) {
        if (idx >= opCount(word)) {
            throw new IllegalArgumentException("Missing destination operand " + idx);
        }
        int mode = opMode(word, idx);
        int reg = opReg(word, idx);
        int imm = opImm(word, idx);
        switch (mode) {
            case BinaryBytecode.MODE_REG -> setReg(reg, value);
            case BinaryBytecode.MODE_RAM_IMM -> writeRam(imm, value);
            case BinaryBytecode.MODE_RAM_REG -> writeRam(getReg(reg), value);
            case BinaryBytecode.MODE_RAM_REG_OFF -> writeRam(getReg(reg) + imm, value);
            default -> throw new IllegalArgumentException("Cannot write to destination");
        }
    }

    private void requireOperands(long word, int expected, String message) {
        if (opCount(word) != expected) {
            throw new IllegalArgumentException(message);
        }
    }

    private void executeNextInstruction() {
        checkAndDispatchInterrupt();

        if (pendingAssemblyError != null) {
            String err = pendingAssemblyError;
            pendingAssemblyError = null;
            haltWithException(err, 0, "");
            return;
        }

        if (binaryCode == null || pc < 0 || pc >= binaryCode.length) {
            state = State.HALTED;
            flushLineBuffer();
            terminalOutput.add("[PROGRAM TERMINATED: END OF FILE]");
            return;
        }

        int currentPc = pc;
        long word = binaryCode[pc++];
        cycles++;

        int opcodeId = BinaryBytecode.extractOpcode(word);
        if (opcodeId == BinaryBytecode.OP_NOP) {
            return;
        }

        String opcodeName = BinaryBytecode.getOpcodeName(opcodeId);
        if (opcodeName == null || !supportedInstructions.contains(opcodeName)) {
            String summary = BinaryBytecode.formatInstructionSummary(word);
            triggerException(VEC_ILLEGAL_OPCODE, "Illegal instruction: " + summary, currentPc, summary);
            return;
        }

        try {
            switch (opcodeId) {
                case BinaryBytecode.OP_MOV -> {
                    requireOperands(word, 2, "MOV requires dest, src");
                    writeOperand(word, 0, readOperand(word, 1));
                }
                case BinaryBytecode.OP_ADD -> {
                    requireOperands(word, 2, "ADD requires dest, src");
                    int res = readOperand(word, 0) + readOperand(word, 1);
                    writeOperand(word, 0, res);
                    updateFlags(res);
                }
                case BinaryBytecode.OP_SUB -> {
                    requireOperands(word, 2, "SUB requires dest, src");
                    int res = readOperand(word, 0) - readOperand(word, 1);
                    writeOperand(word, 0, res);
                    updateFlags(res);
                }
                case BinaryBytecode.OP_MUL -> {
                    requireOperands(word, 2, "MUL requires dest, src");
                    int res = readOperand(word, 0) * readOperand(word, 1);
                    writeOperand(word, 0, res);
                    updateFlags(res);
                }
                case BinaryBytecode.OP_DIV -> {
                    requireOperands(word, 2, "DIV requires dest, src");
                    int divisor = readOperand(word, 1);
                    if (divisor == 0) throw new ArithmeticException("Division by zero");
                    int res = readOperand(word, 0) / divisor;
                    writeOperand(word, 0, res);
                    updateFlags(res);
                }
                case BinaryBytecode.OP_MOD -> {
                    requireOperands(word, 2, "MOD requires dest, src");
                    int divisor = readOperand(word, 1);
                    if (divisor == 0) throw new ArithmeticException("Modulo by zero");
                    int res = readOperand(word, 0) % divisor;
                    writeOperand(word, 0, res);
                    updateFlags(res);
                }
                case BinaryBytecode.OP_INC -> {
                    requireOperands(word, 1, "INC requires dest");
                    int res = readOperand(word, 0) + 1;
                    writeOperand(word, 0, res);
                    updateFlags(res);
                }
                case BinaryBytecode.OP_DEC -> {
                    requireOperands(word, 1, "DEC requires dest");
                    int res = readOperand(word, 0) - 1;
                    writeOperand(word, 0, res);
                    updateFlags(res);
                }
                case BinaryBytecode.OP_CMP -> {
                    requireOperands(word, 2, "CMP requires reg, val");
                    updateFlags(readOperand(word, 0) - readOperand(word, 1));
                }
                case BinaryBytecode.OP_AND -> {
                    requireOperands(word, 2, "AND requires dest, src");
                    int res = readOperand(word, 0) & readOperand(word, 1);
                    writeOperand(word, 0, res);
                    updateFlags(res);
                }
                case BinaryBytecode.OP_OR -> {
                    requireOperands(word, 2, "OR requires dest, src");
                    int res = readOperand(word, 0) | readOperand(word, 1);
                    writeOperand(word, 0, res);
                    updateFlags(res);
                }
                case BinaryBytecode.OP_XOR -> {
                    requireOperands(word, 2, "XOR requires dest, src");
                    int res = readOperand(word, 0) ^ readOperand(word, 1);
                    writeOperand(word, 0, res);
                    updateFlags(res);
                }
                case BinaryBytecode.OP_NOT -> {
                    requireOperands(word, 1, "NOT requires dest");
                    int res = ~readOperand(word, 0);
                    writeOperand(word, 0, res);
                    updateFlags(res);
                }
                case BinaryBytecode.OP_SHL -> {
                    requireOperands(word, 2, "SHL requires dest, count");
                    int res = readOperand(word, 0) << readOperand(word, 1);
                    writeOperand(word, 0, res);
                    updateFlags(res);
                }
                case BinaryBytecode.OP_SHR -> {
                    requireOperands(word, 2, "SHR requires dest, count");
                    int res = readOperand(word, 0) >> readOperand(word, 1);
                    writeOperand(word, 0, res);
                    updateFlags(res);
                }
                case BinaryBytecode.OP_JMP -> jumpIf(word, true);
                case BinaryBytecode.OP_JZ -> jumpIf(word, flagZ);
                case BinaryBytecode.OP_JNZ -> jumpIf(word, !flagZ);
                case BinaryBytecode.OP_JS -> jumpIf(word, flagS);
                case BinaryBytecode.OP_JNS -> jumpIf(word, !flagS);
                case BinaryBytecode.OP_JL -> jumpIf(word, flagS && !flagZ);
                case BinaryBytecode.OP_JLE -> jumpIf(word, flagS || flagZ);
                case BinaryBytecode.OP_JG -> jumpIf(word, !flagS && !flagZ);
                case BinaryBytecode.OP_JGE -> jumpIf(word, !flagS);
                case BinaryBytecode.OP_CALL -> {
                    requireOperands(word, 1, "CALL requires target");
                    int destPc = readOperand(word, 0);
                    callStack.push(pc);
                    pc = Math.max(0, Math.min(destPc, binaryCode.length));
                }
                case BinaryBytecode.OP_RET -> execRet();
                case BinaryBytecode.OP_PUSH -> {
                    requireOperands(word, 1, "PUSH requires value");
                    dataStack.push(readOperand(word, 0));
                }
                case BinaryBytecode.OP_POP -> {
                    requireOperands(word, 1, "POP requires dest");
                    if (dataStack.isEmpty()) throw new IllegalStateException("POP on empty stack: Data stack underflow");
                    writeOperand(word, 0, dataStack.pop());
                }
                case BinaryBytecode.OP_IVEC -> {
                    requireOperands(word, 2, "IVEC requires vector, handler");
                    ivt.put(readOperand(word, 0), readOperand(word, 1));
                }
                case BinaryBytecode.OP_IRET -> execIret();
                case BinaryBytecode.OP_CLI -> interruptsEnabled = false;
                case BinaryBytecode.OP_STI -> interruptsEnabled = true;
                case BinaryBytecode.OP_INT -> execInt(word, currentPc);
                case BinaryBytecode.OP_OUT -> execOut(word);
                case BinaryBytecode.OP_OUTH -> execOuth(word);
                case BinaryBytecode.OP_OUTC -> execOutc(word);
                case BinaryBytecode.OP_CLS -> execCls();
                case BinaryBytecode.OP_SLEEP -> {
                    requireOperands(word, 1, "SLEEP requires ticks");
                    int ticks = readOperand(word, 0);
                    if (ticks > 0) this.sleepTicksRemaining = ticks;
                }
                case BinaryBytecode.OP_TIME -> {
                    requireOperands(word, 1, "TIME requires dest");
                    long t = timeSupplier != null ? timeSupplier.getAsLong() : 0L;
                    writeOperand(word, 0, (int) (t & 0x7FFFFFFF));
                }
                case BinaryBytecode.OP_RAND -> {
                    requireOperands(word, 2, "RAND requires dest, max");
                    int max = readOperand(word, 1);
                    writeOperand(word, 0, max > 0 ? random.nextInt(max) : 0);
                }
                case BinaryBytecode.OP_INPAD -> execInpad(word);
                case BinaryBytecode.OP_INAXIS -> execInaxis(word);
                case BinaryBytecode.OP_TIMER -> {
                    requireOperands(word, 1, "TIMER requires hz");
                    setTimerHz(readOperand(word, 0));
                }
                case BinaryBytecode.OP_VBLANK -> execVblank(word);
                case BinaryBytecode.OP_SSIZE -> execSSize(word);
                case BinaryBytecode.OP_SREAD -> execSRead(word);
                case BinaryBytecode.OP_SWRITE -> execSWrite(word);
                case BinaryBytecode.OP_SBLKR -> execSBlkr(word);
                case BinaryBytecode.OP_SBLKW -> execSBlkw(word);
                case BinaryBytecode.OP_SCOUNT -> execSCount(word);
                case BinaryBytecode.OP_SSEL -> execSSel(word);
                case BinaryBytecode.OP_SDRV -> execSDrv(word);
                case BinaryBytecode.OP_SEXEC -> execSExec(word, currentPc);
                case BinaryBytecode.OP_FBMODE -> execFbMode(word);
                case BinaryBytecode.OP_FBSET -> execFbSet(word);
                case BinaryBytecode.OP_FBGET -> execFbGet(word);
                case BinaryBytecode.OP_FBCLR -> execFbClr(word);
                case BinaryBytecode.OP_FBRECT -> execFbRect(word);
                case BinaryBytecode.OP_FBLINE -> execFbLine(word);
                case BinaryBytecode.OP_FBLIT -> execFbLit(word);
                case BinaryBytecode.OP_FBSWAP -> {
                    displayMode = 1;
                    gpu.setDisplayMode(1);
                    if (isUsingGpu()) {
                        gpu.submitSwap();
                    }
                    fbDirty = true;
                }
                case BinaryBytecode.OP_FBWIDTH -> {
                    requireOperands(word, 1, "FBWIDTH requires dest");
                    writeOperand(word, 0, FB_WIDTH);
                }
                case BinaryBytecode.OP_FBHEIGHT -> {
                    requireOperands(word, 1, "FBHEIGHT requires dest");
                    writeOperand(word, 0, FB_HEIGHT);
                }
                case BinaryBytecode.OP_GFXSEL -> execGfxSel(word);
                case BinaryBytecode.OP_GFXSTAT -> execGfxStat(word);
                case BinaryBytecode.OP_GPUCLR -> execGpuClr(word);
                case BinaryBytecode.OP_GPURECT -> execGpuRect(word);
                case BinaryBytecode.OP_GPULINE -> execGpuLine(word);
                case BinaryBytecode.OP_GPUCIRC -> execGpuCirc(word);
                case BinaryBytecode.OP_GPUTRI -> execGpuTri(word);
                case BinaryBytecode.OP_GPUVRAM -> execGpuVram(word);
                case BinaryBytecode.OP_GPUBLIT -> execGpuBlit(word);
                case BinaryBytecode.OP_GPUSWAP -> execGpuSwap();
                case BinaryBytecode.OP_GPUSYNC -> execGpuSync();
                case BinaryBytecode.OP_APUSH -> execAPush(word);
                case BinaryBytecode.OP_ABUFW -> execABufw(word);
                case BinaryBytecode.OP_ACLR -> execAClr();
                case BinaryBytecode.OP_ASTAT -> {
                    requireOperands(word, 1, "ASTAT requires dest");
                    writeOperand(word, 0, audioCount);
                }
                case BinaryBytecode.OP_ATONE -> execATone(word);
                case BinaryBytecode.OP_APLAY -> audioTriggered = true;
                case BinaryBytecode.OP_PINFO -> submitPeripheralRequest(word, PeripheralBus.Operation.INFO);
                case BinaryBytecode.OP_PWRAP -> submitPeripheralRequest(word, PeripheralBus.Operation.WRAP);
                case BinaryBytecode.OP_PCALL -> submitPeripheralRequest(word, PeripheralBus.Operation.CALL);
                case BinaryBytecode.OP_PCALLS -> submitPeripheralRequest(word, PeripheralBus.Operation.CALL_STRING);
                case BinaryBytecode.OP_PSTATUS -> {
                    requireOperands(word, 1, "PSTATUS requires dest");
                    writeOperand(word, 0, peripheralBus.status());
                }
                case BinaryBytecode.OP_PSCAN -> submitPeripheralRequest(word, PeripheralBus.Operation.SCAN);
                case BinaryBytecode.OP_PRESULT -> {
                    requireOperands(word, 1, "PRESULT requires dest");
                    writeOperand(word, 0, peripheralBus.consumeResult());
                }
                case BinaryBytecode.OP_PLEN -> {
                    requireOperands(word, 1, "PLEN requires dest");
                    writeOperand(word, 0, peripheralBus.textLength());
                }
                case BinaryBytecode.OP_PRESULTS -> readPeripheralText(word);
                case BinaryBytecode.OP_NOPEN -> submitNetwork(word, NetworkPort.Operation.OPEN);
                case BinaryBytecode.OP_NSEND -> submitNetwork(word, NetworkPort.Operation.SEND);
                case BinaryBytecode.OP_NRECV -> submitNetwork(word, NetworkPort.Operation.RECEIVE);
                case BinaryBytecode.OP_NCLOSE -> submitNetwork(word, NetworkPort.Operation.CLOSE);
                case BinaryBytecode.OP_NHTTP -> submitNetwork(word, NetworkPort.Operation.HTTP);
                case BinaryBytecode.OP_NWSOPEN -> submitNetwork(word, NetworkPort.Operation.WEBSOCKET);
                case BinaryBytecode.OP_NWSTEXT -> submitNetwork(word, NetworkPort.Operation.SEND_TEXT);
                case BinaryBytecode.OP_NTLS -> submitNetwork(word, NetworkPort.Operation.TLS);
                case BinaryBytecode.OP_NHTTP3 -> submitNetwork(word, NetworkPort.Operation.HTTP3);
                case BinaryBytecode.OP_NRESPOND -> submitNetwork(word, NetworkPort.Operation.RESPOND);
                case BinaryBytecode.OP_NRESPONDT -> submitNetwork(word, NetworkPort.Operation.RESPOND_TEXT);
                case BinaryBytecode.OP_NACCEPT -> submitNetwork(word, NetworkPort.Operation.ACCEPT);
                case BinaryBytecode.OP_NLISTEN -> submitNetwork(word, NetworkPort.Operation.LISTEN);
                case BinaryBytecode.OP_NSTATUS -> {
                    requireOperands(word, 1, "NSTATUS requires dest");
                    writeOperand(word, 0, networkPort.status());
                }
                case BinaryBytecode.OP_NLEN -> {
                    requireOperands(word, 1, "NLEN requires dest");
                    writeOperand(word, 0, networkPort.resultLength());
                }
                case BinaryBytecode.OP_NRESULT -> readNetworkResult(word);
                case BinaryBytecode.OP_NREADY -> {
                    requireOperands(word, 1, "NREADY requires dest");
                    writeOperand(word, 0, networkPort.ready() ? 1 : 0);
                }
                case BinaryBytecode.OP_NUTF8 -> transcodeNetworkText(word);
                case BinaryBytecode.OP_GALLOC -> {
                    if (opCount(word) != 2
                            || (opMode(word, 0) != BinaryBytecode.MODE_REG && opMode(word, 0) != BinaryBytecode.MODE_IMM)
                            || opMode(word, 1) != BinaryBytecode.MODE_IMM) {
                        throw new IllegalArgumentException("GALLOC requires a size and an immediate heap base");
                    }
                    int size = readOperand(word, 0);
                    int base = opImm(word, 1);
                    try {
                        if (size == 0) {
                            GuestHeap.initialize(ram, base);
                            regA = 0;
                        } else {
                            int address = GuestHeap.allocate(ram, base, size,
                                    regA, regB, regC, regD, dataStack, interruptStack);
                            if (address == 0) pc = currentPc; // Retry after an interrupted collection.
                            else regA = address;
                        }
                    } catch (IndexOutOfBoundsException e) {
                        // The generated program cannot resume with a missing allocation.
                        haltWithException(e.getMessage(), currentPc, BinaryBytecode.formatInstructionSummary(word));
                    }
                }
                case BinaryBytecode.OP_HLT -> {
                    state = State.HALTED;
                    flushLineBuffer();
                    terminalOutput.add("[EXECUTION HALTED]");
                }
                default -> {
                    String summary = BinaryBytecode.formatInstructionSummary(word);
                    triggerException(VEC_ILLEGAL_OPCODE, "Unknown opcode: " + summary, currentPc, summary);
                }
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
            triggerException(vec, desc, currentPc, BinaryBytecode.formatInstructionSummary(word));
        }

        if (terminalOutput.size() > 60) {
            terminalOutput.remove(0);
        }
    }

    private void jumpIf(long word, boolean condition) {
        if (!condition) return;
        requireOperands(word, 1, "Jump requires target");
        int destPc = readOperand(word, 0);
        pc = Math.max(0, Math.min(destPc, binaryCode.length));
    }

    private void submitPeripheralRequest(long word, PeripheralBus.Operation operation) {
        boolean stringCall = operation == PeripheralBus.Operation.CALL_STRING;
        boolean call = stringCall || operation == PeripheralBus.Operation.CALL;
        if (opCount(word) != (call ? 5 : 2)) {
            throw new IllegalArgumentException(operation + " requires a destination and side" +
                    (call ? ", method, and two arguments" : ""));
        }
        int side = readOperand(word, 1);
        int method = call ? readOperand(word, 2) : 0;
        int arg0 = call ? readOperand(word, 3) : 0;
        int arg1 = call ? readOperand(word, 4) : 0;
        if (peripheralBus.status() != PeripheralBus.IDLE) {
            writeOperand(word, 0, 0);
            return;
        }
        String text = stringCall ? readGuestString(arg0, 256) : null;
        boolean accepted = peripheralBus.submit(operation, side, method, arg0, arg1, text);
        writeOperand(word, 0, accepted ? 1 : 0);
    }

    private void readPeripheralText(long word) {
        requireOperands(word, 3, "PRESULTS requires dst, address, capacity");
        int address = readOperand(word, 1);
        int capacity = readOperand(word, 2);
        verifyRamBuffer(address, capacity);
        String text = peripheralBus.consumeText();
        int count = Math.min(text.length(), Math.max(0, capacity - 1));
        for (int i = 0; i < count; i++) ram[address + i] = text.charAt(i);
        if (capacity > 0) ram[address + count] = 0;
        writeOperand(word, 0, count);
    }

    private String readGuestString(int address, int limit) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i <= limit; i++) {
            if (address < 0 || address >= ram.length) {
                throw new IndexOutOfBoundsException("Guest string extends outside RAM");
            }
            int value = ram[address++];
            if (value == 0) return text.toString();
            if (value < 0 || value > Character.MAX_VALUE) {
                throw new IllegalArgumentException("Invalid character in guest string");
            }
            text.append((char) value);
        }
        throw new IllegalArgumentException("Guest string exceeds " + limit + " characters");
    }

    private void submitNetwork(long word, NetworkPort.Operation operation) {
        int count = opCount(word);
        int expected = switch (operation) {
            case OPEN, SEND, SEND_TEXT -> 4;
            case RECEIVE, LISTEN -> 3;
            case CLOSE, WEBSOCKET, TLS, ACCEPT -> 2;
            case HTTP, HTTP3, RESPOND, RESPOND_TEXT -> 5;
        };
        boolean withTextFlag = (operation == NetworkPort.Operation.HTTP || operation == NetworkPort.Operation.HTTP3)
                && count == expected + 1;
        if (count != expected && !withTextFlag) {
            throw new IllegalArgumentException(operation + " has an invalid operand count");
        }
        if (networkPort.status() != NetworkPort.IDLE) {
            writeOperand(word, 0, 0);
            return;
        }

        int arg0 = operation == NetworkPort.Operation.WEBSOCKET ? 0 : readOperand(word, 1);
        int arg1 = 0;
        String text = null;
        byte[] data = null;
        switch (operation) {
            case OPEN -> {
                text = readGuestString(readOperand(word, 2), NetworkPort.MAX_TEXT);
                arg1 = readOperand(word, 3);
            }
            case HTTP, HTTP3 -> {
                text = readGuestString(readOperand(word, 2), NetworkPort.MAX_TEXT);
                if (withTextFlag) arg1 = readOperand(word, 5);
                data = copyNetworkBytes(readOperand(word, 3), readOperand(word, 4));
            }
            case WEBSOCKET -> text = readGuestString(readOperand(word, 1), NetworkPort.MAX_TEXT);
            case SEND, SEND_TEXT -> data = copyNetworkBytes(readOperand(word, 2), readOperand(word, 3));
            case RESPOND, RESPOND_TEXT -> {
                arg1 = readOperand(word, 2);
                data = copyNetworkBytes(readOperand(word, 3), readOperand(word, 4));
            }
            case RECEIVE, LISTEN -> arg1 = readOperand(word, 2);
            case CLOSE, TLS, ACCEPT -> {}
        }
        writeOperand(word, 0, networkPort.submit(new NetworkPort.Request(operation, arg0, arg1, text, data)) ? 1 : 0);
    }

    private byte[] copyNetworkBytes(int address, int length) {
        verifyRamBuffer(address, length);
        if (length > networkPort.transferLimit()) throw new IllegalArgumentException("Network payload exceeds transfer limit");
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) ram[address + i];
        return bytes;
    }

    private void readNetworkResult(long word) {
        requireOperands(word, 3, "NRESULT requires dest, ram_dest, length");
        int address = readOperand(word, 1);
        int length = readOperand(word, 2);
        verifyRamBuffer(address, length);
        NetworkPort.Result result = networkPort.takeResult();
        if (result != null) {
            int count = Math.min(length, result.data().length);
            for (int i = 0; i < count; i++) ram[address + i] = Byte.toUnsignedInt(result.data()[i]);
        }
        writeOperand(word, 0, result == null ? 0 : result.value());
    }

    private void transcodeNetworkText(long word) {
        requireOperands(word, 5, "NUTF8 requires dst, source, count, target, capacity");
        int source = readOperand(word, 1);
        int count = readOperand(word, 2);
        int target = readOperand(word, 3);
        int capacity = readOperand(word, 4);
        verifyRamBuffer(target, capacity);
        int limit = networkPort.transferLimit();
        if (count == -1) {
            byte[] bytes;
            try {
                bytes = readGuestString(source, limit).getBytes(StandardCharsets.UTF_8);
            } catch (IllegalArgumentException invalid) {
                writeOperand(word, 0, NetworkPort.LIMIT);
                return;
            }
            if (bytes.length > limit || (capacity != 0 && bytes.length > capacity)) {
                writeOperand(word, 0, NetworkPort.LIMIT);
                return;
            }
            if (capacity != 0) {
                for (int i = 0; i < bytes.length; i++) ram[target + i] = Byte.toUnsignedInt(bytes[i]);
            }
            writeOperand(word, 0, bytes.length);
        } else {
            if (count < 0 || count > limit) {
                writeOperand(word, 0, NetworkPort.LIMIT);
                return;
            }
            String text = new String(copyNetworkBytes(source, count), StandardCharsets.UTF_8);
            if (capacity != 0 && text.length() >= capacity) {
                writeOperand(word, 0, NetworkPort.LIMIT);
                return;
            }
            if (capacity != 0) {
                for (int i = 0; i < text.length(); i++) ram[target + i] = text.charAt(i);
                ram[target + text.length()] = 0;
            }
            writeOperand(word, 0, text.length());
        }
    }

    private void verifyRamBuffer(int address, int length) {
        if (address < 0 || length < 0 || (long) address + length > ram.length) {
            throw new IndexOutOfBoundsException("Buffer extends outside RAM");
        }
    }

    private void execRet() {
        if (callStack.isEmpty()) {
            throw new IllegalStateException("RET without CALL: Call stack underflow");
        }
        pc = callStack.pop();
        if (inInterruptHandler && !interruptStack.isEmpty() && !interruptStack.peek().restoreRegs()) {
            interruptStack.pop();
            inInterruptHandler = !interruptStack.isEmpty();
        }
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

    private void execInt(long word, int currentPc) {
        requireOperands(word, 1, "INT requires vector");
        int vec = readOperand(word, 0);
        if (ivt.containsKey(vec)) {
            interruptStack.push(new CpuContext(pc, regA, regB, regC, regD, flagZ, flagS, false));
            inInterruptHandler = true;
            regA = vec;
            pc = ivt.get(vec);
        } else {
            triggerException(vec, "Software interrupt " + vec, currentPc, BinaryBytecode.formatInstructionSummary(word));
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

        haltWithException(description, lineNum, lineCode);
    }

    private void haltWithException(String description, int lineNum, String lineCode) {
        state = State.ERROR;
        errorMessage = "Exception not handled: " + description + (lineNum >= 0 ? " at line " + lineNum + ": '" + lineCode + "'" : "");
        flushLineBuffer();
        terminalOutput.add("[CPU EXCEPTION] " + errorMessage);
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
        if (keyBuffer.size() < 64) {
            keyBuffer.offer(keyChar);
        }
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

    private void execInpad(long word) {
        int count = opCount(word);
        if (count < 1 || count > 2) {
            throw new IllegalArgumentException("INPAD requires dest [, pad]");
        }
        int padId = count == 2 ? readOperand(word, 1) : 0;
        int padMask = getPadState(padId);
        writeOperand(word, 0, padMask);
    }

    public void setPadState(int padId, int bitmask) {
        setGamepadState(padId, GamepadState.NEUTRAL.withButtons(bitmask));
    }

    public void setGamepadState(int padId, GamepadState input) {
        if (padId >= 0 && padId < padStates.length()) {
            padStates.set(padId, Objects.requireNonNull(input));
        }
    }

    private void clearPadStates() {
        for (int pad = 0; pad < padStates.length(); pad++) padStates.set(pad, GamepadState.NEUTRAL);
    }

    public int getPadAxis(int padId, int axis) {
        if (padId < 0 || padId >= padStates.length()) return 0;
        GamepadState input = padStates.get(padId);
        return input == null ? 0 : input.axis(axis);
    }

    private void execInaxis(long word) {
        requireOperands(word, 3, "INAXIS requires destination, pad, axis");
        int pad = readOperand(word, 1);
        int axis = readOperand(word, 2);
        writeOperand(word, 0, getPadAxis(pad, axis));
    }

    public int getPadState(int padId) {
        if (padId == -1) {
            Integer k = keyBuffer.poll();
            return k != null ? k : -1;
        }
        if (padId == -2) {
            Integer k = keyBuffer.peek();
            return k != null ? k : -1;
        }
        if (padId >= 0 && padId < padStates.length()) {
            GamepadState input = padStates.get(padId);
            return input == null ? 0 : input.buttons();
        }
        return 0;
    }

    private void execVblank(long word) {
        if (opCount(word) == 0) {
            setTimerHz(60);
            return;
        }
        int val = readOperand(word, 0);
        setTimerHz(val > 0 ? 60 : 0);
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

    private void execSSize(long word) {
        int count = opCount(word);
        if (count < 1 || count > 2) {
            throw new IllegalArgumentException("SSIZE requires dest [, drive]");
        }
        int d = count == 2 ? readOperand(word, 1) : selectedDrive;
        long sz = getDriveCapacity(d);
        writeOperand(word, 0, (int) sz);
        flagZ = (sz == 0);
        flagS = false;
    }

    private void execSRead(long word) {
        int count = opCount(word);
        int d = selectedDrive;
        int addr;
        if (count == 3) {
            d = readOperand(word, 1);
            addr = readOperand(word, 2);
        } else if (count == 2) {
            addr = readOperand(word, 1);
        } else {
            throw new IllegalArgumentException("SREAD requires dest, [drive,] addr");
        }
        long cap = getDriveCapacity(d);
        if (addr >= 0 && addr < cap) {
            DriveStorage drive = storageDrives[d];
            if (drive != null) drive.read(addr, storageBuffer, 1);
            int val = drive == null ? 0 : Byte.toUnsignedInt(storageBuffer[0]);
            writeOperand(word, 0, val);
            flagZ = (val == 0);
            flagS = false;
        } else {
            writeOperand(word, 0, 0);
            flagZ = true;
            flagS = true;
        }
    }

    private void execSWrite(long word) {
        int count = opCount(word);
        int d = selectedDrive;
        int addr;
        int val;
        if (count == 3) {
            d = readOperand(word, 0);
            addr = readOperand(word, 1);
            val = readOperand(word, 2);
        } else if (count == 2) {
            addr = readOperand(word, 0);
            val = readOperand(word, 1);
        } else {
            throw new IllegalArgumentException("SWRITE requires [drive,] addr, val");
        }
        long cap = getDriveCapacity(d);
        if (addr >= 0 && addr < cap) {
            storageBuffer[0] = (byte) val;
            writableDrive(d).write(addr, storageBuffer, 1);
            flagZ = false;
            flagS = false;
            notifyDriveWrite(d);
        } else {
            flagZ = true;
            flagS = true;
        }
    }

    private void execSBlkr(long word) {
        int count = opCount(word);
        int rAddr;
        int d = selectedDrive;
        int sAddr;
        int len;
        if (count == 4) {
            rAddr = readOperand(word, 0);
            d = readOperand(word, 1);
            sAddr = readOperand(word, 2);
            len = readOperand(word, 3);
        } else if (count == 3) {
            rAddr = readOperand(word, 0);
            sAddr = readOperand(word, 1);
            len = readOperand(word, 2);
        } else {
            throw new IllegalArgumentException("SBLKR requires ram_dest, [drive,] storage_addr, count");
        }
        transferStorage(d, sAddr, rAddr, len, false);
    }

    private void execSBlkw(long word) {
        int count = opCount(word);
        int d = selectedDrive;
        int sAddr;
        int rAddr;
        int len;
        if (count == 4) {
            d = readOperand(word, 0);
            sAddr = readOperand(word, 1);
            rAddr = readOperand(word, 2);
            len = readOperand(word, 3);
        } else if (count == 3) {
            sAddr = readOperand(word, 0);
            rAddr = readOperand(word, 1);
            len = readOperand(word, 2);
        } else {
            throw new IllegalArgumentException("SBLKW requires [drive,] storage_addr, ram_src, count");
        }
        transferStorage(d, sAddr, rAddr, len, true);
    }

    private void transferStorage(int drive, int storageAddress, int ramAddress, int count, boolean write) {
        long cap = getDriveCapacity(drive);
        long offset = Math.max(0, Math.max(-(long) storageAddress, -(long) ramAddress));
        long end = Math.min(count, Math.min(cap - storageAddress, (long) ram.length - ramAddress));
        flagZ = offset >= end || cap == 0;
        if (flagZ) return;
        DriveStorage backing = write ? writableDrive(drive) : storageDrives[drive];

        while (offset < end && !Thread.currentThread().isInterrupted()) {
            int size = (int) Math.min(storageBuffer.length, end - offset);
            int disk = (int) (storageAddress + offset);
            int memory = (int) (ramAddress + offset);
            if (write) {
                for (int i = 0; i < size; i++) storageBuffer[i] = (byte) ram[memory + i];
                backing.write(disk, storageBuffer, size);
            } else if (backing != null) {
                backing.read(disk, storageBuffer, size);
                for (int i = 0; i < size; i++) ram[memory + i] = Byte.toUnsignedInt(storageBuffer[i]);
            } else {
                Arrays.fill(ram, memory, memory + size, 0);
            }
            offset += size;
        }
        if (write) notifyDriveWrite(drive);
    }

    private DriveStorage writableDrive(int drive) {
        if (storageDrives[drive] == null) storageDrives[drive] = DriveStorage.inMemory(null);
        return storageDrives[drive];
    }

    private void notifyDriveWrite(int drive) {
        if (driveWriteListener != null) driveWriteListener.accept(drive, getStorageDrive(drive));
    }

    private void execSCount(long word) {
        requireOperands(word, 1, "SCOUNT requires dest");
        int count = getStorageDriveCount();
        writeOperand(word, 0, count);
        flagZ = (count == 0);
        flagS = false;
    }

    private void execSSel(long word) {
        requireOperands(word, 1, "SSEL requires drive");
        int d = readOperand(word, 0);
        if (d >= 0 && d < MAX_DRIVES) {
            selectedDrive = d;
            long cap = getDriveCapacity(d);
            flagZ = (cap == 0);
            flagS = false;
        } else {
            flagZ = true;
            flagS = true;
        }
    }

    private void execSDrv(long word) {
        requireOperands(word, 1, "SDRV requires dest");
        writeOperand(word, 0, selectedDrive);
        flagZ = (selectedDrive == 0);
        flagS = false;
    }

    private void execSExec(long word, int currentPc) {
        requireOperands(word, 1, "SEXEC requires drive");
        int drive = readOperand(word, 0);
        String summary = BinaryBytecode.formatInstructionSummary(word);
        if (drive < 0 || drive >= MAX_DRIVES) {
            triggerException(VEC_OUT_OF_BOUNDS, "Drive index " + drive + " out of bounds", currentPc, summary);
            return;
        }

        byte[] targetBinary = driveBinaries.get(drive);
        if (targetBinary == null || targetBinary.length == 0) {
            byte[] bytes = readDrivePrefix(drive, 16 * 1024 * 1024);
            if (BinaryBytecode.isScbc(bytes)) {
                targetBinary = bytes;
            } else if (bytes != null && bytes.length > 0) {
                int len = 0;
                while (len < bytes.length && bytes[len] != 0) {
                    len++;
                }
                if (len > 0) {
                    String src = new String(bytes, 0, len, StandardCharsets.UTF_8).trim();
                    if (!src.isEmpty()) {
                        Assembler.AssemblerResult assembled = Assembler.assemble(Arrays.asList(src.split("\\r?\\n")));
                        if (!assembled.success()) {
                            triggerException(VEC_GENERAL_EXCEPTION, "Drive assembly failed: " + String.join("; ", assembled.errors()), currentPc, summary);
                            return;
                        }
                        targetBinary = assembled.bytecode();
                    }
                }
            }
        }

        if (targetBinary == null || targetBinary.length == 0) {
            triggerException(VEC_GENERAL_EXCEPTION, "No executable program on storage drive " + drive, currentPc, summary);
            return;
        }

        Set<String> req = BinaryBytecode.getRequiredInstructions(targetBinary);
        List<String> missing = InstructionValidator.getMissingInstructions(req, new ArrayList<>(supportedInstructions));
        if (!missing.isEmpty()) {
            triggerException(VEC_ILLEGAL_OPCODE, "Target drive program uses unsupported instructions: " + missing, currentPc, summary);
            return;
        }

        networkPort.reset();
        peripheralBus.reset();
        applyBinaryImage(targetBinary);
        loadStaticData();
        this.callStack.clear();
        this.pc = 0;
        this.selectedDrive = drive;
        this.flagZ = false;
        this.flagS = false;
        flushLineBuffer();
    }

    // --- Framebuffer Instructions ---
    private void execFbMode(long word) {
        requireOperands(word, 1, "FBMODE requires mode");
        int mode = readOperand(word, 0);
        displayMode = mode;
        gpu.setDisplayMode(mode);
        fbDirty = true;
    }

    private void execFbSet(long word) {
        requireOperands(word, 3, "FBSET requires x, y, color");
        int x = readOperand(word, 0);
        int y = readOperand(word, 1);
        int color = readOperand(word, 2);
        if (isUsingGpu()) {
            gpu.submitSetPixel(x, y, color);
            return;
        }
        if ((color & 0xFF000000) == 0) color |= 0xFF000000;
        if (x >= 0 && x < FB_WIDTH && y >= 0 && y < FB_HEIGHT) {
            framebuffer[y * FB_WIDTH + x] = color;
            fbDirty = true;
        }
    }

    private void execFbGet(long word) {
        requireOperands(word, 3, "FBGET requires dest, x, y");
        int x = readOperand(word, 1);
        int y = readOperand(word, 2);
        int color = 0;
        if (isUsingGpu()) {
            color = gpu.getBackPixel(x, y);
        } else if (x >= 0 && x < FB_WIDTH && y >= 0 && y < FB_HEIGHT) {
            color = framebuffer[y * FB_WIDTH + x];
        }
        writeOperand(word, 0, color);
    }

    private void execFbClr(long word) {
        requireOperands(word, 1, "FBCLR requires color");
        int color = readOperand(word, 0);
        if (isUsingGpu()) {
            gpu.submitClear(color);
            return;
        }
        if ((color & 0xFF000000) == 0) color |= 0xFF000000;
        Arrays.fill(framebuffer, color);
        fbDirty = true;
    }

    private void execFbRect(long word) {
        requireOperands(word, 5, "FBRECT requires x, y, w, h, color");
        int rx = readOperand(word, 0);
        int ry = readOperand(word, 1);
        int rw = readOperand(word, 2);
        int rh = readOperand(word, 3);
        int color = readOperand(word, 4);
        if (isUsingGpu()) {
            gpu.submitFillRect(rx, ry, rw, rh, color);
            return;
        }
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

    private void execFbLine(long word) {
        requireOperands(word, 5, "FBLINE requires x1, y1, x2, y2, color");
        int x0 = readOperand(word, 0);
        int y0 = readOperand(word, 1);
        int x1 = readOperand(word, 2);
        int y1 = readOperand(word, 3);
        int color = readOperand(word, 4);
        if (isUsingGpu()) {
            gpu.submitDrawLine(x0, y0, x1, y1, color);
            return;
        }
        if ((color & 0xFF000000) == 0) color |= 0xFF000000;

        long dx = Math.abs((long) x1 - x0);
        long dy = Math.abs((long) y1 - y0);
        boolean xMajor = dx >= dy;
        long majorLength = xMajor ? dx : dy;
        long minorLength = xMajor ? dy : dx;
        long majorStart = xMajor ? x0 : y0;
        long minorStart = xMajor ? y0 : x0;
        int majorDirection = (xMajor ? x0 <= x1 : y0 <= y1) ? 1 : -1;
        int minorDirection = (xMajor ? y0 <= y1 : x0 <= x1) ? 1 : -1;
        int majorLimit = (xMajor ? FB_WIDTH : FB_HEIGHT) - 1;

        long firstStep = Math.max(0, majorDirection > 0 ? -majorStart : majorStart - majorLimit);
        long lastStep = Math.min(majorLength, majorDirection > 0 ? majorLimit - majorStart : majorStart);
        for (long step = firstStep; step <= lastStep; step++) {
            long minorStep = majorLength == 0 ? 0
                    : Long.divideUnsigned(step * minorLength + (majorLength - 1) / 2, majorLength);
            long major = majorStart + step * majorDirection;
            long minor = minorStart + minorStep * minorDirection;
            long x = xMajor ? major : minor;
            long y = xMajor ? minor : major;
            if (x >= 0 && x < FB_WIDTH && y >= 0 && y < FB_HEIGHT) {
                framebuffer[(int) y * FB_WIDTH + (int) x] = color;
            }
        }
        fbDirty = true;
    }

    private void execFbLit(long word) {
        requireOperands(word, 5, "FBLIT requires x, y, ram_src, w, h");
        int dx = readOperand(word, 0);
        int dy = readOperand(word, 1);
        int ramSrc = readOperand(word, 2);
        int bw = readOperand(word, 3);
        int bh = readOperand(word, 4);
        if (isUsingGpu()) {
            gpu.submitBlitFromRam(dx, dy, ram, ramSrc, bw, bh, false);
            return;
        }

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

    // --- GPU & Graphics Handler Instructions ---
    private void requireGpuInstalled() {
        if (!gpu.isInstalled()) {
            throw new IllegalStateException("GPU hardware instruction requires an installed GPU");
        }
    }

    private void requireGpuFeature(String feature) {
        requireGpuInstalled();
        if (!gpu.supportsFeature(feature)) {
            throw new IllegalStateException("GPU feature not supported by hardware: " + feature);
        }
    }

    private void execGfxSel(long word) {
        requireOperands(word, 1, "GFXSEL requires mode");
        int mode = readOperand(word, 0);
        GraphicsHandler target = switch (mode) {
            case 1 -> GraphicsHandler.CPU;
            case 2 -> GraphicsHandler.GPU;
            default -> GraphicsHandler.AUTO;
        };
        setGraphicsHandler(target);
        flagZ = !isUsingGpu();
        flagS = false;
    }

    private void execGfxStat(long word) {
        int count = opCount(word);
        if (count < 1 || count > 2) {
            throw new IllegalArgumentException("GFXSTAT requires dest [, query]");
        }
        int query = count == 2 ? readOperand(word, 1) : 0;
        int result = switch (query) {
            case 0 -> gpu.isInstalled() ? 1 : 0;
            case 1 -> isUsingGpu() ? 1 : 0;
            case 2 -> graphicsHandler.ordinal();
            case 3 -> gpu.getVramCapacityBytes();
            case 4 -> gpu.getVramCapacityBytes() / 1024;
            case 5 -> (int) gpu.getCommandsExecuted();
            case 6 -> gpu.getComputeUnits();
            case 7 -> gpu.isBusy() ? 1 : 0;
            case 8 -> gpu.getFramesPresented();
            default -> 0;
        };
        writeOperand(word, 0, result);
        flagZ = (result == 0);
        flagS = (result < 0);
    }

    private void execGpuClr(long word) {
        requireGpuFeature("GPUCLR");
        requireOperands(word, 1, "GPUCLR requires color");
        int color = readOperand(word, 0);
        gpu.submitClear(color);
    }

    private void execGpuRect(long word) {
        requireGpuFeature("GPURECT");
        requireOperands(word, 5, "GPURECT requires x, y, w, h, color");
        gpu.submitFillRect(
                readOperand(word, 0),
                readOperand(word, 1),
                readOperand(word, 2),
                readOperand(word, 3),
                readOperand(word, 4)
        );
    }

    private void execGpuLine(long word) {
        requireGpuFeature("GPULINE");
        requireOperands(word, 5, "GPULINE requires x1, y1, x2, y2, color");
        gpu.submitDrawLine(
                readOperand(word, 0),
                readOperand(word, 1),
                readOperand(word, 2),
                readOperand(word, 3),
                readOperand(word, 4)
        );
    }

    private void execGpuCirc(long word) {
        requireGpuFeature("GPUCIRC");
        int count = opCount(word);
        if (count < 4 || count > 5) {
            throw new IllegalArgumentException("GPUCIRC requires cx, cy, r, color [, filled]");
        }
        int cx = readOperand(word, 0);
        int cy = readOperand(word, 1);
        int r = readOperand(word, 2);
        int color = readOperand(word, 3);
        boolean filled = count < 5 || readOperand(word, 4) != 0;
        gpu.submitDrawCircle(cx, cy, r, color, filled);
    }

    private void execGpuTri(long word) {
        requireGpuFeature("GPUTRI");
        requireOperands(word, 1, "GPUTRI requires addr");
        int addr = readOperand(word, 0);
        verifyRamBuffer(addr, 7);
        gpu.submitFillTriangle(
                ram[addr],
                ram[addr + 1],
                ram[addr + 2],
                ram[addr + 3],
                ram[addr + 4],
                ram[addr + 5],
                ram[addr + 6]
        );
    }

    private void execGpuVram(long word) {
        requireGpuFeature("GPUVRAM");
        requireOperands(word, 3, "GPUVRAM requires vram_offset, ram_src, count");
        int vramOffset = readOperand(word, 0);
        int ramSrc = readOperand(word, 1);
        int count = readOperand(word, 2);
        gpu.uploadVram(vramOffset, ram, ramSrc, count);
    }

    private void execGpuBlit(long word) {
        requireGpuFeature("GPUBLIT");
        requireOperands(word, 5, "GPUBLIT requires x, y, vram_offset, w, h");
        gpu.submitBlitFromVram(
                readOperand(word, 0),
                readOperand(word, 1),
                readOperand(word, 2),
                readOperand(word, 3),
                readOperand(word, 4),
                true
        );
    }

    private void execGpuSwap() {
        requireGpuFeature("GPUSWAP");
        displayMode = 1;
        gpu.setDisplayMode(1);
        gpu.submitSwap();
        fbDirty = true;
    }

    private void execGpuSync() {
        requireGpuFeature("GPUSYNC");
        gpu.flush();
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

    private synchronized void execAPush(long word) {
        requireOperands(word, 1, "APUSH requires sample");
        int sample = readOperand(word, 0) & 0xFF;
        ensureAudioQueueCapacity(audioCount + 1);
        audioQueue[audioTail] = (byte) sample;
        audioTail = (audioTail + 1) % audioQueue.length;
        audioCount++;
    }

    private synchronized void execABufw(long word) {
        requireOperands(word, 2, "ABUFW requires ram_src, count");
        int ramSrc = readOperand(word, 0);
        int count = readOperand(word, 1);
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

    private synchronized void execATone(long word) {
        requireOperands(word, 2, "ATONE requires freq, duration_ms");
        int freq = readOperand(word, 0);
        int ms = readOperand(word, 1);
        if (ms > 0) {
            int numSamples = (int) ((8000L * ms) / 1000L);
            ensureAudioQueueCapacity(audioCount + numSamples);
            if (freq <= 0) {
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
    public void setDisplayMode(int mode) {
        this.displayMode = mode;
        this.gpu.setDisplayMode(mode);
    }

    public VirtualGpu getGpu() { return gpu; }

    public void configureGpu(GpuData gpuData) {
        if (gpu.isSameConfig(gpuData)) {
            if (state == State.RUNNING && gpu.isInstalled() && gpu.getState() == VirtualGpu.State.OFF) {
                gpu.powerOn();
            } else if (!gpu.isInstalled() && gpu.getState() != VirtualGpu.State.OFF) {
                gpu.powerOff();
            }
            return;
        }
        gpu.configure(gpuData);
        if (state == State.RUNNING && gpu.isInstalled()) {
            gpu.powerOn();
        } else if (!gpu.isInstalled()) {
            gpu.powerOff();
        }
        fbDirty = true;
    }

    public void configureGpu(boolean present, String archName, int vramBytes, int computeUnits) {
        if (gpu.isSameConfigHardware(present, archName, vramBytes, computeUnits)) {
            if (state == State.RUNNING && gpu.isInstalled() && gpu.getState() == VirtualGpu.State.OFF) {
                gpu.powerOn();
            }
            return;
        }
        gpu.configureHardware(present, archName, vramBytes, computeUnits);
        if (state == State.RUNNING && gpu.isInstalled()) {
            gpu.powerOn();
        } else if (!gpu.isInstalled()) {
            gpu.powerOff();
        }
        fbDirty = true;
    }

    public GraphicsHandler getGraphicsHandler() { return graphicsHandler; }

    public void setGraphicsHandler(GraphicsHandler handler) {
        this.graphicsHandler = (handler == null) ? GraphicsHandler.AUTO : handler;
        this.fbDirty = true;
        this.gpu.setFbDirty(true);
    }

    public void cycleGraphicsHandler() {
        GraphicsHandler next = switch (graphicsHandler) {
            case AUTO -> GraphicsHandler.CPU;
            case CPU -> gpu.isInstalled() ? GraphicsHandler.GPU : GraphicsHandler.AUTO;
            case GPU -> GraphicsHandler.AUTO;
        };
        setGraphicsHandler(next);
    }

    public boolean isUsingGpu() {
        if (!gpu.isInstalled()) return false;
        return graphicsHandler == GraphicsHandler.AUTO || graphicsHandler == GraphicsHandler.GPU;
    }

    public int[] getActiveFramebuffer() {
        if (isUsingGpu()) {
            gpu.flush();
            return gpu.getFrontBuffer();
        }
        return framebuffer;
    }

    public boolean isAnyFbDirty() {
        return fbDirty || (isUsingGpu() && gpu.isFbDirty());
    }

    public void clearAllFbDirty() {
        fbDirty = false;
        gpu.setFbDirty(false);
    }

    private boolean isNewlineFlag(long word) {
        int fmt = BinaryBytecode.extractFormat(word);
        return switch (fmt) {
            case BinaryBytecode.FMT_2 -> (BinaryBytecode.extractFlags(word) & 1) != 0;
            case BinaryBytecode.FMT_0 -> (BinaryBytecode.extractFmt0Aux(word) & 1) != 0;
            case BinaryBytecode.FMT_3 -> (BinaryBytecode.extractFmt3Aux(word) & 1) != 0;
            default -> false;
        };
    }

    private String readRodata(int idx) {
        if (idx < 0 || idx >= binaryRodata.length) {
            throw new IllegalArgumentException("Invalid .rodata index: " + idx);
        }
        return binaryRodata[idx];
    }

    private void execOut(long word) {
        boolean newline = isNewlineFlag(word);
        if (BinaryBytecode.extractFormat(word) == BinaryBytecode.FMT_2) {
            lineBuffer.append(readRodata(BinaryBytecode.extractImm32(word)));
        } else if (opCount(word) > 0) {
            lineBuffer.append(readOperand(word, 0));
        }
        if (newline) {
            flushLineBuffer();
        }
    }

    private void execOuth(long word) {
        boolean newline = isNewlineFlag(word);
        if (BinaryBytecode.extractFormat(word) == BinaryBytecode.FMT_2) {
            lineBuffer.append(readRodata(BinaryBytecode.extractImm32(word)));
        } else if (opCount(word) > 0) {
            int val = readOperand(word, 0);
            lineBuffer.append("0x").append(Integer.toHexString(val).toUpperCase(Locale.ROOT));
        }
        if (newline) {
            flushLineBuffer();
        }
    }

    private void execOutc(long word) {
        if (BinaryBytecode.extractFormat(word) == BinaryBytecode.FMT_2) {
            String s = readRodata(BinaryBytecode.extractImm32(word));
            for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
                emitCharByte(b & 0xFF);
            }
            return;
        }
        requireOperands(word, 1, "OUTC requires character");
        emitCharByte(readOperand(word, 0));
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

    private void updateFlags(int val) {
        flagZ = (val == 0);
        flagS = (val < 0);
    }

    // Getters
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
    public int getPc() { return pc; }
    public void setPc(int pc) { this.pc = pc; }
    public int getRegA() { return regA; }
    public void setRegA(int regA) { this.regA = regA; }
    public int getRegB() { return regB; }
    public void setRegB(int regB) { this.regB = regB; }
    public int getRegC() { return regC; }
    public void setRegC(int regC) { this.regC = regC; }
    public int getRegD() { return regD; }
    public void setRegD(int regD) { this.regD = regD; }
    public boolean isFlagZ() { return flagZ; }
    public void setFlagZ(boolean flagZ) { this.flagZ = flagZ; }
    public boolean isFlagS() { return flagS; }
    public void setFlagS(boolean flagS) { this.flagS = flagS; }
    public int getCycles() { return cycles; }
    public void setCycles(int cycles) { this.cycles = cycles; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Deque<Integer> getCallStack() { return callStack; }
    public void setCallStack(Collection<Integer> stack) {
        this.callStack.clear();
        if (stack != null) this.callStack.addAll(stack);
    }

    public Deque<Integer> getDataStack() { return dataStack; }
    public void setDataStack(Collection<Integer> stack) {
        this.dataStack.clear();
        if (stack != null) this.dataStack.addAll(stack);
    }

    public Deque<CpuContext> getInterruptStack() { return interruptStack; }
    public void setInterruptStack(Collection<CpuContext> stack) {
        this.interruptStack.clear();
        if (stack != null) this.interruptStack.addAll(stack);
    }

    public void setIvt(Map<Integer, Integer> table) {
        this.ivt.clear();
        if (table != null) this.ivt.putAll(table);
    }

    public void setInterruptsEnabled(boolean enabled) { this.interruptsEnabled = enabled; }

    public void setInInterruptHandler(boolean inHandler) { this.inInterruptHandler = inHandler; }

    public int getTimerIntervalCycles() { return timerIntervalCycles; }
    public void setTimerIntervalCycles(int cycles) { this.timerIntervalCycles = cycles; }

    public void setSupportedInstructions(Collection<String> instructions) {
        this.supportedInstructions.clear();
        if (instructions != null) {
            for (String instr : instructions) {
                this.supportedInstructions.add(instr.toUpperCase(Locale.ROOT).trim());
            }
        }
    }

    public void loadBinaryImage(byte[] scbcBytes) {
        networkPort.reset();
        peripheralBus.reset();
        applyBinaryImage(scbcBytes);
    }

    public void loadProgramImage(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            loadBinaryImage(new byte[0]);
            return;
        }
        var assembled = games.jvrcruz.siliconcraft.compiler.Assembler.assemble(lines);
        if (!assembled.success()) {
            loadBinaryImage(new byte[0]);
            this.pendingAssemblyError = String.join("; ", assembled.errors());
            return;
        }
        loadBinaryImage(assembled.bytecode());
    }

    private static final int SPARSE_RAM_MAGIC = -1; // 0xFFFFFFFF distinguishes sparse format from legacy positive length
    private static final int SPARSE_ZERO_GAP_THRESHOLD = 16;

    public byte[] getCompressedRam() {
        if (ram == null || ram.length == 0) return new byte[0];

        // Count non-zero runs separated by at least SPARSE_ZERO_GAP_THRESHOLD zero words
        int chunkCount = 0;
        int i = 0;
        final int len = ram.length;
        while (i < len) {
            while (i < len && ram[i] == 0) i++;
            if (i >= len) break;
            chunkCount++;
            while (i < len) {
                if (ram[i] != 0) {
                    i++;
                } else {
                    int zeroStart = i;
                    while (i < len && ram[i] == 0 && (i - zeroStart) < SPARSE_ZERO_GAP_THRESHOLD) {
                        i++;
                    }
                    if (i - zeroStart >= SPARSE_ZERO_GAP_THRESHOLD || i >= len) {
                        break;
                    }
                }
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(65536, 32 + chunkCount * 64));
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baos, deflater, 32 * 1024)) {
            byte[] buf = new byte[32 * 1024];
            ByteBuffer bb = ByteBuffer.wrap(buf);

            // Header: [SPARSE_RAM_MAGIC (-1), totalRamLength, chunkCount]
            bb.putInt(SPARSE_RAM_MAGIC);
            bb.putInt(len);
            bb.putInt(chunkCount);

            i = 0;
            while (i < len) {
                while (i < len && ram[i] == 0) i++;
                if (i >= len) break;
                int runStart = i;
                int lastNonZero = i;
                while (i < len) {
                    if (ram[i] != 0) {
                        lastNonZero = i;
                        i++;
                    } else {
                        int zeroStart = i;
                        while (i < len && ram[i] == 0 && (i - zeroStart) < SPARSE_ZERO_GAP_THRESHOLD) {
                            i++;
                        }
                        if (i - zeroStart >= SPARSE_ZERO_GAP_THRESHOLD || i >= len) {
                            break;
                        }
                    }
                }
                int runLen = lastNonZero - runStart + 1;

                // Ensure space in buffer for chunk header (8 bytes)
                if (bb.remaining() < 8) {
                    dos.write(buf, 0, bb.position());
                    bb.clear();
                }
                bb.putInt(runStart);
                bb.putInt(runLen);

                int offset = runStart;
                int remaining = runLen;
                while (remaining > 0) {
                    if (bb.remaining() < 4) {
                        dos.write(buf, 0, bb.position());
                        bb.clear();
                    }
                    int batch = Math.min(remaining, bb.remaining() >> 2);
                    bb.asIntBuffer().put(ram, offset, batch);
                    bb.position(bb.position() + (batch << 2));
                    offset += batch;
                    remaining -= batch;
                }
            }

            if (bb.position() > 0) {
                dos.write(buf, 0, bb.position());
            }
        } catch (IOException e) {
            return new byte[0];
        } finally {
            deflater.end();
        }
        return baos.toByteArray();
    }

    public void loadCompressedRam(byte[] data) {
        if (data == null || data.length == 0) return;
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(data)), 32 * 1024))) {
            int firstWord = dis.readInt();
            if (firstWord == SPARSE_RAM_MAGIC) {
                int len = dis.readInt();
                int chunkCount = dis.readInt();
                if (len > 0 && len <= 64 * 1024 * 1024 && chunkCount >= 0) {
                    int targetLen = Math.max(this.ram.length, len);
                    if (this.ram.length != targetLen) {
                        this.ram = new int[targetLen];
                    } else {
                        Arrays.fill(this.ram, 0);
                    }
                    byte[] buf = new byte[32 * 1024];
                    ByteBuffer bb = ByteBuffer.wrap(buf);
                    for (int c = 0; c < chunkCount; c++) {
                        int start = dis.readInt();
                        int runLen = dis.readInt();
                        if (start < 0 || runLen < 0 || (long) start + runLen > this.ram.length) {
                            break;
                        }
                        int offset = start;
                        int remaining = runLen;
                        while (remaining > 0) {
                            int batch = Math.min(remaining, buf.length >> 2);
                            dis.readFully(buf, 0, batch << 2);
                            bb.position(0).limit(batch << 2);
                            bb.asIntBuffer().get(this.ram, offset, batch);
                            offset += batch;
                            remaining -= batch;
                        }
                    }
                }
            } else {
                // Legacy dense format backward compatibility: firstWord is ram.length
                int len = firstWord;
                if (len > 0 && len <= 64 * 1024 * 1024) {
                    int targetLen = Math.max(this.ram.length, len);
                    if (this.ram.length != targetLen) {
                        this.ram = new int[targetLen];
                    } else {
                        Arrays.fill(this.ram, 0);
                    }
                    for (int i = 0; i < len; i++) {
                        this.ram[i] = dis.readInt();
                    }
                }
            }
        } catch (IOException ignored) {}
    }

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
    public byte[] getBinaryImage() { return binaryImage; }
    public int getProgramInstructionCount() { return binaryCode.length; }
    public boolean hasProgram() { return binaryImage.length > 0; }
    public int[] getRam() { return ram; }
    public boolean hasNvme() { return hasNvme; }
    public int getStorageSize() { return storageSize; }
    public byte[] getStorage() {
        if (selectedDrive >= 0 && selectedDrive < MAX_DRIVES && storageDrives[selectedDrive] != null
                && storageDrives[selectedDrive].length() > 0) {
            return getStorageDrive(selectedDrive);
        }
        for (int i = 0; i < MAX_DRIVES; i++) {
            if (storageDrives[i] != null && storageDrives[i].length() > 0) return getStorageDrive(i);
        }
        return new byte[0];
    }

    public void setStorage(byte[] data) {
        int targetDrive = selectedDrive >= 0 && selectedDrive < MAX_DRIVES ? selectedDrive : 0;
        setStorageDrive(targetDrive, data);
        notifyDriveWrite(targetDrive);
    }

    public void setDriveCapacity(int drive, long capacityBytes) {
        if (drive >= 0 && drive < MAX_DRIVES) {
            driveCapacities[drive] = Math.clamp(capacityBytes, 0L, 1L << 31);
        }
    }

    public long getDriveCapacity(int drive) {
        if (drive < 0 || drive >= MAX_DRIVES) return 0;
        if (driveCapacities[drive] > 0) return driveCapacities[drive];
        return storageDrives[drive] == null ? 0 : storageDrives[drive].length();
    }

    public void setStorageDrive(int drive, byte[] data) {
        if (drive < 0 || drive >= MAX_DRIVES) return;
        storageDrives[drive] = data == null || data.length == 0 ? null : DriveStorage.inMemory(data);
        if (data != null) driveCapacities[drive] = Math.max(driveCapacities[drive], data.length);
    }

    public void attachDrive(int drive, DriveStorage backing, long capacityBytes) {
        storageDrives[drive] = backing;
        setDriveCapacity(drive, capacityBytes);
    }

    public byte[] getStorageDrive(int drive) {
        return readDrivePrefix(drive, 16 * 1024 * 1024);
    }

    private byte[] readDrivePrefix(int drive, int limit) {
        if (drive < 0 || drive >= MAX_DRIVES || storageDrives[drive] == null) return new byte[0];
        DriveStorage backing = storageDrives[drive];
        byte[] bytes = new byte[(int) Math.min(backing.length(), limit)];
        backing.read(0, bytes, bytes.length);
        return bytes;
    }

    public int getStorageDriveCount() {
        int count = 0;
        for (int i = 0; i < MAX_DRIVES; i++) {
            if (getDriveCapacity(i) > 0) {
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

    public void setDriveBinary(int drive, byte[] bytecode) {
        if (bytecode != null && bytecode.length > 0) {
            this.driveBinaries.put(drive, Arrays.copyOf(bytecode, bytecode.length));
        } else {
            this.driveBinaries.remove(drive);
        }
    }

    public byte[] getDriveBinary(int drive) {
        return this.driveBinaries.getOrDefault(drive, new byte[0]);
    }

    public void setDriveProgram(int drive, List<String> lines) {
        if (lines != null && !lines.isEmpty()) {
            var assembled = games.jvrcruz.siliconcraft.compiler.Assembler.assemble(lines);
            if (assembled.success() && assembled.bytecode().length > 0) {
                this.driveBinaries.put(drive, assembled.bytecode());
            } else {
                this.driveBinaries.remove(drive);
            }
        } else {
            this.driveBinaries.remove(drive);
        }
    }
}
