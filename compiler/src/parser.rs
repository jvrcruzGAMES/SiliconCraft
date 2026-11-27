use std::collections::HashSet;

use crate::ast::*;
use crate::error::CompileError;
use crate::token::{Literal, Token, TokenType};

pub struct Parser {
    tokens: Vec<Token>,
    file_name: String,
    current: usize,
    known_classes: HashSet<String>,
}

impl Parser {
    pub fn new(tokens: Vec<Token>, file_name: Option<&str>) -> Self {
        let mut known_classes = HashSet::new();
        for i in 0..tokens.len().saturating_sub(1) {
            if tokens[i].kind == TokenType::KeywordClass && tokens[i + 1].kind == TokenType::Identifier {
                known_classes.insert(tokens[i + 1].text.clone());
            }
        }
        Self {
            tokens,
            file_name: file_name.unwrap_or("main.n2").to_string(),
            current: 0,
            known_classes,
        }
    }

    pub fn parse(&mut self) -> Result<Program, CompileError> {
        let mut program = Program::new();
        while !self.is_at_end() {
            if self.match_kind(TokenType::Semicolon) {
                continue;
            }
            if self.check(TokenType::KeywordImport) || self.check(TokenType::KeywordFrom) {
                program.imports.push(self.parse_import()?);
            } else if self.check(TokenType::KeywordClass) {
                program.statements.push(Stmt::Class(self.parse_class()?));
            } else if self.check(TokenType::KeywordFunc) {
                program.statements.push(Stmt::Function(self.parse_function()?));
            } else {
                program.statements.push(self.parse_statement()?);
            }
        }
        Ok(program)
    }

    fn err(&self, message: impl Into<String>, line: usize, col: usize) -> CompileError {
        CompileError::new(message, Some(&self.file_name), line, col)
    }

    fn parse_import(&mut self) -> Result<Import, CompileError> {
        let first = self.advance().clone();
        let line = first.line;
        let col = first.column;

        if first.kind == TokenType::KeywordFrom {
            let (path, is_library) = if self.check(TokenType::LibPath) {
                (self.advance().text.clone(), true)
            } else if self.check(TokenType::String) {
                let p = self.advance().text.clone();
                if p.starts_with("lib/") {
                    return Err(self.err(
                        format!("System libraries must use angle brackets e.g. from <{}> import ..., not quotes.", p),
                        line,
                        col,
                    ));
                }
                (p, false)
            } else if self.check(TokenType::Lt) {
                return Err(self.err(
                    "Project files must be imported using quotes: from \"file\" import ..., not <>",
                    line,
                    col,
                ));
            } else {
                return Err(self.err(
                    "Expected library path '<lib/...>' or '\"project_file\"' after 'from'",
                    line,
                    col,
                ));
            };

            self.consume(TokenType::KeywordImport, "Expected 'import' after library path in 'from ... import'")?;

            let mut symbols = Vec::new();
            loop {
                if self.check(TokenType::Star) {
                    self.advance();
                    symbols.clear();
                    break;
                }
                let sym = self.consume(TokenType::Identifier, "Expected symbol name to import")?.text.clone();
                symbols.push(sym);
                if !self.match_kind(TokenType::Comma) {
                    break;
                }
            }

            Ok(Import {
                path,
                is_library,
                symbols,
                alias: None,
                line,
                column: col,
            })
        } else if self.check(TokenType::LibPath) {
            let path = self.advance().text.clone();
            let alias = self.parse_import_alias()?;
            Ok(Import {
                path,
                is_library: true,
                symbols: Vec::new(),
                alias,
                line,
                column: col,
            })
        } else if self.check(TokenType::String) {
            let path = self.advance().text.clone();
            if path.starts_with("lib/") {
                return Err(self.err(
                    format!("System libraries must be imported using angle brackets: import <{}>, not quotes.", path),
                    line,
                    col,
                ));
            }
            let alias = self.parse_import_alias()?;
            Ok(Import {
                path,
                is_library: false,
                symbols: Vec::new(),
                alias,
                line,
                column: col,
            })
        } else if self.check(TokenType::Lt) {
            self.advance();
            let mut attempted = String::new();
            while !self.is_at_end() && !self.check(TokenType::Gt) {
                attempted.push_str(&self.advance().text);
            }
            self.match_kind(TokenType::Gt);
            Err(self.err(
                format!("Project files must be imported using quotes: import \"{}\", not <>", attempted),
                line,
                col,
            ))
        } else {
            Err(self.err("Expected '<lib/...>' or '\"project_file\"' after import", line, col))
        }
    }

