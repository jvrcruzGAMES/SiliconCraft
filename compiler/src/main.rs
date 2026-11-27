use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process;

use siliconcraft_compiler::sc::inspect_sc_summary;
use siliconcraft_compiler::{compile_path, init_project, BuildOptions};

const VERSION: &str = env!("CARGO_PKG_VERSION");

fn print_help() {
    println!(
        "\
SiliconCraft N2 Compiler & Assembler (scc) v{VERSION}
Builds N2 (.n2) and Assembly (.asm/.s) projects into SiliconCraft .sc packages.

USAGE:
    scc [build] <INPUT> [OPTIONS]
    scc asm <INPUT.asm> [OPTIONS]
    scc init [DIR] [--name <NAME>]
    scc info <FILE.sc>

ARGS:
    <INPUT>                 Source file (.n2 / .asm / .s) or project directory (defaults to '.')

OPTIONS:
    -o, --output <FILE>     Output .sc file path (defaults to <name>.sc)
    -e, --entry <FILE>      Entrypoint file relative to project root (e.g. main.n2)
    -I, --include <DIR>     Add module/include search directory (repeatable)
    -n, --name <NAME>       Override program display name embedded in the .sc package
    -t, --target <TARGET>   Force target mode: auto (default), n2, or asm
        --emit-asm <FILE>   Write intermediate un-assembled N2 assembly to <FILE>
        --emit-bytecode <F> Write resolved bytecode lines to <FILE>
        --check             Compile and validate without writing .sc output
    -q, --quiet             Suppress status output on success
    -h, --help              Print help information
    -V, --version           Print version information
"
    );
}

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();
    if args.is_empty() {
        print_help();
        return;
    }

    match args[0].as_str() {
        "-h" | "--help" | "help" => {
            print_help();
            return;
        }
        "-V" | "--version" | "version" => {
            println!("scc {}", VERSION);
            return;
        }
        "init" => {
            if let Err(e) = run_init(&args[1..]) {
                eprintln!("error: {}", e);
                process::exit(1);
            }
            return;
        }
        "info" => {
            if let Err(e) = run_info(&args[1..]) {
                eprintln!("error: {}", e);
                process::exit(1);
            }
            return;
        }
        "asm" => {
            if let Err(code) = run_build(&args[1..], Some("asm")) {
                process::exit(code);
            }
            return;
        }
        "build" => {
            if let Err(code) = run_build(&args[1..], None) {
                process::exit(code);
            }
            return;
        }
        _ => {
            if let Err(code) = run_build(&args, None) {
                process::exit(code);
            }
        }
    }
}

fn run_init(args: &[String]) -> Result<(), String> {
    let mut dir: Option<PathBuf> = None;
    let mut name: Option<String> = None;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "-h" | "--help" => {
                println!("Usage: scc init [DIR] [--name <PROJECT_NAME>]");
                return Ok(());
            }
            "-n" | "--name" => {
                i += 1;
                if i >= args.len() {
                    return Err("Missing value for --name".to_string());
                }
                name = Some(args[i].clone());
            }
            arg if !arg.starts_with('-') => {
                dir = Some(PathBuf::from(arg));
            }
            other => return Err(format!("Unknown option '{}'", other)),
        }
        i += 1;
    }

    let target_dir = dir.unwrap_or_else(|| PathBuf::from("."));
    let inferred = name.unwrap_or_else(|| {
        target_dir
            .canonicalize()
            .ok()
            .and_then(|p| p.file_name().map(|n| n.to_string_lossy().to_string()))
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| "my_project".to_string())
    });

    init_project(&target_dir, &inferred)?;
    println!(
        "Initialized SiliconCraft N2 project '{}' in {}",
        inferred,
        target_dir.display()
    );
    Ok(())
}

fn run_info(args: &[String]) -> Result<(), String> {
    if args.is_empty() || args[0] == "-h" || args[0] == "--help" {
        println!("Usage: scc info <FILE.sc>");
        return Ok(());
    }
    let path = Path::new(&args[0]);
    let content = fs::read_to_string(path)
        .map_err(|e| format!("Failed to read '{}': {}", path.display(), e))?;
    let summary = inspect_sc_summary(&content)?;
    println!("{}", summary);
    Ok(())
}

