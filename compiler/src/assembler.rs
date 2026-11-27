use std::collections::{BTreeSet, HashMap};

use crate::validator::get_required_instructions;

#[derive(Debug, Clone)]
pub struct AssemblerResult {
    pub success: bool,
    pub bytecode: Vec<String>,
    pub required_instructions: BTreeSet<String>,
    pub errors: Vec<String>,
}

pub fn assemble(source_lines: &[String]) -> AssemblerResult {
    if source_lines.is_empty() {
        return AssemblerResult {
            success: true,
            bytecode: Vec::new(),
            required_instructions: BTreeSet::new(),
            errors: Vec::new(),
        };
    }

    let mut cleaned_lines: Vec<String> = Vec::new();
    let mut labels: HashMap<String, usize> = HashMap::new();
    let mut errors: Vec<String> = Vec::new();

    // Pass 1: Strip comments, collect label positions, prepare cleaned instruction list
    for (i, raw) in source_lines.iter().enumerate() {
        let line = strip_comments(raw).trim().to_string();
        if line.is_empty() {
            continue;
        }

        if let Some(colon) = find_label_colon(&line) {
            let label_name = line[..colon].trim().to_ascii_lowercase();
            if label_name.is_empty() {
                errors.push(format!("Line {}: Empty label declaration", i + 1));
                continue;
            }
            let rest = line[colon + 1..].trim().to_string();
            labels.insert(label_name, cleaned_lines.len());
            if !rest.is_empty() {
                cleaned_lines.push(rest);
            }
        } else {
            cleaned_lines.push(line);
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

    // Pass 2: Resolve label references into direct numeric addresses
    let mut bytecode = Vec::with_capacity(cleaned_lines.len());
    for line in &cleaned_lines {
        let (opcode, args) = match line.find(' ') {
            Some(space) => (
                line[..space].trim().to_ascii_uppercase(),
                line[space + 1..].trim(),
            ),
            None => (line.trim().to_ascii_uppercase(), ""),
        };

        let is_jump = matches!(
            opcode.as_str(),
            "JMP" | "JZ" | "JNZ" | "JS" | "JNS" | "JL" | "JLE" | "JG" | "JGE" | "CALL"
        );

        if is_jump && !args.is_empty() {
            let target_lower = args.to_ascii_lowercase();
            if let Some(&target_pc) = labels.get(&target_lower) {
                bytecode.push(format!("{} {}", opcode, target_pc));
            } else {
                bytecode.push(line.clone());
            }
        } else if opcode == "IVEC" && !args.is_empty() {
            if let Some((vec_part, handler_part)) = args.split_once(',') {
                let vec = vec_part.trim();
                let handler = handler_part.trim();
                let handler_lower = handler.to_ascii_lowercase();
                if let Some(&target_pc) = labels.get(&handler_lower) {
                    bytecode.push(format!("IVEC {}, {}", vec, target_pc));
                } else {
                    bytecode.push(line.clone());
                }
            } else {
                bytecode.push(line.clone());
            }
        } else if !args.is_empty() {
            let resolved_args = resolve_labels_in_args(args, &labels);
            bytecode.push(format!("{} {}", opcode, resolved_args));
        } else {
            bytecode.push(line.clone());
        }
    }

    let required_instructions = get_required_instructions(&bytecode);
    AssemblerResult {
        success: true,
        bytecode,
        required_instructions,
        errors: Vec::new(),
    }
}

fn resolve_labels_in_args(args: &str, labels: &HashMap<String, usize>) -> String {
    if labels.is_empty() || args.is_empty() {
        return args.to_string();
    }
    if args.starts_with('"') && args.ends_with('"') {
        return args.to_string();
    }

    let mut out = String::new();
    for (p, part) in args.split(',').enumerate() {
        if p > 0 {
            out.push_str(", ");
        }
        let token = part.trim();
        let token_lower = token.to_ascii_lowercase();
        if let Some(&pc) = labels.get(&token_lower) {
            out.push_str(&pc.to_string());
            continue;
        }
        if token.starts_with('[') && token.ends_with(']') {
            let inside = token[1..token.len() - 1].trim();
            let inside_lower = inside.to_ascii_lowercase();
            if let Some(&pc) = labels.get(&inside_lower) {
                out.push('[');
                out.push_str(&pc.to_string());
                out.push(']');
                continue;
            }
        }
        out.push_str(token);
    }
    out
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
