package games.jvrcruz.siliconcraft.vm;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Canonical 64-bit binary bytecode format ({@code SCBC} v1) for SiliconCraft VirtualCpu.
 * <p>
 * Every instruction occupies exactly one 64-bit word (little-endian). Multi-operand
 * instructions (&gt;2 operands or dual 32-bit immediates) store descriptors in {@code .ext},
 * and string literals for {@code OUT}/{@code OUTH}/{@code OUTC} reference UTF-8 entries in {@code .rodata}.
 */
public final class BinaryBytecode {
    public static final byte[] MAGIC = {'S', 'C', 'B', 'C'};
    public static final int VERSION = 1;

    // Canonical opcode IDs (0 = unknown, 1..112 = ISA opcodes)
    public static final int OP_UNKNOWN = 0;
    public static final int OP_NOP = 1;
    public static final int OP_MOV = 2;
    public static final int OP_ADD = 3;
    public static final int OP_SUB = 4;
    public static final int OP_MUL = 5;
    public static final int OP_DIV = 6;
    public static final int OP_MOD = 7;
    public static final int OP_INC = 8;
    public static final int OP_DEC = 9;
    public static final int OP_CMP = 10;
    public static final int OP_AND = 11;
    public static final int OP_OR = 12;
    public static final int OP_XOR = 13;
    public static final int OP_NOT = 14;
    public static final int OP_SHL = 15;
    public static final int OP_SHR = 16;
    public static final int OP_JMP = 17;
    public static final int OP_JZ = 18;
    public static final int OP_JNZ = 19;
    public static final int OP_JS = 20;
    public static final int OP_JNS = 21;
    public static final int OP_JL = 22;
    public static final int OP_JLE = 23;
    public static final int OP_JG = 24;
    public static final int OP_JGE = 25;
    public static final int OP_CALL = 26;
    public static final int OP_RET = 27;
    public static final int OP_PUSH = 28;
    public static final int OP_POP = 29;
    public static final int OP_IVEC = 30;
    public static final int OP_IRET = 31;
    public static final int OP_CLI = 32;
    public static final int OP_STI = 33;
    public static final int OP_INT = 34;
    public static final int OP_OUT = 35;
    public static final int OP_OUTH = 36;
    public static final int OP_OUTC = 37;
    public static final int OP_CLS = 38;
    public static final int OP_SLEEP = 39;
    public static final int OP_TIME = 40;
    public static final int OP_RAND = 41;
    public static final int OP_INPAD = 42;
    public static final int OP_TIMER = 43;
    public static final int OP_VBLANK = 44;
    public static final int OP_SSIZE = 45;
    public static final int OP_SREAD = 46;
    public static final int OP_SWRITE = 47;
    public static final int OP_SBLKR = 48;
    public static final int OP_SBLKW = 49;
    public static final int OP_SCOUNT = 50;
    public static final int OP_SSEL = 51;
    public static final int OP_SDRV = 52;
    public static final int OP_SEXEC = 53;
    public static final int OP_FBMODE = 54;
    public static final int OP_FBSET = 55;
    public static final int OP_FBGET = 56;
    public static final int OP_FBCLR = 57;
    public static final int OP_FBRECT = 58;
    public static final int OP_FBLINE = 59;
    public static final int OP_FBLIT = 60;
    public static final int OP_FBSWAP = 61;
    public static final int OP_FBWIDTH = 62;
    public static final int OP_FBHEIGHT = 63;
    public static final int OP_APUSH = 64;
    public static final int OP_ABUFW = 65;
    public static final int OP_ACLR = 66;
    public static final int OP_ASTAT = 67;
    public static final int OP_ATONE = 68;
    public static final int OP_APLAY = 69;
    public static final int OP_HLT = 70;
    // 71 reserved (legacy directive marker)
    public static final int OP_PINFO = 72;
    public static final int OP_PWRAP = 73;
    public static final int OP_PCALL = 74;
    public static final int OP_PSTATUS = 75;
    public static final int OP_PRESULT = 76;
    public static final int OP_PCALLS = 77;
    public static final int OP_PSCAN = 78;
    public static final int OP_NOPEN = 79;
    public static final int OP_NSEND = 80;
    public static final int OP_NRECV = 81;
    public static final int OP_NCLOSE = 82;
    public static final int OP_NHTTP = 83;
    public static final int OP_NSTATUS = 84;
    public static final int OP_NLEN = 85;
    public static final int OP_NRESULT = 86;
    public static final int OP_GALLOC = 87;
    public static final int OP_NWSOPEN = 88;
    public static final int OP_NWSTEXT = 89;
    public static final int OP_NTLS = 90;
    public static final int OP_NHTTP3 = 91;
    // 92 reserved
    public static final int OP_NLISTEN = 93;
    public static final int OP_NACCEPT = 94;
    public static final int OP_NRESPOND = 95;
    public static final int OP_NREADY = 96;
    public static final int OP_NUTF8 = 97;
    public static final int OP_NRESPONDT = 98;
    public static final int OP_PLEN = 99;
    public static final int OP_PRESULTS = 100;
    public static final int OP_INAXIS = 101;
    public static final int OP_GFXSEL = 102;
    public static final int OP_GFXSTAT = 103;
    public static final int OP_GPUCLR = 104;
    public static final int OP_GPURECT = 105;
    public static final int OP_GPULINE = 106;
    public static final int OP_GPUCIRC = 107;
    public static final int OP_GPUTRI = 108;
    public static final int OP_GPUVRAM = 109;
    public static final int OP_GPUBLIT = 110;
    public static final int OP_GPUSWAP = 111;
    public static final int OP_GPUSYNC = 112;
    public static final int MAX_OPCODE_ID = 112;

