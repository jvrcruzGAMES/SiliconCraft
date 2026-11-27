pub mod aliases;
pub mod assembler;
pub mod ast;
pub mod binary;
pub mod codegen;
pub mod error;
pub mod lexer;
pub mod library;
pub mod parser;
pub mod project;
pub mod sc;
pub mod token;
pub mod validator;

use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::{Path, PathBuf};

use assembler::{assemble, assemble_with_base};
use codegen::CodeGenerator;
use lexer::Lexer;
use parser::Parser;
use project::{
    collect_directory_files, discover_entrypoint, expand_assembly_includes, is_assembly_path,
    is_n1_path, normalize_path, resolve_and_merge_project,
};
use sc::{normalize_sc_name, ScPackage};
use validator::get_required_instructions;

#[derive(Debug, Clone)]
pub struct BuildOutput {
    pub package: ScPackage,
    pub assembly: Vec<String>,
}

#[derive(Debug, Clone)]
pub struct BuildOptions {
    pub entrypoint: Option<String>,
    pub include_dirs: Vec<PathBuf>,
    pub output_name: Option<String>,
    pub target: Option<String>,
}

pub fn compile_n2_source(source: &str, file_name: &str, package_name: &str) -> Result<BuildOutput, Vec<String>> {
    if source.trim().is_empty() {
        return Err(vec!["Source code is empty!".to_string()]);
    }
    let mut lexer = Lexer::new(source, Some(file_name));
    let tokens = lexer.tokenize().map_err(|e| vec![e.to_string()])?;
    let mut parser = Parser::new(tokens, Some(file_name));
    let mut program = parser.parse().map_err(|e| vec![e.to_string()])?;

    if program.imports.iter().any(|imp| !imp.is_library && imp.alias.is_some()) {
        return Err(vec!["Module aliases require project compilation".to_string()]);
    }

    if program.imports.iter().any(|imp| imp.alias.is_some()) {
        let mut linked = ast::Program::new();
        for imp in &program.imports {
            if imp.alias.is_none() {
                linked.imports.push(imp.clone());
            }
        }
        let mut errors = Vec::new();
        let mods = vec![(file_name.to_string(), program)];
        let bindings = std::collections::HashMap::new();
        let unqualified = std::collections::HashSet::from([file_name.to_string()]);
        aliases::merge_modules(&mods, &bindings, &unqualified, &mut linked, &mut errors);
        if !errors.is_empty() {
            return Err(errors);
        }
        program = linked;
    }

    let mut codegen = CodeGenerator::new(program);
    let assembly = codegen.generate();
    if !codegen.errors().is_empty() {
        return Err(codegen.errors().to_vec());
    }

    let asm = assemble(&assembly);
    if !asm.success {
        return Err(asm.errors);
    }

    let mut req: BTreeSet<String> = codegen.required_instructions().clone();
    req.extend(get_required_instructions(&assembly));
    req.extend(asm.required_instructions);

    let package = ScPackage {
        name: normalize_sc_name(package_name),
        target: "n2".to_string(),
        arch: "N2".to_string(),
        required_instructions: req,
        bytecode: asm.bytecode,
        include_metadata: true,
    };

    Ok(BuildOutput { package, assembly })
}

