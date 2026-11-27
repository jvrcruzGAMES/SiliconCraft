use std::collections::{BTreeSet, HashMap, HashSet, VecDeque};

use crate::ast::*;
use crate::lexer::Lexer;
use crate::library::{
    get_library, get_suggested_library_for_symbol, network_apis, normalize_lib_path, SymbolInfo,
    SymbolType,
};
use crate::parser::Parser;
use crate::validator::is_valid_instruction;

const NETWORK_N2_SOURCE: &str = include_str!("network.n2");
const NETWORK_PREFIX: &str = "__net_";

pub struct CodeGenerator {
    program: Program,
    lines: Vec<String>,
    data_lines: Vec<String>,
    required_instructions: BTreeSet<String>,
    errors: Vec<String>,

    label_counter: usize,
    string_counter: usize,
    needs_gpu_tri_scratch: bool,
    string_pool: Vec<(String, String)>,
    string_pool_map: HashMap<String, String>,

    global_vars: BTreeSet<String>,
    current_locals: BTreeSet<String>,
    current_function: Option<String>,
    current_class: Option<String>,

    class_field_offsets: HashMap<String, HashMap<String, usize>>,
    class_order: Vec<String>,
    class_ids: HashMap<String, usize>,
    class_methods: HashMap<String, HashSet<String>>,
    string_vars: HashSet<String>,
    string_fields: HashSet<String>,

    imported_symbols: HashMap<String, SymbolInfo>,

    function_arities: HashMap<String, usize>,
    network_functions: Vec<String>,
    network_functions_set: HashSet<String>,
    network_link_queue: VecDeque<String>,
    linking_network_runtime: bool,
}

impl CodeGenerator {
    pub fn new(program: Program) -> Self {
        Self {
            program,
            lines: Vec::new(),
            data_lines: Vec::new(),
            required_instructions: BTreeSet::new(),
            errors: Vec::new(),
            label_counter: 0,
            string_counter: 0,
            needs_gpu_tri_scratch: false,
            string_pool: Vec::new(),
            string_pool_map: HashMap::new(),
            global_vars: BTreeSet::new(),
            current_locals: BTreeSet::new(),
            current_function: None,
            current_class: None,
            class_field_offsets: HashMap::new(),
            class_order: Vec::new(),
            class_ids: HashMap::new(),
            class_methods: HashMap::new(),
            string_vars: HashSet::new(),
            string_fields: HashSet::new(),
            imported_symbols: HashMap::new(),
            function_arities: HashMap::new(),
            network_functions: Vec::new(),
            network_functions_set: HashSet::new(),
            network_link_queue: VecDeque::new(),
            linking_network_runtime: false,
        }
    }

    pub fn errors(&self) -> &[String] {
        &self.errors
    }

    pub fn required_instructions(&self) -> &BTreeSet<String> {
        &self.required_instructions
    }

    pub fn generate(&mut self) -> Vec<String> {
        self.process_imports();
        self.analyze_classes_and_fields();
        for node in &self.program.statements {
            if let Stmt::Function(fn_decl) = node {
                self.function_arities
                    .insert(fn_decl.name.clone(), fn_decl.params.len());
            }
        }

        // 1. Entry jump to start
        self.emit_req("JMP _start", "JMP");

        // 2. String pointer print subroutine
        self.emit_comment("--- String Pointer Print Subroutine ---");
        self.emit_label("_print_str");
        self.emit_req("MOV B, A", "MOV");
        self.emit_label("_print_str_loop");
        self.emit_req("MOV A, [B]", "MOV");
        self.emit_req("CMP A, 0", "CMP");
        self.emit_req("JZ _print_str_end", "JZ");
        self.emit_req("OUTC A", "OUTC");
        self.emit_req("INC B", "INC");
        self.emit_req("JMP _print_str_loop", "JMP");
        self.emit_label("_print_str_end");
        self.emit_req("RET", "RET");

        // 3. Classes and Methods
        let stmts = self.program.statements.clone();
        for node in &stmts {
            if let Stmt::Class(cls) = node {
                self.generate_class(cls);
            }
        }

        // 4. Top-level Functions
        for node in &stmts {
            if let Stmt::Function(func) = node {
                self.generate_function(func);
            }
        }

        // 5. Execution entry point
        self.emit_comment("--- Execution Entry Point ---");
        self.emit_label("_start");
        let heap_initialization = self.lines.len();
        self.emit_req("MOV [_this_ptr], 0", "MOV");

        let string_initialization = self.lines.len();

        for node in &stmts {
            if !matches!(node, Stmt::Class(_) | Stmt::Function(_)) {
                self.generate_statement(node);
            }
        }

        let has_main = stmts
            .iter()
            .any(|node| matches!(node, Stmt::Function(fn_decl) if fn_decl.name == "main"));
        if has_main {
            self.emit_req("CALL fn_main", "CALL");
        }

        self.emit_req("HLT", "HLT");

        self.link_network_runtime();
        self.initialize_strings(string_initialization);

        if self.lines.iter().any(|l| l == "CALL _alloc") {
            let heap_base = (6 + self.global_vars.len() + self.data_lines.len()).max(16384);
            self.lines
                .insert(heap_initialization, format!("GALLOC 0, {}", heap_base));
            self.emit_label("_alloc");
            self.emit_req(&format!("GALLOC A, {}", heap_base), "GALLOC");
            self.emit_req("RET", "RET");
        }

        // 6. Data Section
        self.emit_comment("--- Data Section ---");
        self.emit("_this_ptr: DB 0");
        self.emit("_tmp_a: DB 0");
        self.emit("_tmp_b: DB 0");
        self.emit("_tmp_c: DB 0");
        self.emit("_tmp_d: DB 0");
        self.emit("_pcall_val: DB 0");
        if self.needs_gpu_tri_scratch {
            for g in 0..7 {
                self.emit(&format!("_tmp_g{}: DB 0", g));
            }
        }

        let globals: Vec<String> = self.global_vars.iter().cloned().collect();
        for g in globals {
            self.emit(&format!("g_{}: DB 0", g));
        }

        let data = self.data_lines.clone();
        for d in data {
            self.emit(&d);
        }

        self.lines.clone()
    }

    fn initialize_strings(&mut self, index: usize) {
        if self.string_pool.is_empty() {
            return;
        }
        let mut init = Vec::new();
        for (text, label) in &self.string_pool {
            let chars: Vec<u16> = text.encode_utf16().collect();
            init.push(format!("MOV A, {}", chars.len() + 1));
            init.push("CALL _alloc".to_string());
            init.push(format!("MOV [{}], A", label));
            init.push("MOV B, A".to_string());
            for ch in chars {
                init.push(format!("MOV [B], {}", ch));
                init.push("INC B".to_string());
            }
            init.push("MOV [B], 0".to_string());
        }
        self.lines.splice(index..index, init);
        self.required_instructions.insert("MOV".to_string());
        self.required_instructions.insert("INC".to_string());
        self.required_instructions.insert("CALL".to_string());
    }

    fn queue_network_function(&mut self, name: &str) {
        if self.network_functions_set.insert(name.to_string()) {
            self.network_functions.push(name.to_string());
            self.network_link_queue.push_back(name.to_string());
        }
    }

    fn link_network_runtime(&mut self) {
        if self.network_link_queue.is_empty() {
            return;
        }
        let path = "/siliconcraft/n2/network.n2";
        let mut lexer = Lexer::new(NETWORK_N2_SOURCE, Some(path));
        let tokens = match lexer.tokenize() {
            Ok(t) => t,
            Err(e) => {
                self.errors.push(format!("Network runtime lexer error: {}", e));
                return;
            }
        };
        let mut parser = Parser::new(tokens, Some(path));
        let program = match parser.parse() {
            Ok(p) => p,
            Err(e) => {
                self.errors.push(format!("Network runtime parser error: {}", e));
                return;
            }
        };
        let mut functions: HashMap<String, FunctionDecl> = HashMap::new();
        for stmt in program.statements {
            if let Stmt::Function(func) = stmt {
                if !func.name.starts_with(NETWORK_PREFIX)
                    || functions.insert(func.name.clone(), func).is_some()
                {
                    self.errors
                        .push("Invalid declaration in N2 Network runtime".to_string());
                    return;
                }
            } else {
                self.errors
                    .push("Invalid declaration in N2 Network runtime".to_string());
                return;
            }
        }

        for name in functions.keys() {
            if self.function_arities.contains_key(name) {
                self.errors.push(format!(
                    "Function name '{}' is reserved by the Network library",
                    name
                ));
                return;
            }
        }

        let user_imports = self.imported_symbols.clone();
        if let Some(net_lib) = get_library("network") {
            self.imported_symbols.extend(net_lib);
        }
        if let Some(std_lib) = get_library("std") {
            self.imported_symbols.extend(std_lib);
        }
        self.linking_network_runtime = true;

        while let Some(name) = self.network_link_queue.pop_front() {
            if let Some(func) = functions.get(&name).cloned() {
                self.generate_function(&func);
            } else {
                self.errors
                    .push(format!("Missing Network library function '{}'", name));
            }
        }

        self.linking_network_runtime = false;
        self.imported_symbols = user_imports;
    }

    fn process_imports(&mut self) {
        let imports = self.program.imports.clone();
        for imp in imports {
            if imp.is_library {
                let lib_name = normalize_lib_path(&imp.path);
                let Some(lib) = get_library(&lib_name) else {
                    self.errors.push(format!(
                        "Unknown system library: <{}>. Available: <lib/std>, <lib/io>, <lib/math>, <lib/graphics>, <lib/audio>, <lib/input>, <lib/storage>, <lib/time>",
                        lib_name
                    ));
                    continue;
                };
                if imp.symbols.is_empty() {
                    self.imported_symbols.extend(lib);
                } else {
                    for sym in imp.symbols {
                        if let Some(info) = lib.get(&sym) {
                            self.imported_symbols.insert(sym, info.clone());
                        } else {
                            self.errors.push(format!(
                                "Symbol '{}' not found in library <{}>",
                                sym, lib_name
                            ));
                        }
                    }
                }
            }
        }
    }

