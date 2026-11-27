use std::collections::BTreeSet;

pub const SCBC_MAGIC: &[u8; 4] = b"SCBC";
pub const SCBC_VERSION: u16 = 1;

pub const SCPK_MAGIC: &[u8; 4] = b"SCPK";
pub const SCPK_VERSION: u16 = 3;
pub const SCPK_FLAG_HAS_METADATA: u16 = 0x0001;
pub const SECTION_MANIFEST: u16 = 0x0001;
pub const SECTION_METADATA: u16 = 0x0002;
pub const SECTION_PROGRAM: u16 = 0x0003;

pub const OP_UNKNOWN: u8 = 0;
pub const OP_NOP: u8 = 1;
pub const OP_MOV: u8 = 2;
pub const OP_ADD: u8 = 3;
pub const OP_SUB: u8 = 4;
pub const OP_MUL: u8 = 5;
pub const OP_DIV: u8 = 6;
pub const OP_MOD: u8 = 7;
pub const OP_INC: u8 = 8;
pub const OP_DEC: u8 = 9;
pub const OP_CMP: u8 = 10;
pub const OP_AND: u8 = 11;
pub const OP_OR: u8 = 12;
pub const OP_XOR: u8 = 13;
pub const OP_NOT: u8 = 14;
pub const OP_SHL: u8 = 15;
pub const OP_SHR: u8 = 16;
pub const OP_JMP: u8 = 17;
pub const OP_JZ: u8 = 18;
pub const OP_JNZ: u8 = 19;
pub const OP_JS: u8 = 20;
pub const OP_JNS: u8 = 21;
pub const OP_JL: u8 = 22;
pub const OP_JLE: u8 = 23;
pub const OP_JG: u8 = 24;
pub const OP_JGE: u8 = 25;
pub const OP_CALL: u8 = 26;
pub const OP_RET: u8 = 27;
pub const OP_PUSH: u8 = 28;
pub const OP_POP: u8 = 29;
pub const OP_IVEC: u8 = 30;
pub const OP_IRET: u8 = 31;
pub const OP_CLI: u8 = 32;
pub const OP_STI: u8 = 33;
pub const OP_INT: u8 = 34;
pub const OP_OUT: u8 = 35;
pub const OP_OUTH: u8 = 36;
pub const OP_OUTC: u8 = 37;
pub const OP_CLS: u8 = 38;
pub const OP_SLEEP: u8 = 39;
pub const OP_TIME: u8 = 40;
pub const OP_RAND: u8 = 41;
pub const OP_INPAD: u8 = 42;
pub const OP_TIMER: u8 = 43;
pub const OP_VBLANK: u8 = 44;
pub const OP_SSIZE: u8 = 45;
pub const OP_SREAD: u8 = 46;
pub const OP_SWRITE: u8 = 47;
pub const OP_SBLKR: u8 = 48;
pub const OP_SBLKW: u8 = 49;
pub const OP_SCOUNT: u8 = 50;
pub const OP_SSEL: u8 = 51;
pub const OP_SDRV: u8 = 52;
pub const OP_SEXEC: u8 = 53;
pub const OP_FBMODE: u8 = 54;
pub const OP_FBSET: u8 = 55;
pub const OP_FBGET: u8 = 56;
pub const OP_FBCLR: u8 = 57;
pub const OP_FBRECT: u8 = 58;
pub const OP_FBLINE: u8 = 59;
pub const OP_FBLIT: u8 = 60;
pub const OP_FBSWAP: u8 = 61;
pub const OP_FBWIDTH: u8 = 62;
pub const OP_FBHEIGHT: u8 = 63;
pub const OP_APUSH: u8 = 64;
pub const OP_ABUFW: u8 = 65;
pub const OP_ACLR: u8 = 66;
pub const OP_ASTAT: u8 = 67;
pub const OP_ATONE: u8 = 68;
pub const OP_APLAY: u8 = 69;
pub const OP_HLT: u8 = 70;
// 71 reserved
pub const OP_PINFO: u8 = 72;
pub const OP_PWRAP: u8 = 73;
pub const OP_PCALL: u8 = 74;
pub const OP_PSTATUS: u8 = 75;
pub const OP_PRESULT: u8 = 76;
pub const OP_PCALLS: u8 = 77;
pub const OP_PSCAN: u8 = 78;
pub const OP_NOPEN: u8 = 79;
pub const OP_NSEND: u8 = 80;
pub const OP_NRECV: u8 = 81;
pub const OP_NCLOSE: u8 = 82;
pub const OP_NHTTP: u8 = 83;
pub const OP_NSTATUS: u8 = 84;
pub const OP_NLEN: u8 = 85;
pub const OP_NRESULT: u8 = 86;
pub const OP_GALLOC: u8 = 87;
pub const OP_NWSOPEN: u8 = 88;
pub const OP_NWSTEXT: u8 = 89;
pub const OP_NTLS: u8 = 90;
pub const OP_NHTTP3: u8 = 91;
// 92 reserved
pub const OP_NLISTEN: u8 = 93;
pub const OP_NACCEPT: u8 = 94;
pub const OP_NRESPOND: u8 = 95;
pub const OP_NREADY: u8 = 96;
pub const OP_NUTF8: u8 = 97;
pub const OP_NRESPONDT: u8 = 98;
pub const OP_PLEN: u8 = 99;
pub const OP_PRESULTS: u8 = 100;
pub const OP_INAXIS: u8 = 101;
pub const OP_GFXSEL: u8 = 102;
pub const OP_GFXSTAT: u8 = 103;
pub const OP_GPUCLR: u8 = 104;
pub const OP_GPURECT: u8 = 105;
pub const OP_GPULINE: u8 = 106;
pub const OP_GPUCIRC: u8 = 107;
pub const OP_GPUTRI: u8 = 108;
pub const OP_GPUVRAM: u8 = 109;
pub const OP_GPUBLIT: u8 = 110;
pub const OP_GPUSWAP: u8 = 111;
pub const OP_GPUSYNC: u8 = 112;
pub const MAX_OPCODE_ID: u8 = 112;

