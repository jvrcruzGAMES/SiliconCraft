use std::collections::BTreeSet;

use crate::binary::{
    decode_scbc, get_instruction_count, get_required_instructions_from_code, opcode_id_by_name,
    opcode_name_by_id, SCPK_FLAG_HAS_METADATA, SCPK_MAGIC, SCPK_VERSION, SECTION_MANIFEST,
    SECTION_METADATA, SECTION_PROGRAM,
};

pub const FORMAT_VERSION: u16 = SCPK_VERSION;
pub const METADATA_VERSION: u16 = 1;
const MANIFEST_HEADER_SIZE: usize = 12;
const SECTION_ENTRY_SIZE: usize = 12;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SectionEntry {
    pub id: u16,
    pub flags: u16,
    pub offset: u32,
    pub size: u32,
}

impl SectionEntry {
    pub fn name(&self) -> &'static str {
        section_name(self.id)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ScManifest {
    pub version: u16,
    pub flags: u16,
    pub sections: Vec<SectionEntry>,
}

impl ScManifest {
    pub fn find_section(&self, id: u16) -> Option<&SectionEntry> {
        self.sections.iter().find(|s| s.id == id)
    }
}

#[derive(Debug, Clone)]
pub struct ScPackage {
    pub name: String,
    pub target: String,
    pub arch: String,
    pub required_instructions: BTreeSet<String>,
    pub bytecode: Vec<u8>,
    pub include_metadata: bool,
}

impl ScPackage {
    pub fn instruction_count(&self) -> usize {
        get_instruction_count(&self.bytecode)
    }