    fn analyze_classes_and_fields(&mut self) {
        let mut next_class_id = 1usize;
        let stmts = self.program.statements.clone();
        for node in &stmts {
            if let Stmt::Class(cls) = node {
                self.class_ids.insert(cls.name.clone(), next_class_id);
                next_class_id += 1;
                self.class_order.push(cls.name.clone());

                let mut field_order: Vec<String> = Vec::new();
                for method in &cls.methods {
                    self.class_methods
                        .entry(cls.name.clone())
                        .or_default()
                        .insert(method.name.clone());
                    Self::find_this_fields_in_statements(&method.body, &mut field_order);
                }
                let mut field_map = HashMap::new();
                for (idx, field_name) in field_order.into_iter().enumerate() {
                    field_map.insert(field_name, idx + 1);
                }
                self.class_field_offsets.insert(cls.name.clone(), field_map);
            }
        }
        self.find_string_vars_and_fields(&stmts);
        self.pre_scan_strings(&stmts);
    }

    fn find_this_fields_in_statements(stmts: &[Stmt], field_order: &mut Vec<String>) {
        for stmt in stmts {
            match stmt {
                Stmt::Assign {
                    target: AssignTarget::Field { object, field, .. },
                    ..
                } => {
                    if matches!(**object, Expr::This { .. }) && !field_order.contains(field) {
                        field_order.push(field.clone());
                    }
                }
                Stmt::If {
                    then_body,
                    elseifs,
                    else_body,
                    ..
                } => {
                    Self::find_this_fields_in_statements(then_body, field_order);
                    for e in elseifs {
                        Self::find_this_fields_in_statements(&e.body, field_order);
                    }
                    Self::find_this_fields_in_statements(else_body, field_order);
                }
                Stmt::While { body, .. } | Stmt::ForRange { body, .. } => {
                    Self::find_this_fields_in_statements(body, field_order);
                }
                _ => {}
            }
        }
    }

    fn find_string_vars_and_fields(&mut self, stmts: &[Stmt]) {
        for stmt in stmts {
            match stmt {
                Stmt::VarDecl {
                    name,
                    init_expr: Some(expr),
                    ..
                } if self.is_text_expression(expr) => {
                    self.string_vars.insert(name.clone());
                }
                Stmt::Assign { target, value, .. } => match target {
                    AssignTarget::Var { name, .. } if self.is_text_expression(value) => {
                        self.string_vars.insert(name.clone());
                    }
                    AssignTarget::Field { field, .. } => {
                        let is_str = matches!(value, Expr::String { .. })
                            || matches!(value, Expr::Variable { name, .. } if name.contains("name") || name.contains("str") || self.string_vars.contains(name));
                        if is_str {
                            self.string_fields.insert(field.clone());
                        }
                    }
                    _ => {}
                },
                Stmt::Class(cls) => {
                    for m in &cls.methods {
                        self.find_string_vars_and_fields(&m.body);
                    }
                }
                Stmt::Function(fn_decl) => {
                    self.find_string_vars_and_fields(&fn_decl.body);
                }
                Stmt::If {
                    then_body,
                    else_body,
                    ..
                } => {
                    self.find_string_vars_and_fields(then_body);
                    self.find_string_vars_and_fields(else_body);
                }
                Stmt::While { body, .. } => {
                    self.find_string_vars_and_fields(body);
                }
                _ => {}
            }
        }
    }

    fn pre_scan_strings(&mut self, stmts: &[Stmt]) {
        for s in stmts {
            self.scan_strings_in_stmt(s);
        }
    }

    fn scan_strings_in_stmt(&mut self, stmt: &Stmt) {
        match stmt {
            Stmt::Class(cls) => {
                for m in &cls.methods {
                    self.pre_scan_strings(&m.body);
                }
            }
            Stmt::Function(fn_decl) => {
                self.pre_scan_strings(&fn_decl.body);
            }
            Stmt::VarDecl {
                init_expr: Some(expr),
                ..
            } => self.scan_strings_in_expr(expr),
            Stmt::Assign { value, .. } => self.scan_strings_in_expr(value),
            Stmt::ExprStmt { expr, .. } => self.scan_strings_in_expr(expr),
            Stmt::If {
                condition,
                then_body,
                elseifs,
                else_body,
                ..
            } => {
                self.scan_strings_in_expr(condition);
                self.pre_scan_strings(then_body);
                for b in elseifs {
                    self.scan_strings_in_expr(&b.condition);
                    self.pre_scan_strings(&b.body);
                }
                self.pre_scan_strings(else_body);
            }
            Stmt::While {
                condition, body, ..
            } => {
                self.scan_strings_in_expr(condition);
                self.pre_scan_strings(body);
            }
            Stmt::ForRange {
                start_expr,
                end_expr,
                step_expr,
                body,
                ..
            } => {
                self.scan_strings_in_expr(start_expr);
                self.scan_strings_in_expr(end_expr);
                if let Some(step) = step_expr {
                    self.scan_strings_in_expr(step);
                }
                self.pre_scan_strings(body);
            }
            Stmt::Return {
                expr: Some(expr), ..
            } => self.scan_strings_in_expr(expr),
            _ => {}
        }
    }

    fn scan_strings_in_expr(&mut self, expr: &Expr) {
        match expr {
            Expr::String { value, .. } => {
                self.get_string_literal_label(value);
            }
            Expr::BinaryOp { left, right, .. } => {
                self.scan_strings_in_expr(left);
                self.scan_strings_in_expr(right);
            }
            Expr::UnaryOp { operand, .. } => self.scan_strings_in_expr(operand),
            Expr::Call { args, .. } | Expr::SystemCall { args, .. } | Expr::New { args, .. } => {
                for a in args {
                    self.scan_strings_in_expr(a);
                }
            }
            Expr::MethodCall { object, args, .. } => {
                self.scan_strings_in_expr(object);
                for a in args {
                    self.scan_strings_in_expr(a);
                }
            }
            Expr::List { elements, .. } => {
                for el in elements {
                    self.scan_strings_in_expr(el);
                }
            }
            Expr::Map { entries, .. } => {
                for me in entries {
                    self.scan_strings_in_expr(&me.key);
                    self.scan_strings_in_expr(&me.value);
                }
            }
            Expr::FieldAccess { object, .. } => self.scan_strings_in_expr(object),
            Expr::IndexAccess {
                container, index, ..
            } => {
                self.scan_strings_in_expr(container);
                self.scan_strings_in_expr(index);
            }
            _ => {}
        }
    }

    fn generate_class(&mut self, cls: &ClassDecl) {
        self.current_class = Some(cls.name.clone());
        self.emit_comment(&format!("=== Class: {} ===", cls.name));
        for method in &cls.methods {
            self.generate_method(&cls.name, method);
        }
        self.current_class = None;
    }

    fn generate_method(&mut self, class_name: &str, method: &MethodDecl) {
        let label = format!("m_{}_{}", class_name, method.name);
        self.emit_comment(&format!("Method: {}.{}", class_name, method.name));
        self.emit_label(&label);

        self.current_function = Some(label.clone());
        self.current_locals.clear();

        for param in method.params.iter().rev() {
            let slot = format!("v_{}_{}", label, param);
            self.data_lines.push(format!("{}: DB 0", slot));
            self.current_locals.insert(param.clone());
            self.emit_req("POP B", "POP");
            self.emit_req(&format!("MOV [{}], B", slot), "MOV");
        }

        self.emit_req("PUSH [_this_ptr]", "PUSH");
        self.emit_req("MOV [_this_ptr], A", "MOV");

        for stmt in &method.body {
            self.generate_statement(stmt);
        }

        self.emit_label(&format!("{}_epilogue", label));
        self.clear_local_slots();
        self.emit_req("POP [_this_ptr]", "POP");
        self.emit_req("RET", "RET");

        self.current_function = None;
        self.current_locals.clear();
    }

    fn generate_function(&mut self, func: &FunctionDecl) {
        let label = format!("fn_{}", func.name);
        self.emit_comment(&format!("Function: {}", func.name));
        self.emit_label(&label);

        self.current_function = Some(label.clone());
        self.current_locals.clear();

        for param in func.params.iter().rev() {
            let slot = format!("v_{}_{}", label, param);
            self.data_lines.push(format!("{}: DB 0", slot));
            self.current_locals.insert(param.clone());
            self.emit_req("POP B", "POP");
            self.emit_req(&format!("MOV [{}], B", slot), "MOV");
        }

        for stmt in &func.body {
            self.generate_statement(stmt);
        }

        self.emit_label(&format!("{}_epilogue", label));
        self.clear_local_slots();
        self.emit_req("RET", "RET");

        self.current_function = None;
        self.current_locals.clear();
    }

    fn clear_local_slots(&mut self) {
        if let Some(cur_fn) = self.current_function.clone() {
            let locals: Vec<String> = self.current_locals.iter().cloned().collect();
            for name in locals {
                self.emit_req(&format!("MOV [v_{}_{}], 0", cur_fn, name), "MOV");
            }
        }
    }

