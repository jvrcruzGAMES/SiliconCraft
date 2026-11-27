use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

use siliconcraft_compiler::binary::{
    decode_scbc, SCBC_MAGIC, SCPK_MAGIC, SECTION_MANIFEST, SECTION_METADATA, SECTION_PROGRAM,
};
use siliconcraft_compiler::sc::{inspect_sc_summary, ScPackage};
use siliconcraft_compiler::{compile_n2_source, compile_path, compile_project_map, init_project, BuildOptions};

fn repo_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("compiler crate must live under repo root")
        .to_path_buf()
}

fn default_opts() -> BuildOptions {
    BuildOptions {
        entrypoint: None,
        include_dirs: Vec::new(),
        output_name: None,
        target: None,
    }
}

#[test]
fn compiles_all_n2_doc_examples() {
    let examples_dir = repo_root().join("docs/n-language/n2/examples");
    assert!(examples_dir.is_dir(), "Missing examples dir: {}", examples_dir.display());

    let mut count = 0usize;
    for entry in fs::read_dir(&examples_dir).expect("read_dir examples") {
        let entry = entry.expect("dir entry");
        let path = entry.path();
        if path.is_file() && path.extension().and_then(|s| s.to_str()) == Some("n2") {
            let res = compile_path(&path, &default_opts())
                .unwrap_or_else(|errs| panic!("Failed to compile {}: {:?}", path.display(), errs));
            assert!(!res.package.bytecode.is_empty(), "Empty bytecode for {}", path.display());
            assert!(res.package.bytecode.starts_with(SCBC_MAGIC));
            let image = decode_scbc(&res.package.bytecode)
                .unwrap_or_else(|e| panic!("Invalid SCBC in {}: {}", path.display(), e));
            assert!(!image.code.is_empty());
            assert_eq!(res.package.target, "n2");
            assert_eq!(res.package.arch, "N2");
            let bytes = res.package.to_bytes().expect("serialize SCPK");
            assert!(bytes.starts_with(SCPK_MAGIC));
            let decoded = ScPackage::from_bytes(&bytes).expect("roundtrip SCPK");
            assert_eq!(decoded.bytecode, res.package.bytecode);
            assert!(inspect_sc_summary(&bytes).is_ok());
            count += 1;
        }
    }
    assert!(count >= 10, "Expected at least 10 N2 examples, found {}", count);
}

#[test]
fn compiles_multi_file_n2_oop_game_project() {
    let project_dir = repo_root().join("docs/n-language/n2/examples/07_oop_game_project");
    let res = compile_path(&project_dir, &default_opts())
        .unwrap_or_else(|errs| panic!("Failed to compile 07_oop_game_project: {:?}", errs));
    assert!(!res.package.bytecode.is_empty());
    assert!(res.package.name.ends_with(".sc"));
    assert_eq!(res.package.arch, "N2");
}

#[test]
fn assembles_all_assembly_doc_examples() {
    let asm_dir = repo_root().join("docs/assembly/examples");
    assert!(asm_dir.is_dir(), "Missing asm examples dir: {}", asm_dir.display());

    let mut count = 0usize;
    for entry in fs::read_dir(&asm_dir).expect("read_dir asm") {
        let entry = entry.expect("dir entry");
        let path = entry.path();
        if path.is_file() && path.extension().and_then(|s| s.to_str()) == Some("asm") {
            let res = compile_path(&path, &default_opts())
                .unwrap_or_else(|errs| panic!("Failed to assemble {}: {:?}", path.display(), errs));
            assert!(!res.package.bytecode.is_empty(), "Empty bytecode for {}", path.display());
            assert_eq!(res.package.target, "asm");
            assert_eq!(res.package.arch, "ASM");
            count += 1;
        }
    }
    assert!(count >= 5, "Expected at least 5 ASM examples, found {}", count);
}

