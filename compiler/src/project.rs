use std::collections::{BTreeMap, HashMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};

use crate::aliases::merge_modules;
use crate::ast::{Program, Stmt};
use crate::lexer::Lexer;
use crate::parser::Parser;

pub fn normalize_path(path: &str) -> String {
    let mut clean = path.replace('\\', "/").trim().to_string();
    while clean.starts_with('/') {
        clean.remove(0);
    }
    let mut parts: Vec<&str> = Vec::new();
    for p in clean.split('/') {
        if p.is_empty() || p == "." {
            continue;
        }
        if p == ".." {
            if let Some(last) = parts.last() {
                if *last != ".." {
                    parts.pop();
                    continue;
                }
            }
        } else {
            parts.push(p);
        }
    }
    parts.join("/")
}

pub fn is_n1_path(path: &str) -> bool {
    path.to_ascii_lowercase().ends_with(".n")
}

pub fn is_assembly_path(path: &str) -> bool {
    let lower = path.to_ascii_lowercase();
    lower.ends_with(".asm") || lower.ends_with(".s")
}

pub fn resolve_import_path(
    current_file: &str,
    import_str: &str,
    available_files: &BTreeMap<String, Vec<String>>,
) -> Option<String> {
    let clean = import_str
        .trim()
        .trim_matches('"')
        .trim();
    if clean.is_empty() {
        return None;
    }

    let current_dir = match current_file.rfind('/') {
        Some(idx) => &current_file[..idx],
        None => "",
    };

    let mut candidates = Vec::new();
    let extensions = ["", ".n2", ".asm", ".s", ".S"];

    let rel = if current_dir.is_empty() {
        clean.to_string()
    } else {
        format!("{}/{}", current_dir, clean)
    };
    for ext in extensions {
        candidates.push(normalize_path(&format!("{}{}", rel, ext)));
    }
    for ext in extensions {
        candidates.push(normalize_path(&format!("{}{}", clean, ext)));
    }

    for cand in &candidates {
        if available_files.contains_key(cand) {
            return Some(cand.clone());
        }
    }

    for cand in &candidates {
        for key in available_files.keys() {
            if key.eq_ignore_ascii_case(cand) {
                return Some(key.clone());
            }
        }
    }

    None
}

fn try_load_from_include_dirs(
    import_str: &str,
    include_dirs: &[PathBuf],
    files: &mut BTreeMap<String, Vec<String>>,
) -> Option<String> {
    let clean = import_str.trim().trim_matches('"').trim();
    let extensions = ["", ".n2", ".asm", ".s", ".S"];
    for dir in include_dirs {
        for ext in extensions {
            let candidate = dir.join(format!("{}{}", clean, ext));
            if candidate.is_file() {
                if let Ok(content) = fs::read_to_string(&candidate) {
                    let key = normalize_path(&format!("_inc/{}/{}", dir.display(), format!("{}{}", clean, ext)));
                    let lines: Vec<String> = content.lines().map(|s| s.to_string()).collect();
                    files.insert(key.clone(), lines);
                    return Some(key);
                }
            }
        }
    }
    None
}

pub fn expand_assembly_includes(
    current_file: &str,
    files: &mut BTreeMap<String, Vec<String>>,
    include_dirs: &[PathBuf],
    visiting: &mut Vec<String>,
    errors: &mut Vec<String>,
) -> Vec<String> {
    if visiting.contains(&current_file.to_string()) {
        errors.push(format!(
            "Circular include detected: {} -> {}",
            visiting.join(" -> "),
            current_file
        ));
        return Vec::new();
    }
    let Some(lines) = files.get(current_file).cloned() else {
        errors.push(format!("Assembly file not found: {}", current_file));
        return Vec::new();
    };

    visiting.push(current_file.to_string());
    let mut expanded = Vec::new();

    for (idx, line) in lines.iter().enumerate() {
        let trimmed = line.trim();
        let include_target = parse_asm_include_directive(trimmed);
        if let Some(target_raw) = include_target {
            if is_n1_path(&target_raw) {
                errors.push(format!(
                    "{}:{}: N1 (.n) files were discontinued in SiliconCraft 0.2.0. Convert '{}' to N2 (.n2).",
                    current_file,
                    idx + 1,
                    target_raw
                ));
                continue;
            }
            let resolved = resolve_import_path(current_file, &target_raw, files)
                .or_else(|| try_load_from_include_dirs(&target_raw, include_dirs, files));
            match resolved {
                Some(path) => {
                    if is_assembly_path(&path) {
                        let sub = expand_assembly_includes(&path, files, include_dirs, visiting, errors);
                        expanded.extend(sub);
                    } else {
                        errors.push(format!(
                            "{}:{}: Pure assembly include '{}' must target a .asm/.s file (got '{}').",
                            current_file,
                            idx + 1,
                            target_raw,
                            path
                        ));
                    }
                }
                None => {
                    errors.push(format!(
                        "{}:{}: Cannot resolve assembly include '{}'",
                        current_file,
                        idx + 1,
                        target_raw
                    ));
                }
            }
        } else {
            expanded.push(line.clone());
        }
    }

    visiting.pop();
    expanded
}

