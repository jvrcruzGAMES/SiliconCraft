use std::collections::{BTreeSet, HashMap};
use std::fs;
use std::path::PathBuf;

use crate::binary::{
    coalesce_data_segments, encode_fmt2, encode_instruction, encode_scbc, opcode_id_by_name,
    opcode_name_by_id, DataSegment, ExtOperand, ProgramImage, MODE_IMM, MODE_RAM_IMM,
    MODE_RAM_REG, MODE_RAM_REG_OFF, MODE_REG, OP_IVEC, OP_OUT, OP_OUTC, OP_OUTH, OP_VBLANK,
    REG_A, REG_B, REG_C, REG_D, REG_FLAGS, REG_PC,
};
use crate::validator::is_directive;

const VEC_DIV_ZERO: i32 = 0;
const VEC_ILLEGAL_OPCODE: i32 = 1;
const VEC_OUT_OF_BOUNDS: i32 = 2;
const VEC_STACK_FAULT: i32 = 3;
const VEC_GENERAL_EXCEPTION: i32 = 4;
const VEC_KEYBOARD: i32 = 10;
const VEC_MOUSE_MOVE: i32 = 11;
const VEC_MOUSE_CLICK: i32 = 12;
const VEC_TIMER: i32 = 13;
const VEC_RESET: i32 = 14;

#[derive(Debug, Clone)]
pub struct AssemblerResult {
    pub success: bool,
    pub bytecode: Vec<u8>,
    pub required_instructions: BTreeSet<String>,
    pub errors: Vec<String>,
}

struct ParsedInstruction {
    line_num: usize,
    opcode: String,
    args: String,
}

struct ParsedDirective {
    line_num: usize,
    opcode: String,
    args: String,
    start_addr: i32,
    incbin_bytes: Option<Vec<u8>>,
}

pub fn assemble(source_lines: &[String]) -> AssemblerResult {
    assemble_with_base(source_lines, &[])
}