#[test]
fn rejects_discontinued_n1_files() {
    let tmp = std::env::temp_dir().join("scc_test_reject_n1");
    let _ = fs::create_dir_all(&tmp);
    let n1_file = tmp.join("legacy.n");
    fs::write(&n1_file, "var x = 1;\n").unwrap();

    let err = compile_path(&n1_file, &default_opts()).expect_err("Expected .n file to be rejected");
    let msg = err.join("\n");
    assert!(
        msg.contains("N1") && msg.contains("discontinued"),
        "Unexpected error message: {}",
        msg
    );

    let mut map = BTreeMap::new();
    map.insert("main.n".to_string(), vec!["var x = 1;".to_string()]);
    let map_err = compile_project_map(map, &default_opts()).expect_err("Expected main.n in map to fail");
    assert!(map_err.join("\n").contains("N1"));

    let _ = fs::remove_dir_all(&tmp);
}

#[test]
fn compiles_lists_maps_oop_and_pcall() {
    let src = r#"
import <std>

class Counter {
    func init(start)
        this.value = start
    end

    func inc(delta)
        this.value = this.value + delta
        return this.value
    end
}

func risky(flag)
    if flag == 0 then
        error(404)
    end
    return flag * 2
end

func main()
    local c = Counter(10)
    local items = [1, 2, 3]
    local cfg = {"ok": 1, "sum": c.inc(5)}
    local res = pcall(risky, 0)
    print(items[1])
    print(cfg["sum"])
    print(res)
end
"#;
    let out = compile_n2_source(src, "main.n2", "test_oop.sc")
        .unwrap_or_else(|e| panic!("Unexpected failure: {:?}", e));
    assert_eq!(out.package.name, "test_oop.sc");
    assert!(!out.package.bytecode.is_empty());
    assert!(out.package.required_instructions.contains("OUT"));
}

#[test]
fn compiles_gpu_and_controller_apis() {
    let src = r#"
import <std>
import <Glass>
import <GameController>

func main()
    use_gpu()
    gpu_clear(0xFF102030)
    gpu_rect(10, 20, 100, 50, COLOR_WHITE)
    gpu_line(0, 0, 100, 100, COLOR_GREEN)
    gpu_circle(200, 120, 30, COLOR_RED)
    gpu_swap()

    local lx = gc_axis(0, GC_AXIS_LEFT_X)
    local lt = gc_left_trigger(0)
    if gc_left_stick_button(0) == 1 then
        print(lx + lt)
    end
end
"#;
    let out = compile_n2_source(src, "main.n2", "gpu_pad.sc")
        .unwrap_or_else(|e| panic!("Failed GPU + controller compile: {:?}", e));
    assert!(out.package.required_instructions.contains("INAXIS"));
    assert!(out.package.required_instructions.contains("INPAD"));
    assert!(out.package.required_instructions.contains("GPUCLR"));
    assert!(out.package.required_instructions.contains("GPUSWAP"));
}

#[test]
fn compiles_network_runtime_linked_program() {
    let src = r#"
import <std>
import <Network>

func on_status(address, length)
    return http_reply(200, "ok")
end

func received(status, code, address, length)
    return code
end

func main()
    local route = http_route(NET_GET, "/status", on_status)
    http_get("https://example.com", received)
    print(route)
end
"#;
    let out = compile_n2_source(src, "main.n2", "net_demo.sc")
        .unwrap_or_else(|e| panic!("Failed network runtime compile: {:?}", e));
    assert!(out.package.required_instructions.contains("NHTTP"));
    assert!(out.package.required_instructions.contains("NSTATUS"));
}