fn parse_asm_include_directive(trimmed: &str) -> Option<String> {
    let rest = if let Some(r) = trimmed.strip_prefix(".include") {
        r.trim()
    } else if let Some(r) = trimmed.strip_prefix("#include") {
        r.trim()
    } else if let Some(r) = trimmed.strip_prefix("include") {
        if r.chars().next().map(|c| c.is_whitespace()).unwrap_or(false) {
            r.trim()
        } else {
            return None;
        }
    } else {
        return None;
    };

    if rest.starts_with('"') && rest.ends_with('"') && rest.len() >= 2 {
        Some(rest[1..rest.len() - 1].trim().to_string())
    } else if rest.starts_with('<') && rest.ends_with('>') && rest.len() >= 2 {
        Some(rest[1..rest.len() - 1].trim().to_string())
    } else {
        None
    }
}

pub fn resolve_and_merge_project(
    entrypoint: &str,
    files: &mut BTreeMap<String, Vec<String>>,
    include_dirs: &[PathBuf],
    errors: &mut Vec<String>,
) -> Program {
    let mut already_imported: HashSet<String> = HashSet::new();
    let mut currently_importing: Vec<String> = Vec::new();
    let mut merged_program = Program::new();
    let mut seen_lib_imports: HashSet<String> = HashSet::new();

    let mut modules: Vec<(String, Program)> = Vec::new();
    let mut aliases: HashMap<String, HashMap<String, String>> = HashMap::new();
    let mut unqualified: HashSet<String> = HashSet::new();
    unqualified.insert(entrypoint.to_string());

    resolve_file_recursive(
        entrypoint,
        files,
        include_dirs,
        &mut already_imported,
        &mut currently_importing,
        &mut merged_program,
        &mut seen_lib_imports,
        &mut modules,
        &mut aliases,
        &mut unqualified,
        errors,
    );

    merge_modules(&modules, &aliases, &unqualified, &mut merged_program, errors);
    merged_program
}

#[allow(clippy::too_many_arguments)]
fn resolve_file_recursive(
    current_file: &str,
    files: &mut BTreeMap<String, Vec<String>>,
    include_dirs: &[PathBuf],
    already_imported: &mut HashSet<String>,
    currently_importing: &mut Vec<String>,
    merged_program: &mut Program,
    seen_lib_imports: &mut HashSet<String>,
    modules: &mut Vec<(String, Program)>,
    aliases: &mut HashMap<String, HashMap<String, String>>,
    unqualified: &mut HashSet<String>,
    errors: &mut Vec<String>,
) {
    if currently_importing.contains(&current_file.to_string()) {
        errors.push(format!(
            "Circular import detected: {} -> {}",
            currently_importing.join(" -> "),
            current_file
        ));
        return;
    }

    if already_imported.contains(current_file) {
        return;
    }

    if is_n1_path(current_file) {
        errors.push(format!(
            "Unsupported N1 file '{}': N1 (.n) was discontinued in SiliconCraft 0.2.0. Please migrate to N2 (.n2).",
            current_file
        ));
        return;
    }

    currently_importing.push(current_file.to_string());

    let Some(lines) = files.get(current_file).cloned() else {
        errors.push(format!("Project file not found: {}", current_file));
        currently_importing.pop();
        return;
    };

    if is_assembly_path(current_file) {
        let mut visiting = Vec::new();
        let expanded = expand_assembly_includes(current_file, files, include_dirs, &mut visiting, errors);
        let mut asm_prog = Program::new();
        asm_prog.statements.push(Stmt::InlineAssembly {
            lines: expanded,
            line: 1,
            column: 1,
        });
        modules.push((current_file.to_string(), asm_prog));
        currently_importing.pop();
        already_imported.insert(current_file.to_string());
        return;
    }

    let source = lines.join("\n");
    let mut lexer = Lexer::new(&source, Some(current_file));
    let tokens = match lexer.tokenize() {
        Ok(t) => t,
        Err(e) => {
            errors.push(e.to_string());
            currently_importing.pop();
            return;
        }
    };

    let mut parser = Parser::new(tokens, Some(current_file));
    let file_ast = match parser.parse() {
        Ok(ast) => ast,
        Err(e) => {
            errors.push(e.to_string());
            currently_importing.pop();
            return;
        }
    };

    for imp in &file_ast.imports {
        if imp.is_library {
            if imp.alias.is_some() {
                continue;
            }
            let key = format!("{}:{}", imp.path, imp.symbols.join(","));
            if seen_lib_imports.insert(key) {
                merged_program.imports.push(imp.clone());
            }
        } else {
            if is_n1_path(&imp.path) {
                errors.push(format!(
                    "{}:{}: N1 (.n) imports are discontinued in SiliconCraft 0.2.0 ('{}'). Migrate to N2 (.n2).",
                    current_file, imp.line, imp.path
                ));
                continue;
            }
            let resolved_target = resolve_import_path(current_file, &imp.path, files)
                .or_else(|| try_load_from_include_dirs(&imp.path, include_dirs, files));

            match resolved_target {
                None => {
                    errors.push(format!(
                        "{}:{}: Cannot resolve project import '\"{}\"'",
                        current_file, imp.line, imp.path
                    ));
                }
                Some(target) => {
                    if let Some(alias) = &imp.alias {
                        let file_bindings = aliases.entry(current_file.to_string()).or_default();
                        if is_assembly_path(&target) {
                            errors.push(format!(
                                "{}:{}: Assembly modules do not support import aliases",
                                current_file, imp.line
                            ));
                        } else if file_bindings.contains_key(alias) {
                            errors.push(format!(
                                "{}:{}: Duplicate module alias '{}'",
                                current_file, imp.line, alias
                            ));
                        } else {
                            file_bindings.insert(alias.clone(), target.clone());
                        }
                    } else {
                        unqualified.insert(target.clone());
                    }

                    resolve_file_recursive(
                        &target,
                        files,
                        include_dirs,
                        already_imported,
                        currently_importing,
                        merged_program,
                        seen_lib_imports,
                        modules,
                        aliases,
                        unqualified,
                        errors,
                    );
                }
            }
        }
    }

    modules.push((current_file.to_string(), file_ast));
    currently_importing.pop();
    already_imported.insert(current_file.to_string());
}