    fn generate_statement(&mut self, stmt: &Stmt) {
        match stmt {
            Stmt::VarDecl {
                name,
                secondary_names,
                is_local,
                init_expr,
                ..
            } => {
                if *is_local && self.current_function.is_some() {
                    let cur_fn = self.current_function.as_ref().unwrap().clone();
                    self.current_locals.insert(name.clone());
                    let slot = format!("v_{}_{}", cur_fn, name);
                    self.data_lines.push(format!("{}: DB 0", slot));
                    for sec in secondary_names {
                        self.current_locals.insert(sec.clone());
                        self.data_lines
                            .push(format!("v_{}_{}: DB 0", cur_fn, sec));
                    }
                    if let Some(init) = init_expr {
                        self.generate_expression(init);
                        self.emit_req(&format!("MOV [{}], A", slot), "MOV");
                        if let Some(first_sec) = secondary_names.first() {
                            let sec_slot = format!("v_{}_{}", cur_fn, first_sec);
                            self.emit_req(&format!("MOV [{}], [_pcall_val]", sec_slot), "MOV");
                        }
                    }
                } else {
                    self.global_vars.insert(name.clone());
                    for sec in secondary_names {
                        self.global_vars.insert(sec.clone());
                    }
                    if let Some(init) = init_expr {
                        self.generate_expression(init);
                        self.emit_req(&format!("MOV [g_{}], A", name), "MOV");
                        if let Some(first_sec) = secondary_names.first() {
                            self.emit_req(&format!("MOV [g_{}], [_pcall_val]", first_sec), "MOV");
                        }
                    }
                }
            }
            Stmt::Assign {
                target,
                secondary_targets,
                op,
                value,
                ..
            } => {
                self.generate_assign(target, secondary_targets, op, value);
            }
            Stmt::ExprStmt { expr, .. } => {
                self.generate_expression(expr);
            }
            Stmt::If {
                condition,
                then_body,
                elseifs,
                else_body,
                ..
            } => {
                self.generate_if(condition, then_body, elseifs, else_body);
            }
            Stmt::While {
                condition, body, ..
            } => {
                self.generate_while(condition, body);
            }
            Stmt::ForRange {
                var_name,
                start_expr,
                end_expr,
                step_expr,
                body,
                ..
            } => {
                self.generate_for(var_name, start_expr, end_expr, step_expr.as_ref(), body);
            }
            Stmt::Return { expr, .. } => {
                if let Some(e) = expr {
                    self.generate_expression(e);
                }
                if let Some(cur_fn) = &self.current_function {
                    let target = format!("JMP {}_epilogue", cur_fn);
                    self.emit_req(&target, "JMP");
                } else {
                    self.emit_req("RET", "RET");
                }
            }
            Stmt::Break { .. } | Stmt::Continue { .. } => {}
            Stmt::InlineAssembly { lines, .. } => {
                for asm_line in lines {
                    self.emit(asm_line);
                    let stripped = asm_line.trim();
                    let opcode = match stripped.find(' ') {
                        Some(space) => stripped[..space].to_ascii_uppercase(),
                        None => stripped.to_ascii_uppercase(),
                    };
                    if is_valid_instruction(&opcode) {
                        self.required_instructions.insert(opcode);
                    }
                }
            }
            Stmt::Class(_) | Stmt::Function(_) => {}
        }
    }

    fn generate_assign(
        &mut self,
        target: &AssignTarget,
        secondary_targets: &[AssignTarget],
        op: &str,
        value: &Expr,
    ) {
        match target {
            AssignTarget::Var { name, .. } => {
                let slot = self.resolve_var_slot(name);
                self.generate_expression(value);
                if op != "=" {
                    self.emit_req(&format!("MOV B, [{}]", slot), "MOV");
                    match op {
                        "+=" => {
                            self.emit_req("ADD B, A", "ADD");
                            self.emit_req("MOV A, B", "MOV");
                        }
                        "-=" => {
                            self.emit_req("SUB B, A", "SUB");
                            self.emit_req("MOV A, B", "MOV");
                        }
                        "*=" => {
                            self.emit_req("MUL B, A", "MUL");
                            self.emit_req("MOV A, B", "MOV");
                        }
                        "/=" => {
                            self.emit_req("DIV B, A", "DIV");
                            self.emit_req("MOV A, B", "MOV");
                        }
                        _ => {}
                    }
                }
                self.emit_req(&format!("MOV [{}], A", slot), "MOV");
                if let Some(AssignTarget::Var { name: sec_name, .. }) = secondary_targets.first() {
                    let sec_slot = self.resolve_var_slot(sec_name);
                    self.emit_req(&format!("MOV [{}], [_pcall_val]", sec_slot), "MOV");
                }
            }
            AssignTarget::Field { object, field, .. } => {
                self.generate_expression(value);
                self.emit_req("PUSH A", "PUSH");
                if matches!(**object, Expr::This { .. }) {
                    self.emit_req("MOV B, [_this_ptr]", "MOV");
                } else {
                    self.generate_expression(object);
                    self.emit_req("MOV B, A", "MOV");
                }
                let offset = self.resolve_field_offset(field);
                self.emit_req(&format!("ADD B, {}", offset), "ADD");
                self.emit_req("POP A", "POP");
                self.emit_req("MOV [B], A", "MOV");
            }
            AssignTarget::Index {
                container, index, ..
            } => {
                self.generate_expression(value);
                self.emit_req("PUSH A", "PUSH");
                self.generate_expression(container);
                self.emit_req("PUSH A", "PUSH");
                self.generate_expression(index);
                self.emit_req("MOV C, A", "MOV");
                self.emit_req("POP B", "POP");
                self.emit_req("ADD B, 2", "ADD");
                self.emit_req("ADD B, C", "ADD");
                self.emit_req("POP A", "POP");
                self.emit_req("MOV [B], A", "MOV");
            }
        }
    }

    fn next_label_id(&mut self) -> usize {
        self.label_counter += 1;
        self.label_counter
    }

    fn generate_if(
        &mut self,
        condition: &Expr,
        then_body: &[Stmt],
        elseifs: &[ElseIfBranch],
        else_body: &[Stmt],
    ) {
        let id = self.next_label_id();
        let else_label = format!("_else_{}", id);
        let end_label = format!("_endif_{}", id);

        self.generate_condition_jump(condition, &else_label, false);
        for s in then_body {
            self.generate_statement(s);
        }
        self.emit_req(&format!("JMP {}", end_label), "JMP");

        self.emit_label(&else_label);
        for elif in elseifs {
            let elif_id = self.next_label_id();
            let next_elif = format!("_elif_{}", elif_id);
            self.generate_condition_jump(&elif.condition, &next_elif, false);
            for s in &elif.body {
                self.generate_statement(s);
            }
            self.emit_req(&format!("JMP {}", end_label), "JMP");
            self.emit_label(&next_elif);
        }

        for s in else_body {
            self.generate_statement(s);
        }
        self.emit_label(&end_label);
    }

    fn generate_while(&mut self, condition: &Expr, body: &[Stmt]) {
        let id = self.next_label_id();
        let start_label = format!("_while_start_{}", id);
        let end_label = format!("_while_end_{}", id);

        self.emit_label(&start_label);
        self.generate_condition_jump(condition, &end_label, false);

        for s in body {
            match s {
                Stmt::Break { .. } => self.emit_req(&format!("JMP {}", end_label), "JMP"),
                Stmt::Continue { .. } => self.emit_req(&format!("JMP {}", start_label), "JMP"),
                _ => self.generate_statement(s),
            }
        }
        self.emit_req(&format!("JMP {}", start_label), "JMP");
        self.emit_label(&end_label);
    }

    fn generate_for(
        &mut self,
        var_name: &str,
        start_expr: &Expr,
        end_expr: &Expr,
        step_expr: Option<&Expr>,
        body: &[Stmt],
    ) {
        let id = self.next_label_id();
        let start_label = format!("_for_start_{}", id);
        let end_label = format!("_for_end_{}", id);
        let var_slot = self.resolve_var_slot(var_name);

        self.generate_expression(start_expr);
        self.emit_req(&format!("MOV [{}], A", var_slot), "MOV");
        self.emit_label(&start_label);

        self.emit_req(&format!("MOV A, [{}]", var_slot), "MOV");
        self.emit_req("PUSH A", "PUSH");
        self.generate_expression(end_expr);
        self.emit_req("MOV B, A", "MOV");
        self.emit_req("POP A", "POP");
        self.emit_req("CMP A, B", "CMP");
        self.emit_req(&format!("JG {}", end_label), "JG");

        for s in body {
            match s {
                Stmt::Break { .. } => self.emit_req(&format!("JMP {}", end_label), "JMP"),
                Stmt::Continue { .. } => {}
                _ => self.generate_statement(s),
            }
        }

        if let Some(step) = step_expr {
            self.generate_expression(step);
            self.emit_req(&format!("ADD [{}], A", var_slot), "ADD");
        } else {
            self.emit_req(&format!("INC [{}]", var_slot), "INC");
        }

        self.emit_req(&format!("JMP {}", start_label), "JMP");
        self.emit_label(&end_label);
    }

    fn generate_condition_jump(&mut self, cond: &Expr, target_label: &str, jump_if_true: bool) {
        if let Expr::BinaryOp { op, left, right, .. } = cond {
            if matches!(op.as_str(), "==" | "!=" | "<" | "<=" | ">" | ">=") {
                self.generate_expression(left);
                self.emit_req("PUSH A", "PUSH");
                self.generate_expression(right);
                self.emit_req("MOV B, A", "MOV");
                self.emit_req("POP A", "POP");
                self.emit_req("CMP A, B", "CMP");

                let jump = if jump_if_true {
                    match op.as_str() {
                        "==" => "JZ",
                        "!=" => "JNZ",
                        "<" => "JL",
                        "<=" => "JLE",
                        ">" => "JG",
                        ">=" => "JGE",
                        _ => unreachable!(),
                    }
                } else {
                    match op.as_str() {
                        "==" => "JNZ",
                        "!=" => "JZ",
                        "<" => "JGE",
                        "<=" => "JG",
                        ">" => "JLE",
                        ">=" => "JL",
                        _ => unreachable!(),
                    }
                };
                self.emit_req(&format!("{} {}", jump, target_label), jump);
                return;
            }
        }

        self.generate_expression(cond);
        self.emit_req("CMP A, 0", "CMP");
        if jump_if_true {
            self.emit_req(&format!("JNZ {}", target_label), "JNZ");
        } else {
            self.emit_req(&format!("JZ {}", target_label), "JZ");
        }
    }