pub fn assemble_with_base(source_lines: &[String], base_dirs: &[PathBuf]) -> AssemblerResult {
    if source_lines.is_empty() {
        return AssemblerResult {
            success: true,
            bytecode: Vec::new(),
            required_instructions: BTreeSet::new(),
            errors: Vec::new(),
        };
    }

    let mut instructions: Vec<ParsedInstruction> = Vec::new();
    let mut directives: Vec<ParsedDirective> = Vec::new();
    let mut labels: HashMap<String, i32> = HashMap::new();
    let mut pending_labels: Vec<String> = Vec::new();
    let mut errors: Vec<String> = Vec::new();
    let mut current_data_addr: i32 = 0;

    // Pass 1: Strip comments, collect code and data labels, measure directives
    for (i, raw) in source_lines.iter().enumerate() {
        let line_num = i + 1;
        let mut line = strip_comments(raw).trim().to_string();
        if line.is_empty() {
            continue;
        }

        while let Some(colon) = find_label_colon(&line) {
            let label_name = line[..colon].trim().to_ascii_lowercase();
            if label_name.is_empty()
                || label_name.contains(' ')
                || label_name.contains('\t')
                || label_name.contains(',')
            {
                errors.push(format!("Line {}: Invalid label declaration", line_num));
                line.clear();
                break;
            }
            pending_labels.push(label_name);
            line = line[colon + 1..].trim().to_string();
            if line.is_empty() {
                break;
            }
        }
        if line.is_empty() {
            continue;
        }

        let (opcode, args) = match line.find(char::is_whitespace) {
            Some(space) => (
                line[..space].trim().to_ascii_uppercase(),
                line[space + 1..].trim().to_string(),
            ),
            None => (line.trim().to_ascii_uppercase(), String::new()),
        };

        if is_directive(&opcode) {
            if opcode == "ORG" {
                match eval_const_expr(&args, &labels) {
                    Ok(addr) => {
                        current_data_addr = addr as i32;
                        for lbl in pending_labels.drain(..) {
                            labels.insert(lbl, current_data_addr);
                        }
                    }
                    Err(e) => {
                        errors.push(format!("Line {}: {}", line_num, e));
                        pending_labels.clear();
                    }
                }
            } else if opcode == "DB" || opcode == "DW" || opcode == "DL" {
                for lbl in pending_labels.drain(..) {
                    labels.insert(lbl, current_data_addr);
                }
                let byte_len = measure_data_directive(&opcode, &args);
                directives.push(ParsedDirective {
                    line_num,
                    opcode,
                    args,
                    start_addr: current_data_addr,
                    incbin_bytes: None,
                });
                current_data_addr = current_data_addr.wrapping_add(byte_len as i32);
            } else if opcode == "INCBIN" {
                for lbl in pending_labels.drain(..) {
                    labels.insert(lbl, current_data_addr);
                }
                match read_incbin_slice(&args, &labels, base_dirs) {
                    Ok(incbin_bytes) => {
                        let len = incbin_bytes.len() as i32;
                        directives.push(ParsedDirective {
                            line_num,
                            opcode,
                            args,
                            start_addr: current_data_addr,
                            incbin_bytes: Some(incbin_bytes),
                        });
                        current_data_addr = current_data_addr.wrapping_add(len);
                    }
                    Err(e) => {
                        errors.push(format!("Line {}: {}", line_num, e));
                    }
                }
            }
        } else {
            let pc = instructions.len() as i32;
            for lbl in pending_labels.drain(..) {
                labels.insert(lbl, pc);
            }
            instructions.push(ParsedInstruction {
                line_num,
                opcode,
                args,
            });
        }
    }

    if !pending_labels.is_empty() {
        let end_pc = instructions.len() as i32;
        for lbl in pending_labels.drain(..) {
            labels.insert(lbl, end_pc);
        }
    }

    if !errors.is_empty() {
        return AssemblerResult {
            success: false,
            bytecode: Vec::new(),
            required_instructions: BTreeSet::new(),
            errors,
        };
    }

    // Pass 2a: Resolve static data directives (.data)
    let mut raw_segments: Vec<DataSegment> = Vec::new();
    for dir in directives {
        if dir.opcode == "INCBIN" {
            if let Some(bytes) = dir.incbin_bytes {
                if !bytes.is_empty() {
                    raw_segments.push(DataSegment {
                        ram_start: dir.start_addr,
                        bytes,
                    });
                }
            }
        } else {
            match encode_data_directive(&dir.opcode, &dir.args, &labels) {
                Ok(bytes) => {
                    if !bytes.is_empty() {
                        raw_segments.push(DataSegment {
                            ram_start: dir.start_addr,
                            bytes,
                        });
                    }
                }
                Err(e) => errors.push(format!("Line {}: {}", dir.line_num, e)),
            }
        }
    }

    // Pass 2b: Encode instructions into 64-bit binary words
    let mut code: Vec<u64> = Vec::with_capacity(instructions.len());
    let mut rodata_list: Vec<String> = Vec::new();
    let mut rodata_pool: HashMap<String, usize> = HashMap::new();
    let mut ext_list: Vec<ExtOperand> = Vec::new();
    let mut required = BTreeSet::new();

    for instr in instructions {
        let Some(opcode_id) = opcode_id_by_name(&instr.opcode) else {
            errors.push(format!(
                "Line {}: Unknown instruction '{}'",
                instr.line_num, instr.opcode
            ));
            continue;
        };
        if let Some(canonical) = opcode_name_by_id(opcode_id) {
            required.insert(canonical.to_string());
        }

        match encode_single_instruction(
            opcode_id,
            &instr.args,
            &labels,
            &mut rodata_list,
            &mut rodata_pool,
            &mut ext_list,
        ) {
            Ok(word) => code.push(word),
            Err(e) => errors.push(format!("Line {}: {}", instr.line_num, e)),
        }
    }

    if !errors.is_empty() {
        return AssemblerResult {
            success: false,
            bytecode: Vec::new(),
            required_instructions: BTreeSet::new(),
            errors,
        };
    }

    let data_segments = coalesce_data_segments(raw_segments);
    if code.is_empty() && rodata_list.is_empty() && ext_list.is_empty() && data_segments.is_empty()
    {
        return AssemblerResult {
            success: true,
            bytecode: Vec::new(),
            required_instructions: BTreeSet::new(),
            errors: Vec::new(),
        };
    }

    let image = ProgramImage {
        code,
        rodata: rodata_list,
        ext: ext_list,
        data_segments,
    };
    let binary = encode_scbc(&image);
    AssemblerResult {
        success: true,
        bytecode: binary,
        required_instructions: required,
        errors: Vec::new(),
    }
}