    pub fn to_bytes(&self) -> Result<Vec<u8>, String> {
        if self.bytecode.is_empty() {
            return Err("Cannot build .sc package with empty bytecode.".to_string());
        }
        let decoded = decode_scbc(&self.bytecode)?;
        if decoded.code.is_empty() {
            return Err("Cannot build .sc package with 0 instructions.".to_string());
        }
        let prog_len = self.bytecode.len();
        if prog_len > u32::MAX as usize {
            return Err("Program binary exceeds 4 GiB section limit.".to_string());
        }

        let metadata_bytes = if self.include_metadata {
            Some(self.encode_metadata_section(decoded.code.len() as u32)?)
        } else {
            None
        };

        let section_count: usize = if metadata_bytes.is_some() { 3 } else { 2 };
        let manifest_size = MANIFEST_HEADER_SIZE + section_count * SECTION_ENTRY_SIZE;
        let flags = if metadata_bytes.is_some() {
            SCPK_FLAG_HAS_METADATA
        } else {
            0
        };

        let mut cursor = manifest_size;
        let mut entries = Vec::with_capacity(section_count);
        entries.push(SectionEntry {
            id: SECTION_MANIFEST,
            flags: 0,
            offset: 0,
            size: manifest_size as u32,
        });
        if let Some(ref meta) = metadata_bytes {
            entries.push(SectionEntry {
                id: SECTION_METADATA,
                flags: 0,
                offset: cursor as u32,
                size: meta.len() as u32,
            });
            cursor += meta.len();
        }
        entries.push(SectionEntry {
            id: SECTION_PROGRAM,
            flags: 0,
            offset: cursor as u32,
            size: prog_len as u32,
        });
        cursor += prog_len;

        let mut out = Vec::with_capacity(cursor);
        // Section 1: manifest (first section)
        out.extend_from_slice(SCPK_MAGIC);
        out.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
        out.extend_from_slice(&flags.to_le_bytes());
        out.extend_from_slice(&(section_count as u16).to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        for entry in &entries {
            out.extend_from_slice(&entry.id.to_le_bytes());
            out.extend_from_slice(&entry.flags.to_le_bytes());
            out.extend_from_slice(&entry.offset.to_le_bytes());
            out.extend_from_slice(&entry.size.to_le_bytes());
        }
        // Section 2 (optional): metadata
        if let Some(meta) = metadata_bytes {
            out.extend_from_slice(&meta);
        }
        // Section 3 (mandatory): program
        out.extend_from_slice(&self.bytecode);
        Ok(out)
    }

    fn encode_metadata_section(&self, instr_count: u32) -> Result<Vec<u8>, String> {
        let norm_name = normalize_sc_name(&self.name);
        let clean_target = self.target.trim();
        let clean_arch = if self.arch.trim().is_empty() {
            default_arch_for_target(clean_target)
        } else {
            self.arch.trim()
        };

        let name_bytes = norm_name.as_bytes();
        let target_bytes = clean_target.as_bytes();
        let arch_bytes = clean_arch.as_bytes();
        if name_bytes.len() > u16::MAX as usize
            || target_bytes.len() > u16::MAX as usize
            || arch_bytes.len() > u16::MAX as usize
        {
            return Err(".sc metadata string exceeds 65535 bytes.".to_string());
        }

        let mut req_ids: Vec<u16> = Vec::with_capacity(self.required_instructions.len());
        for op in &self.required_instructions {
            let trimmed = op.trim();
            if trimmed.is_empty() {
                continue;
            }
            let id = opcode_id_by_name(trimmed)
                .ok_or_else(|| format!("Unknown required instruction opcode: {}", trimmed))?;
            let id16 = id as u16;
            if !req_ids.contains(&id16) {
                req_ids.push(id16);
            }
        }
        if req_ids.len() > u16::MAX as usize {
            return Err("Too many required instructions for .sc metadata section.".to_string());
        }

        let total_meta = 8
            + 2
            + name_bytes.len()
            + 2
            + target_bytes.len()
            + 2
            + arch_bytes.len()
            + 2
            + req_ids.len() * 2;
        let mut meta = Vec::with_capacity(total_meta);
        meta.extend_from_slice(&METADATA_VERSION.to_le_bytes());
        meta.extend_from_slice(&0u16.to_le_bytes());
        meta.extend_from_slice(&instr_count.to_le_bytes());

        write_utf8_u16(&mut meta, name_bytes);
        write_utf8_u16(&mut meta, target_bytes);
        write_utf8_u16(&mut meta, arch_bytes);

        meta.extend_from_slice(&(req_ids.len() as u16).to_le_bytes());
        for id in req_ids {
            meta.extend_from_slice(&id.to_le_bytes());
        }
        Ok(meta)
    }

    pub fn parse_manifest(raw: &[u8]) -> Result<ScManifest, String> {
        if raw.len() < MANIFEST_HEADER_SIZE + 2 * SECTION_ENTRY_SIZE {
            return Err("Invalid .sc file: truncated manifest header.".to_string());
        }
        if &raw[0..4] != SCPK_MAGIC {
            return Err(format!(
                "Invalid .sc file: expected binary SCPK v{} package.",
                FORMAT_VERSION
            ));
        }
        let version = u16::from_le_bytes([raw[4], raw[5]]);
        if version != FORMAT_VERSION {
            return Err(format!("Unsupported .sc format version: {}", version));
        }
        let flags = u16::from_le_bytes([raw[6], raw[7]]);
        let section_count = u16::from_le_bytes([raw[8], raw[9]]) as usize;
        if !(2..=3).contains(&section_count) {
            return Err(format!(
                "Invalid .sc manifest: expected 2 or 3 sections, found {}.",
                section_count
            ));
        }

        let manifest_size = MANIFEST_HEADER_SIZE + section_count * SECTION_ENTRY_SIZE;
        if raw.len() < manifest_size {
            return Err("Invalid .sc file: truncated manifest section table.".to_string());
        }

        let mut sections = Vec::with_capacity(section_count);
        let mut seen_ids = Vec::with_capacity(section_count);
        let mut pos = MANIFEST_HEADER_SIZE;
        for i in 0..section_count {
            let id = u16::from_le_bytes([raw[pos], raw[pos + 1]]);
            let sec_flags = u16::from_le_bytes([raw[pos + 2], raw[pos + 3]]);
            let offset = u32::from_le_bytes(raw[pos + 4..pos + 8].try_into().unwrap());
            let size = u32::from_le_bytes(raw[pos + 8..pos + 12].try_into().unwrap());
            pos += SECTION_ENTRY_SIZE;

            if !matches!(id, SECTION_MANIFEST | SECTION_METADATA | SECTION_PROGRAM) {
                return Err(format!("Invalid .sc manifest: unknown section ID 0x{:04X}.", id));
            }
            if seen_ids.contains(&id) {
                return Err(format!(
                    "Invalid .sc manifest: duplicate section '{}' (0x{:04X}).",
                    section_name(id),
                    id
                ));
            }
            seen_ids.push(id);

            if i == 0 && id != SECTION_MANIFEST {
                return Err("Invalid .sc manifest: manifest must be the first section.".to_string());
            }

            let off_usize = offset as usize;
            let size_usize = size as usize;
            let end_usize = off_usize
                .checked_add(size_usize)
                .ok_or_else(|| "Invalid .sc manifest: section offset/size overflow.".to_string())?;
            if end_usize > raw.len() {
                return Err(format!(
                    "Invalid .sc manifest: section '{}' (offset={}, size={}) exceeds file size ({}).",
                    section_name(id),
                    offset,
                    size,
                    raw.len()
                ));
            }

            if id == SECTION_MANIFEST {
                if off_usize != 0 || size_usize != manifest_size {
                    return Err(
                        "Invalid .sc manifest: manifest section bounds do not match first-section layout."
                            .to_string(),
                    );
                }
            } else {
                if off_usize < manifest_size {
                    return Err(format!(
                        "Invalid .sc manifest: section '{}' overlaps manifest.",
                        section_name(id)
                    ));
                }
                if size_usize == 0 {
                    return Err(format!(
                        "Invalid .sc manifest: section '{}' cannot be empty.",
                        section_name(id)
                    ));
                }
            }

            sections.push(SectionEntry {
                id,
                flags: sec_flags,
                offset,
                size,
            });
        }

        if !seen_ids.contains(&SECTION_PROGRAM) {
            return Err("Invalid .sc manifest: missing mandatory program section.".to_string());
        }

        // Verify non-manifest sections do not overlap one another
        for a in 0..sections.len() {
            if sections[a].id == SECTION_MANIFEST {
                continue;
            }
            let a_start = sections[a].offset as u64;
            let a_end = a_start + sections[a].size as u64;
            for b in (a + 1)..sections.len() {
                if sections[b].id == SECTION_MANIFEST {
                    continue;
                }
                let b_start = sections[b].offset as u64;
                let b_end = b_start + sections[b].size as u64;
                if a_start < b_end && b_start < a_end {
                    return Err(format!(
                        "Invalid .sc manifest: sections '{}' and '{}' overlap.",
                        sections[a].name(),
                        sections[b].name()
                    ));
                }
            }
        }

        Ok(ScManifest {
            version,
            flags,
            sections,
        })
    }

    pub fn from_bytes(raw: &[u8]) -> Result<Self, String> {
        let manifest = Self::parse_manifest(raw)?;

        let prog_entry = manifest
            .find_section(SECTION_PROGRAM)
            .ok_or_else(|| "Missing mandatory program section.".to_string())?;
        let prog_start = prog_entry.offset as usize;
        let prog_end = prog_start + prog_entry.size as usize;
        let bytecode = raw[prog_start..prog_end].to_vec();
        let decoded = decode_scbc(&bytecode)?;
        if decoded.code.is_empty() {
            return Err("Invalid .sc file: program section contains 0 instructions.".to_string());
        }

        if let Some(meta_entry) = manifest.find_section(SECTION_METADATA) {
            let meta_start = meta_entry.offset as usize;
            let meta_end = meta_start + meta_entry.size as usize;
            let meta = parse_metadata_section(&raw[meta_start..meta_end])?;
            let required = if meta.required_instructions.is_empty() {
                get_required_instructions_from_code(&decoded.code)
            } else {
                meta.required_instructions
            };
            Ok(ScPackage {
                name: normalize_sc_name(&meta.name),
                target: meta.target,
                arch: meta.arch,
                required_instructions: required,
                bytecode,
                include_metadata: true,
            })
        } else {
            Ok(ScPackage {
                name: "program.sc".to_string(),
                target: String::new(),
                arch: "Any".to_string(),
                required_instructions: get_required_instructions_from_code(&decoded.code),
                bytecode,
                include_metadata: false,
            })
        }
    }
}

struct ParsedMetadata {
    name: String,
    target: String,
    arch: String,
    required_instructions: BTreeSet<String>,
}

fn parse_metadata_section(meta: &[u8]) -> Result<ParsedMetadata, String> {
    if meta.len() < 16 {
        return Err("Invalid .sc metadata section: truncated header.".to_string());
    }
    let meta_version = u16::from_le_bytes([meta[0], meta[1]]);
    if meta_version != METADATA_VERSION {
        return Err(format!(
            "Unsupported .sc metadata section version: {}.",
            meta_version
        ));
    }
    let _meta_flags = u16::from_le_bytes([meta[2], meta[3]]);
    let _instr_count = u32::from_le_bytes(meta[4..8].try_into().unwrap());

    let mut pos = 8usize;
    let name = read_utf8_u16(meta, &mut pos, "name")?;
    let target = read_utf8_u16(meta, &mut pos, "target")?.trim().to_string();
    let mut arch = read_utf8_u16(meta, &mut pos, "arch")?.trim().to_string();
    if arch.is_empty() {
        arch = default_arch_for_target(&target).to_string();
    }

    if pos + 2 > meta.len() {
        return Err("Invalid .sc metadata section: missing required instruction table.".to_string());
    }
    let req_count = u16::from_le_bytes([meta[pos], meta[pos + 1]]) as usize;
    pos += 2;
    if req_count.saturating_mul(2) > meta.len() - pos {
        return Err("Invalid .sc metadata section: truncated required instruction table.".to_string());
    }
    let mut required_instructions = BTreeSet::new();
    for _ in 0..req_count {
        let opcode_u16 = u16::from_le_bytes([meta[pos], meta[pos + 1]]);
        pos += 2;
        let op_name = opcode_u16
            .try_into()
            .ok()
            .and_then(opcode_name_by_id)
            .ok_or_else(|| format!("Invalid .sc metadata: unknown opcode ID {}.", opcode_u16))?;
        required_instructions.insert(op_name.to_string());
    }

    Ok(ParsedMetadata {
        name,
        target,
        arch,
        required_instructions,
    })
}

pub fn section_name(id: u16) -> &'static str {
    match id {
        SECTION_MANIFEST => "manifest",
        SECTION_METADATA => "metadata",
        SECTION_PROGRAM => "program",
        _ => "unknown",
    }
}

fn default_arch_for_target(target: &str) -> &'static str {
    match target.to_ascii_lowercase().as_str() {
        "n2" => "N2",
        "asm" | "assembly" => "ASM",
        _ => "Any",
    }
}