    // 64-bit instruction formats
    public static final int FMT_0 = 0; // Inline <=2 ops, <=1 imm32 + aux
    public static final int FMT_1 = 1; // Inline 2 ops, signed 24-bit values
    public static final int FMT_2 = 2; // String literal index in .rodata
    public static final int FMT_3 = 3; // Extended operand slice in .ext

    // Operand addressing modes (3 bits)
    public static final int MODE_NONE = 0;
    public static final int MODE_REG = 1;
    public static final int MODE_IMM = 2;
    public static final int MODE_RAM_IMM = 3;
    public static final int MODE_RAM_REG = 4;
    public static final int MODE_RAM_REG_OFF = 5;

    // Register IDs (3 bits)
    public static final int REG_A = 0;
    public static final int REG_B = 1;
    public static final int REG_C = 2;
    public static final int REG_D = 3;
    public static final int REG_PC = 4;
    public static final int REG_FLAGS = 5;

    private static final int FMT1_MIN_IMM = -(1 << 23);
    private static final int FMT1_MAX_IMM = (1 << 23) - 1;
    private static final int MAX_SECTION_ELEMENTS = 16_777_216;

    public static final Map<String, Integer> OPCODE_MAP = new HashMap<>();
    private static final String[] ID_TO_NAME = new String[MAX_OPCODE_ID + 1];

