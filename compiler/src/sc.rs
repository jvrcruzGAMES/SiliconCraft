use std::collections::BTreeSet;
use std::fmt::Write as _;

pub const FORMAT_ID: &str = "siliconcraft-sc";
pub const FORMAT_VERSION: u32 = 1;

#[derive(Debug, Clone)]
pub struct ScPackage {
    pub name: String,
    pub target: String,
    pub arch: String,
    pub required_instructions: BTreeSet<String>,
    pub bytecode: Vec<String>,
}

impl ScPackage {
    pub fn to_json(&self) -> String {
        let mut out = String::new();
        out.push_str("{\n");
        let _ = writeln!(out, "  \"format\": \"{}\",", json_escape(FORMAT_ID));
        let _ = writeln!(out, "  \"version\": {},", FORMAT_VERSION);
        let _ = writeln!(out, "  \"name\": \"{}\",", json_escape(&self.name));
        let _ = writeln!(out, "  \"target\": \"{}\",", json_escape(&self.target));
        let _ = writeln!(out, "  \"arch\": \"{}\",", json_escape(&self.arch));

        out.push_str("  \"requiredInstructions\": [");
        for (idx, instr) in self.required_instructions.iter().enumerate() {
            if idx > 0 {
                out.push_str(", ");
            }
            let _ = write!(out, "\"{}\"", json_escape(instr));
        }
        out.push_str("],\n");

        out.push_str("  \"bytecode\": [\n");
        for (idx, line) in self.bytecode.iter().enumerate() {
            out.push_str("    \"");
            out.push_str(&json_escape(line));
            if idx + 1 < self.bytecode.len() {
                out.push_str("\",\n");
            } else {
                out.push_str("\"\n");
            }
        }
        out.push_str("  ]\n");
        out.push_str("}\n");
        out
    }
}

pub fn normalize_sc_name(raw: &str) -> String {
    let trimmed = raw.trim();
    let base = trimmed
        .rsplit(['/', '\\'])
        .next()
        .unwrap_or(trimmed)
        .trim();
    let mut name = if base.is_empty() {
        "program.sc".to_string()
    } else {
        base.to_string()
    };
    if !name.to_ascii_lowercase().ends_with(".sc") {
        name.push_str(".sc");
    }
    if name.len() > 128 {
        let truncated: String = name.chars().take(125).collect();
        name = format!("{}.sc", truncated);
    }
    name
}

fn json_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\x08' => out.push_str("\\b"),
            '\x0c' => out.push_str("\\f"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => {
                let _ = write!(out, "\\u{:04x}", c as u32);
            }
            other => out.push(other),
        }
    }
    out
}

pub fn inspect_sc_summary(json_text: &str) -> Result<String, String> {
    let format = extract_string_field(json_text, "format").unwrap_or_default();
    if format != FORMAT_ID {
        return Err(format!("Not a valid .sc file (expected format '{}')", FORMAT_ID));
    }
    let name = extract_string_field(json_text, "name").unwrap_or_else(|| "unknown.sc".to_string());
    let target = extract_string_field(json_text, "target").unwrap_or_else(|| "unknown".to_string());
    let arch = extract_string_field(json_text, "arch").unwrap_or_else(|| "Any".to_string());
    let req = extract_string_array_field(json_text, "requiredInstructions");
    let bytecode = extract_string_array_field(json_text, "bytecode");

    Ok(format!(
        "Name: {}\nFormat: {} v1\nTarget: {} (Arch: {})\nInstructions: {}\nRequired opcodes ({}): {}",
        name,
        format,
        target,
        arch,
        bytecode.len(),
        req.len(),
        if req.is_empty() {
            "(none)".to_string()
        } else {
            req.join(", ")
        }
    ))
}

fn extract_string_field(json: &str, key: &str) -> Option<String> {
    let needle = format!("\"{}\"", key);
    let pos = json.find(&needle)?;
    let rest = json[pos + needle.len()..].trim_start();
    let rest = rest.strip_prefix(':')?.trim_start();
    let (val, _) = parse_json_string(rest)?;
    Some(val)
}

fn extract_string_array_field(json: &str, key: &str) -> Vec<String> {
    let needle = format!("\"{}\"", key);
    let Some(pos) = json.find(&needle) else {
        return Vec::new();
    };
    let rest = json[pos + needle.len()..].trim_start();
    let Some(rest) = rest.strip_prefix(':').map(|s| s.trim_start()) else {
        return Vec::new();
    };
    let Some(mut cur) = rest.strip_prefix('[') else {
        return Vec::new();
    };
    let mut items = Vec::new();
    loop {
        cur = cur.trim_start();
        if cur.starts_with(']') || cur.is_empty() {
            break;
        }
        if cur.starts_with(',') {
            cur = &cur[1..];
            continue;
        }
        if cur.starts_with('"') {
            if let Some((s, next)) = parse_json_string(cur) {
                items.push(s);
                cur = next;
                continue;
            }
        }
        break;
    }
    items
}

fn parse_json_string(input: &str) -> Option<(String, &str)> {
    let mut chars = input.char_indices();
    let (_, first) = chars.next()?;
    if first != '"' {
        return None;
    }
    let mut out = String::new();
    while let Some((idx, c)) = chars.next() {
        match c {
            '"' => return Some((out, &input[idx + 1..])),
            '\\' => {
                let (_, esc) = chars.next()?;
                match esc {
                    '"' => out.push('"'),
                    '\\' => out.push('\\'),
                    '/' => out.push('/'),
                    'b' => out.push('\x08'),
                    'f' => out.push('\x0c'),
                    'n' => out.push('\n'),
                    'r' => out.push('\r'),
                    't' => out.push('\t'),
                    other => out.push(other),
                }
            }
            other => out.push(other),
        }
    }
    None
}