fn encode_single_instruction(
    opcode_id: u8,
    raw_args: &str,
    labels: &HashMap<String, i32>,
    rodata_list: &mut Vec<String>,
    rodata_pool: &mut HashMap<String, usize>,
    ext_list: &mut Vec<ExtOperand>,
) -> Result<u64, String> {
    if opcode_id == OP_OUT || opcode_id == OP_OUTH || opcode_id == OP_OUTC {
        return encode_out_instruction(
            opcode_id,
            raw_args,
            labels,
            rodata_list,
            rodata_pool,
            ext_list,
        );
    }

    if raw_args.trim().is_empty() {
        return encode_instruction(opcode_id, &[], 0, ext_list);
    }

    let parts = split_args_list(raw_args);
    if opcode_id == OP_VBLANK && parts.len() == 1 {
        let trimmed = parts[0].trim();
        if trimmed.eq_ignore_ascii_case("ON") || trimmed.eq_ignore_ascii_case("true") {
            return encode_instruction(
                opcode_id,
                &[ExtOperand {
                    mode: MODE_IMM,
                    reg: 0,
                    imm: 1,
                }],
                0,
                ext_list,
            );
        }
        if trimmed.eq_ignore_ascii_case("OFF") || trimmed.eq_ignore_ascii_case("false") {
            return encode_instruction(
                opcode_id,
                &[ExtOperand {
                    mode: MODE_IMM,
                    reg: 0,
                    imm: 0,
                }],
                0,
                ext_list,
            );
        }
    }

    let mut operands = Vec::with_capacity(parts.len());
    for (p, part) in parts.iter().enumerate() {
        let trimmed = part.trim();
        if opcode_id == OP_IVEC && p == 0 {
            if let Some(vec_id) = parse_vector_name(trimmed) {
                operands.push(ExtOperand {
                    mode: MODE_IMM,
                    reg: 0,
                    imm: vec_id,
                });
                continue;
            }
        }
        operands.push(parse_operand(trimmed, labels)?);
    }

    encode_instruction(opcode_id, &operands, 0, ext_list)
}

fn encode_out_instruction(
    opcode_id: u8,
    raw_args: &str,
    labels: &HashMap<String, i32>,
    rodata_list: &mut Vec<String>,
    rodata_pool: &mut HashMap<String, usize>,
    ext_list: &mut Vec<ExtOperand>,
) -> Result<u64, String> {
    if raw_args.trim().is_empty() {
        return encode_instruction(opcode_id, &[], 1, ext_list);
    }
    let parts = split_args_list(raw_args);
    if parts.is_empty() {
        return encode_instruction(opcode_id, &[], 1, ext_list);
    }

    let val_part = parts[0].trim();
    let mut newline: u8 = 1;
    if parts.len() == 2 {
        let flag = parts[1].trim().to_ascii_lowercase();
        if flag == "0" || flag == "cont" || flag == "no_nl" || flag == "false" {
            newline = 0;
        }
    } else if parts.len() > 2 {
        let mut operands = Vec::with_capacity(parts.len());
        for part in parts {
            operands.push(parse_operand(part.trim(), labels)?);
        }
        return encode_instruction(opcode_id, &operands, 1, ext_list);
    }

    if is_quoted_string_literal(val_part) {
        let s = unescape_quoted_string(val_part);
        let idx = if let Some(&existing) = rodata_pool.get(&s) {
            existing
        } else {
            let new_idx = rodata_list.len();
            rodata_list.push(s.clone());
            rodata_pool.insert(s, new_idx);
            new_idx
        };
        return Ok(encode_fmt2(opcode_id, newline, idx));
    }

    let op0 = parse_operand(val_part, labels)?;
    encode_instruction(opcode_id, &[op0], newline, ext_list)
}