    static {
        register("NOP", OP_NOP);
        register("MOV", OP_MOV);
        register("ADD", OP_ADD);
        register("SUB", OP_SUB);
        register("MUL", OP_MUL);
        register("DIV", OP_DIV);
        register("MOD", OP_MOD);
        register("INC", OP_INC);
        register("DEC", OP_DEC);
        register("CMP", OP_CMP);
        register("AND", OP_AND);
        register("OR", OP_OR);
        register("XOR", OP_XOR);
        register("NOT", OP_NOT);
        register("SHL", OP_SHL);
        register("SHR", OP_SHR);
        register("JMP", OP_JMP);
        register("JZ", OP_JZ);
        register("JNZ", OP_JNZ);
        register("JS", OP_JS);
        register("JNS", OP_JNS);
        register("JL", OP_JL);
        register("JLE", OP_JLE);
        register("JG", OP_JG);
        register("JGE", OP_JGE);
        register("CALL", OP_CALL);
        register("RET", OP_RET);
        register("PUSH", OP_PUSH);
        register("POP", OP_POP);
        register("IVEC", OP_IVEC);
        register("IRET", OP_IRET);
        register("CLI", OP_CLI);
        register("STI", OP_STI);
        register("INT", OP_INT);
        register("OUT", OP_OUT);
        register("OUTH", OP_OUTH);
        register("OUTC", OP_OUTC);
        register("CLS", OP_CLS);
        register("SLEEP", OP_SLEEP);
        register("TIME", OP_TIME);
        register("RAND", OP_RAND);
        register("INPAD", OP_INPAD);
        register("TIMER", OP_TIMER);
        register("VBLANK", OP_VBLANK);
        register("SSIZE", OP_SSIZE);
        register("SREAD", OP_SREAD);
        register("SWRITE", OP_SWRITE);
        register("SBLKR", OP_SBLKR);
        register("SBLKW", OP_SBLKW);
        register("SCOUNT", OP_SCOUNT);
        register("SSEL", OP_SSEL);
        register("SDRV", OP_SDRV);
        register("SEXEC", OP_SEXEC);
        register("FBMODE", OP_FBMODE);
        register("FBSET", OP_FBSET);
        register("FBGET", OP_FBGET);
        register("FBCLR", OP_FBCLR);
        register("FBRECT", OP_FBRECT);
        register("FBLINE", OP_FBLINE);
        register("FBLIT", OP_FBLIT);
        register("FBSWAP", OP_FBSWAP);
        register("FBWIDTH", OP_FBWIDTH);
        register("FBHEIGHT", OP_FBHEIGHT);
        register("APUSH", OP_APUSH);
        register("ABUFW", OP_ABUFW);
        register("ACLR", OP_ACLR);
        register("ASTAT", OP_ASTAT);
        register("ATONE", OP_ATONE);
        register("APLAY", OP_APLAY);
        register("HLT", OP_HLT);
        register("PINFO", OP_PINFO);
        register("PWRAP", OP_PWRAP);
        register("PCALL", OP_PCALL);
        register("PSTATUS", OP_PSTATUS);
        register("PRESULT", OP_PRESULT);
        register("PCALLS", OP_PCALLS);
        register("PSCAN", OP_PSCAN);
        register("NOPEN", OP_NOPEN);
        register("NSEND", OP_NSEND);
        register("NRECV", OP_NRECV);
        register("NCLOSE", OP_NCLOSE);
        register("NHTTP", OP_NHTTP);
        register("NSTATUS", OP_NSTATUS);
        register("NLEN", OP_NLEN);
        register("NRESULT", OP_NRESULT);
        register("GALLOC", OP_GALLOC);
        register("NWSOPEN", OP_NWSOPEN);
        register("NWSTEXT", OP_NWSTEXT);
        register("NTLS", OP_NTLS);
        register("NHTTP3", OP_NHTTP3);
        register("NLISTEN", OP_NLISTEN);
        register("NACCEPT", OP_NACCEPT);
        register("NRESPOND", OP_NRESPOND);
        register("NREADY", OP_NREADY);
        register("NUTF8", OP_NUTF8);
        register("NRESPONDT", OP_NRESPONDT);
        register("PLEN", OP_PLEN);
        register("PRESULTS", OP_PRESULTS);
        register("INAXIS", OP_INAXIS);
        register("GFXSEL", OP_GFXSEL);
        register("GFXSTAT", OP_GFXSTAT);
        register("GPUCLR", OP_GPUCLR);
        register("GPURECT", OP_GPURECT);
        register("GPULINE", OP_GPULINE);
        register("GPUCIRC", OP_GPUCIRC);
        register("GPUTRI", OP_GPUTRI);
        register("GPUVRAM", OP_GPUVRAM);
        register("GPUBLIT", OP_GPUBLIT);
        register("GPUSWAP", OP_GPUSWAP);
        register("GPUSYNC", OP_GPUSYNC);

        // Aliases
        OPCODE_MAP.put("SETTIMER", OP_TIMER);
        OPCODE_MAP.put("SBOOT", OP_SEXEC);
        OPCODE_MAP.put("JNE", OP_JNZ);
    }