pub fn compile_project_map(
    mut files: BTreeMap<String, Vec<String>>,
    options: &BuildOptions,
) -> Result<BuildOutput, Vec<String>> {
    if files.is_empty() {
        return Err(vec!["Project contains no files!".to_string()]);
    }

    let normalized: BTreeMap<String, Vec<String>> = files
        .into_iter()
        .map(|(k, v)| (normalize_path(&k), v))
        .collect();
    files = normalized;

    let Some(entry) = discover_entrypoint(&files, options.entrypoint.as_deref()) else {
        if let Some(n1_file) = files.keys().find(|k| is_n1_path(k)) {
            return Err(vec![format!(
                "Unsupported N1 file '{}': N1 (.n) was discontinued in SiliconCraft 0.2.0. Please migrate to N2 (.n2).",
                n1_file
            )]);
        }
        return Err(vec![
            "Project must contain a 'main.n2' (or 'main.asm' / 'main.s') entrypoint!".to_string(),
        ]);
    };

    if is_n1_path(&entry) {
        return Err(vec![format!(
            "Unsupported N1 file '{}': N1 (.n) was discontinued in SiliconCraft 0.2.0. Please migrate to N2 (.n2).",
            entry
        )]);
    }

    let force_asm = matches!(
        options.target.as_deref().map(|s| s.to_ascii_lowercase()).as_deref(),
        Some("asm" | "assembly")
    );

    if is_assembly_path(&entry) || force_asm {
        let mut errors = Vec::new();
        let mut visiting = Vec::new();
        let assembly = expand_assembly_includes(
            &entry,
            &mut files,
            &options.include_dirs,
            &mut visiting,
            &mut errors,
        );
        if !errors.is_empty() {
            return Err(errors);
        }
        let asm = assemble_with_base(&assembly, &options.include_dirs);
        if !asm.success {
            return Err(asm.errors);
        }
        let name = options
            .output_name
            .as_deref()
            .unwrap_or("program.sc");
        let package = ScPackage {
            name: normalize_sc_name(name),
            target: "asm".to_string(),
            arch: "ASM".to_string(),
            required_instructions: asm.required_instructions,
            bytecode: asm.bytecode,
            include_metadata: true,
        };
        return Ok(BuildOutput { package, assembly });
    }

    let mut errors = Vec::new();
    let merged = resolve_and_merge_project(&entry, &mut files, &options.include_dirs, &mut errors);
    if !errors.is_empty() {
        return Err(errors);
    }

    let mut codegen = CodeGenerator::new(merged);
    let assembly = codegen.generate();
    errors.extend(codegen.errors().iter().cloned());
    if !errors.is_empty() {
        return Err(errors);
    }

    let asm = assemble_with_base(&assembly, &options.include_dirs);
    if !asm.success {
        return Err(asm.errors);
    }

    let mut req: BTreeSet<String> = codegen.required_instructions().clone();
    req.extend(get_required_instructions(&assembly));
    req.extend(asm.required_instructions);

    let name = options
        .output_name
        .as_deref()
        .unwrap_or("program.sc");
    let package = ScPackage {
        name: normalize_sc_name(name),
        target: "n2".to_string(),
        arch: "N2".to_string(),
        required_instructions: req,
        bytecode: asm.bytecode,
        include_metadata: true,
    };

    Ok(BuildOutput { package, assembly })
}

pub fn compile_path(input: &Path, options: &BuildOptions) -> Result<BuildOutput, Vec<String>> {
    if !input.exists() {
        return Err(vec![format!("Input path does not exist: '{}'", input.display())]);
    }

    let default_name = input
        .file_stem()
        .or_else(|| input.file_name())
        .and_then(|s| s.to_str())
        .unwrap_or("program");
    let pkg_name = options
        .output_name
        .clone()
        .unwrap_or_else(|| format!("{}.sc", default_name));

    if input.is_dir() {
        let files = collect_directory_files(input).map_err(|e| vec![e])?;
        let mut dir_opts = options.clone();
        dir_opts.include_dirs.insert(0, input.to_path_buf());
        dir_opts.output_name = Some(pkg_name);
        return compile_project_map(files, &dir_opts);
    }

    let file_str = input.to_string_lossy().to_string();
    if is_n1_path(&file_str) {
        return Err(vec![format!(
            "Unsupported N1 file '{}': N1 (.n) was discontinued in SiliconCraft 0.2.0. Please migrate to N2 (.n2).",
            input.display()
        )]);
    }

    let parent = input.parent().unwrap_or_else(|| Path::new("."));
    let file_name = input
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("main.n2")
        .to_string();

    if is_assembly_path(&file_name)
        || matches!(
            options.target.as_deref().map(|s| s.to_ascii_lowercase()).as_deref(),
            Some("asm" | "assembly")
        )
    {
        let mut files = collect_directory_files(parent).unwrap_or_default();
        let source = fs::read_to_string(input)
            .map_err(|e| vec![format!("Failed to read '{}': {}", input.display(), e)])?;
        files.insert(
            file_name.clone(),
            source.lines().map(|l| l.to_string()).collect(),
        );
        let mut opts = options.clone();
        opts.include_dirs.insert(0, parent.to_path_buf());
        opts.entrypoint = Some(file_name);
        opts.output_name = Some(pkg_name);
        return compile_project_map(files, &opts);
    }

    let source = fs::read_to_string(input)
        .map_err(|e| vec![format!("Failed to read '{}': {}", input.display(), e)])?;

    // Check if the single .n2 file imports relative project files; if so, compile as project rooted at parent dir
    let mut lexer = Lexer::new(&source, Some(&file_name));
    let tokens = lexer.tokenize().map_err(|e| vec![e.to_string()])?;
    let mut parser = Parser::new(tokens, Some(&file_name));
    let ast = parser.parse().map_err(|e| vec![e.to_string()])?;
    let has_project_imports = ast.imports.iter().any(|i| !i.is_library);

    if has_project_imports {
        let mut files = collect_directory_files(parent).map_err(|e| vec![e])?;
        files.insert(
            file_name.clone(),
            source.lines().map(|l| l.to_string()).collect(),
        );
        let mut opts = options.clone();
        opts.include_dirs.insert(0, parent.to_path_buf());
        opts.entrypoint = Some(file_name);
        opts.output_name = Some(pkg_name);
        return compile_project_map(files, &opts);
    }

    compile_n2_source(&source, &file_name, &pkg_name)
}

