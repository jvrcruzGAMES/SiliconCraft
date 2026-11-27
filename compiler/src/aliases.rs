use std::collections::{HashMap, HashSet};

use crate::ast::*;
use crate::error::CompileError;
use crate::library::{get_library, normalize_lib_path, SymbolInfo, SymbolType};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Kind {
    Variable,
    Function,
    Class,
    SystemFunction,
    Constant,
}

#[derive(Debug, Clone)]
struct Export {
    symbol: String,
    kind: Kind,
    library_symbol: Option<SymbolInfo>,
}

impl Export {
    fn new(symbol: impl Into<String>, kind: Kind) -> Self {
        Self {
            symbol: symbol.into(),
            kind,
            library_symbol: None,
        }
    }
}

pub fn merge_modules(
    modules: &[(String, Program)],
    bindings: &HashMap<String, HashMap<String, String>>,
    unqualified: &HashSet<String>,
    merged: &mut Program,
    errors: &mut Vec<String>,
) {
    let has_library_aliases = modules
        .iter()
        .any(|(_, p)| p.imports.iter().any(|imp| imp.is_library && imp.alias.is_some()));

    if (!has_library_aliases && bindings.is_empty()) || !errors.is_empty() {
        for (_, module) in modules {
            merged.statements.extend(module.statements.clone());
        }
        return;
    }

    let mut exports: Vec<(String, HashMap<String, Export>)> = Vec::new();
    let mut names: HashSet<String> = HashSet::new();
    for (file, program) in modules {
        let symbols = collect_exports(program);
        names.extend(symbols.keys().cloned());
        exports.push((file.clone(), symbols));
    }

    let mut index = 0usize;
    for (file, mod_exports) in &mut exports {
        if unqualified.contains(file) {
            continue;
        }
        let prefix = loop {
            let candidate = format!("_n2module_{}_", index);
            index += 1;
            if !mod_exports
                .keys()
                .any(|k| names.contains(&format!("{}{}", candidate, k)))
            {
                break candidate;
            }
        };
        for exp in mod_exports.values_mut() {
            exp.symbol = format!("{}{}", prefix, exp.symbol);
        }
    }

    let exports_map: HashMap<String, HashMap<String, Export>> = exports.into_iter().collect();

    for (file, module) in modules {
        let mut module_aliases: HashMap<String, HashMap<String, Export>> = HashMap::new();
        if let Some(file_bindings) = bindings.get(file) {
            for (alias, target) in file_bindings {
                if let Some(target_exp) = exports_map.get(target) {
                    module_aliases.insert(alias.clone(), target_exp.clone());
                }
            }
        }
        let own = exports_map.get(file).cloned().unwrap_or_default();
        let mut lowerer = ModuleLowerer {
            file: file.clone(),
            own,
            aliases: module_aliases,
            locals: HashSet::new(),
            in_function: false,
        };

        let res: Result<(), CompileError> = (|| {
            for imp in &module.imports {
                if imp.is_library && imp.alias.is_some() {
                    lowerer.bind_library(imp)?;
                }
            }
            for node in &module.statements {
                merged.statements.push(lowerer.statement(node.clone())?);
            }
            Ok(())
        })();

        if let Err(err) = res {
            errors.push(err.to_string());
        }
    }
}

fn collect_exports(module: &Program) -> HashMap<String, Export> {
    let mut symbols = HashMap::new();
    for node in &module.statements {
        match node {
            Stmt::Function(func) => {
                symbols.insert(func.name.clone(), Export::new(&func.name, Kind::Function));
            }
            Stmt::Class(cls) => {
                symbols.insert(cls.name.clone(), Export::new(&cls.name, Kind::Class));
            }
            Stmt::VarDecl {
                name,
                secondary_names,
                ..
            } => {
                symbols.insert(name.clone(), Export::new(name, Kind::Variable));
                for sec in secondary_names {
                    symbols.insert(sec.clone(), Export::new(sec, Kind::Variable));
                }
            }
            Stmt::Assign {
                target,
                secondary_targets,
                ..
            } => {
                collect_var_target(target, &mut symbols);
                for sec in secondary_targets {
                    collect_var_target(sec, &mut symbols);
                }
            }
            _ => {}
        }
    }
    symbols
}

fn collect_var_target(target: &AssignTarget, symbols: &mut HashMap<String, Export>) {
    if let AssignTarget::Var { name, .. } = target {
        symbols
            .entry(name.clone())
            .or_insert_with(|| Export::new(name, Kind::Variable));
    }
}