    private static void register(String name, int id) {
        OPCODE_MAP.put(name, id);
        ID_TO_NAME[id] = name;
    }

    private BinaryBytecode() {}

    public record ExtOperand(int mode, int reg, int imm) {}

    public record DataSegment(int ramStart, byte[] bytes) {
        public DataSegment {
            bytes = bytes == null ? new byte[0] : bytes;
        }
    }

    public record ProgramImage(
            long[] code,
            String[] rodata,
            ExtOperand[] ext,
            List<DataSegment> dataSegments
    ) {
        public static final ProgramImage EMPTY = new ProgramImage(new long[0], new String[0], new ExtOperand[0], List.of());

        public ProgramImage {
            code = code == null ? new long[0] : code;
            rodata = rodata == null ? new String[0] : rodata;
            ext = ext == null ? new ExtOperand[0] : ext;
            dataSegments = dataSegments == null ? List.of() : List.copyOf(dataSegments);
        }
    }

    public static String getOpcodeName(int opcodeId) {
        if (opcodeId > 0 && opcodeId <= MAX_OPCODE_ID) {
            return ID_TO_NAME[opcodeId];
        }
        return null;
    }

    public static boolean isScbc(byte[] data) {
        return data != null
                && data.length >= 24
                && data[0] == MAGIC[0]
                && data[1] == MAGIC[1]
                && data[2] == MAGIC[2]
                && data[3] == MAGIC[3];
    }