    fn parse_import_alias(&mut self) -> Result<Option<String>, CompileError> {
        if !self.check(TokenType::Identifier) || self.peek().text != "as" {
            return Ok(None);
        }
        self.advance();
        let name = self.consume(TokenType::Identifier, "Expected module name after 'as'")?.text.clone();
        Ok(Some(name))
    }

    fn parse_class(&mut self) -> Result<ClassDecl, CompileError> {
        let class_kw = self.consume(TokenType::KeywordClass, "Expected 'class'")?.clone();
        let class_name = self.consume(TokenType::Identifier, "Expected class name")?.text.clone();
        self.known_classes.insert(class_name.clone());
        self.consume(TokenType::LBrace, "Expected '{' after class name")?;

        let mut methods = Vec::new();
        while !self.check(TokenType::RBrace) && !self.is_at_end() {
            if self.match_kind(TokenType::Semicolon) {
                continue;
            }
            if self.check(TokenType::KeywordFunc) {
                methods.push(self.parse_method()?);
            } else {
                let tok = self.peek().clone();
                return Err(self.err(
                    "Expected method declaration 'func ...' inside class body",
                    tok.line,
                    tok.column,
                ));
            }
        }
        self.consume(TokenType::RBrace, "Expected '}' closing class body")?;

        Ok(ClassDecl {
            name: class_name,
            methods,
            line: class_kw.line,
            column: class_kw.column,
        })
    }

    fn parse_method(&mut self) -> Result<MethodDecl, CompileError> {
        let func_kw = self.consume(TokenType::KeywordFunc, "Expected 'func'")?.clone();
        let method_name = self.consume(TokenType::Identifier, "Expected method name")?.text.clone();
        self.consume(TokenType::LParen, "Expected '(' after method name")?;
        let params = self.parse_param_list()?;
        self.consume(TokenType::RParen, "Expected ')' after parameters")?;

        let mut body = Vec::new();
        while !self.check(TokenType::KeywordEnd) && !self.is_at_end() {
            if self.match_kind(TokenType::Semicolon) {
                continue;
            }
            body.push(self.parse_statement()?);
        }
        self.consume(
            TokenType::KeywordEnd,
            &format!("Expected 'end' closing method '{}'", method_name),
        )?;

        Ok(MethodDecl {
            name: method_name,
            params,
            body,
            line: func_kw.line,
            column: func_kw.column,
        })
    }

    fn parse_function(&mut self) -> Result<FunctionDecl, CompileError> {
        let func_kw = self.consume(TokenType::KeywordFunc, "Expected 'func'")?.clone();
        let func_name = self.consume(TokenType::Identifier, "Expected function name")?.text.clone();
        self.consume(TokenType::LParen, "Expected '(' after function name")?;
        let params = self.parse_param_list()?;
        self.consume(TokenType::RParen, "Expected ')' after parameters")?;

        let mut body = Vec::new();
        while !self.check(TokenType::KeywordEnd) && !self.is_at_end() {
            if self.match_kind(TokenType::Semicolon) {
                continue;
            }
            body.push(self.parse_statement()?);
        }
        self.consume(
            TokenType::KeywordEnd,
            &format!("Expected 'end' closing function '{}'", func_name),
        )?;

        Ok(FunctionDecl {
            name: func_name,
            params,
            body,
            line: func_kw.line,
            column: func_kw.column,
        })
    }