#[test]
fn resolves_include_dirs_for_n2_and_assembly() {
    let tmp = std::env::temp_dir().join("scc_test_includes");
    let _ = fs::remove_dir_all(&tmp);
    let lib_dir = tmp.join("libs");
    let proj_dir = tmp.join("app");
    fs::create_dir_all(&lib_dir).unwrap();
    fs::create_dir_all(&proj_dir).unwrap();

    fs::write(
        lib_dir.join("mathlib.n2"),
        "func triple(v)\n    return v * 3\nend\n",
    )
    .unwrap();
    fs::write(
        proj_dir.join("main.n2"),
        "import <std>\nimport \"mathlib.n2\" as Math\nfunc main()\n    print(Math.triple(7))\nend\n",
    )
    .unwrap();

    let opts = BuildOptions {
        entrypoint: Some("main.n2".to_string()),
        include_dirs: vec![lib_dir.clone()],
        output_name: Some("inc_n2.sc".to_string()),
        target: None,
    };
    let n2_out = compile_path(&proj_dir, &opts)
        .unwrap_or_else(|e| panic!("Failed N2 -I test: {:?}", e));
    assert!(!n2_out.package.bytecode.is_empty());

    // Assembly include test
    fs::write(lib_dir.join("defs.asm"), "MOV A, 42\n").unwrap();
    fs::write(
        proj_dir.join("boot.asm"),
        "include \"defs.asm\"\nOUT A, 0\nHLT\n",
    )
    .unwrap();
    let asm_opts = BuildOptions {
        entrypoint: Some("boot.asm".to_string()),
        include_dirs: vec![lib_dir],
        output_name: Some("inc_asm.sc".to_string()),
        target: Some("asm".to_string()),
    };
    let asm_out = compile_path(&proj_dir, &asm_opts)
        .unwrap_or_else(|e| panic!("Failed ASM -I test: {:?}", e));
    assert_eq!(asm_out.package.arch, "ASM");
    assert!(!asm_out.package.bytecode.is_empty());

    let _ = fs::remove_dir_all(&tmp);
}

#[test]
fn init_project_scaffolds_and_builds_via_cli() {
    let tmp = std::env::temp_dir().join("scc_test_init_cli");
    let _ = fs::remove_dir_all(&tmp);
    fs::create_dir_all(&tmp).unwrap();

    init_project(&tmp, "demo_app").expect("init_project failed");
    assert!(tmp.join("src/main.n2").is_file());
    assert!(tmp.join("src/helper.n2").is_file());
    assert!(tmp.join("Makefile").is_file());
    assert!(tmp.join("CMakeLists.txt").is_file());

    let sc_path = tmp.join("build/demo_app.sc");
    let bc_path = tmp.join("build/demo_app.o");
    let scc_bin = env!("CARGO_BIN_EXE_scc");
    let status = Command::new(scc_bin)
        .arg(tmp.join("src"))
        .args(["--entry", "main.n2", "--name", "demo_app.sc", "--emit-bytecode"])
        .arg(&bc_path)
        .arg("-o")
        .arg(&sc_path)
        .status()
        .expect("Failed to execute scc binary");
    assert!(status.success(), "scc CLI failed");
    assert!(sc_path.is_file());
    assert!(bc_path.is_file());

    let bytes = fs::read(&sc_path).unwrap();
    assert!(bytes.starts_with(SCPK_MAGIC));
    let info = inspect_sc_summary(&bytes).expect("scc info parser failed");
    assert!(info.contains("demo_app.sc"));
    assert!(info.contains("Target: n2"));

    let bc_bytes = fs::read(&bc_path).unwrap();
    assert!(bc_bytes.starts_with(SCBC_MAGIC));
    let image = decode_scbc(&bc_bytes).expect("decode emitted SCBC");
    assert!(!image.code.is_empty());

    let info_out = Command::new(scc_bin)
        .arg("info")
        .arg(&sc_path)
        .output()
        .expect("Failed to run scc info");
    assert!(info_out.status.success());
    let stdout = String::from_utf8_lossy(&info_out.stdout);
    assert!(stdout.contains("SCPK v3"));
    assert!(stdout.contains("Sections (3): manifest@0x0"));

    // Build again with --no-metadata (2 mandatory sections: manifest + program)
    let stripped_sc_path = tmp.join("build/demo_app_stripped.sc");
    let stripped_status = Command::new(scc_bin)
        .arg(tmp.join("src"))
        .args(["--entry", "main.n2", "--no-metadata", "-o"])
        .arg(&stripped_sc_path)
        .status()
        .expect("Failed to execute scc --no-metadata");
    assert!(stripped_status.success(), "scc --no-metadata failed");
    let stripped_bytes = fs::read(&stripped_sc_path).unwrap();
    let stripped_manifest = ScPackage::parse_manifest(&stripped_bytes).expect("parse stripped manifest");
    assert_eq!(stripped_manifest.sections.len(), 2);
    assert_eq!(stripped_manifest.sections[0].id, SECTION_MANIFEST);
    assert_eq!(stripped_manifest.sections[0].offset, 0);
    assert_eq!(stripped_manifest.sections[1].id, SECTION_PROGRAM);
    let stripped_pkg = ScPackage::from_bytes(&stripped_bytes).expect("parse stripped pkg");
    assert!(!stripped_pkg.include_metadata);
    assert_eq!(stripped_pkg.bytecode, fs::read(&bc_path).unwrap());
    assert!(stripped_pkg.required_instructions.contains("OUT"));

    let _ = fs::remove_dir_all(&tmp);
}