    fn generate_expression(&mut self, expr: &Expr) {
        match expr {
            Expr::Number { value, .. } => self.emit_req(&format!("MOV A, {}", value), "MOV"),
            Expr::Boolean { value, .. } => {
                self.emit_req(&format!("MOV A, {}", if *value { 1 } else { 0 }), "MOV")
            }
            Expr::Nil { .. } => self.emit_req("MOV A, 0", "MOV"),
            Expr::String { value, .. } => {
                let label = self.get_string_literal_label(value);
                self.emit_req(&format!("MOV A, [{}]", label), "MOV");
            }
            Expr::This { .. } => self.emit_req("MOV A, [_this_ptr]", "MOV"),
            Expr::Variable { name, line, column } => self.generate_variable_read(name, *line, *column),
            Expr::BinaryOp { op, left, right, .. } => self.generate_binary_op(op, left, right),
            Expr::UnaryOp { op, operand, .. } => self.generate_unary_op(op, operand),
            Expr::Call {
                callee,
                args,
                line,
                column,
            } => self.generate_call(callee, None, args, *line, *column),
            Expr::SystemCall {
                symbol,
                args,
                line,
                column,
            } => self.generate_call(&symbol.name, Some(symbol), args, *line, *column),
            Expr::MethodCall {
                object,
                method_name,
                args,
                ..
            } => self.generate_method_call(object, method_name, args),
            Expr::FieldAccess {
                object, field_name, ..
            } => self.generate_field_access(object, field_name),
            Expr::IndexAccess {
                container, index, ..
            } => self.generate_index_access(container, index),
            Expr::New {
                class_name, args, ..
            } => self.generate_new(class_name, args),
            Expr::List { elements, .. } => self.generate_list_literal(elements),
            Expr::Map { entries, .. } => self.generate_map_literal(entries),
        }
    }

    fn generate_variable_read(&mut self, name: &str, line: usize, col: usize) {
        if let Some(sym) = self.imported_symbols.get(name) {
            if sym.kind == SymbolType::Constant {
                if let Some(val) = sym.int_value {
                    self.emit_req(&format!("MOV A, {}", val), "MOV");
                    return;
                }
            }
        }
        if let Some(suggested_lib) = get_suggested_library_for_symbol(name) {
            if !self.imported_symbols.contains_key(name)
                && !self.current_locals.contains(name)
                && !self.global_vars.contains(name)
            {
                self.errors.push(format!(
                    "Line {}:{}: Undefined symbol '{}'. Did you forget to 'import <{}>'?",
                    line, col, name, suggested_lib
                ));
            }
        }
        let slot = self.resolve_var_slot(name);
        self.emit_req(&format!("MOV A, [{}]", slot), "MOV");
    }

    fn generate_binary_op(&mut self, op: &str, left: &Expr, right: &Expr) {
        self.generate_expression(left);
        self.emit_req("PUSH A", "PUSH");
        self.generate_expression(right);
        self.emit_req("MOV B, A", "MOV");
        self.emit_req("POP A", "POP");

        match op {
            "+" => self.emit_req("ADD A, B", "ADD"),
            "-" => self.emit_req("SUB A, B", "SUB"),
            "*" => self.emit_req("MUL A, B", "MUL"),
            "/" => self.emit_req("DIV A, B", "DIV"),
            "%" => self.emit_req("MOD A, B", "MOD"),
            "&" => self.emit_req("AND A, B", "AND"),
            "|" => self.emit_req("OR A, B", "OR"),
            "^" => self.emit_req("XOR A, B", "XOR"),
            "<<" => self.emit_req("SHL A, B", "SHL"),
            ">>" => self.emit_req("SHR A, B", "SHR"),
            "==" | "!=" | "~=" | "<" | "<=" | ">" | ">=" => {
                let id = self.next_label_id();
                let true_lbl = format!("_cmp_true_{}", id);
                let end_lbl = format!("_cmp_end_{}", id);
                let jump = match op {
                    "==" => "JZ",
                    "!=" | "~=" => "JNZ",
                    "<" => "JL",
                    "<=" => "JLE",
                    ">" => "JG",
                    ">=" => "JGE",
                    _ => unreachable!(),
                };
                self.emit_req("CMP A, B", "CMP");
                self.emit_req(&format!("{} {}", jump, true_lbl), jump);
                self.emit_req("MOV A, 0", "MOV");
                self.emit_req(&format!("JMP {}", end_lbl), "JMP");
                self.emit_label(&true_lbl);
                self.emit_req("MOV A, 1", "MOV");
                self.emit_label(&end_lbl);
            }
            "and" => self.emit_req("AND A, B", "AND"),
            "or" => self.emit_req("OR A, B", "OR"),
            _ => {}
        }
    }

    fn generate_unary_op(&mut self, op: &str, operand: &Expr) {
        self.generate_expression(operand);
        match op {
            "-" => {
                self.emit_req("MOV B, A", "MOV");
                self.emit_req("MOV A, 0", "MOV");
                self.emit_req("SUB A, B", "SUB");
            }
            "not" => {
                let id = self.next_label_id();
                let true_lbl = format!("_not_true_{}", id);
                let end_lbl = format!("_not_end_{}", id);
                self.emit_req("CMP A, 0", "CMP");
                self.emit_req(&format!("JZ {}", true_lbl), "JZ");
                self.emit_req("MOV A, 0", "MOV");
                self.emit_req(&format!("JMP {}", end_lbl), "JMP");
                self.emit_label(&true_lbl);
                self.emit_req("MOV A, 1", "MOV");
                self.emit_label(&end_lbl);
            }
            "~" => self.emit_req("NOT A", "NOT"),
            "#" => self.emit_req("MOV A, [A]", "MOV"),
            _ => {}
        }
    }

    fn generate_call(
        &mut self,
        name: &str,
        system_symbol: Option<&SymbolInfo>,
        args: &[Expr],
        line: usize,
        col: usize,
    ) {
        if let Some(sym) = system_symbol {
            self.generate_system_lib_call(sym, args, line, col);
            return;
        }
        if self.linking_network_runtime {
            if name.starts_with(NETWORK_PREFIX) {
                self.generate_network_runtime_call(name, args);
                return;
            }
            if let Some(sym) = self.imported_symbols.get(name).cloned() {
                self.generate_system_lib_call(&sym, args, line, col);
                return;
            }
        }
        if self.class_field_offsets.contains_key(name) {
            self.generate_new(name, args);
            return;
        }
        if let Some(sym) = self.imported_symbols.get(name).cloned() {
            self.generate_system_lib_call(&sym, args, line, col);
            return;
        }
        if let Some(suggested_lib) = get_suggested_library_for_symbol(name) {
            self.errors.push(format!(
                "Line {}:{}: Function '{}' requires 'import <{}>'",
                line, col, name, suggested_lib
            ));
            return;
        }

        for arg in args {
            self.generate_expression(arg);
            self.emit_req("PUSH A", "PUSH");
        }
        self.emit_req(&format!("CALL fn_{}", name), "CALL");
    }

    fn generate_network_runtime_call(&mut self, name: &str, args: &[Expr]) {
        match name {
            "__net_alloc" => {
                self.generate_expression(&args[0]);
                self.emit_req("CALL _alloc", "CALL");
            }
            "__net_peek" => {
                self.generate_expression(&args[0]);
                self.emit_req("MOV A, [A]", "MOV");
            }
            "__net_invoke" => {
                for arg in &args[1..] {
                    self.generate_expression(arg);
                    self.emit_req("PUSH A", "PUSH");
                }
                self.generate_expression(&args[0]);
                self.emit_req("CALL A", "CALL");
            }
            _ => {
                for arg in args {
                    self.generate_expression(arg);
                    self.emit_req("PUSH A", "PUSH");
                }
                self.queue_network_function(name);
                self.emit_req(&format!("CALL fn_{}", name), "CALL");
            }
        }
    }

    fn generate_http_helper(
        &mut self,
        name: &str,
        arity: usize,
        callback_arity: Option<usize>,
        args: &[Expr],
        line: usize,
        col: usize,
    ) {
        let loc = format!("Line {}:{}: ", line, col);
        if args.len() != arity {
            self.errors
                .push(format!("{}{} expects {} arguments", loc, name, arity));
            return;
        }
        if let Some(cb_arity) = callback_arity {
            let valid = match args.last() {
                Some(Expr::Variable { name: cb_name, .. }) => {
                    self.function_arities.get(cb_name) == Some(&cb_arity)
                }
                _ => false,
            };
            if !valid {
                self.errors.push(format!(
                    "{}{} requires a named {}-parameter callback",
                    loc, name, cb_arity
                ));
                return;
            }
        }
        for (i, arg) in args.iter().enumerate() {
            if callback_arity.is_some() && i == args.len() - 1 {
                if let Expr::Variable { name: cb_name, .. } = arg {
                    self.emit_req(&format!("MOV A, fn_{}", cb_name), "MOV");
                }
            } else {
                self.generate_expression(arg);
            }
            self.emit_req("PUSH A", "PUSH");
        }
        let target = format!("{}{}", NETWORK_PREFIX, name);
        self.queue_network_function(&target);
        self.emit_req(&format!("CALL fn_{}", target), "CALL");
    }