fn parse_vector_name(token: &str) -> Option<i32> {
    match token.trim().to_ascii_uppercase().as_str() {
        "DIV_ZERO" | "DIV0" | "ZERO" => Some(VEC_DIV_ZERO),
        "ILLEGAL_OPCODE" | "OPCODE" | "ILL" => Some(VEC_ILLEGAL_OPCODE),
        "OUT_OF_BOUNDS" | "BOUNDS" | "MEM_FAULT" => Some(VEC_OUT_OF_BOUNDS),
        "STACK_FAULT" | "STACK" => Some(VEC_STACK_FAULT),
        "GENERAL_EXCEPTION" | "EXCEPTION" | "ERROR" => Some(VEC_GENERAL_EXCEPTION),
        "KEYBOARD" | "KEY" | "CHAR" => Some(VEC_KEYBOARD),
        "MOUSE_MOVE" | "MOUSE_POS" => Some(VEC_MOUSE_MOVE),
        "MOUSE_CLICK" | "CLICK" => Some(VEC_MOUSE_CLICK),
        "TIMER" | "VBLANK" | "VBL" | "NMI" | "FRAME" => Some(VEC_TIMER),
        "RESET" | "RST" => Some(VEC_RESET),
        _ => None,
    }
}

fn parse_operand(token: &str, labels: &HashMap<String, i32>) -> Result<ExtOperand, String> {
    let s = strip_outer_parens(token.trim());
    if s.is_empty() {
        return Err("Empty operand".to_string());
    }

    if let Some(reg) = parse_register(s) {
        return Ok(ExtOperand {
            mode: MODE_REG,
            reg,
            imm: 0,
        });
    }

    if s.starts_with('[') && s.ends_with(']') {
        let inner = strip_outer_parens(s[1..s.len() - 1].trim());
        if let Some(inner_reg) = parse_register(inner) {
            if inner_reg <= REG_D {
                return Ok(ExtOperand {
                    mode: MODE_RAM_REG,
                    reg: inner_reg,
                    imm: 0,
                });
            }
        }
        if let Ok(addr) = eval_const_expr(inner, labels) {
            return Ok(ExtOperand {
                mode: MODE_RAM_IMM,
                reg: 0,
                imm: addr as i32,
            });
        }
        if let Some(reg_off) = try_parse_reg_offset(inner, labels)? {
            return Ok(reg_off);
        }
        return Err(format!("Invalid memory operand: {}", token));
    }

    let imm = eval_const_expr(s, labels)? as i32;
    Ok(ExtOperand {
        mode: MODE_IMM,
        reg: 0,
        imm,
    })
}

fn try_parse_reg_offset(
    inner: &str,
    labels: &HashMap<String, i32>,
) -> Result<Option<ExtOperand>, String> {
    if let Some(plus_idx) = find_top_level_binary_op(inner, &["+"]) {
        let left = strip_outer_parens(inner[..plus_idx].trim());
        let right = strip_outer_parens(inner[plus_idx + 1..].trim());
        if let Some(rl) = parse_register(left) {
            if rl <= REG_D {
                let off = eval_const_expr(right, labels)? as i32;
                return Ok(Some(ExtOperand {
                    mode: MODE_RAM_REG_OFF,
                    reg: rl,
                    imm: off,
                }));
            }
        }
        if let Some(rr) = parse_register(right) {
            if rr <= REG_D {
                let off = eval_const_expr(left, labels)? as i32;
                return Ok(Some(ExtOperand {
                    mode: MODE_RAM_REG_OFF,
                    reg: rr,
                    imm: off,
                }));
            }
        }
    }
    if let Some(minus_idx) = find_top_level_binary_op(inner, &["-"]) {
        let left = strip_outer_parens(inner[..minus_idx].trim());
        let right = strip_outer_parens(inner[minus_idx + 1..].trim());
        if let Some(rl) = parse_register(left) {
            if rl <= REG_D {
                let off = eval_const_expr(right, labels)? as i32;
                return Ok(Some(ExtOperand {
                    mode: MODE_RAM_REG_OFF,
                    reg: rl,
                    imm: off.wrapping_neg(),
                }));
            }
        }
    }
    Ok(None)
}

fn parse_register(s: &str) -> Option<u8> {
    match s.trim().to_ascii_uppercase().as_str() {
        "A" => Some(REG_A),
        "B" => Some(REG_B),
        "C" => Some(REG_C),
        "D" => Some(REG_D),
        "PC" => Some(REG_PC),
        "FLAGS" => Some(REG_FLAGS),
        _ => None,
    }
}