    fn parse_param_list(&mut self) -> Result<Vec<String>, CompileError> {
        let mut params = Vec::new();
        if !self.check(TokenType::RParen) {
            loop {
                let p = self.consume(TokenType::Identifier, "Expected parameter name")?.text.clone();
                params.push(p);
                if !self.match_kind(TokenType::Comma) {
                    break;
                }
            }
        }
        Ok(params)
    }

    fn parse_statement(&mut self) -> Result<Stmt, CompileError> {
        if self.check(TokenType::KeywordLocal) {
            return self.parse_var_decl(true);
        }
        if self.check(TokenType::KeywordGlobal) {
            if self.peek_next().kind == TokenType::Identifier {
                return self.parse_var_decl(false);
            } else {
                let g_tok = self.advance().clone();
                let g_var = Expr::Variable {
                    name: "global".to_string(),
                    line: g_tok.line,
                    column: g_tok.column,
                };
                if self.check_assign_op() {
                    let op = self.advance().text.clone();
                    let val = self.parse_expression()?;
                    return Ok(Stmt::Assign {
                        target: AssignTarget::Var {
                            name: "global".to_string(),
                            line: g_tok.line,
                            column: g_tok.column,
                        },
                        secondary_targets: Vec::new(),
                        op,
                        value: val,
                        line: g_tok.line,
                        column: g_tok.column,
                    });
                }
                return Ok(Stmt::ExprStmt {
                    expr: g_var,
                    line: g_tok.line,
                    column: g_tok.column,
                });
            }
        }
        if self.check(TokenType::KeywordIf) {
            return self.parse_if();
        }
        if self.check(TokenType::KeywordWhile) {
            return self.parse_while();
        }
        if self.check(TokenType::KeywordFor) {
            return self.parse_for();
        }
        if self.check(TokenType::KeywordReturn) {
            return self.parse_return();
        }
        if self.check(TokenType::KeywordBreak) {
            let t = self.advance().clone();
            return Ok(Stmt::Break {
                line: t.line,
                column: t.column,
            });
        }
        if self.check(TokenType::KeywordContinue) {
            let t = self.advance().clone();
            return Ok(Stmt::Continue {
                line: t.line,
                column: t.column,
            });
        }

        let expr = self.parse_expression()?;
        let (line, col) = expr.pos();

        if self.check(TokenType::Comma) {
            let first_target = self.convert_expr_to_assign_target(expr)?;
            let mut secondary = Vec::new();
            while self.match_kind(TokenType::Comma) {
                let next_expr = self.parse_expression()?;
                secondary.push(self.convert_expr_to_assign_target(next_expr)?);
            }
            let op = self
                .consume(TokenType::Assign, "Expected '=' after multiple assignment targets")?
                .text
                .clone();
            let val = self.parse_expression()?;
            return Ok(Stmt::Assign {
                target: first_target,
                secondary_targets: secondary,
                op,
                value: val,
                line,
                column: col,
            });
        }

        if self.check_assign_op() {
            let op = self.advance().text.clone();
            let target = self.convert_expr_to_assign_target(expr)?;
            let val = self.parse_expression()?;
            return Ok(Stmt::Assign {
                target,
                secondary_targets: Vec::new(),
                op,
                value: val,
                line,
                column: col,
            });
        }

        Ok(Stmt::ExprStmt {
            expr,
            line,
            column: col,
        })
    }

    fn check_assign_op(&self) -> bool {
        matches!(
            self.peek().kind,
            TokenType::Assign
                | TokenType::PlusAssign
                | TokenType::MinusAssign
                | TokenType::StarAssign
                | TokenType::SlashAssign
        )
    }