pub const FMT_0: u8 = 0;
pub const FMT_1: u8 = 1;
pub const FMT_2: u8 = 2;
pub const FMT_3: u8 = 3;

pub const MODE_NONE: u8 = 0;
pub const MODE_REG: u8 = 1;
pub const MODE_IMM: u8 = 2;
pub const MODE_RAM_IMM: u8 = 3;
pub const MODE_RAM_REG: u8 = 4;
pub const MODE_RAM_REG_OFF: u8 = 5;

pub const REG_A: u8 = 0;
pub const REG_B: u8 = 1;
pub const REG_C: u8 = 2;
pub const REG_D: u8 = 3;
pub const REG_PC: u8 = 4;
pub const REG_FLAGS: u8 = 5;

const FMT1_MIN_IMM: i32 = -(1 << 23);
const FMT1_MAX_IMM: i32 = (1 << 23) - 1;
const MAX_SECTION_ELEMENTS: usize = 16_777_216;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ExtOperand {
    pub mode: u8,
    pub reg: u8,
    pub imm: i32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DataSegment {
    pub ram_start: i32,
    pub bytes: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct ProgramImage {
    pub code: Vec<u64>,
    pub rodata: Vec<String>,
    pub ext: Vec<ExtOperand>,
    pub data_segments: Vec<DataSegment>,
}

pub fn opcode_id_by_name(name: &str) -> Option<u8> {
    match name.trim().to_ascii_uppercase().as_str() {
        "NOP" => Some(OP_NOP),
        "MOV" => Some(OP_MOV),
        "ADD" => Some(OP_ADD),
        "SUB" => Some(OP_SUB),
        "MUL" => Some(OP_MUL),
        "DIV" => Some(OP_DIV),
        "MOD" => Some(OP_MOD),
        "INC" => Some(OP_INC),
        "DEC" => Some(OP_DEC),
        "CMP" => Some(OP_CMP),
        "AND" => Some(OP_AND),
        "OR" => Some(OP_OR),
        "XOR" => Some(OP_XOR),
        "NOT" => Some(OP_NOT),
        "SHL" => Some(OP_SHL),
        "SHR" => Some(OP_SHR),
        "JMP" => Some(OP_JMP),
        "JZ" => Some(OP_JZ),
        "JNZ" | "JNE" => Some(OP_JNZ),
        "JS" => Some(OP_JS),
        "JNS" => Some(OP_JNS),
        "JL" => Some(OP_JL),
        "JLE" => Some(OP_JLE),
        "JG" => Some(OP_JG),
        "JGE" => Some(OP_JGE),
        "CALL" => Some(OP_CALL),
        "RET" => Some(OP_RET),
        "PUSH" => Some(OP_PUSH),
        "POP" => Some(OP_POP),
        "IVEC" => Some(OP_IVEC),
        "IRET" => Some(OP_IRET),
        "CLI" => Some(OP_CLI),
        "STI" => Some(OP_STI),
        "INT" => Some(OP_INT),
        "OUT" => Some(OP_OUT),
        "OUTH" => Some(OP_OUTH),
        "OUTC" => Some(OP_OUTC),
        "CLS" => Some(OP_CLS),
        "SLEEP" => Some(OP_SLEEP),
        "TIME" => Some(OP_TIME),
        "RAND" => Some(OP_RAND),
        "INPAD" => Some(OP_INPAD),
        "TIMER" | "SETTIMER" => Some(OP_TIMER),
        "VBLANK" => Some(OP_VBLANK),
        "SSIZE" => Some(OP_SSIZE),
        "SREAD" => Some(OP_SREAD),
        "SWRITE" => Some(OP_SWRITE),
        "SBLKR" => Some(OP_SBLKR),
        "SBLKW" => Some(OP_SBLKW),
        "SCOUNT" => Some(OP_SCOUNT),
        "SSEL" => Some(OP_SSEL),
        "SDRV" => Some(OP_SDRV),
        "SEXEC" | "SBOOT" => Some(OP_SEXEC),
        "FBMODE" => Some(OP_FBMODE),
        "FBSET" => Some(OP_FBSET),
        "FBGET" => Some(OP_FBGET),
        "FBCLR" => Some(OP_FBCLR),
        "FBRECT" => Some(OP_FBRECT),
        "FBLINE" => Some(OP_FBLINE),
        "FBLIT" => Some(OP_FBLIT),
        "FBSWAP" => Some(OP_FBSWAP),
        "FBWIDTH" => Some(OP_FBWIDTH),
        "FBHEIGHT" => Some(OP_FBHEIGHT),
        "APUSH" => Some(OP_APUSH),
        "ABUFW" => Some(OP_ABUFW),
        "ACLR" => Some(OP_ACLR),
        "ASTAT" => Some(OP_ASTAT),
        "ATONE" => Some(OP_ATONE),
        "APLAY" => Some(OP_APLAY),
        "HLT" => Some(OP_HLT),
        "PINFO" => Some(OP_PINFO),
        "PWRAP" => Some(OP_PWRAP),
        "PCALL" => Some(OP_PCALL),
        "PSTATUS" => Some(OP_PSTATUS),
        "PRESULT" => Some(OP_PRESULT),
        "PCALLS" => Some(OP_PCALLS),
        "PSCAN" => Some(OP_PSCAN),
        "NOPEN" => Some(OP_NOPEN),
        "NSEND" => Some(OP_NSEND),
        "NRECV" => Some(OP_NRECV),
        "NCLOSE" => Some(OP_NCLOSE),
        "NHTTP" => Some(OP_NHTTP),
        "NSTATUS" => Some(OP_NSTATUS),
        "NLEN" => Some(OP_NLEN),
        "NRESULT" => Some(OP_NRESULT),
        "GALLOC" => Some(OP_GALLOC),
        "NWSOPEN" => Some(OP_NWSOPEN),
        "NWSTEXT" => Some(OP_NWSTEXT),
        "NTLS" => Some(OP_NTLS),
        "NHTTP3" => Some(OP_NHTTP3),
        "NLISTEN" => Some(OP_NLISTEN),
        "NACCEPT" => Some(OP_NACCEPT),
        "NRESPOND" => Some(OP_NRESPOND),
        "NREADY" => Some(OP_NREADY),
        "NUTF8" => Some(OP_NUTF8),
        "NRESPONDT" => Some(OP_NRESPONDT),
        "PLEN" => Some(OP_PLEN),
        "PRESULTS" => Some(OP_PRESULTS),
        "INAXIS" => Some(OP_INAXIS),
        "GFXSEL" => Some(OP_GFXSEL),
        "GFXSTAT" => Some(OP_GFXSTAT),
        "GPUCLR" => Some(OP_GPUCLR),
        "GPURECT" => Some(OP_GPURECT),
        "GPULINE" => Some(OP_GPULINE),
        "GPUCIRC" => Some(OP_GPUCIRC),
        "GPUTRI" => Some(OP_GPUTRI),
        "GPUVRAM" => Some(OP_GPUVRAM),
        "GPUBLIT" => Some(OP_GPUBLIT),
        "GPUSWAP" => Some(OP_GPUSWAP),
        "GPUSYNC" => Some(OP_GPUSYNC),
        _ => None,
    }
}

pub fn opcode_name_by_id(id: u8) -> Option<&'static str> {
    match id {
        OP_NOP => Some("NOP"),
        OP_MOV => Some("MOV"),
        OP_ADD => Some("ADD"),
        OP_SUB => Some("SUB"),
        OP_MUL => Some("MUL"),
        OP_DIV => Some("DIV"),
        OP_MOD => Some("MOD"),
        OP_INC => Some("INC"),
        OP_DEC => Some("DEC"),
        OP_CMP => Some("CMP"),
        OP_AND => Some("AND"),
        OP_OR => Some("OR"),
        OP_XOR => Some("XOR"),
        OP_NOT => Some("NOT"),
        OP_SHL => Some("SHL"),
        OP_SHR => Some("SHR"),
        OP_JMP => Some("JMP"),
        OP_JZ => Some("JZ"),
        OP_JNZ => Some("JNZ"),
        OP_JS => Some("JS"),
        OP_JNS => Some("JNS"),
        OP_JL => Some("JL"),
        OP_JLE => Some("JLE"),
        OP_JG => Some("JG"),
        OP_JGE => Some("JGE"),
        OP_CALL => Some("CALL"),
        OP_RET => Some("RET"),
        OP_PUSH => Some("PUSH"),
        OP_POP => Some("POP"),
        OP_IVEC => Some("IVEC"),
        OP_IRET => Some("IRET"),
        OP_CLI => Some("CLI"),
        OP_STI => Some("STI"),
        OP_INT => Some("INT"),
        OP_OUT => Some("OUT"),
        OP_OUTH => Some("OUTH"),
        OP_OUTC => Some("OUTC"),
        OP_CLS => Some("CLS"),
        OP_SLEEP => Some("SLEEP"),
        OP_TIME => Some("TIME"),
        OP_RAND => Some("RAND"),
        OP_INPAD => Some("INPAD"),
        OP_TIMER => Some("TIMER"),
        OP_VBLANK => Some("VBLANK"),
        OP_SSIZE => Some("SSIZE"),
        OP_SREAD => Some("SREAD"),
        OP_SWRITE => Some("SWRITE"),
        OP_SBLKR => Some("SBLKR"),
        OP_SBLKW => Some("SBLKW"),
        OP_SCOUNT => Some("SCOUNT"),
        OP_SSEL => Some("SSEL"),
        OP_SDRV => Some("SDRV"),
        OP_SEXEC => Some("SEXEC"),
        OP_FBMODE => Some("FBMODE"),
        OP_FBSET => Some("FBSET"),
        OP_FBGET => Some("FBGET"),
        OP_FBCLR => Some("FBCLR"),
        OP_FBRECT => Some("FBRECT"),
        OP_FBLINE => Some("FBLINE"),
        OP_FBLIT => Some("FBLIT"),
        OP_FBSWAP => Some("FBSWAP"),
        OP_FBWIDTH => Some("FBWIDTH"),
        OP_FBHEIGHT => Some("FBHEIGHT"),
        OP_APUSH => Some("APUSH"),
        OP_ABUFW => Some("ABUFW"),
        OP_ACLR => Some("ACLR"),
        OP_ASTAT => Some("ASTAT"),
        OP_ATONE => Some("ATONE"),
        OP_APLAY => Some("APLAY"),
        OP_HLT => Some("HLT"),
        OP_PINFO => Some("PINFO"),
        OP_PWRAP => Some("PWRAP"),
        OP_PCALL => Some("PCALL"),
        OP_PSTATUS => Some("PSTATUS"),
        OP_PRESULT => Some("PRESULT"),
        OP_PCALLS => Some("PCALLS"),
        OP_PSCAN => Some("PSCAN"),
        OP_NOPEN => Some("NOPEN"),
        OP_NSEND => Some("NSEND"),
        OP_NRECV => Some("NRECV"),
        OP_NCLOSE => Some("NCLOSE"),
        OP_NHTTP => Some("NHTTP"),
        OP_NSTATUS => Some("NSTATUS"),
        OP_NLEN => Some("NLEN"),
        OP_NRESULT => Some("NRESULT"),
        OP_GALLOC => Some("GALLOC"),
        OP_NWSOPEN => Some("NWSOPEN"),
        OP_NWSTEXT => Some("NWSTEXT"),
        OP_NTLS => Some("NTLS"),
        OP_NHTTP3 => Some("NHTTP3"),
        OP_NLISTEN => Some("NLISTEN"),
        OP_NACCEPT => Some("NACCEPT"),
        OP_NRESPOND => Some("NRESPOND"),
        OP_NREADY => Some("NREADY"),
        OP_NUTF8 => Some("NUTF8"),
        OP_NRESPONDT => Some("NRESPONDT"),
        OP_PLEN => Some("PLEN"),
        OP_PRESULTS => Some("PRESULTS"),
        OP_INAXIS => Some("INAXIS"),
        OP_GFXSEL => Some("GFXSEL"),
        OP_GFXSTAT => Some("GFXSTAT"),
        OP_GPUCLR => Some("GPUCLR"),
        OP_GPURECT => Some("GPURECT"),
        OP_GPULINE => Some("GPULINE"),
        OP_GPUCIRC => Some("GPUCIRC"),
        OP_GPUTRI => Some("GPUTRI"),
        OP_GPUVRAM => Some("GPUVRAM"),
        OP_GPUBLIT => Some("GPUBLIT"),
        OP_GPUSWAP => Some("GPUSWAP"),
        OP_GPUSYNC => Some("GPUSYNC"),
        _ => None,
    }
}

pub fn extract_opcode(word: u64) -> u8 {
    ((word >> 56) & 0xFF) as u8
}

pub fn extract_format(word: u64) -> u8 {
    ((word >> 52) & 0xF) as u8
}

pub fn extract_flags(word: u64) -> u8 {
    ((word >> 48) & 0xF) as u8
}

fn uses_imm(mode: u8) -> bool {
    mode == MODE_IMM || mode == MODE_RAM_IMM || mode == MODE_RAM_REG_OFF
}

fn fits_24_signed(value: i32) -> bool {
    (FMT1_MIN_IMM..=FMT1_MAX_IMM).contains(&value)
}

fn to_fmt1_mode_2b(mode: u8) -> Option<u8> {
    match mode {
        MODE_IMM => Some(0),
        MODE_RAM_IMM => Some(1),
        MODE_REG => Some(2),
        MODE_RAM_REG => Some(3),
        _ => None,
    }
}

#[allow(clippy::too_many_arguments)]
pub fn encode_fmt0(
    opcode_id: u8,
    op_count: u8,
    op0_mode: u8,
    op0_reg: u8,
    op1_mode: u8,
    op1_reg: u8,
    aux: u8,
    imm32: i32,
) -> u64 {
    ((opcode_id as u64) << 56)
        | ((FMT_0 as u64) << 52)
        | (((op_count & 0xF) as u64) << 48)
        | (((op0_mode & 0x7) as u64) << 45)
        | (((op0_reg & 0x7) as u64) << 42)
        | (((op1_mode & 0x7) as u64) << 39)
        | (((op1_reg & 0x7) as u64) << 36)
        | (((aux & 0xF) as u64) << 32)
        | (imm32 as u32 as u64)
}

pub fn encode_fmt1(opcode_id: u8, op0_mode_2b: u8, val0: i32, op1_mode_2b: u8, val1: i32) -> u64 {
    let flags = ((op0_mode_2b & 0x3) << 2) | (op1_mode_2b & 0x3);
    ((opcode_id as u64) << 56)
        | ((FMT_1 as u64) << 52)
        | (((flags & 0xF) as u64) << 48)
        | (((val0 as i64 as u64) & 0xFFFFFF) << 24)
        | ((val1 as i64 as u64) & 0xFFFFFF)
}

pub fn encode_fmt2(opcode_id: u8, flags: u8, rodata_index: usize) -> u64 {
    ((opcode_id as u64) << 56)
        | ((FMT_2 as u64) << 52)
        | (((flags & 0xF) as u64) << 48)
        | ((rodata_index as u32) as u64)
}

pub fn encode_fmt3(opcode_id: u8, op_count: u8, aux: u16, ext_offset: usize) -> u64 {
    ((opcode_id as u64) << 56)
        | ((FMT_3 as u64) << 52)
        | (((op_count & 0xF) as u64) << 48)
        | ((aux as u64) << 32)
        | ((ext_offset as u32) as u64)
}

pub fn encode_instruction(
    opcode_id: u8,
    operands: &[ExtOperand],
    aux: u8,
    ext_table: &mut Vec<ExtOperand>,
) -> Result<u64, String> {
    let count = operands.len();
    if count == 0 {
        return Ok(encode_fmt0(opcode_id, 0, MODE_NONE, 0, MODE_NONE, 0, aux, 0));
    }
    if count == 1 {
        let op0 = operands[0];
        let imm = if uses_imm(op0.mode) { op0.imm } else { 0 };
        return Ok(encode_fmt0(
            opcode_id, 1, op0.mode, op0.reg, MODE_NONE, 0, aux, imm,
        ));
    }
    if count == 2 {
        let op0 = operands[0];
        let op1 = operands[1];
        let i0 = uses_imm(op0.mode);
        let i1 = uses_imm(op1.mode);
        if !(i0 && i1) {
            let imm = if i0 {
                op0.imm
            } else if i1 {
                op1.imm
            } else {
                0
            };
            return Ok(encode_fmt0(
                opcode_id, 2, op0.mode, op0.reg, op1.mode, op1.reg, aux, imm,
            ));
        }
        if aux == 0 {
            if let (Some(m0), Some(m1)) = (to_fmt1_mode_2b(op0.mode), to_fmt1_mode_2b(op1.mode)) {
                let v0 = if op0.mode == MODE_REG || op0.mode == MODE_RAM_REG {
                    op0.reg as i32
                } else {
                    op0.imm
                };
                let v1 = if op1.mode == MODE_REG || op1.mode == MODE_RAM_REG {
                    op1.reg as i32
                } else {
                    op1.imm
                };
                if fits_24_signed(v0) && fits_24_signed(v1) {
                    return Ok(encode_fmt1(opcode_id, m0, v0, m1, v1));
                }
            }
        }
    }
    if count > 15 {
        return Err(format!(
            "Instruction has too many operands ({}, max 15)",
            count
        ));
    }
    let ext_offset = ext_table.len();
    ext_table.extend_from_slice(operands);
    Ok(encode_fmt3(opcode_id, count as u8, aux as u16, ext_offset))
}

pub fn coalesce_data_segments(raw: Vec<DataSegment>) -> Vec<DataSegment> {
    if raw.is_empty() {
        return Vec::new();
    }
    let mut merged: Vec<DataSegment> = Vec::new();
    for seg in raw {
        if let Some(last) = merged.last_mut() {
            let expected_next = last.ram_start.wrapping_add(last.bytes.len() as i32);
            if expected_next == seg.ram_start {
                last.bytes.extend_from_slice(&seg.bytes);
                continue;
            }
        }
        merged.push(seg);
    }
    merged
}

pub fn encode_scbc(image: &ProgramImage) -> Vec<u8> {
    let mut rodata_payload = 0usize;
    for s in &image.rodata {
        rodata_payload += 4 + s.len();
    }
    let mut data_payload = 0usize;
    for seg in &image.data_segments {
        data_payload += 8 + seg.bytes.len();
    }
    let total = 8
        + 4
        + image.code.len() * 8
        + 4
        + rodata_payload
        + 4
        + image.ext.len() * 8
        + 4
        + data_payload;

    let mut out = Vec::with_capacity(total);
    out.extend_from_slice(SCBC_MAGIC);
    out.extend_from_slice(&SCBC_VERSION.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes());

    out.extend_from_slice(&(image.code.len() as u32).to_le_bytes());
    for word in &image.code {
        out.extend_from_slice(&word.to_le_bytes());
    }

    out.extend_from_slice(&(image.rodata.len() as u32).to_le_bytes());
    for s in &image.rodata {
        let bytes = s.as_bytes();
        out.extend_from_slice(&(bytes.len() as u32).to_le_bytes());
        out.extend_from_slice(bytes);
    }

    out.extend_from_slice(&(image.ext.len() as u32).to_le_bytes());
    for op in &image.ext {
        out.push(op.mode);
        out.push(op.reg);
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(&op.imm.to_le_bytes());
    }

    out.extend_from_slice(&(image.data_segments.len() as u32).to_le_bytes());
    for seg in &image.data_segments {
        out.extend_from_slice(&seg.ram_start.to_le_bytes());
        out.extend_from_slice(&(seg.bytes.len() as u32).to_le_bytes());
        out.extend_from_slice(&seg.bytes);
    }

    out
}

pub fn decode_scbc(raw: &[u8]) -> Result<ProgramImage, String> {
    if raw.is_empty() {
        return Ok(ProgramImage::default());
    }
    if raw.len() < 24 {
        return Err(format!(
            "Invalid SCBC bytecode: truncated header ({} bytes)",
            raw.len()
        ));
    }
    if &raw[0..4] != SCBC_MAGIC {
        return Err("Invalid SCBC magic header".to_string());
    }
    let version = u16::from_le_bytes([raw[4], raw[5]]);
    if version != SCBC_VERSION {
        return Err(format!("Unsupported SCBC bytecode version: {}", version));
    }

    let mut pos = 8usize;

    let instr_count = read_u32(raw, &mut pos, "instruction count")? as usize;
    if instr_count > MAX_SECTION_ELEMENTS || instr_count.saturating_mul(8) > raw.len() - pos {
        return Err(format!("Invalid SCBC instruction count: {}", instr_count));
    }
    let mut code = Vec::with_capacity(instr_count);
    for _ in 0..instr_count {
        let word = u64::from_le_bytes(raw[pos..pos + 8].try_into().unwrap());
        pos += 8;
        code.push(word);
    }

    let rodata_count = read_u32(raw, &mut pos, ".rodata count")? as usize;
    if rodata_count > MAX_SECTION_ELEMENTS || rodata_count.saturating_mul(4) > raw.len() - pos {
        return Err(format!("Invalid SCBC .rodata count: {}", rodata_count));
    }
    let mut rodata = Vec::with_capacity(rodata_count);
    for _ in 0..rodata_count {
        let len = read_u32(raw, &mut pos, ".rodata entry length")? as usize;
        if len > raw.len() - pos {
            return Err(format!("Invalid SCBC .rodata entry length: {}", len));
        }
        let s = std::str::from_utf8(&raw[pos..pos + len])
            .map_err(|e| format!("Invalid UTF-8 in SCBC .rodata: {}", e))?;
        pos += len;
        rodata.push(s.to_string());
    }

    let ext_count = read_u32(raw, &mut pos, ".ext count")? as usize;
    if ext_count > MAX_SECTION_ELEMENTS || ext_count.saturating_mul(8) > raw.len() - pos {
        return Err(format!("Invalid SCBC .ext count: {}", ext_count));
    }
    let mut ext = Vec::with_capacity(ext_count);
    for _ in 0..ext_count {
        let mode = raw[pos];
        let reg = raw[pos + 1];
        let imm = i32::from_le_bytes(raw[pos + 4..pos + 8].try_into().unwrap());
        pos += 8;
        ext.push(ExtOperand { mode, reg, imm });
    }

    let data_count = read_u32(raw, &mut pos, ".data segment count")? as usize;
    if data_count > MAX_SECTION_ELEMENTS || data_count.saturating_mul(8) > raw.len() - pos {
        return Err(format!("Invalid SCBC .data segment count: {}", data_count));
    }
    let mut data_segments = Vec::with_capacity(data_count.min(256));
    for _ in 0..data_count {
        let ram_start = read_i32(raw, &mut pos, ".data ram_start")?;
        let len = read_u32(raw, &mut pos, ".data length")? as usize;
        if len > raw.len() - pos {
            return Err(format!("Invalid SCBC .data segment length: {}", len));
        }
        let bytes = raw[pos..pos + len].to_vec();
        pos += len;
        data_segments.push(DataSegment { ram_start, bytes });
    }

    Ok(ProgramImage {
        code,
        rodata,
        ext,
        data_segments,
    })
}

pub fn get_instruction_count(scbc: &[u8]) -> usize {
    if scbc.len() < 12 || &scbc[0..4] != SCBC_MAGIC {
        return 0;
    }
    let version = u16::from_le_bytes([scbc[4], scbc[5]]);
    if version != SCBC_VERSION {
        return 0;
    }
    u32::from_le_bytes([scbc[8], scbc[9], scbc[10], scbc[11]]) as usize
}

pub fn get_required_instructions_from_code(code: &[u64]) -> BTreeSet<String> {
    let mut set = BTreeSet::new();
    for &word in code {
        if let Some(name) = opcode_name_by_id(extract_opcode(word)) {
            set.insert(name.to_string());
        }
    }
    set
}

pub fn get_required_instructions(scbc: &[u8]) -> Result<BTreeSet<String>, String> {
    let image = decode_scbc(scbc)?;
    Ok(get_required_instructions_from_code(&image.code))
}

pub fn read_u32(raw: &[u8], pos: &mut usize, what: &str) -> Result<u32, String> {
    if *pos + 4 > raw.len() {
        return Err(format!("Truncated SCBC {}", what));
    }
    let val = u32::from_le_bytes(raw[*pos..*pos + 4].try_into().unwrap());
    *pos += 4;
    Ok(val)
}

fn read_i32(raw: &[u8], pos: &mut usize, what: &str) -> Result<i32, String> {
    if *pos + 4 > raw.len() {
        return Err(format!("Truncated SCBC {}", what));
    }
    let val = i32::from_le_bytes(raw[*pos..*pos + 4].try_into().unwrap());
    *pos += 4;
    Ok(val)
}