fn measure_data_directive(opcode: &str, args: &str) -> usize {
    let tokens = split_args_list(args);
    let mut total = 0usize;
    for raw_tok in tokens {
        let token = raw_tok.trim();
        if token.is_empty() {
            continue;
        }
        if opcode == "DB" {
            if token.starts_with('"') && token.ends_with('"') && is_quoted_string_literal(token) {
                total += unescape_quoted_string(token).len();
            } else {
                total += 1;
            }
        } else if opcode == "DW" {
            total += 2;
        } else if opcode == "DL" {
            total += 3;
        }
    }
    total
}

fn encode_data_directive(
    opcode: &str,
    args: &str,
    labels: &HashMap<String, i32>,
) -> Result<Vec<u8>, String> {
    let tokens = split_args_list(args);
    let mut out = Vec::new();
    for raw_tok in tokens {
        let token = raw_tok.trim();
        if token.is_empty() {
            continue;
        }
        if opcode == "DB"
            && token.starts_with('"')
            && token.ends_with('"')
            && is_quoted_string_literal(token)
        {
            out.extend_from_slice(unescape_quoted_string(token).as_bytes());
            continue;
        }
        let val = eval_const_expr(token, labels)? as i32;
        if opcode == "DB" {
            out.push((val & 0xFF) as u8);
        } else if opcode == "DW" {
            out.push((val & 0xFF) as u8);
            out.push(((val >> 8) & 0xFF) as u8);
        } else if opcode == "DL" {
            out.push((val & 0xFF) as u8);
            out.push(((val >> 8) & 0xFF) as u8);
            out.push(((val >> 16) & 0xFF) as u8);
        }
    }
    Ok(out)
}

fn read_incbin_slice(
    args: &str,
    labels: &HashMap<String, i32>,
    base_dirs: &[PathBuf],
) -> Result<Vec<u8>, String> {
    let tokens = split_args_list(args);
    if tokens.is_empty() {
        return Ok(Vec::new());
    }
    let mut path_token = tokens[0].trim();
    if path_token.starts_with('"') && path_token.ends_with('"') && path_token.len() >= 2 {
        path_token = &path_token[1..path_token.len() - 1];
    }
    let offset = if tokens.len() > 1 {
        eval_const_expr(tokens[1].trim(), labels)? as i32
    } else {
        0
    };
    let length = if tokens.len() > 2 {
        Some(eval_const_expr(tokens[2].trim(), labels)? as i32)
    } else {
        None
    };

    let mut found_path: Option<PathBuf> = None;
    let direct = PathBuf::from(path_token);
    if direct.is_file() {
        found_path = Some(direct);
    } else {
        for dir in base_dirs {
            let cand = dir.join(path_token);
            if cand.is_file() {
                found_path = Some(cand);
                break;
            }
        }
    }
    let Some(p) = found_path else {
        return Err(format!("INCBIN file not found: {}", path_token));
    };

    let all_bytes = fs::read(&p).map_err(|e| format!("INCBIN failed: {}", e))?;
    let start = offset.max(0).min(all_bytes.len() as i32) as usize;
    let count = match length {
        Some(len) if len >= 0 => (len as usize).min(all_bytes.len() - start),
        _ => all_bytes.len() - start,
    };
    Ok(all_bytes[start..start + count].to_vec())
}