    fn generate_system_lib_call(
        &mut self,
        symbol: &SymbolInfo,
        args: &[Expr],
        line: usize,
        col: usize,
    ) {
        let name = symbol.name.as_str();
        if symbol.library == "GameController"
            && (name == "gc_axis" || name.contains("stick") || name.contains("trigger"))
            && args.len() != symbol.arity
        {
            self.errors.push(format!(
                "Line {}:{}: {} expects {} arguments",
                line, col, name, symbol.arity
            ));
            return;
        }
        if symbol.library == "Glass"
            && matches!(
                name,
                "gfx_set_handler"
                    | "use_auto_gfx"
                    | "use_cpu"
                    | "use_gpu"
                    | "has_gpu"
                    | "gpu_available"
                    | "gfx_handler"
                    | "is_using_gpu"
                    | "gfx_mode"
                    | "gpu_vram"
                    | "gpu_vram_kb"
                    | "gpu_rect"
                    | "gpu_line"
                    | "draw_circle"
                    | "gpu_circle"
                    | "fill_circle"
                    | "gpu_fill_circle"
                    | "fill_triangle"
                    | "gpu_triangle"
                    | "vram_upload"
                    | "gpu_blit"
                    | "gpu_swap"
                    | "gpu_sync"
            )
            && args.len() != symbol.arity
        {
            self.errors.push(format!(
                "Line {}:{}: {} expects {} arguments",
                line, col, name, symbol.arity
            ));
            return;
        }

        if let Some(api) = network_apis().get(name) {
            self.generate_http_helper(name, api.arity, api.callback_arity, args, line, col);
            return;
        }

        match name {
            "print" | "println" => {
                if let Some(arg) = args.first() {
                    if let Expr::String { value, .. } = arg {
                        let escaped = Self::escape_string(value);
                        self.emit_req(&format!("OUT \"{}\"", escaped), "OUT");
                    } else if self.is_string_valued(arg) {
                        self.generate_expression(arg);
                        self.emit_req("CALL _print_str", "CALL");
                        self.emit_req("OUTC 10", "OUTC");
                    } else {
                        self.generate_expression(arg);
                        self.emit_req("OUT A", "OUT");
                    }
                } else {
                    self.emit_req("OUTC 10", "OUTC");
                }
            }
            "write" => {
                if let Some(arg) = args.first() {
                    if let Expr::String { value, .. } = arg {
                        let escaped = Self::escape_string(value);
                        self.emit_req(&format!("OUT \"{}\", 0", escaped), "OUT");
                    } else if self.is_string_valued(arg) {
                        self.generate_expression(arg);
                        self.emit_req("CALL _print_str", "CALL");
                    } else {
                        self.generate_expression(arg);
                        self.emit_req("OUT A, 0", "OUT");
                    }
                }
            }
            "cls" => self.emit_req("CLS", "CLS"),
            "time" => self.emit_req("TIME A", "TIME"),
            "sleep" => {
                if let Some(a) = args.first() {
                    self.generate_expression(a);
                    self.emit_req("SLEEP A", "SLEEP");
                }
            }
            "set_timer" => {
                if let Some(a) = args.first() {
                    self.generate_expression(a);
                    self.emit_req("TIMER A", "TIMER");
                }
            }
            "rand" => {
                if let Some(a) = args.first() {
                    self.generate_expression(a);
                    self.emit_req("RAND A, A", "RAND");
                }
            }
            "abs" => {
                if let Some(a) = args.first() {
                    self.generate_expression(a);
                    let id = self.next_label_id();
                    let pos = format!("_abs_pos_{}", id);
                    self.emit_req("CMP A, 0", "CMP");
                    self.emit_req(&format!("JGE {}", pos), "JGE");
                    self.emit_req("MUL A, -1", "MUL");
                    self.emit_label(&pos);
                }
            }
            "min" => {
                if args.len() >= 2 {
                    self.generate_expression(&args[0]);
                    self.emit_req("PUSH A", "PUSH");
                    self.generate_expression(&args[1]);
                    self.emit_req("MOV B, A", "MOV");
                    self.emit_req("POP A", "POP");
                    let id = self.next_label_id();
                    let done = format!("_min_done_{}", id);
                    self.emit_req("CMP A, B", "CMP");
                    self.emit_req(&format!("JLE {}", done), "JLE");
                    self.emit_req("MOV A, B", "MOV");
                    self.emit_label(&done);
                }
            }
            "max" => {
                if args.len() >= 2 {
                    self.generate_expression(&args[0]);
                    self.emit_req("PUSH A", "PUSH");
                    self.generate_expression(&args[1]);
                    self.emit_req("MOV B, A", "MOV");
                    self.emit_req("POP A", "POP");
                    let id = self.next_label_id();
                    let done = format!("_max_done_{}", id);
                    self.emit_req("CMP A, B", "CMP");
                    self.emit_req(&format!("JGE {}", done), "JGE");
                    self.emit_req("MOV A, B", "MOV");
                    self.emit_label(&done);
                }
            }
            "clamp" => {
                if args.len() >= 3 {
                    self.generate_expression(&args[0]);
                    self.emit_req("PUSH A", "PUSH");
                    self.generate_expression(&args[1]);
                    self.emit_req("PUSH A", "PUSH");
                    self.generate_expression(&args[2]);
                    self.emit_req("MOV C, A", "MOV");
                    self.emit_req("POP B", "POP");
                    self.emit_req("POP A", "POP");
                    let id = self.next_label_id();
                    let not_below = format!("_clamp_nb_{}", id);
                    let done = format!("_clamp_d_{}", id);
                    self.emit_req("CMP A, B", "CMP");
                    self.emit_req(&format!("JGE {}", not_below), "JGE");
                    self.emit_req("MOV A, B", "MOV");
                    self.emit_req(&format!("JMP {}", done), "JMP");
                    self.emit_label(&not_below);
                    self.emit_req("CMP A, C", "CMP");
                    self.emit_req(&format!("JLE {}", done), "JLE");
                    self.emit_req("MOV A, C", "MOV");
                    self.emit_label(&done);
                }
            }
            "assert" => {
                if let Some(a) = args.first() {
                    self.generate_expression(a);
                    let id = self.next_label_id();
                    let pass = format!("_assert_pass_{}", id);
                    self.emit_req("CMP A, 0", "CMP");
                    self.emit_req(&format!("JNE {}", pass), "JNE");
                    self.emit_req(&format!("OUT \"Assertion failed at line {}\", 1", line), "OUT");
                    self.emit_req("HLT", "HLT");
                    self.emit_label(&pass);
                }
            }
            "error" => {
                if let Some(a) = args.first() {
                    self.generate_expression(a);
                } else {
                    self.emit_req("MOV A, 4", "MOV");
                }
                self.emit_req("MOV [_pcall_val], A", "MOV");
                self.emit_req("INT 4", "INT");
            }
            "pcall" => {
                if args.is_empty() {
                    self.errors.push(format!(
                        "Line {}:{}: pcall requires a target function argument",
                        line, col
                    ));
                    return;
                }
                let target_fn = match &args[0] {
                    Expr::Variable { name: vn, .. } => Some(format!("fn_{}", vn)),
                    Expr::String { value: sn, .. } => Some(format!("fn_{}", sn)),
                    _ => None,
                };
                let Some(target_fn) = target_fn else {
                    self.errors.push(format!(
                        "Line {}:{}: pcall first argument must be a function name",
                        line, col
                    ));
                    return;
                };
                let id = self.next_label_id();
                let catch_lbl = format!("_pcall_catch_{}", id);
                let fail_lbl = format!("_pcall_fail_{}", id);
                let end_lbl = format!("_pcall_end_{}", id);

                for arg in &args[1..] {
                    self.generate_expression(arg);
                    self.emit_req("PUSH A", "PUSH");
                }
                self.emit_req("MOV [_pcall_val], 0", "MOV");
                self.emit_req("MOV [_tmp_d], 0", "MOV");
                self.emit_req(&format!("IVEC 0, {}", catch_lbl), "IVEC");
                self.emit_req(&format!("IVEC 1, {}", catch_lbl), "IVEC");
                self.emit_req(&format!("IVEC 2, {}", catch_lbl), "IVEC");
                self.emit_req(&format!("IVEC 3, {}", catch_lbl), "IVEC");
                self.emit_req(&format!("IVEC 4, {}", catch_lbl), "IVEC");

                self.emit_req(&format!("CALL {}", target_fn), "CALL");
                self.emit_req("CMP [_tmp_d], 0", "CMP");
                self.emit_req(&format!("JNZ {}", fail_lbl), "JNZ");

                self.emit_req("MOV [_pcall_val], A", "MOV");
                self.emit_req("MOV A, 1", "MOV");
                self.emit_req(&format!("JMP {}", end_lbl), "JMP");

                self.emit_label(&fail_lbl);
                self.emit_req("MOV A, 0", "MOV");
                self.emit_req(&format!("JMP {}", end_lbl), "JMP");

                self.emit_label(&catch_lbl);
                self.emit_req("MOV [_tmp_d], 1", "MOV");
                let keep_lbl = format!("_pcall_keep_{}", id);
                self.emit_req("CMP [_pcall_val], 0", "CMP");
                self.emit_req(&format!("JNZ {}", keep_lbl), "JNZ");
                self.emit_req("MOV [_pcall_val], A", "MOV");
                self.emit_label(&keep_lbl);
                self.emit_req("RET", "RET");

                self.emit_label(&end_lbl);
            }
            "sqrt" => {
                if let Some(a) = args.first() {
                    self.generate_expression(a);
                    let id = self.next_label_id();
                    let loop_lbl = format!("_sqrt_loop_{}", id);
                    let end_lbl = format!("_sqrt_end_{}", id);
                    self.emit_req("MOV B, 0", "MOV");
                    self.emit_label(&loop_lbl);
                    self.emit_req("MOV C, B", "MOV");
                    self.emit_req("MUL C, C", "MUL");
                    self.emit_req("CMP C, A", "CMP");
                    self.emit_req(&format!("JG {}", end_lbl), "JG");
                    self.emit_req("INC B", "INC");
                    self.emit_req(&format!("JMP {}", loop_lbl), "JMP");
                    self.emit_label(&end_lbl);
                    self.emit_req("DEC B", "DEC");
                    self.emit_req("MOV A, B", "MOV");
                }
            }
            "fb_mode" | "set_mode" => {
                if let Some(a) = args.first() {
                    self.generate_expression(a);
                    self.emit_req("FBMODE A", "FBMODE");
                }
            }
            "fb_clear" | "clear_screen" => {
                if let Some(a) = args.first() {
                    self.generate_expression(a);
                    self.emit_req("FBCLR A", "FBCLR");
                } else {
                    self.emit_req("FBCLR 0", "FBCLR");
                }
            }
            "fb_swap" | "swap" | "flip" => self.emit_req("FBSWAP", "FBSWAP"),
            "fb_width" | "width" => self.emit_req("FBWIDTH A", "FBWIDTH"),
            "fb_height" | "height" => self.emit_req("FBHEIGHT A", "FBHEIGHT"),
            "fb_pixel" | "set_pixel" => {
                if args.len() >= 3 {
                    self.generate_expression(&args[0]);
                    self.emit_req("PUSH A", "PUSH");
                    self.generate_expression(&args[1]);
                    self.emit_req("PUSH A", "PUSH");
                    self.generate_expression(&args[2]);
                    self.emit_req("MOV C, A", "MOV");
                    self.emit_req("POP B", "POP");
                    self.emit_req("POP A", "POP");
                    self.emit_req("FBSET A, B, C", "FBSET");
                }
            }
            "fb_get" | "get_pixel" => {
                if args.len() >= 2 {
                    self.generate_expression(&args[0]);
                    self.emit_req("PUSH A", "PUSH");
                    self.generate_expression(&args[1]);
                    self.emit_req("MOV B, A", "MOV");
                    self.emit_req("POP A", "POP");
                    self.emit_req("FBGET A, A, B", "FBGET");
                }
            }
            "fb_rect" | "draw_rect" => self.emit_five_arg_gfx(args, "FBRECT"),
            "fb_line" | "draw_line" => self.emit_five_arg_gfx(args, "FBLINE"),
            "fb_blit" | "blit" => self.emit_five_arg_gfx(args, "FBLIT"),
            "gfx_set_handler" => {
                if let Some(a) = args.first() {
                    self.generate_expression(a);
                    self.emit_req("GFXSEL A", "GFXSEL");
                }
            }
            "use_auto_gfx" => self.emit_req("GFXSEL 0", "GFXSEL"),
            "use_cpu" => self.emit_req("GFXSEL 1", "GFXSEL"),
            "use_gpu" => self.emit_req("GFXSEL 2", "GFXSEL"),
            "has_gpu" | "gpu_available" => self.emit_req("GFXSTAT A, 0", "GFXSTAT"),
            "gfx_handler" | "is_using_gpu" => self.emit_req("GFXSTAT A, 1", "GFXSTAT"),
            "gfx_mode" => self.emit_req("GFXSTAT A, 2", "GFXSTAT"),
            "gpu_vram" => self.emit_req("GFXSTAT A, 3", "GFXSTAT"),
            "gpu_vram_kb" => self.emit_req("GFXSTAT A, 4", "GFXSTAT"),
            "gpu_clear" => {
                if let Some(a) = args.first() {
                    self.generate_expression(a);
                    self.emit_req("GPUCLR A", "GPUCLR");
                } else {
                    self.emit_req("GPUCLR 0", "GPUCLR");
                }
            }
            "gpu_rect" => self.emit_five_arg_gfx(args, "GPURECT"),
            "gpu_line" => self.emit_five_arg_gfx(args, "GPULINE"),
            "draw_circle" | "gpu_circle" | "fill_circle" | "gpu_fill_circle" => {
                if args.len() >= 4 {
                    let filled = if name == "fill_circle" || name == "gpu_fill_circle" {
                        1
                    } else {
                        0
                    };
                    for a in &args[..4] {
                        self.generate_expression(a);
                        self.emit_req("PUSH A", "PUSH");
                    }
                    self.emit_req("POP [_tmp_c]", "POP");
                    self.emit_req("POP [_tmp_b]", "POP");
                    self.emit_req("POP [_tmp_a]", "POP");
                    self.emit_req("POP A", "POP");
                    self.emit_req(
                        &format!("GPUCIRC A, [_tmp_a], [_tmp_b], [_tmp_c], {}", filled),
                        "GPUCIRC",
                    );
                }
            }
            "fill_triangle" | "gpu_triangle" => {
                if args.len() >= 7 {
                    self.needs_gpu_tri_scratch = true;
                    for a in &args[..7] {
                        self.generate_expression(a);
                        self.emit_req("PUSH A", "PUSH");
                    }
                    for i in (0..7).rev() {
                        self.emit_req(&format!("POP [_tmp_g{}]", i), "POP");
                    }
                    self.emit_req("GPUTRI _tmp_g0", "GPUTRI");
                }
            }
            "vram_upload" => {
                if args.len() >= 3 {
                    for a in &args[..3] {
                        self.generate_expression(a);
                        self.emit_req("PUSH A", "PUSH");
                    }
                    self.emit_req("POP [_tmp_b]", "POP");
                    self.emit_req("POP [_tmp_a]", "POP");
                    self.emit_req("POP A", "POP");
                    self.emit_req("GPUVRAM A, [_tmp_a], [_tmp_b]", "GPUVRAM");
                }
            }
            "gpu_blit" => self.emit_five_arg_gfx(args, "GPUBLIT"),
            "gpu_swap" => self.emit_req("GPUSWAP", "GPUSWAP"),
            "gpu_sync" => self.emit_req("GPUSYNC", "GPUSYNC"),
            "tone" | "play_tone" => {
                if args.len() >= 2 {
                    self.generate_expression(&args[0]);
                    self.emit_req("PUSH A", "PUSH");
                    self.generate_expression(&args[1]);
                    self.emit_req("MOV B, A", "MOV");
                    self.emit_req("POP A", "POP");
                    self.emit_req("ATONE A, B", "ATONE");
                }
            }
            "play_audio" | "audio_play" => self.emit_req("APLAY", "APLAY"),
            "audio_clear" => self.emit_req("ACLR", "ACLR"),
            "audio_status" => self.emit_req("ASTAT A", "ASTAT"),
            "audio_push" => {
                if let Some(a) = args.first() {
                    self.generate_expression(a);
                    self.emit_req("APUSH A", "APUSH");
                }
            }
            "select_drive" | "drive_select" => {
                if let Some(a) = args.first() {
                    self.generate_expression(a);
                    self.emit_req("SSEL A", "SSEL");
                }
            }
            "storage_size" | "drive_size" => {
                if let Some(a) = args.first() {
                    self.generate_expression(a);
                    self.emit_req("SSIZE A, A", "SSIZE");
                }
            }
            "storage_read" | "drive_read" => {
                if args.len() >= 2 {
                    self.generate_expression(&args[0]);
                    self.emit_req("PUSH A", "PUSH");
                    self.generate_expression(&args[1]);
                    self.emit_req("MOV B, A", "MOV");
                    self.emit_req("POP A", "POP");
                    self.emit_req("SREAD A, A, B", "SREAD");
                }
            }
            "storage_write" | "drive_write" => {
                if args.len() >= 3 {
                    self.generate_expression(&args[0]);
                    self.emit_req("PUSH A", "PUSH");
                    self.generate_expression(&args[1]);
                    self.emit_req("PUSH A", "PUSH");
                    self.generate_expression(&args[2]);
                    self.emit_req("MOV C, A", "MOV");
                    self.emit_req("POP B", "POP");
                    self.emit_req("POP A", "POP");
                    self.emit_req("SWRITE A, B, C", "SWRITE");
                }
            }
            "drive_count" => self.emit_req("SCOUNT A", "SCOUNT"),
            "network_open"
            | "network_send"
            | "network_receive"
            | "network_close"
            | "network_http"
            | "network_websocket"
            | "network_send_text"
            | "network_tls"
            | "network_http3"
            | "network_http_text"
            | "network_http3_text"
            | "network_listen"
            | "network_accept"
            | "network_respond"
            | "network_status"
            | "network_length"
            | "network_result"
            | "network_ready"
            | "network_utf8"
            | "network_respond_text" => {
                self.generate_network_instruction(name, symbol.arity, args, line, col)
            }
            "peripheral_scan"
            | "peripheral_info"
            | "peripheral_wrap"
            | "peripheral_call"
            | "peripheral_call_string"
            | "peripheral_status"
            | "peripheral_result"
            | "peripheral_length"
            | "peripheral_result_text"
            | "peripheral_text"
            | "peripheral_text_unit"
            | "speaker_sample_rate"
            | "speaker_tone"
            | "note_block_get"
            | "note_block_set"
            | "note_block_play"
            | "note_block_play_instrument" => {
                self.generate_peripheral_instruction(name, symbol.arity, args, line, col)
            }
            "read_key" | "get_key" => self.emit_req("INPAD A, -1", "INPAD"),
            "peek_key" => self.emit_req("INPAD A, -2", "INPAD"),
            "key_available" | "has_key" => {
                self.emit_req("INPAD A, -2", "INPAD");
                let id = self.next_label_id();
                let no_key = format!("_no_key_{}", id);
                let key_done = format!("_key_done_{}", id);
                self.emit_req("CMP A, 0", "CMP");
                self.emit_req(&format!("JL {}", no_key), "JL");
                self.emit_req("MOV A, 1", "MOV");
                self.emit_req(&format!("JMP {}", key_done), "JMP");
                self.emit_label(&no_key);
                self.emit_req("MOV A, 0", "MOV");
                self.emit_label(&key_done);
            }
            "on_key" => {
                if let Some(arg) = args.first() {
                    let target_name = match arg {
                        Expr::Variable { name, .. } => name.as_str(),
                        Expr::String { value, .. } => value.as_str(),
                        _ => "",
                    };
                    if !target_name.is_empty() {
                        self.emit_req(&format!("IVEC KEYBOARD, fn_{}", target_name), "IVEC");
                    }
                }
            }
            "read_gamepad" | "poll_gamepad" | "get_pad" | "gc_poll" => {
                if let Some(a) = args.first() {
                    self.generate_expression(a);
                    self.emit_req("INPAD A, A", "INPAD");
                } else {
                    self.emit_req("INPAD A, 0", "INPAD");
                }
            }
            "is_gamepad_connected" | "gamepad_count" | "gc_controllers" => {
                self.emit_req("MOV A, 1", "MOV");
            }
            "gc_current" => self.emit_req("MOV A, 0", "MOV"),
            "gc_button_a" | "gc_button_east" => self.generate_gamepad_button_check(args, 0x0080),
            "gc_button_b" | "gc_button_south" => self.generate_gamepad_button_check(args, 0x0040),
            "gc_button_x" | "gc_button_north" => self.generate_gamepad_button_check(args, 0x0200),
            "gc_button_y" | "gc_button_west" => self.generate_gamepad_button_check(args, 0x0100),
            "gc_dpad_up" => self.generate_gamepad_button_check(args, 0x0008),
            "gc_dpad_down" => self.generate_gamepad_button_check(args, 0x0004),
            "gc_dpad_left" => self.generate_gamepad_button_check(args, 0x0002),
            "gc_dpad_right" => self.generate_gamepad_button_check(args, 0x0001),
            "gc_left_shoulder" => self.generate_gamepad_button_check(args, 0x0400),
            "gc_right_shoulder" => self.generate_gamepad_button_check(args, 0x0800),
            "gc_button_menu" => self.generate_gamepad_button_check(args, 0x0010),
            "gc_button_options" => self.generate_gamepad_button_check(args, 0x0020),
            "gc_left_trigger_pressed" => self.generate_gamepad_button_check(args, 0x1000),
            "gc_right_trigger_pressed" => self.generate_gamepad_button_check(args, 0x2000),
            "gc_left_stick_button" => self.generate_gamepad_button_check(args, 0x4000),
            "gc_right_stick_button" => self.generate_gamepad_button_check(args, 0x8000),
            "gc_left_stick_x" => self.generate_gamepad_axis(args, 0),
            "gc_left_stick_y" => self.generate_gamepad_axis(args, 1),
            "gc_right_stick_x" => self.generate_gamepad_axis(args, 2),
            "gc_right_stick_y" => self.generate_gamepad_axis(args, 3),
            "gc_left_trigger" => self.generate_gamepad_axis(args, 4),
            "gc_right_trigger" => self.generate_gamepad_axis(args, 5),
            "gc_axis" => {
                self.generate_expression(&args[0]);
                self.emit_req("PUSH A", "PUSH");
                self.generate_expression(&args[1]);
                self.emit_req("POP B", "POP");
                self.emit_req("INAXIS A, B, A", "INAXIS");
            }
            _ => self
                .errors
                .push(format!("Unimplemented library function: {}", name)),
        }
    }