    public static int getInstructionCount(byte[] scbcBytes) {
        if (!isScbc(scbcBytes)) {
            return 0;
        }
        ByteBuffer buf = ByteBuffer.wrap(scbcBytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(4);
        int version = Short.toUnsignedInt(buf.getShort());
        if (version != VERSION) {
            return 0;
        }
        buf.getShort(); // flags
        int count = buf.getInt();
        return count < 0 ? 0 : count;
    }

    public static Set<String> getRequiredInstructions(byte[] scbcBytes) {
        Set<String> required = new LinkedHashSet<>();
        if (scbcBytes == null || scbcBytes.length == 0) {
            return required;
        }
        ProgramImage image = decodeScbc(scbcBytes);
        for (long word : image.code()) {
            int opcodeId = extractOpcode(word);
            String name = getOpcodeName(opcodeId);
            if (name != null) {
                required.add(name);
            }
        }
        return required;
    }

    public static int extractOpcode(long word) {
        return (int) ((word >>> 56) & 0xFFL);
    }

    public static int extractFormat(long word) {
        return (int) ((word >>> 52) & 0xFL);
    }

    public static int extractFlags(long word) {
        return (int) ((word >>> 48) & 0xFL);
    }

    public static int extractFmt0Op0Mode(long word) {
        return (int) ((word >>> 45) & 0x7L);
    }

    public static int extractFmt0Op0Reg(long word) {
        return (int) ((word >>> 42) & 0x7L);
    }

    public static int extractFmt0Op1Mode(long word) {
        return (int) ((word >>> 39) & 0x7L);
    }

    public static int extractFmt0Op1Reg(long word) {
        return (int) ((word >>> 36) & 0x7L);
    }

    public static int extractFmt0Aux(long word) {
        return (int) ((word >>> 32) & 0xFL);
    }

    public static int extractImm32(long word) {
        return (int) word;
    }

    public static int extractFmt1Mode0(long word) {
        return mapFmt1Mode((int) ((word >>> 50) & 0x3L));
    }

    public static int extractFmt1Mode1(long word) {
        return mapFmt1Mode((int) ((word >>> 48) & 0x3L));
    }

    public static int extractFmt1Val0(long word) {
        int raw24 = (int) ((word >>> 24) & 0xFFFFFFL);
        return (raw24 << 8) >> 8;
    }

    public static int extractFmt1Val1(long word) {
        int raw24 = (int) (word & 0xFFFFFFL);
        return (raw24 << 8) >> 8;
    }

    public static int extractFmt3Aux(long word) {
        return (int) ((word >>> 32) & 0xFFFFL);
    }

    private static int mapFmt1Mode(int mode2b) {
        return switch (mode2b) {
            case 0 -> MODE_IMM;
            case 1 -> MODE_RAM_IMM;
            case 2 -> MODE_REG;
            case 3 -> MODE_RAM_REG;
            default -> MODE_NONE;
        };
    }

    private static int toFmt1Mode2b(int mode) {
        return switch (mode) {
            case MODE_IMM -> 0;
            case MODE_RAM_IMM -> 1;
            case MODE_REG -> 2;
            case MODE_RAM_REG -> 3;
            default -> -1;
        };
    }

    private static boolean usesImm(int mode) {
        return mode == MODE_IMM || mode == MODE_RAM_IMM || mode == MODE_RAM_REG_OFF;
    }

    private static boolean fits24Signed(int value) {
        return value >= FMT1_MIN_IMM && value <= FMT1_MAX_IMM;
    }

    public static long encodeFmt0(int opcodeId, int opCount, int op0Mode, int op0Reg, int op1Mode, int op1Reg, int aux, int imm32) {
        return ((long) (opcodeId & 0xFF) << 56)
                | ((long) FMT_0 << 52)
                | ((long) (opCount & 0xF) << 48)
                | ((long) (op0Mode & 0x7) << 45)
                | ((long) (op0Reg & 0x7) << 42)
                | ((long) (op1Mode & 0x7) << 39)
                | ((long) (op1Reg & 0x7) << 36)
                | ((long) (aux & 0xF) << 32)
                | (imm32 & 0xFFFFFFFFL);
    }

    public static long encodeFmt1(int opcodeId, int op0Mode2b, int val0, int op1Mode2b, int val1) {
        int flags = ((op0Mode2b & 0x3) << 2) | (op1Mode2b & 0x3);
        return ((long) (opcodeId & 0xFF) << 56)
                | ((long) FMT_1 << 52)
                | ((long) (flags & 0xF) << 48)
                | ((val0 & 0xFFFFFFL) << 24)
                | (val1 & 0xFFFFFFL);
    }

    public static long encodeFmt2(int opcodeId, int flags, int rodataIndex) {
        return ((long) (opcodeId & 0xFF) << 56)
                | ((long) FMT_2 << 52)
                | ((long) (flags & 0xF) << 48)
                | (rodataIndex & 0xFFFFFFFFL);
    }

    public static long encodeFmt3(int opcodeId, int opCount, int aux, int extOffset) {
        return ((long) (opcodeId & 0xFF) << 56)
                | ((long) FMT_3 << 52)
                | ((long) (opCount & 0xF) << 48)
                | ((long) (aux & 0xFFFF) << 32)
                | (extOffset & 0xFFFFFFFFL);
    }

    public static long encodeInstruction(int opcodeId, List<ExtOperand> operands, int aux, List<ExtOperand> extTable) {
        int count = operands == null ? 0 : operands.size();
        if (count == 0) {
            return encodeFmt0(opcodeId, 0, MODE_NONE, 0, MODE_NONE, 0, aux, 0);
        }
        if (count == 1) {
            ExtOperand op0 = operands.get(0);
            return encodeFmt0(opcodeId, 1, op0.mode(), op0.reg(), MODE_NONE, 0, aux, usesImm(op0.mode()) ? op0.imm() : 0);
        }
        if (count == 2) {
            ExtOperand op0 = operands.get(0);
            ExtOperand op1 = operands.get(1);
            boolean i0 = usesImm(op0.mode());
            boolean i1 = usesImm(op1.mode());
            if (!(i0 && i1)) {
                int imm = i0 ? op0.imm() : (i1 ? op1.imm() : 0);
                return encodeFmt0(opcodeId, 2, op0.mode(), op0.reg(), op1.mode(), op1.reg(), aux, imm);
            }
            if (aux == 0) {
                int m0 = toFmt1Mode2b(op0.mode());
                int m1 = toFmt1Mode2b(op1.mode());
                if (m0 != -1 && m1 != -1) {
                    int v0 = op0.mode() == MODE_REG || op0.mode() == MODE_RAM_REG ? op0.reg() : op0.imm();
                    int v1 = op1.mode() == MODE_REG || op1.mode() == MODE_RAM_REG ? op1.reg() : op1.imm();
                    if (fits24Signed(v0) && fits24Signed(v1)) {
                        return encodeFmt1(opcodeId, m0, v0, m1, v1);
                    }
                }
            }
        }
        if (count > 15) {
            throw new IllegalArgumentException("Instruction has too many operands (" + count + ", max 15)");
        }
        int extOffset = extTable.size();
        extTable.addAll(operands);
        return encodeFmt3(opcodeId, count, aux, extOffset);
    }

    public static byte[] encodeScbc(ProgramImage image) {
        long[] code = image.code();
        String[] rodata = image.rodata();
        ExtOperand[] ext = image.ext();
        List<DataSegment> dataSegments = image.dataSegments();

        byte[][] rodataBytes = new byte[rodata.length][];
        int rodataPayloadBytes = 0;
        for (int i = 0; i < rodata.length; i++) {
            rodataBytes[i] = (rodata[i] == null ? "" : rodata[i]).getBytes(StandardCharsets.UTF_8);
            rodataPayloadBytes += 4 + rodataBytes[i].length;
        }

        int dataPayloadBytes = 0;
        for (DataSegment seg : dataSegments) {
            dataPayloadBytes += 8 + seg.bytes().length;
        }

        int totalSize = 8 // magic + version + flags
                + 4 + code.length * 8
                + 4 + rodataPayloadBytes
                + 4 + ext.length * 8
                + 4 + dataPayloadBytes;

        ByteBuffer buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(MAGIC);
        buf.putShort((short) VERSION);
        buf.putShort((short) 0);

        buf.putInt(code.length);
        for (long word : code) {
            buf.putLong(word);
        }

        buf.putInt(rodataBytes.length);
        for (byte[] bytes : rodataBytes) {
            buf.putInt(bytes.length);
            buf.put(bytes);
        }

        buf.putInt(ext.length);
        for (ExtOperand op : ext) {
            buf.put((byte) (op.mode() & 0xFF));
            buf.put((byte) (op.reg() & 0xFF));
            buf.putShort((short) 0);
            buf.putInt(op.imm());
        }

        buf.putInt(dataSegments.size());
        for (DataSegment seg : dataSegments) {
            buf.putInt(seg.ramStart());
            buf.putInt(seg.bytes().length);
            buf.put(seg.bytes());
        }

        return buf.array();
    }

    public static ProgramImage decodeScbc(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return ProgramImage.EMPTY;
        }
        if (raw.length < 24) {
            throw new IllegalArgumentException("Invalid SCBC bytecode: truncated header (" + raw.length + " bytes)");
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4; i++) {
            if (buf.get() != MAGIC[i]) {
                throw new IllegalArgumentException("Invalid SCBC magic header");
            }
        }
        int version = Short.toUnsignedInt(buf.getShort());
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported SCBC bytecode version: " + version);
        }
        buf.getShort(); // flags