    fn convert_expr_to_assign_target(&self, expr: Expr) -> Result<AssignTarget, CompileError> {
        match expr {
            Expr::Variable { name, line, column } => Ok(AssignTarget::Var { name, line, column }),
            Expr::FieldAccess {
                object,
                field_name,
                line,
                column,
            } => Ok(AssignTarget::Field {
                object,
                field: field_name,
                line,
                column,
            }),
            Expr::IndexAccess {
                container,
                index,
                line,
                column,
            } => Ok(AssignTarget::Index {
                container,
                index,
                line,
                column,
            }),
            other => {
                let (line, col) = other.pos();
                Err(self.err("Invalid assignment target", line, col))
            }
        }
    }

    fn parse_var_decl(&mut self, is_local: bool) -> Result<Stmt, CompileError> {
        let kw = self.advance().clone();
        let name = self.consume(TokenType::Identifier, "Expected variable name")?.text.clone();
        let mut secondary_names = Vec::new();
        while self.match_kind(TokenType::Comma) {
            let next = self.consume(TokenType::Identifier, "Expected variable name after ','")?.text.clone();
            secondary_names.push(next);
        }
        let init_expr = if self.match_kind(TokenType::Assign) {
            Some(self.parse_expression()?)
        } else {
            None
        };
        Ok(Stmt::VarDecl {
            name,
            secondary_names,
            is_local,
            init_expr,
            line: kw.line,
            column: kw.column,
        })
    }

    fn parse_if(&mut self) -> Result<Stmt, CompileError> {
        let if_tok = self.consume(TokenType::KeywordIf, "Expected 'if'")?.clone();
        let condition = self.parse_expression()?;
        self.match_kind(TokenType::KeywordThen);
        self.match_kind(TokenType::Colon);

        let mut then_body = Vec::new();
        while !self.check(TokenType::KeywordElseIf)
            && !self.check(TokenType::KeywordElse)
            && !self.check(TokenType::KeywordEnd)
            && !self.is_at_end()
        {
            if self.match_kind(TokenType::Semicolon) {
                continue;
            }
            then_body.push(self.parse_statement()?);
        }

        let mut elseifs = Vec::new();
        while self.match_kind(TokenType::KeywordElseIf) {
            let elif_cond = self.parse_expression()?;
            self.match_kind(TokenType::KeywordThen);
            self.match_kind(TokenType::Colon);
            let mut elif_body = Vec::new();
            while !self.check(TokenType::KeywordElseIf)
                && !self.check(TokenType::KeywordElse)
                && !self.check(TokenType::KeywordEnd)
                && !self.is_at_end()
            {
                if self.match_kind(TokenType::Semicolon) {
                    continue;
                }
                elif_body.push(self.parse_statement()?);
            }
            elseifs.push(ElseIfBranch {
                condition: elif_cond,
                body: elif_body,
            });
        }

        let mut else_body = Vec::new();
        if self.match_kind(TokenType::KeywordElse) {
            self.match_kind(TokenType::Colon);
            while !self.check(TokenType::KeywordEnd) && !self.is_at_end() {
                if self.match_kind(TokenType::Semicolon) {
                    continue;
                }
                else_body.push(self.parse_statement()?);
            }
        }

        self.consume(TokenType::KeywordEnd, "Expected 'end' closing if statement")?;
        Ok(Stmt::If {
            condition,
            then_body,
            elseifs,
            else_body,
            line: if_tok.line,
            column: if_tok.column,
        })
    }

    fn parse_while(&mut self) -> Result<Stmt, CompileError> {
        let while_tok = self.consume(TokenType::KeywordWhile, "Expected 'while'")?.clone();
        let condition = self.parse_expression()?;
        self.match_kind(TokenType::KeywordDo);
        self.match_kind(TokenType::Colon);

        let mut body = Vec::new();
        while !self.check(TokenType::KeywordEnd) && !self.is_at_end() {
            if self.match_kind(TokenType::Semicolon) {
                continue;
            }
            body.push(self.parse_statement()?);
        }
        self.consume(TokenType::KeywordEnd, "Expected 'end' closing while loop")?;
        Ok(Stmt::While {
            condition,
            body,
            line: while_tok.line,
            column: while_tok.column,
        })
    }