fn write_utf8_u16(out: &mut Vec<u8>, bytes: &[u8]) {
    out.extend_from_slice(&(bytes.len() as u16).to_le_bytes());
    out.extend_from_slice(bytes);
}

fn read_utf8_u16(raw: &[u8], pos: &mut usize, field: &str) -> Result<String, String> {
    if *pos + 2 > raw.len() {
        return Err(format!("Invalid .sc file: truncated {} length.", field));
    }
    let len = u16::from_le_bytes([raw[*pos], raw[*pos + 1]]) as usize;
    *pos += 2;
    if len > raw.len() - *pos {
        return Err(format!("Invalid .sc file: truncated {} data.", field));
    }
    let s = std::str::from_utf8(&raw[*pos..*pos + len])
        .map_err(|e| format!("Invalid UTF-8 in {}: {}", field, e))?;
    *pos += len;
    Ok(s.to_string())
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

pub fn inspect_sc_summary(raw: &[u8]) -> Result<String, String> {
    let manifest = ScPackage::parse_manifest(raw)?;
    let pkg = ScPackage::from_bytes(raw)?;
    let image = decode_scbc(&pkg.bytecode)?;
    let req: Vec<String> = pkg.required_instructions.iter().cloned().collect();
    let section_summary = manifest
        .sections
        .iter()
        .map(|s| format!("{}@0x{:X}({}B)", s.name(), s.offset, s.size))
        .collect::<Vec<_>>()
        .join(", ");

    Ok(format!(
        "Name: {}\nFormat: SCPK v{} (64-bit SCBC v{})\nSections ({}): {}\nMetadata: {}\nTarget: {} (Arch: {})\nInstructions: {} ({} code bytes, {} .rodata, {} .ext, {} .data segments, {} program bytes)\nRequired opcodes ({}): {}",
        pkg.name,
        FORMAT_VERSION,
        crate::binary::SCBC_VERSION,
        manifest.sections.len(),
        section_summary,
        if pkg.include_metadata { "present" } else { "omitted" },
        if pkg.target.is_empty() { "(unspecified)" } else { &pkg.target },
        pkg.arch,
        image.code.len(),
        image.code.len() * 8,
        image.rodata.len(),
        image.ext.len(),
        image.data_segments.len(),
        pkg.bytecode.len(),
        req.len(),
        if req.is_empty() {
            "(none)".to_string()
        } else {
            req.join(", ")
        }
    ))
}