        int instrCount = buf.getInt();
        if (instrCount < 0 || instrCount > MAX_SECTION_ELEMENTS || (long) instrCount * 8L > buf.remaining()) {
            throw new IllegalArgumentException("Invalid SCBC instruction count: " + instrCount);
        }
        long[] code = new long[instrCount];
        for (int i = 0; i < instrCount; i++) {
            code[i] = buf.getLong();
        }

        if (buf.remaining() < 4) {
            throw new IllegalArgumentException("Truncated SCBC .rodata section");
        }
        int rodataCount = buf.getInt();
        if (rodataCount < 0 || rodataCount > MAX_SECTION_ELEMENTS || (long) rodataCount * 4L > buf.remaining()) {
            throw new IllegalArgumentException("Invalid SCBC .rodata count: " + rodataCount);
        }
        String[] rodata = new String[rodataCount];
        for (int i = 0; i < rodataCount; i++) {
            if (buf.remaining() < 4) {
                throw new IllegalArgumentException("Truncated SCBC .rodata entry length");
            }
            int len = buf.getInt();
            if (len < 0 || len > buf.remaining()) {
                throw new IllegalArgumentException("Invalid SCBC .rodata entry length: " + len);
            }
            byte[] strBytes = new byte[len];
            buf.get(strBytes);
            rodata[i] = new String(strBytes, StandardCharsets.UTF_8);
        }