    fn parse_for(&mut self) -> Result<Stmt, CompileError> {
        let for_tok = self.consume(TokenType::KeywordFor, "Expected 'for'")?.clone();
        let var_name = self.consume(TokenType::Identifier, "Expected loop variable name")?.text.clone();
        self.consume(TokenType::Assign, "Expected '=' in for loop, e.g. for i = 1, 10 do")?;
        let start_expr = self.parse_expression()?;
        self.consume(TokenType::Comma, "Expected ',' between for loop range boundaries")?;
        let end_expr = self.parse_expression()?;
        let step_expr = if self.match_kind(TokenType::Comma) {
            Some(self.parse_expression()?)
        } else {
            None
        };
        self.match_kind(TokenType::KeywordDo);
        self.match_kind(TokenType::Colon);

        let mut body = Vec::new();
        while !self.check(TokenType::KeywordEnd) && !self.is_at_end() {
            if self.match_kind(TokenType::Semicolon) {
                continue;
            }
            body.push(self.parse_statement()?);
        }
        self.consume(TokenType::KeywordEnd, "Expected 'end' closing for loop")?;
        Ok(Stmt::ForRange {
            var_name,
            start_expr,
            end_expr,
            step_expr,
            body,
            line: for_tok.line,
            column: for_tok.column,
        })
    }

    fn parse_return(&mut self) -> Result<Stmt, CompileError> {
        let ret_tok = self.consume(TokenType::KeywordReturn, "Expected 'return'")?.clone();
        let expr = if !self.check(TokenType::KeywordEnd)
            && !self.check(TokenType::KeywordElse)
            && !self.check(TokenType::KeywordElseIf)
            && !self.check(TokenType::Semicolon)
            && !self.is_at_end()
        {
            Some(self.parse_expression()?)
        } else {
            None
        };
        Ok(Stmt::Return {
            expr,
            line: ret_tok.line,
            column: ret_tok.column,
        })
    }

    pub fn parse_expression(&mut self) -> Result<Expr, CompileError> {
        self.parse_logical_or()
    }

    fn parse_logical_or(&mut self) -> Result<Expr, CompileError> {
        let mut expr = self.parse_logical_and()?;
        while self.check(TokenType::KeywordOr) || self.check(TokenType::Pipe) {
            let op = self.advance().clone();
            let right = self.parse_logical_and()?;
            expr = Expr::BinaryOp {
                op: op.text.to_ascii_lowercase(),
                left: Box::new(expr),
                right: Box::new(right),
                line: op.line,
                column: op.column,
            };
        }
        Ok(expr)
    }

    fn parse_logical_and(&mut self) -> Result<Expr, CompileError> {
        let mut expr = self.parse_bitwise_or()?;
        while self.check(TokenType::KeywordAnd) || self.check(TokenType::Amp) {
            let op = self.advance().clone();
            let right = self.parse_bitwise_or()?;
            expr = Expr::BinaryOp {
                op: op.text.to_ascii_lowercase(),
                left: Box::new(expr),
                right: Box::new(right),
                line: op.line,
                column: op.column,
            };
        }
        Ok(expr)
    }

    fn parse_bitwise_or(&mut self) -> Result<Expr, CompileError> {
        let mut expr = self.parse_bitwise_xor()?;
        while self.check(TokenType::Pipe) {
            let op = self.advance().clone();
            let right = self.parse_bitwise_xor()?;
            expr = Expr::BinaryOp {
                op: "|".to_string(),
                left: Box::new(expr),
                right: Box::new(right),
                line: op.line,
                column: op.column,
            };
        }
        Ok(expr)
    }