#[test]
fn emits_64bit_scbc_instructions_and_segments() {
    let tmp = std::env::temp_dir().join("scc_test_64bit_binary");
    let _ = fs::remove_dir_all(&tmp);
    fs::create_dir_all(&tmp).unwrap();

    fs::write(tmp.join("blob.bin"), [0xDEu8, 0xAD, 0xBE, 0xEF]).unwrap();
    fs::write(
        tmp.join("main.asm"),
        r#"
            ORG 256
            DB 1, 2, "HI"
            INCBIN "blob.bin"
        start:
            NOP
            MOV A, 123456
            MOV [100], 42
            OUTH "Hello", 0
            FBRECT 10, 20, 30, 40, 0xFF112233
            HLT
        "#,
    )
    .unwrap();

    let out = compile_path(&tmp.join("main.asm"), &default_opts())
        .unwrap_or_else(|e| panic!("Failed 64-bit ASM build: {:?}", e));
    let pkg_bytes = out.package.to_bytes().expect("serialize SCPK v3");
    let manifest = ScPackage::parse_manifest(&pkg_bytes).expect("parse manifest");
    assert_eq!(manifest.sections.len(), 3);
    assert_eq!(manifest.sections[0].id, SECTION_MANIFEST);
    assert_eq!(manifest.sections[0].offset, 0);
    assert_eq!(manifest.sections[1].id, SECTION_METADATA);
    assert_eq!(manifest.sections[2].id, SECTION_PROGRAM);

    let image = decode_scbc(&out.package.bytecode).expect("decode SCBC");
    assert_eq!(image.code.len(), 6);
    assert_eq!(out.package.instruction_count(), 6);
    // 6 instructions * 8 bytes each + headers/segments
    assert_eq!(image.rodata, vec!["Hello".to_string()]);
    assert!(!image.ext.is_empty());
    assert_eq!(image.data_segments.len(), 1);
    assert_eq!(image.data_segments[0].ram_start, 256);
    assert_eq!(
        image.data_segments[0].bytes,
        vec![1, 2, b'H', b'I', 0xDE, 0xAD, 0xBE, 0xEF]
    );

    // Reject manifest that does not start with SECTION_MANIFEST
    let mut bad_first = pkg_bytes.clone();
    bad_first[12] = 0x03; // overwrite entry 0 id with SECTION_PROGRAM
    assert!(ScPackage::from_bytes(&bad_first).is_err());

    let _ = fs::remove_dir_all(&tmp);
}