pub fn eval_const_expr(raw: &str, labels: &HashMap<String, i32>) -> Result<i64, String> {
    let s = strip_outer_parens(raw.trim());
    if s.is_empty() {
        return Err("Empty expression".to_string());
    }

    if s.starts_with('\'') && s.ends_with('\'') && is_quoted_string_literal(s) {
        return Ok(parse_char_literal(&s[1..s.len() - 1]));
    }

    if let Some(or_idx) = find_top_level_binary_op(s, &["|"]) {
        return Ok(eval_const_expr(&s[..or_idx], labels)?
            | eval_const_expr(&s[or_idx + 1..], labels)?);
    }
    if let Some(xor_idx) = find_top_level_binary_op(s, &["^"]) {
        return Ok(eval_const_expr(&s[..xor_idx], labels)?
            ^ eval_const_expr(&s[xor_idx + 1..], labels)?);
    }
    if let Some(and_idx) = find_top_level_binary_op(s, &["&"]) {
        return Ok(eval_const_expr(&s[..and_idx], labels)?
            & eval_const_expr(&s[and_idx + 1..], labels)?);
    }
    if let Some(shift_idx) = find_top_level_binary_op(s, &["<<", ">>"]) {
        if s[shift_idx..].starts_with("<<") {
            let l = eval_const_expr(&s[..shift_idx], labels)?;
            let r = eval_const_expr(&s[shift_idx + 2..], labels)?;
            return Ok(l.wrapping_shl(r as u32));
        } else {
            let l = eval_const_expr(&s[..shift_idx], labels)?;
            let r = eval_const_expr(&s[shift_idx + 2..], labels)?;
            return Ok(l.wrapping_shr(r as u32));
        }
    }
    if let Some(add_sub_idx) = find_top_level_binary_op(s, &["+", "-"]) {
        let op = s.as_bytes()[add_sub_idx];
        let l = eval_const_expr(&s[..add_sub_idx], labels)?;
        let r = eval_const_expr(&s[add_sub_idx + 1..], labels)?;
        return Ok(if op == b'+' {
            l.wrapping_add(r)
        } else {
            l.wrapping_sub(r)
        });
    }
    if let Some(mul_div_idx) = find_top_level_binary_op(s, &["*", "/", "%"]) {
        let op = s.as_bytes()[mul_div_idx];
        let l = eval_const_expr(&s[..mul_div_idx], labels)?;
        let r = eval_const_expr(&s[mul_div_idx + 1..], labels)?;
        return match op {
            b'*' => Ok(l.wrapping_mul(r)),
            b'/' => {
                if r == 0 {
                    Err(format!("Division by zero in expression: {}", s))
                } else {
                    Ok(l.wrapping_div(r))
                }
            }
            _ => {
                if r == 0 {
                    Err(format!("Modulo by zero in expression: {}", s))
                } else {
                    Ok(l.wrapping_rem(r))
                }
            }
        };
    }

    if let Some(rest) = s.strip_prefix('-') {
        return Ok(eval_const_expr(rest, labels)?.wrapping_neg());
    }
    if let Some(rest) = s.strip_prefix('+') {
        return eval_const_expr(rest, labels);
    }
    if let Some(rest) = s.strip_prefix('~') {
        return Ok(!eval_const_expr(rest, labels)?);
    }

    if let Some(&val) = labels.get(&s.to_ascii_lowercase()) {
        return Ok(val as i64);
    }

    if let Some(hex) = s.strip_prefix("0x").or_else(|| s.strip_prefix("0X")) {
        return u64::from_str_radix(hex, 16)
            .map(|v| v as i64)
            .map_err(|_| format!("Invalid value or label: {}", raw));
    }
    if let Some(hex) = s.strip_prefix('$') {
        return u64::from_str_radix(hex, 16)
            .map(|v| v as i64)
            .map_err(|_| format!("Invalid value or label: {}", raw));
    }
    if let Some(bin) = s.strip_prefix("0b").or_else(|| s.strip_prefix("0B")) {
        return u64::from_str_radix(bin, 2)
            .map(|v| v as i64)
            .map_err(|_| format!("Invalid value or label: {}", raw));
    }
    s.parse::<i64>()
        .map_err(|_| format!("Invalid value or label: {}", raw))
}

fn parse_char_literal(inner: &str) -> i64 {
    if inner.is_empty() {
        return 0;
    }
    let chars: Vec<char> = inner.chars().collect();
    if chars[0] == '\\' && chars.len() >= 2 {
        return match chars[1] {
            'n' => 10,
            't' => 9,
            'r' => 13,
            '0' => 0,
            '\\' => 92,
            '\'' => 39,
            '"' => 34,
            'x' | 'X' if chars.len() >= 4 => {
                let hex: String = chars[2..4].iter().collect();
                i64::from_str_radix(&hex, 16).unwrap_or(chars[1] as i64)
            }
            'u' | 'U' if chars.len() >= 6 => {
                let hex: String = chars[2..6].iter().collect();
                i64::from_str_radix(&hex, 16).unwrap_or(chars[1] as i64)
            }
            other => other as i64,
        };
    }
    chars[0] as i64
}