    fn emit_five_arg_gfx(&mut self, args: &[Expr], opcode: &str) {
        if args.len() >= 5 {
            for a in &args[..5] {
                self.generate_expression(a);
                self.emit_req("PUSH A", "PUSH");
            }
            self.emit_req("POP [_tmp_d]", "POP");
            self.emit_req("POP [_tmp_c]", "POP");
            self.emit_req("POP [_tmp_b]", "POP");
            self.emit_req("POP [_tmp_a]", "POP");
            self.emit_req("POP A", "POP");
            self.emit_req(
                &format!("{} A, [_tmp_a], [_tmp_b], [_tmp_c], [_tmp_d]", opcode),
                opcode,
            );
        }
    }

    fn generate_network_instruction(
        &mut self,
        name: &str,
        arity: usize,
        args: &[Expr],
        line: usize,
        col: usize,
    ) {
        if args.len() != arity {
            self.errors.push(format!(
                "Line {}:{}: {} expects {} arguments",
                line, col, name, arity
            ));
            return;
        }
        let opcode = match name {
            "network_open" => "NOPEN",
            "network_send" => "NSEND",
            "network_receive" => "NRECV",
            "network_close" => "NCLOSE",
            "network_http" | "network_http_text" => "NHTTP",
            "network_websocket" => "NWSOPEN",
            "network_send_text" => "NWSTEXT",
            "network_tls" => "NTLS",
            "network_http3" | "network_http3_text" => "NHTTP3",
            "network_listen" => "NLISTEN",
            "network_accept" => "NACCEPT",
            "network_respond" => "NRESPOND",
            "network_respond_text" => "NRESPONDT",
            "network_status" => "NSTATUS",
            "network_length" => "NLEN",
            "network_result" => "NRESULT",
            "network_ready" => "NREADY",
            "network_utf8" => "NUTF8",
            _ => unreachable!(),
        };
        for arg in args {
            self.generate_expression(arg);
            self.emit_req("PUSH A", "PUSH");
        }
        let regs = ['A', 'B', 'C', 'D'];
        for i in (0..args.len()).rev() {
            self.emit_req(&format!("POP {}", regs[i]), "POP");
        }
        let suffix = ["", ", A", ", A, B", ", A, B, C", ", A, B, C, D"][arity];
        let text_flag = if name == "network_http_text" || name == "network_http3_text" {
            ", 1"
        } else {
            ""
        };
        self.emit_req(&format!("{} A{}{}", opcode, suffix, text_flag), opcode);
    }