pub fn init_project(dir: &Path, project_name: &str) -> Result<(), String> {
    let clean_name = project_name
        .trim()
        .strip_suffix(".sc")
        .unwrap_or(project_name.trim());
    let clean_name = if clean_name.is_empty() {
        "my_project"
    } else {
        clean_name
    };

    let src_dir = dir.join("src");
    fs::create_dir_all(&src_dir)
        .map_err(|e| format!("Failed to create '{}': {}", src_dir.display(), e))?;

    let main_n2 = src_dir.join("main.n2");
    if !main_n2.exists() {
        fs::write(
            &main_n2,
            format!(
                "import <std>\nimport \"helper.n2\" as Helper\n\nfunc main()\n    print(\"Booting {}...\")\n    print(Helper.add_one(41))\nend\n",
                clean_name
            ),
        )
        .map_err(|e| format!("Failed to write '{}': {}", main_n2.display(), e))?;
    }

    let helper_n2 = src_dir.join("helper.n2");
    if !helper_n2.exists() {
        fs::write(
            &helper_n2,
            "func add_one(value)\n    return value + 1\nend\n",
        )
        .map_err(|e| format!("Failed to write '{}': {}", helper_n2.display(), e))?;
    }

    let makefile = dir.join("Makefile");
    if !makefile.exists() {
        fs::write(
            &makefile,
            format!(
                "SCC ?= scc\nPROJECT ?= {name}\nSRC_DIR := src\nBUILD_DIR := build\nTARGET := $(BUILD_DIR)/$(PROJECT).sc\nSOURCES := $(shell find $(SRC_DIR) -type f \\( -name '*.n2' -o -name '*.asm' -o -name '*.s' \\))\n\n.PHONY: all clean check\n\nall: $(TARGET)\n\n$(TARGET): $(SOURCES)\n\t@mkdir -p $(BUILD_DIR)\n\t$(SCC) $(SRC_DIR) --entry main.n2 --name $(PROJECT).sc -o $@\n\ncheck:\n\t$(SCC) $(SRC_DIR) --entry main.n2 --name $(PROJECT).sc --check\n\nclean:\n\trm -rf $(BUILD_DIR)\n",
                name = clean_name
            ),
        )
        .map_err(|e| format!("Failed to write '{}': {}", makefile.display(), e))?;
    }

    let cmake = dir.join("CMakeLists.txt");
    if !cmake.exists() {
        fs::write(
            &cmake,
            format!(
                "cmake_minimum_required(VERSION 3.16)\nproject({name} NONE)\n\nfind_program(SCC_EXECUTABLE NAMES scc REQUIRED)\n\nfile(GLOB_RECURSE N2_SOURCES CONFIGURE_DEPENDS\n    \"${{CMAKE_CURRENT_SOURCE_DIR}}/src/*.n2\"\n    \"${{CMAKE_CURRENT_SOURCE_DIR}}/src/*.asm\"\n    \"${{CMAKE_CURRENT_SOURCE_DIR}}/src/*.s\"\n)\n\nset(SC_OUTPUT \"${{CMAKE_CURRENT_BINARY_DIR}}/{name}.sc\")\n\nadd_custom_command(\n    OUTPUT \"${{SC_OUTPUT}}\"\n    COMMAND \"${{SCC_EXECUTABLE}}\" \"${{CMAKE_CURRENT_SOURCE_DIR}}/src\" --entry main.n2 --name \"{name}.sc\" -o \"${{SC_OUTPUT}}\"\n    DEPENDS ${{N2_SOURCES}}\n    COMMENT \"Compiling SiliconCraft N2 project -> {name}.sc\"\n    VERBATIM\n)\n\nadd_custom_target({name}_sc ALL DEPENDS \"${{SC_OUTPUT}}\")\n",
                name = clean_name
            ),
        )
        .map_err(|e| format!("Failed to write '{}': {}", cmake.display(), e))?;
    }

    let gitignore = dir.join(".gitignore");
    if !gitignore.exists() {
        fs::write(&gitignore, "build/\n*.sc\n*.o\n")
            .map_err(|e| format!("Failed to write '{}': {}", gitignore.display(), e))?;
    }

    Ok(())
}