struct ModuleLowerer {
    file: String,
    own: HashMap<String, Export>,
    aliases: HashMap<String, HashMap<String, Export>>,
    locals: HashSet<String>,
    in_function: bool,
}

impl ModuleLowerer {
    fn err(&self, message: impl Into<String>, line: usize, col: usize) -> CompileError {
        CompileError::new(message, Some(&self.file), line, col)
    }

    fn bind_library(&mut self, imp: &Import) -> Result<(), CompileError> {
        let alias = imp.alias.as_ref().expect("alias must exist");
        if self.aliases.contains_key(alias) {
            return Err(self.err(
                format!("Duplicate module alias '{}'", alias),
                imp.line,
                imp.column,
            ));
        }
        let clean = normalize_lib_path(&imp.path);
        let Some(lib) = get_library(&clean) else {
            return Err(self.err(
                format!("Unknown system library: <{}>", imp.path),
                imp.line,
                imp.column,
            ));
        };
        let mut members = HashMap::new();
        for (name, symbol) in lib {
            let kind = if symbol.kind == SymbolType::Constant {
                Kind::Constant
            } else {
                Kind::SystemFunction
            };
            members.insert(
                name.clone(),
                Export {
                    symbol: name,
                    kind,
                    library_symbol: Some(symbol),
                },
            );
        }
        self.aliases.insert(alias.clone(), members);
        Ok(())
    }

    fn symbol(&self, name: &str, kind: Kind) -> String {
        if let Some(exp) = self.own.get(name) {
            if exp.kind == kind {
                return exp.symbol.clone();
            }
        }
        name.to_string()
    }

    fn variable(&self, name: &str, line: usize, col: usize) -> Result<String, CompileError> {
        if self.locals.contains(name) {
            return Ok(name.to_string());
        }
        if self.aliases.contains_key(name) {
            return Err(self.err(
                format!("Module alias '{}' requires a member name", name),
                line,
                col,
            ));
        }
        Ok(self.symbol(name, Kind::Variable))
    }

    fn module(&self, object: &Expr) -> Option<&HashMap<String, Export>> {
        if let Expr::Variable { name, .. } = object {
            if !self.locals.contains(name) {
                return self.aliases.get(name);
            }
        }
        None
    }