    fn generate_peripheral_instruction(
        &mut self,
        name: &str,
        arity: usize,
        args: &[Expr],
        line: usize,
        col: usize,
    ) {
        if args.len() != arity {
            self.errors.push(format!(
                "Line {}:{}: {} expects {} arguments",
                line, col, name, arity
            ));
            return;
        }
        match name {
            "peripheral_scan" | "peripheral_info" | "peripheral_wrap" => {
                self.generate_expression(&args[0]);
                let opcode = match name {
                    "peripheral_scan" => "PSCAN",
                    "peripheral_info" => "PINFO",
                    _ => "PWRAP",
                };
                self.emit_req(&format!("{} A, A", opcode), opcode);
            }
            "peripheral_status" => self.emit_req("PSTATUS A", "PSTATUS"),
            "peripheral_result" => self.emit_req("PRESULT A", "PRESULT"),
            "peripheral_length" => self.emit_req("PLEN A", "PLEN"),
            "peripheral_text" => {
                self.emit_req("PLEN A", "PLEN");
                self.emit_req("INC A", "INC");
                self.emit_req("PUSH A", "PUSH");
                self.emit_req("CALL _alloc", "CALL");
                self.emit_req("POP B", "POP");
                self.emit_req("PRESULTS C, A, B", "PRESULTS");
            }
            "peripheral_text_unit" => {
                self.generate_expression(&args[0]);
                self.emit_req("PUSH A", "PUSH");
                self.generate_expression(&args[1]);
                self.emit_req("POP B", "POP");
                self.emit_req("ADD A, B", "ADD");
                self.emit_req("MOV A, [A]", "MOV");
            }
            "peripheral_result_text" => {
                self.generate_expression(&args[0]);
                self.emit_req("PUSH A", "PUSH");
                self.generate_expression(&args[1]);
                self.emit_req("POP B", "POP");
                self.emit_req("PRESULTS A, B, A", "PRESULTS");
            }
            "speaker_sample_rate" => self.generate_peripheral_call(args, Some("0x100"), "PCALL"),
            "speaker_tone" => self.generate_peripheral_call(args, Some("0x101"), "PCALL"),
            "note_block_get" => self.generate_peripheral_call(args, Some("0x200"), "PCALL"),
            "note_block_set" => self.generate_peripheral_call(args, Some("0x201"), "PCALL"),
            "note_block_play" => self.generate_peripheral_call(args, Some("0x202"), "PCALL"),
            "note_block_play_instrument" => {
                self.generate_peripheral_call(args, Some("0x203"), "PCALLS")
            }
            "peripheral_call" => self.generate_peripheral_call(args, None, "PCALL"),
            "peripheral_call_string" => self.generate_peripheral_call(args, None, "PCALLS"),
            _ => {}
        }
    }

    fn generate_peripheral_call(&mut self, args: &[Expr], method: Option<&str>, opcode: &str) {
        for arg in args {
            self.generate_expression(arg);
            self.emit_req("PUSH A", "PUSH");
        }
        let regs = ['A', 'B', 'C', 'D'];
        for i in (0..args.len()).rev() {
            self.emit_req(&format!("POP {}", regs[i]), "POP");
        }
        match method {
            None => self.emit_req(&format!("{} A, A, B, C, D", opcode), opcode),
            Some(m) => {
                let arg0 = if args.len() >= 2 { "B" } else { "0" };
                let arg1 = if args.len() >= 3 { "C" } else { "0" };
                self.emit_req(&format!("{} A, A, {}, {}, {}", opcode, m, arg0, arg1), opcode);
            }
        }
    }

    fn generate_gamepad_axis(&mut self, args: &[Expr], axis: usize) {
        self.generate_expression(&args[0]);
        self.emit_req(&format!("INAXIS A, A, {}", axis), "INAXIS");
    }

    fn generate_gamepad_button_check(&mut self, args: &[Expr], mask: i32) {
        if let Some(a) = args.first() {
            self.generate_expression(a);
            self.emit_req("INPAD A, A", "INPAD");
        } else {
            self.emit_req("INPAD A, 0", "INPAD");
        }
        self.emit_req(&format!("AND A, {}", mask), "AND");
        let id = self.next_label_id();
        let not_pressed = format!("_gc_np_{}", id);
        let done = format!("_gc_d_{}", id);
        self.emit_req(&format!("JZ {}", not_pressed), "JZ");
        self.emit_req("MOV A, 1", "MOV");
        self.emit_req(&format!("JMP {}", done), "JMP");
        self.emit_label(&not_pressed);
        self.emit_req("MOV A, 0", "MOV");
        self.emit_label(&done);
    }