fn find_top_level_binary_op(expr: &str, ops: &[&str]) -> Option<usize> {
    let bytes = expr.as_bytes();
    let mut paren_depth = 0i32;
    let mut bracket_depth = 0i32;
    let mut in_quotes = false;
    let mut quote_char = b'\0';

    let mut i = bytes.len();
    while i > 0 {
        i -= 1;
        let b = bytes[i];
        if (b == b'"' || b == b'\'') && !is_escaped_byte(bytes, i) {
            if in_quotes && b == quote_char {
                in_quotes = false;
            } else if !in_quotes {
                in_quotes = true;
                quote_char = b;
            }
        }
        if in_quotes {
            continue;
        }
        match b {
            b')' => paren_depth += 1,
            b'(' => paren_depth -= 1,
            b']' => bracket_depth += 1,
            b'[' => bracket_depth -= 1,
            _ if paren_depth == 0 && bracket_depth == 0 => {
                for &op in ops {
                    if i + op.len() <= bytes.len()
                        && expr.is_char_boundary(i)
                        && expr[i..].starts_with(op)
                        && is_valid_binary_op_position(expr, i, op)
                    {
                        return Some(i);
                    }
                }
            }
            _ => {}
        }
    }
    None
}

fn is_valid_binary_op_position(expr: &str, i: usize, op: &str) -> bool {
    if i == 0 || i + op.len() >= expr.len() {
        return false;
    }
    let left_prefix = expr[..i].trim();
    if left_prefix.is_empty() {
        return false;
    }
    let last_left = left_prefix.as_bytes()[left_prefix.len() - 1];
    if matches!(
        last_left,
        b'+' | b'-' | b'*' | b'/' | b'%' | b'&' | b'|' | b'^' | b'<' | b'>' | b'=' | b'(' | b'[' | b'~'
    ) {
        return false;
    }
    let bytes = expr.as_bytes();
    match op {
        "|" => (i == 0 || bytes[i - 1] != b'|') && (i + 1 >= bytes.len() || bytes[i + 1] != b'|'),
        "&" => (i == 0 || bytes[i - 1] != b'&') && (i + 1 >= bytes.len() || bytes[i + 1] != b'&'),
        "<" => (i == 0 || bytes[i - 1] != b'<') && (i + 1 >= bytes.len() || bytes[i + 1] != b'<'),
        ">" => (i == 0 || bytes[i - 1] != b'>') && (i + 1 >= bytes.len() || bytes[i + 1] != b'>'),
        _ => true,
    }
}

fn strip_outer_parens(mut s: &str) -> &str {
    while s.len() >= 2 && s.starts_with('(') && s.ends_with(')') && is_matching_outer_parens(s) {
        s = s[1..s.len() - 1].trim();
    }
    s
}

fn is_matching_outer_parens(s: &str) -> bool {
    let bytes = s.as_bytes();
    let mut depth = 0i32;
    let mut in_quotes = false;
    let mut quote_char = b'\0';
    for (i, &b) in bytes.iter().enumerate() {
        if (b == b'"' || b == b'\'') && !is_escaped_byte(bytes, i) {
            if in_quotes && b == quote_char {
                in_quotes = false;
            } else if !in_quotes {
                in_quotes = true;
                quote_char = b;
            }
        }
        if in_quotes {
            continue;
        }
        if b == b'(' {
            depth += 1;
        } else if b == b')' {
            depth -= 1;
            if depth == 0 && i < bytes.len() - 1 {
                return false;
            }
        }
    }
    depth == 0
}

pub fn split_args_list(args: &str) -> Vec<&str> {
    if args.trim().is_empty() {
        return Vec::new();
    }
    let bytes = args.as_bytes();
    let mut list = Vec::new();
    let mut paren_depth = 0i32;
    let mut bracket_depth = 0i32;
    let mut in_quotes = false;
    let mut quote_char = b'\0';
    let mut start = 0usize;
    for (i, &b) in bytes.iter().enumerate() {
        if (b == b'"' || b == b'\'') && !is_escaped_byte(bytes, i) {
            if in_quotes && b == quote_char {
                in_quotes = false;
            } else if !in_quotes {
                in_quotes = true;
                quote_char = b;
            }
        }
        if in_quotes {
            continue;
        }
        match b {
            b'(' => paren_depth += 1,
            b')' => paren_depth -= 1,
            b'[' => bracket_depth += 1,
            b']' => bracket_depth -= 1,
            b',' if paren_depth == 0 && bracket_depth == 0 => {
                list.push(&args[start..i]);
                start = i + 1;
            }
            _ => {}
        }
    }
    list.push(&args[start..]);
    list
}

