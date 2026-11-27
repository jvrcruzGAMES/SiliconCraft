use std::collections::BTreeSet;

pub const ALL_INSTRUCTIONS: &[&str] = &[
    // Flow & Control
    "NOP", "HLT", "JMP", "JZ", "JNZ", "JS", "JNS", "JL", "JLE", "JG", "JGE", "CALL", "RET",
    // Data movement & Stack
    "MOV", "PUSH", "POP",
    // Arithmetic & Logic
    "ADD", "SUB", "MUL", "DIV", "MOD", "INC", "DEC", "CMP", "AND", "OR", "XOR", "NOT", "SHL", "SHR",
    // I/O & System / BIOS
    "OUT", "OUTC", "OUTH", "CLS", "SLEEP", "TIME", "RAND", "INPAD", "INAXIS",
    // Storage (multi-drive)
    "SSIZE", "SREAD", "SWRITE", "SBLKR", "SBLKW", "SCOUNT", "SSEL", "SDRV", "SEXEC",
    // Framebuffer / Graphics
    "FBMODE", "FBSET", "FBGET", "FBCLR", "FBRECT", "FBLINE", "FBLIT", "FBSWAP", "FBWIDTH", "FBHEIGHT",
    // GPU & Graphics Handler
    "GFXSEL", "GFXSTAT", "GPUCLR", "GPURECT", "GPULINE", "GPUCIRC", "GPUTRI", "GPUVRAM", "GPUBLIT", "GPUSWAP", "GPUSYNC",
    // Audio Buffer & Sound
    "APUSH", "ABUFW", "ACLR", "ASTAT", "ATONE", "APLAY",
    // Adjacent peripherals
    "PINFO", "PWRAP", "PCALL", "PCALLS", "PSTATUS", "PRESULT", "PLEN", "PRESULTS", "PSCAN", "GALLOC",
    // Server network
    "NOPEN", "NSEND", "NRECV", "NCLOSE", "NHTTP", "NSTATUS", "NLEN", "NRESULT", "NWSOPEN", "NWSTEXT", "NTLS", "NHTTP3", "NLISTEN", "NACCEPT", "NRESPOND", "NREADY", "NUTF8", "NRESPONDT",
    // Interrupts & Exceptions
    "IVEC", "IRET", "CLI", "STI", "INT", "TIMER", "VBLANK",
];

pub const ASSEMBLER_DIRECTIVES: &[&str] = &["ORG", "DB", "DW", "DL", "INCBIN"];

pub fn is_valid_instruction(opcode: &str) -> bool {
    ALL_INSTRUCTIONS.contains(&opcode)
}

pub fn is_directive(opcode: &str) -> bool {
    ASSEMBLER_DIRECTIVES.contains(&opcode)
}

pub fn get_required_instructions(assembly_lines: &[String]) -> BTreeSet<String> {
    let mut required = BTreeSet::new();
    for raw in assembly_lines {
        let line = strip_comments(raw).trim().to_string();
        if line.is_empty() || line.ends_with(':') {
            continue;
        }
        let opcode = match line.find(' ') {
            Some(space) => line[..space].trim().to_ascii_uppercase(),
            None => line.trim().to_ascii_uppercase(),
        };
        if is_directive(&opcode) {
            continue;
        }
        if is_valid_instruction(&opcode) {
            required.insert(opcode);
        }
    }
    required
}

pub fn strip_comments(line: &str) -> &str {
    let semi = line.find(';');
    let slash = line.find("//");
    let hash = line.find('#');

    let mut cutoff: Option<usize> = semi;
    if let Some(s) = slash {
        cutoff = Some(cutoff.map_or(s, |c| c.min(s)));
    }
    if let Some(h) = hash {
        cutoff = Some(cutoff.map_or(h, |c| c.min(h)));
    }

    match cutoff {
        Some(idx) => &line[..idx],
        None => line,
    }
}