    fn generate_method_call(&mut self, object: &Expr, method_name: &str, args: &[Expr]) {
        if method_name == "push" || method_name == "append" {
            self.generate_expression(object);
            self.emit_req("PUSH A", "PUSH");
            self.generate_expression(&args[0]);
            self.emit_req("MOV C, A", "MOV");
            self.emit_req("POP B", "POP");
            self.emit_req("MOV A, [B]", "MOV");
            self.emit_req("ADD B, 2", "ADD");
            self.emit_req("ADD B, A", "ADD");
            self.emit_req("MOV [B], C", "MOV");
            self.emit_req("SUB B, 2", "SUB");
            self.emit_req("SUB B, A", "SUB");
            self.emit_req("INC [B]", "INC");
            return;
        }
        if method_name == "pop" {
            self.generate_expression(object);
            self.emit_req("MOV B, A", "MOV");
            self.emit_req("DEC [B]", "DEC");
            self.emit_req("MOV A, [B]", "MOV");
            self.emit_req("ADD B, 2", "ADD");
            self.emit_req("ADD B, A", "ADD");
            self.emit_req("MOV A, [B]", "MOV");
            return;
        }
        if method_name == "length" || method_name == "len" || method_name == "size" {
            self.generate_expression(object);
            self.emit_req("MOV A, [A]", "MOV");
            return;
        }

        for arg in args {
            self.generate_expression(arg);
            self.emit_req("PUSH A", "PUSH");
        }
        self.generate_expression(object);
        let target_method = self.find_method_label(method_name);
        self.emit_req(&format!("CALL {}", target_method), "CALL");
    }

    fn find_method_label(&self, method_name: &str) -> String {
        if let Some(cur_cls) = &self.current_class {
            if self
                .class_methods
                .get(cur_cls)
                .map_or(false, |m| m.contains(method_name))
            {
                return format!("m_{}_{}", cur_cls, method_name);
            }
        }
        for cls in &self.class_order {
            if self
                .class_methods
                .get(cls)
                .map_or(false, |m| m.contains(method_name))
            {
                return format!("m_{}_{}", cls, method_name);
            }
        }
        if let Some(cls) = self.class_order.first() {
            return format!("m_{}_{}", cls, method_name);
        }
        format!("m_Player_{}", method_name)
    }

    fn generate_field_access(&mut self, object: &Expr, field_name: &str) {
        if matches!(object, Expr::This { .. }) {
            self.emit_req("MOV A, [_this_ptr]", "MOV");
        } else {
            self.generate_expression(object);
        }
        let offset = self.resolve_field_offset(field_name);
        self.emit_req(&format!("ADD A, {}", offset), "ADD");
        self.emit_req("MOV A, [A]", "MOV");
    }

    fn generate_index_access(&mut self, container: &Expr, index: &Expr) {
        self.generate_expression(container);
        self.emit_req("PUSH A", "PUSH");
        self.generate_expression(index);
        self.emit_req("MOV B, A", "MOV");
        self.emit_req("POP A", "POP");
        self.emit_req("ADD A, 2", "ADD");
        self.emit_req("ADD A, B", "ADD");
        self.emit_req("MOV A, [A]", "MOV");
    }

    fn generate_new(&mut self, class_name: &str, args: &[Expr]) {
        let field_count = self
            .class_field_offsets
            .get(class_name)
            .map_or(0, |m| m.len());
        let size = 1 + field_count;
        self.emit_req(&format!("MOV A, {}", size), "MOV");
        self.emit_req("CALL _alloc", "CALL");
        self.emit_req("MOV [_tmp_a], A", "MOV");

        let cls_id = *self.class_ids.get(class_name).unwrap_or(&1);
        self.emit_req("MOV D, [_tmp_a]", "MOV");
        self.emit_req(&format!("MOV [D], {}", cls_id), "MOV");
        self.emit_req("PUSH [_tmp_a]", "PUSH");

        for arg in args {
            self.generate_expression(arg);
            self.emit_req("PUSH A", "PUSH");
        }

        self.emit_req("MOV A, [_tmp_a]", "MOV");
        self.emit_req(&format!("CALL m_{}_init", class_name), "CALL");
        self.emit_req("POP A", "POP");
    }

    fn generate_list_literal(&mut self, elements: &[Expr]) {
        let count = elements.len();
        let size = 2 + count;
        self.emit_req(&format!("MOV A, {}", size), "MOV");
        self.emit_req("CALL _alloc", "CALL");
        self.emit_req("PUSH A", "PUSH");
        self.emit_req("MOV D, A", "MOV");
        self.emit_req(&format!("MOV [D], {}", count), "MOV");
        self.emit_req("ADD D, 1", "ADD");
        self.emit_req(&format!("MOV [D], {}", count), "MOV");

        for (i, el) in elements.iter().enumerate() {
            self.generate_expression(el);
            self.emit_req("MOV C, A", "MOV");
            self.emit_req("POP B", "POP");
            self.emit_req("PUSH B", "PUSH");
            self.emit_req("MOV D, B", "MOV");
            self.emit_req(&format!("ADD D, {}", 2 + i), "ADD");
            self.emit_req("MOV [D], C", "MOV");
        }
        self.emit_req("POP A", "POP");
    }

    fn generate_map_literal(&mut self, entries: &[MapEntry]) {
        let count = entries.len();
        let size = 2 + count * 2;
        self.emit_req(&format!("MOV A, {}", size), "MOV");
        self.emit_req("CALL _alloc", "CALL");
        self.emit_req("PUSH A", "PUSH");
        self.emit_req("MOV D, A", "MOV");
        self.emit_req(&format!("MOV [D], {}", count), "MOV");
        self.emit_req("ADD D, 1", "ADD");
        self.emit_req(&format!("MOV [D], {}", count), "MOV");

        for (i, entry) in entries.iter().enumerate() {
            self.generate_expression(&entry.key);
            self.emit_req("PUSH A", "PUSH");
            self.generate_expression(&entry.value);
            self.emit_req("MOV C, A", "MOV");
            self.emit_req("POP B", "POP");
            self.emit_req("POP D", "POP");
            self.emit_req("PUSH D", "PUSH");
            self.emit_req("MOV [_tmp_a], D", "MOV");
            self.emit_req("MOV D, [_tmp_a]", "MOV");
            self.emit_req(&format!("ADD D, {}", 2 + i * 2), "ADD");
            self.emit_req("MOV [D], B", "MOV");
            self.emit_req("MOV D, [_tmp_a]", "MOV");
            self.emit_req(&format!("ADD D, {}", 3 + i * 2), "ADD");
            self.emit_req("MOV [D], C", "MOV");
        }
        self.emit_req("POP A", "POP");
    }

    fn is_text_expression(&self, expr: &Expr) -> bool {
        match expr {
            Expr::String { .. } => true,
            Expr::SystemCall { symbol, .. } => {
                matches!(symbol.name.as_str(), "http_text" | "http_body" | "peripheral_text")
            }
            Expr::Call { callee, .. } => {
                self.imported_symbols.contains_key(callee)
                    && matches!(callee.as_str(), "http_text" | "http_body" | "peripheral_text")
            }
            _ => false,
        }
    }

    fn is_string_valued(&self, arg: &Expr) -> bool {
        if self.is_text_expression(arg) {
            return true;
        }
        match arg {
            Expr::Variable { name, .. } => self.string_vars.contains(name),
            Expr::FieldAccess { field_name, .. } => self.string_fields.contains(field_name),
            _ => false,
        }
    }

    fn escape_string(s: &str) -> String {
        let mut out = String::new();
        for c in s.chars() {
            match c {
                '"' => out.push_str("\\\""),
                '\\' => out.push_str("\\\\"),
                '\n' => out.push_str("\\n"),
                '\r' => out.push_str("\\r"),
                '\t' => out.push_str("\\t"),
                other => out.push(other),
            }
        }
        out
    }

    fn resolve_var_slot(&mut self, name: &str) -> String {
        if self.current_locals.contains(name) {
            if let Some(cur_fn) = &self.current_function {
                return format!("v_{}_{}", cur_fn, name);
            }
        }
        self.global_vars.insert(name.to_string());
        format!("g_{}", name)
    }

    fn resolve_field_offset(&self, field_name: &str) -> usize {
        if let Some(cur_cls) = &self.current_class {
            if let Some(map) = self.class_field_offsets.get(cur_cls) {
                if let Some(&off) = map.get(field_name) {
                    return off;
                }
            }
        }
        for cls in &self.class_order {
            if let Some(map) = self.class_field_offsets.get(cls) {
                if let Some(&off) = map.get(field_name) {
                    return off;
                }
            }
        }
        1
    }

    fn get_string_literal_label(&mut self, value: &str) -> String {
        if let Some(lbl) = self.string_pool_map.get(value) {
            return lbl.clone();
        }
        self.string_counter += 1;
        let label = format!("_str_{}", self.string_counter);
        self.string_pool_map.insert(value.to_string(), label.clone());
        self.string_pool.push((value.to_string(), label.clone()));
        self.data_lines.push(format!("{}: DB 0", label));
        label
    }

    fn emit(&mut self, line: &str) {
        self.lines.push(line.to_string());
    }

    fn emit_req(&mut self, line: &str, required_opcode: &str) {
        self.lines.push(line.to_string());
        self.required_instructions.insert(required_opcode.to_string());
    }

    fn emit_label(&mut self, label: &str) {
        self.lines.push(format!("{}:", label));
    }

    fn emit_comment(&mut self, comment: &str) {
        self.lines.push(format!("; {}", comment));
    }
}