    fn parse_bitwise_xor(&mut self) -> Result<Expr, CompileError> {
        let mut expr = self.parse_bitwise_and()?;
        while self.check(TokenType::Caret) {
            let op = self.advance().clone();
            let right = self.parse_bitwise_and()?;
            expr = Expr::BinaryOp {
                op: "^".to_string(),
                left: Box::new(expr),
                right: Box::new(right),
                line: op.line,
                column: op.column,
            };
        }
        Ok(expr)
    }

    fn parse_bitwise_and(&mut self) -> Result<Expr, CompileError> {
        let mut expr = self.parse_equality()?;
        while self.check(TokenType::Amp) {
            let op = self.advance().clone();
            let right = self.parse_equality()?;
            expr = Expr::BinaryOp {
                op: "&".to_string(),
                left: Box::new(expr),
                right: Box::new(right),
                line: op.line,
                column: op.column,
            };
        }
        Ok(expr)
    }

    fn parse_equality(&mut self) -> Result<Expr, CompileError> {
        let mut expr = self.parse_relational()?;
        while self.check(TokenType::Eq) || self.check(TokenType::Neq) {
            let op = self.advance().clone();
            let right = self.parse_relational()?;
            expr = Expr::BinaryOp {
                op: op.text,
                left: Box::new(expr),
                right: Box::new(right),
                line: op.line,
                column: op.column,
            };
        }
        Ok(expr)
    }

    fn parse_relational(&mut self) -> Result<Expr, CompileError> {
        let mut expr = self.parse_shift()?;
        while matches!(
            self.peek().kind,
            TokenType::Lt | TokenType::Lte | TokenType::Gt | TokenType::Gte
        ) {
            let op = self.advance().clone();
            let right = self.parse_shift()?;
            expr = Expr::BinaryOp {
                op: op.text,
                left: Box::new(expr),
                right: Box::new(right),
                line: op.line,
                column: op.column,
            };
        }
        Ok(expr)
    }

    fn parse_shift(&mut self) -> Result<Expr, CompileError> {
        let mut expr = self.parse_additive()?;
        while self.check(TokenType::Shl) || self.check(TokenType::Shr) {
            let op = self.advance().clone();
            let right = self.parse_additive()?;
            expr = Expr::BinaryOp {
                op: op.text,
                left: Box::new(expr),
                right: Box::new(right),
                line: op.line,
                column: op.column,
            };
        }
        Ok(expr)
    }

    fn parse_additive(&mut self) -> Result<Expr, CompileError> {
        let mut expr = self.parse_multiplicative()?;
        while self.check(TokenType::Plus) || self.check(TokenType::Minus) {
            let op = self.advance().clone();
            let right = self.parse_multiplicative()?;
            expr = Expr::BinaryOp {
                op: op.text,
                left: Box::new(expr),
                right: Box::new(right),
                line: op.line,
                column: op.column,
            };
        }
        Ok(expr)
    }

    fn parse_multiplicative(&mut self) -> Result<Expr, CompileError> {
        let mut expr = self.parse_unary()?;
        while matches!(
            self.peek().kind,
            TokenType::Star | TokenType::Slash | TokenType::Percent
        ) {
            let op = self.advance().clone();
            let right = self.parse_unary()?;
            expr = Expr::BinaryOp {
                op: op.text,
                left: Box::new(expr),
                right: Box::new(right),
                line: op.line,
                column: op.column,
            };
        }
        Ok(expr)
    }

    fn parse_unary(&mut self) -> Result<Expr, CompileError> {
        if matches!(
            self.peek().kind,
            TokenType::Minus | TokenType::KeywordNot | TokenType::Tilde | TokenType::Hash
        ) {
            let op = self.advance().clone();
            let operand = self.parse_unary()?;
            return Ok(Expr::UnaryOp {
                op: op.text,
                operand: Box::new(operand),
                line: op.line,
                column: op.column,
            });
        }
        self.parse_postfix()
    }