    fn member<'a>(
        &self,
        module: &'a HashMap<String, Export>,
        name: &str,
        line: usize,
        col: usize,
    ) -> Result<&'a Export, CompileError> {
        module
            .get(name)
            .ok_or_else(|| self.err(format!("Module has no export named '{}'", name), line, col))
    }

    fn field<'a>(
        &self,
        module: &'a HashMap<String, Export>,
        name: &str,
        line: usize,
        col: usize,
    ) -> Result<&'a Export, CompileError> {
        let export = self.member(module, name, line, col)?;
        if export.kind != Kind::Variable && export.kind != Kind::Constant {
            return Err(self.err(
                format!("Module member '{}' must be called", name),
                line,
                col,
            ));
        }
        Ok(export)
    }

    fn body(&mut self, nodes: Vec<Stmt>) -> Result<Vec<Stmt>, CompileError> {
        nodes.into_iter().map(|s| self.statement(s)).collect()
    }

    fn function_body(
        &mut self,
        params: &[String],
        nodes: Vec<Stmt>,
    ) -> Result<Vec<Stmt>, CompileError> {
        self.in_function = true;
        for p in params {
            self.locals.insert(p.clone());
        }
        let res = self.body(nodes);
        self.locals.clear();
        self.in_function = false;
        res
    }

    fn statement(&mut self, node: Stmt) -> Result<Stmt, CompileError> {
        match node {
            Stmt::Function(fn_decl) => {
                let body = self.function_body(&fn_decl.params, fn_decl.body)?;
                Ok(Stmt::Function(FunctionDecl {
                    name: self.symbol(&fn_decl.name, Kind::Function),
                    params: fn_decl.params,
                    body,
                    line: fn_decl.line,
                    column: fn_decl.column,
                }))
            }
            Stmt::Class(cls) => {
                let mut methods = Vec::new();
                for m in cls.methods {
                    let body = self.function_body(&m.params, m.body)?;
                    methods.push(MethodDecl {
                        name: m.name,
                        params: m.params,
                        body,
                        line: m.line,
                        column: m.column,
                    });
                }
                Ok(Stmt::Class(ClassDecl {
                    name: self.symbol(&cls.name, Kind::Class),
                    methods,
                    line: cls.line,
                    column: cls.column,
                }))
            }
            Stmt::VarDecl {
                name,
                secondary_names,
                is_local,
                init_expr,
                line,
                column,
            } => {
                if is_local && self.in_function {
                    self.locals.insert(name.clone());
                    for s in &secondary_names {
                        self.locals.insert(s.clone());
                    }
                }
                let new_name = self.variable(&name, line, column)?;
                let mut new_sec = Vec::with_capacity(secondary_names.len());
                for s in secondary_names {
                    new_sec.push(self.variable(&s, line, column)?);
                }
                let init_expr = match init_expr {
                    Some(e) => Some(self.expression(e)?),
                    None => None,
                };
                Ok(Stmt::VarDecl {
                    name: new_name,
                    secondary_names: new_sec,
                    is_local,
                    init_expr,
                    line,
                    column,
                })
            }
            Stmt::Assign {
                target,
                secondary_targets,
                op,
                value,
                line,
                column,
            } => {
                let target = self.target(target)?;
                let mut new_secs = Vec::with_capacity(secondary_targets.len());
                for sec in secondary_targets {
                    new_secs.push(self.target(sec)?);
                }
                let value = self.expression(value)?;
                Ok(Stmt::Assign {
                    target,
                    secondary_targets: new_secs,
                    op,
                    value,
                    line,
                    column,
                })
            }
            Stmt::ExprStmt { expr, line, column } => Ok(Stmt::ExprStmt {
                expr: self.expression(expr)?,
                line,
                column,
            }),
            Stmt::If {
                condition,
                then_body,
                elseifs,
                else_body,
                line,
                column,
            } => {
                let condition = self.expression(condition)?;
                let then_body = self.body(then_body)?;
                let mut new_elseifs = Vec::with_capacity(elseifs.len());
                for b in elseifs {
                    new_elseifs.push(ElseIfBranch {
                        condition: self.expression(b.condition)?,
                        body: self.body(b.body)?,
                    });
                }
                let else_body = self.body(else_body)?;
                Ok(Stmt::If {
                    condition,
                    then_body,
                    elseifs: new_elseifs,
                    else_body,
                    line,
                    column,
                })
            }
            Stmt::While {
                condition,
                body,
                line,
                column,
            } => Ok(Stmt::While {
                condition: self.expression(condition)?,
                body: self.body(body)?,
                line,
                column,
            }),
            Stmt::ForRange {
                var_name,
                start_expr,
                end_expr,
                step_expr,
                body,
                line,
                column,
            } => {
                let start_expr = self.expression(start_expr)?;
                let end_expr = self.expression(end_expr)?;
                let step_expr = match step_expr {
                    Some(e) => Some(self.expression(e)?),
                    None => None,
                };
                let body = self.body(body)?;
                let var_name = self.variable(&var_name, line, column)?;
                Ok(Stmt::ForRange {
                    var_name,
                    start_expr,
                    end_expr,
                    step_expr,
                    body,
                    line,
                    column,
                })
            }
            Stmt::Return { expr, line, column } => Ok(Stmt::Return {
                expr: match expr {
                    Some(e) => Some(self.expression(e)?),
                    None => None,
                },
                line,
                column,
            }),
            other => Ok(other),
        }
    }

    fn target(&mut self, target: AssignTarget) -> Result<AssignTarget, CompileError> {
        match target {
            AssignTarget::Var { name, line, column } => Ok(AssignTarget::Var {
                name: self.variable(&name, line, column)?,
                line,
                column,
            }),
            AssignTarget::Field {
                object,
                field,
                line,
                column,
            } => {
                if let Some(module) = self.module(&object).cloned() {
                    let export = self.field(&module, &field, line, column)?;
                    if export.kind == Kind::Constant {
                        return Err(self.err(
                            format!("Cannot assign to library constant '{}'", field),
                            line,
                            column,
                        ));
                    }
                    return Ok(AssignTarget::Var {
                        name: export.symbol.clone(),
                        line,
                        column,
                    });
                }
                Ok(AssignTarget::Field {
                    object: Box::new(self.expression(*object)?),
                    field,
                    line,
                    column,
                })
            }
            AssignTarget::Index {
                container,
                index,
                line,
                column,
            } => Ok(AssignTarget::Index {
                container: Box::new(self.expression(*container)?),
                index: Box::new(self.expression(*index)?),
                line,
                column,
            }),
        }
    }

    fn arguments(&mut self, args: Vec<Expr>) -> Result<Vec<Expr>, CompileError> {
        args.into_iter().map(|a| self.expression(a)).collect()
    }

    fn expression(&mut self, expr: Expr) -> Result<Expr, CompileError> {
        match expr {
            Expr::Variable { name, line, column } => Ok(Expr::Variable {
                name: self.variable(&name, line, column)?,
                line,
                column,
            }),
            Expr::UnaryOp {
                op,
                operand,
                line,
                column,
            } => Ok(Expr::UnaryOp {
                op,
                operand: Box::new(self.expression(*operand)?),
                line,
                column,
            }),
            Expr::BinaryOp {
                op,
                left,
                right,
                line,
                column,
            } => Ok(Expr::BinaryOp {
                op,
                left: Box::new(self.expression(*left)?),
                right: Box::new(self.expression(*right)?),
                line,
                column,
            }),
            Expr::Call {
                callee,
                args,
                line,
                column,
            } => {
                if self.aliases.contains_key(&callee) && !self.locals.contains(&callee) {
                    return Err(self.err(
                        format!("Module alias '{}' requires a member name", callee),
                        line,
                        column,
                    ));
                }
                let mut name = self.symbol(&callee, Kind::Function);
                if name == callee {
                    name = self.symbol(&name, Kind::Class);
                }
                Ok(Expr::Call {
                    callee: name,
                    args: self.arguments(args)?,
                    line,
                    column,
                })
            }
            Expr::MethodCall {
                object,
                method_name,
                args,
                line,
                column,
            } => {
                if let Some(module) = self.module(&object).cloned() {
                    let export = self.member(&module, &method_name, line, column)?;
                    let args = self.arguments(args)?;
                    return match export.kind {
                        Kind::Function => Ok(Expr::Call {
                            callee: export.symbol.clone(),
                            args,
                            line,
                            column,
                        }),
                        Kind::Class => Ok(Expr::New {
                            class_name: export.symbol.clone(),
                            args,
                            line,
                            column,
                        }),
                        Kind::SystemFunction => Ok(Expr::SystemCall {
                            symbol: export.library_symbol.clone().expect("system symbol"),
                            args,
                            line,
                            column,
                        }),
                        Kind::Variable | Kind::Constant => Err(self.err(
                            format!("Module variable '{}' is not callable", method_name),
                            line,
                            column,
                        )),
                    };
                }
                Ok(Expr::MethodCall {
                    object: Box::new(self.expression(*object)?),
                    method_name,
                    args: self.arguments(args)?,
                    line,
                    column,
                })
            }
            Expr::New {
                class_name,
                args,
                line,
                column,
            } => {
                let resolved_name = if let Some((alias, member_name)) = class_name.split_once('.') {
                    let module = self.aliases.get(alias).ok_or_else(|| {
                        self.err(format!("Unknown module alias '{}'", alias), line, column)
                    })?;
                    let export = self.member(module, member_name, line, column)?;
                    if export.kind != Kind::Class {
                        return Err(self.err(format!("'{}' is not a class", class_name), line, column));
                    }
                    export.symbol.clone()
                } else {
                    self.symbol(&class_name, Kind::Class)
                };
                Ok(Expr::New {
                    class_name: resolved_name,
                    args: self.arguments(args)?,
                    line,
                    column,
                })
            }
            Expr::FieldAccess {
                object,
                field_name,
                line,
                column,
            } => {
                if let Some(module) = self.module(&object).cloned() {
                    let export = self.field(&module, &field_name, line, column)?;
                    if export.kind == Kind::Constant {
                        let val = export
                            .library_symbol
                            .as_ref()
                            .and_then(|s| s.int_value)
                            .unwrap_or(0);
                        return Ok(Expr::Number {
                            value: val,
                            line,
                            column,
                        });
                    }
                    return Ok(Expr::Variable {
                        name: export.symbol.clone(),
                        line,
                        column,
                    });
                }
                Ok(Expr::FieldAccess {
                    object: Box::new(self.expression(*object)?),
                    field_name,
                    line,
                    column,
                })
            }
            Expr::IndexAccess {
                container,
                index,
                line,
                column,
            } => Ok(Expr::IndexAccess {
                container: Box::new(self.expression(*container)?),
                index: Box::new(self.expression(*index)?),
                line,
                column,
            }),
            Expr::List {
                elements,
                line,
                column,
            } => Ok(Expr::List {
                elements: self.arguments(elements)?,
                line,
                column,
            }),
            Expr::Map {
                entries,
                line,
                column,
            } => {
                let mut new_entries = Vec::with_capacity(entries.len());
                for e in entries {
                    new_entries.push(MapEntry {
                        key: self.expression(e.key)?,
                        value: self.expression(e.value)?,
                    });
                }
                Ok(Expr::Map {
                    entries: new_entries,
                    line,
                    column,
                })
            }
            other => Ok(other),
        }
    }
}