        if (buf.remaining() < 4) {
            throw new IllegalArgumentException("Truncated SCBC .ext section");
        }
        int extCount = buf.getInt();
        if (extCount < 0 || extCount > MAX_SECTION_ELEMENTS || (long) extCount * 8L > buf.remaining()) {
            throw new IllegalArgumentException("Invalid SCBC .ext count: " + extCount);
        }
        ExtOperand[] ext = new ExtOperand[extCount];
        for (int i = 0; i < extCount; i++) {
            int mode = Byte.toUnsignedInt(buf.get());
            int reg = Byte.toUnsignedInt(buf.get());
            buf.getShort();
            int imm = buf.getInt();
            ext[i] = new ExtOperand(mode, reg, imm);
        }

        if (buf.remaining() < 4) {
            throw new IllegalArgumentException("Truncated SCBC .data section");
        }
        int dataCount = buf.getInt();
        if (dataCount < 0 || dataCount > MAX_SECTION_ELEMENTS || (long) dataCount * 8L > buf.remaining()) {
            throw new IllegalArgumentException("Invalid SCBC .data segment count: " + dataCount);
        }
        List<DataSegment> dataSegments = new ArrayList<>(Math.min(dataCount, 256));
        for (int i = 0; i < dataCount; i++) {
            if (buf.remaining() < 8) {
                throw new IllegalArgumentException("Truncated SCBC .data segment header");
            }
            int ramStart = buf.getInt();
            int len = buf.getInt();
            if (len < 0 || len > buf.remaining()) {
                throw new IllegalArgumentException("Invalid SCBC .data segment length: " + len);
            }
            byte[] bytes = new byte[len];
            buf.get(bytes);
            dataSegments.add(new DataSegment(ramStart, bytes));
        }

        return new ProgramImage(code, rodata, ext, dataSegments);
    }

    public static List<DataSegment> coalesceDataSegments(List<DataSegment> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<DataSegment> merged = new ArrayList<>();
        int curStart = raw.get(0).ramStart();
        ByteArrayOutputStream curBytes = new ByteArrayOutputStream();
        curBytes.writeBytes(raw.get(0).bytes());
        for (int i = 1; i < raw.size(); i++) {
            DataSegment next = raw.get(i);
            if (curStart + curBytes.size() == next.ramStart()) {
                curBytes.writeBytes(next.bytes());
            } else {
                merged.add(new DataSegment(curStart, curBytes.toByteArray()));
                curStart = next.ramStart();
                curBytes.reset();
                curBytes.writeBytes(next.bytes());
            }
        }
        merged.add(new DataSegment(curStart, curBytes.toByteArray()));
        return merged;
    }

    public static String formatInstructionHex(long word) {
        return String.format(Locale.ROOT, "%016X", word);
    }

    public static String formatInstructionSummary(long word) {
        int opcodeId = extractOpcode(word);
        String name = getOpcodeName(opcodeId);
        if (name == null) {
            name = String.format(Locale.ROOT, "OP_%02X", opcodeId);
        }
        return name + " [" + formatInstructionHex(word) + "]";
    }

    public static List<String> previewInstructions(byte[] scbcBytes, int maxCount) {
        if (!isScbc(scbcBytes) || maxCount <= 0) {
            return List.of();
        }
        ByteBuffer buf = ByteBuffer.wrap(scbcBytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(8);
        int count = Math.max(0, buf.getInt());
        int limit = Math.min(count, maxCount);
        List<String> lines = new ArrayList<>(limit);
        for (int i = 0; i < limit && buf.remaining() >= 8; i++) {
            lines.add(formatInstructionSummary(buf.getLong()));
        }
        return lines;
    }
}