    fn parse_postfix(&mut self) -> Result<Expr, CompileError> {
        let mut expr = self.parse_primary()?;
        loop {
            if self.match_kind(TokenType::Dot) {
                let field = self
                    .consume(TokenType::Identifier, "Expected field or method name after '.'")?
                    .clone();
                if self.match_kind(TokenType::LParen) {
                    let args = self.parse_arg_list()?;
                    self.consume(TokenType::RParen, "Expected ')' after method arguments")?;
                    expr = Expr::MethodCall {
                        object: Box::new(expr),
                        method_name: field.text,
                        args,
                        line: field.line,
                        column: field.column,
                    };
                } else {
                    expr = Expr::FieldAccess {
                        object: Box::new(expr),
                        field_name: field.text,
                        line: field.line,
                        column: field.column,
                    };
                }
            } else if self.match_kind(TokenType::LBracket) {
                let (line, col) = expr.pos();
                let index = self.parse_expression()?;
                self.consume(TokenType::RBracket, "Expected ']' after index")?;
                expr = Expr::IndexAccess {
                    container: Box::new(expr),
                    index: Box::new(index),
                    line,
                    column: col,
                };
            } else {
                break;
            }
        }
        Ok(expr)
    }

    fn parse_primary(&mut self) -> Result<Expr, CompileError> {
        let token = self.peek().clone();

        if self.match_kind(TokenType::Number) {
            let val = match token.literal {
                Literal::Int(v) => v,
                _ => 0,
            };
            return Ok(Expr::Number {
                value: val,
                line: token.line,
                column: token.column,
            });
        }
        if self.match_kind(TokenType::String) {
            let val = match token.literal {
                Literal::Str(s) => s,
                _ => token.text,
            };
            return Ok(Expr::String {
                value: val,
                line: token.line,
                column: token.column,
            });
        }
        if self.match_kind(TokenType::KeywordTrue) {
            return Ok(Expr::Boolean {
                value: true,
                line: token.line,
                column: token.column,
            });
        }
        if self.match_kind(TokenType::KeywordFalse) {
            return Ok(Expr::Boolean {
                value: false,
                line: token.line,
                column: token.column,
            });
        }
        if self.match_kind(TokenType::KeywordNil) {
            return Ok(Expr::Nil {
                line: token.line,
                column: token.column,
            });
        }
        if self.match_kind(TokenType::KeywordThis) || self.match_kind(TokenType::KeywordSelf) {
            return Ok(Expr::This {
                line: token.line,
                column: token.column,
            });
        }

        if self.match_kind(TokenType::KeywordNew) {
            let cls_tok = self.consume(TokenType::Identifier, "Expected class name after 'new'")?.clone();
            let mut name = cls_tok.text;
            if self.match_kind(TokenType::Dot) {
                let sub = self.consume(TokenType::Identifier, "Expected class name after '.'")?.text.clone();
                name = format!("{}.{}", name, sub);
            }
            self.consume(TokenType::LParen, "Expected '(' after class name in instantiation")?;
            let args = self.parse_arg_list()?;
            self.consume(TokenType::RParen, "Expected ')' after constructor arguments")?;
            return Ok(Expr::New {
                class_name: name,
                args,
                line: cls_tok.line,
                column: cls_tok.column,
            });
        }

        if self.match_kind(TokenType::LBracket) {
            let mut elems = Vec::new();
            if !self.check(TokenType::RBracket) {
                loop {
                    elems.push(self.parse_expression()?);
                    if !self.match_kind(TokenType::Comma) {
                        break;
                    }
                }
            }
            self.consume(TokenType::RBracket, "Expected ']' closing list literal")?;
            return Ok(Expr::List {
                elements: elems,
                line: token.line,
                column: token.column,
            });
        }

        if self.match_kind(TokenType::LBrace) {
            let mut entries = Vec::new();
            if !self.check(TokenType::RBrace) {
                loop {
                    let key = if self.check(TokenType::Identifier)
                        && self.peek_next().kind != TokenType::LParen
                        && self.peek_next().kind != TokenType::Dot
                    {
                        let id = self.advance().clone();
                        Expr::String {
                            value: id.text,
                            line: id.line,
                            column: id.column,
                        }
                    } else {
                        self.parse_expression()?
                    };

                    if !self.match_kind(TokenType::Colon) && !self.match_kind(TokenType::Assign) {
                        let pk = self.peek().clone();
                        return Err(self.err(
                            "Expected ':' or '=' between key and value in map literal",
                            pk.line,
                            pk.column,
                        ));
                    }
                    let value = self.parse_expression()?;
                    entries.push(MapEntry { key, value });
                    if !self.match_kind(TokenType::Comma) {
                        break;
                    }
                }
            }
            self.consume(TokenType::RBrace, "Expected '}' closing map literal")?;
            return Ok(Expr::Map {
                entries,
                line: token.line,
                column: token.column,
            });
        }

        if self.match_kind(TokenType::Identifier) {
            let name = token.text.clone();
            if self.match_kind(TokenType::LParen) {
                let args = self.parse_arg_list()?;
                self.consume(TokenType::RParen, "Expected ')' after function arguments")?;
                if self.known_classes.contains(&name) {
                    return Ok(Expr::New {
                        class_name: name,
                        args,
                        line: token.line,
                        column: token.column,
                    });
                }
                return Ok(Expr::Call {
                    callee: name,
                    args,
                    line: token.line,
                    column: token.column,
                });
            }
            return Ok(Expr::Variable {
                name,
                line: token.line,
                column: token.column,
            });
        }

        if self.match_kind(TokenType::KeywordGlobal) {
            return Ok(Expr::Variable {
                name: "global".to_string(),
                line: token.line,
                column: token.column,
            });
        }

        if self.match_kind(TokenType::LParen) {
            let inner = self.parse_expression()?;
            self.consume(TokenType::RParen, "Expected ')' closing parenthesized expression")?;
            return Ok(inner);
        }

        Err(self.err(
            format!("Unexpected token in expression: {:?} ('{}')", token.kind, token.text),
            token.line,
            token.column,
        ))
    }