pub fn discover_entrypoint(
    files: &BTreeMap<String, Vec<String>>,
    explicit_entry: Option<&str>,
) -> Option<String> {
    if let Some(entry) = explicit_entry {
        let trimmed = entry.trim();
        if !trimmed.is_empty() {
            let norm = normalize_path(trimmed);
            if files.contains_key(&norm) {
                return Some(norm);
            }
            for k in files.keys() {
                if k.eq_ignore_ascii_case(&norm) {
                    return Some(k.clone());
                }
            }
        }
    }

    let common = [
        "main.n2",
        "main.asm",
        "main.s",
        "main.S",
        "entry.n2",
        "entry.asm",
        "entry.s",
        "entry.S",
        "start.n2",
        "start.asm",
        "start.s",
        "start.S",
        "index.n2",
        "index.asm",
        "index.s",
        "index.S",
    ];
    for cand in common {
        if files.contains_key(cand) {
            return Some(cand.to_string());
        }
    }

    for path in files.keys() {
        let lower = path.to_ascii_lowercase();
        if lower.ends_with("/main.n2")
            || lower.ends_with("/main.asm")
            || lower.ends_with("/main.s")
            || lower.ends_with("/entry.n2")
            || lower.ends_with("/entry.asm")
            || lower.ends_with("/entry.s")
            || lower.ends_with("/start.n2")
            || lower.ends_with("/start.asm")
            || lower.ends_with("/start.s")
        {
            return Some(path.clone());
        }
    }

    None
}

pub fn collect_directory_files(root: &Path) -> Result<BTreeMap<String, Vec<String>>, String> {
    let mut out = BTreeMap::new();
    collect_recursive(root, root, &mut out)?;
    Ok(out)
}

fn collect_recursive(
    root: &Path,
    dir: &Path,
    out: &mut BTreeMap<String, Vec<String>>,
) -> Result<(), String> {
    let entries = fs::read_dir(dir)
        .map_err(|e| format!("Failed to read directory '{}': {}", dir.display(), e))?;
    for entry in entries {
        let entry = entry.map_err(|e| format!("Directory entry error: {}", e))?;
        let path = entry.path();
        if let Some(name) = path.file_name().and_then(|n| n.to_str()) {
            if name.starts_with('.') || name == "target" || name == "build" || name == "CMakeFiles" {
                continue;
            }
        }
        if path.is_dir() {
            collect_recursive(root, &path, out)?;
        } else if path.is_file() {
            if let Some(ext) = path.extension().and_then(|e| e.to_str()) {
                let lower = ext.to_ascii_lowercase();
                if matches!(lower.as_str(), "n2" | "asm" | "s") {
                    let rel = path
                        .strip_prefix(root)
                        .map_err(|e| format!("Path strip error: {}", e))?;
                    let norm = normalize_path(&rel.to_string_lossy());
                    let text = fs::read_to_string(&path)
                        .map_err(|e| format!("Failed to read '{}': {}", path.display(), e))?;
                    let lines: Vec<String> = text.lines().map(|l| l.to_string()).collect();
                    out.insert(norm, lines);
                }
            }
        }
    }
    Ok(())
}