fn is_quoted_string_literal(s: &str) -> bool {
    let bytes = s.as_bytes();
    if bytes.len() < 2 {
        return false;
    }
    let q = bytes[0];
    if (q != b'"' && q != b'\'') || bytes[bytes.len() - 1] != q {
        return false;
    }
    let mut i = 1usize;
    while i < bytes.len() - 1 {
        if bytes[i] == b'\\' {
            i += 2;
            continue;
        }
        if bytes[i] == q {
            return false;
        }
        i += 1;
    }
    !is_escaped_byte(bytes, bytes.len() - 1)
}

fn unescape_quoted_string(s: &str) -> String {
    if s.len() < 2 {
        return s.to_string();
    }
    let inner = &s[1..s.len() - 1];
    let mut out = String::with_capacity(inner.len());
    let mut chars = inner.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '\\' {
            if let Some(next) = chars.next() {
                match next {
                    'n' => out.push('\n'),
                    't' => out.push('\t'),
                    'r' => out.push('\r'),
                    '0' => out.push('\0'),
                    '\\' => out.push('\\'),
                    '"' => out.push('"'),
                    '\'' => out.push('\''),
                    'x' | 'X' => {
                        let h1 = chars.peek().copied();
                        if let Some(c1) = h1 {
                            chars.next();
                            if let Some(c2) = chars.peek().copied() {
                                let hex: String = [c1, c2].into_iter().collect();
                                if let Ok(v) = u32::from_str_radix(&hex, 16) {
                                    if let Some(ch) = char::from_u32(v) {
                                        chars.next();
                                        out.push(ch);
                                        continue;
                                    }
                                }
                            }
                            out.push(next);
                            out.push(c1);
                            continue;
                        }
                        out.push(next);
                    }
                    'u' | 'U' => {
                        let mut hex = String::with_capacity(4);
                        for _ in 0..4 {
                            if let Some(ch) = chars.peek().copied() {
                                if ch.is_ascii_hexdigit() {
                                    hex.push(ch);
                                    chars.next();
                                } else {
                                    break;
                                }
                            }
                        }
                        if hex.len() == 4 {
                            if let Ok(v) = u32::from_str_radix(&hex, 16) {
                                if let Some(ch) = char::from_u32(v) {
                                    out.push(ch);
                                    continue;
                                }
                            }
                        }
                        out.push(next);
                        out.push_str(&hex);
                    }
                    other => out.push(other),
                }
            }
        } else {
            out.push(c);
        }
    }
    out
}

fn is_escaped_byte(bytes: &[u8], index: usize) -> bool {
    let mut backslashes = 0usize;
    let mut i = index;
    while i > 0 && bytes[i - 1] == b'\\' {
        backslashes += 1;
        i -= 1;
    }
    (backslashes & 1) == 1
}

pub fn find_label_colon(line: &str) -> Option<usize> {
    let mut in_quotes = false;
    let mut quote_char = '\0';
    let mut prev_backslash = false;

    for (idx, c) in line.char_indices() {
        if (c == '"' || c == '\'') && !prev_backslash {
            if in_quotes && c == quote_char {
                in_quotes = false;
            } else if !in_quotes {
                in_quotes = true;
                quote_char = c;
            }
        }
        if !in_quotes && c == ':' {
            return Some(idx);
        }
        prev_backslash = c == '\\' && !prev_backslash;
    }
    None
}

pub fn strip_comments(raw: &str) -> &str {
    let mut in_quotes = false;
    let mut quote_char = '\0';
    let mut prev_backslash = false;
    let bytes = raw.as_bytes();

    for (idx, c) in raw.char_indices() {
        if (c == '"' || c == '\'') && !prev_backslash {
            if in_quotes && c == quote_char {
                in_quotes = false;
            } else if !in_quotes {
                in_quotes = true;
                quote_char = c;
            }
        }
        if !in_quotes {
            if c == ';' || c == '#' {
                return &raw[..idx];
            }
            if c == '/' && idx + 1 < bytes.len() && bytes[idx + 1] == b'/' {
                return &raw[..idx];
            }
        }
        prev_backslash = c == '\\' && !prev_backslash;
    }
    raw
}