    fn parse_arg_list(&mut self) -> Result<Vec<Expr>, CompileError> {
        let mut args = Vec::new();
        if !self.check(TokenType::RParen) {
            loop {
                args.push(self.parse_expression()?);
                if !self.match_kind(TokenType::Comma) {
                    break;
                }
            }
        }
        Ok(args)
    }

    fn is_at_end(&self) -> bool {
        self.peek().kind == TokenType::Eof
    }

    fn peek(&self) -> &Token {
        self.tokens
            .get(self.current)
            .unwrap_or_else(|| self.tokens.last().expect("tokens always ends with Eof"))
    }

    fn peek_next(&self) -> &Token {
        self.tokens
            .get(self.current + 1)
            .unwrap_or_else(|| self.tokens.last().expect("tokens always ends with Eof"))
    }

    fn check(&self, kind: TokenType) -> bool {
        if self.is_at_end() {
            return kind == TokenType::Eof;
        }
        self.peek().kind == kind
    }

    fn advance(&mut self) -> &Token {
        if !self.is_at_end() {
            self.current += 1;
        }
        &self.tokens[self.current - 1]
    }

    fn match_kind(&mut self, kind: TokenType) -> bool {
        if self.check(kind) {
            self.advance();
            true
        } else {
            false
        }
    }

    fn consume(&mut self, kind: TokenType, err_msg: &str) -> Result<&Token, CompileError> {
        if self.check(kind) {
            return Ok(self.advance());
        }
        let pk = self.peek().clone();
        Err(self.err(format!("{} (got: '{}')", err_msg, pk.text), pk.line, pk.column))
    }
}