fn run_build(args: &[String], forced_target: Option<&str>) -> Result<(), i32> {
    let mut input: Option<PathBuf> = None;
    let mut output: Option<PathBuf> = None;
    let mut entrypoint: Option<String> = None;
    let mut include_dirs: Vec<PathBuf> = Vec::new();
    let mut name: Option<String> = None;
    let mut target: Option<String> = forced_target.map(|s| s.to_string());
    let mut emit_asm: Option<PathBuf> = None;
    let mut emit_bytecode: Option<PathBuf> = None;
    let mut check_only = false;
    let mut quiet = false;

    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "-h" | "--help" => {
                print_help();
                return Ok(());
            }
            "-o" | "--output" => {
                i += 1;
                if i >= args.len() {
                    eprintln!("error: missing path after {}", args[i - 1]);
                    return Err(2);
                }
                output = Some(PathBuf::from(&args[i]));
            }
            "-e" | "--entry" => {
                i += 1;
                if i >= args.len() {
                    eprintln!("error: missing file after {}", args[i - 1]);
                    return Err(2);
                }
                entrypoint = Some(args[i].clone());
            }
            "-I" | "--include" => {
                i += 1;
                if i >= args.len() {
                    eprintln!("error: missing directory after {}", args[i - 1]);
                    return Err(2);
                }
                include_dirs.push(PathBuf::from(&args[i]));
            }
            arg if arg.starts_with("-I") && arg.len() > 2 => {
                include_dirs.push(PathBuf::from(&arg[2..]));
            }
            "-n" | "--name" => {
                i += 1;
                if i >= args.len() {
                    eprintln!("error: missing name after {}", args[i - 1]);
                    return Err(2);
                }
                name = Some(args[i].clone());
            }
            "-t" | "--target" => {
                i += 1;
                if i >= args.len() {
                    eprintln!("error: missing target after {}", args[i - 1]);
                    return Err(2);
                }
                let t = args[i].to_ascii_lowercase();
                if t != "auto" {
                    target = Some(t);
                }
            }
            "--emit-asm" => {
                i += 1;
                if i >= args.len() {
                    eprintln!("error: missing path after --emit-asm");
                    return Err(2);
                }
                emit_asm = Some(PathBuf::from(&args[i]));
            }
            "--emit-bytecode" => {
                i += 1;
                if i >= args.len() {
                    eprintln!("error: missing path after --emit-bytecode");
                    return Err(2);
                }
                emit_bytecode = Some(PathBuf::from(&args[i]));
            }
            "--check" => {
                check_only = true;
            }
            "-q" | "--quiet" => {
                quiet = true;
            }
            arg if arg.starts_with('-') => {
                eprintln!("error: unknown option '{}'", arg);
                return Err(2);
            }
            arg => {
                if input.is_some() {
                    eprintln!("error: unexpected extra positional argument '{}'", arg);
                    return Err(2);
                }
                input = Some(PathBuf::from(arg));
            }
        }
        i += 1;
    }

    let input_path = input.unwrap_or_else(|| PathBuf::from("."));

    // Derive default package name from -o filename if --name wasn't given
    let derived_name = name.or_else(|| {
        output
            .as_ref()
            .and_then(|p| p.file_name())
            .map(|n| n.to_string_lossy().to_string())
    });

    let options = BuildOptions {
        entrypoint,
        include_dirs,
        output_name: derived_name,
        target,
    };

    let result = match compile_path(&input_path, &options) {
        Ok(res) => res,
        Err(errs) => {
            for err in errs {
                eprintln!("error: {}", err);
            }
            return Err(1);
        }
    };

    if let Some(asm_path) = emit_asm {
        if let Some(parent) = asm_path.parent().filter(|p| !p.as_os_str().is_empty()) {
            let _ = fs::create_dir_all(parent);
        }
        if let Err(e) = fs::write(&asm_path, result.assembly.join("\n") + "\n") {
            eprintln!("error: failed to write '{}': {}", asm_path.display(), e);
            return Err(1);
        }
    }

    if let Some(bc_path) = emit_bytecode {
        if let Some(parent) = bc_path.parent().filter(|p| !p.as_os_str().is_empty()) {
            let _ = fs::create_dir_all(parent);
        }
        if let Err(e) = fs::write(&bc_path, result.package.bytecode.join("\n") + "\n") {
            eprintln!("error: failed to write '{}': {}", bc_path.display(), e);
            return Err(1);
        }
    }

    if check_only {
        if !quiet {
            println!(
                "Check passed: {} ({} instructions, required opcodes: {})",
                result.package.name,
                result.package.bytecode.len(),
                result
                    .package
                    .required_instructions
                    .iter()
                    .cloned()
                    .collect::<Vec<_>>()
                    .join(", ")
            );
        }
        return Ok(());
    }

    let output_path = output.unwrap_or_else(|| {
        if input_path.is_file() {
            let stem = input_path
                .file_stem()
                .and_then(|s| s.to_str())
                .unwrap_or("program");
            input_path.with_file_name(format!("{}.sc", stem))
        } else {
            PathBuf::from(&result.package.name)
        }
    });

    if let Some(parent) = output_path.parent().filter(|p| !p.as_os_str().is_empty()) {
        if let Err(e) = fs::create_dir_all(parent) {
            eprintln!("error: failed to create '{}': {}", parent.display(), e);
            return Err(1);
        }
    }

    let json = result.package.to_json();
    if let Err(e) = fs::write(&output_path, json) {
        eprintln!("error: failed to write '{}': {}", output_path.display(), e);
        return Err(1);
    }

    if !quiet {
        println!(
            "Built {} -> {} ({} instructions, arch={})",
            result.package.name,
            output_path.display(),
            result.package.bytecode.len(),
            result.package.arch
        );
    }

    Ok(())
}
