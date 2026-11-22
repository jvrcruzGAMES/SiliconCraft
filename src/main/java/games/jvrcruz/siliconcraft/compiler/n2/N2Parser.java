package games.jvrcruz.siliconcraft.compiler.n2;

import java.util.*;

public class N2Parser {
    private final List<N2Token> tokens;
    private final String fileName;
    private int current = 0;
    private final Set<String> knownClasses = new HashSet<>();

    public N2Parser(List<N2Token> tokens, String fileName) {
        this.tokens = tokens != null ? tokens : List.of();
        this.fileName = fileName;
        // Pre-scan class names so Player() can be parsed as instance creation
        for (int i = 0; i < this.tokens.size() - 1; i++) {
            if (this.tokens.get(i).type() == N2TokenType.KEYWORD_CLASS &&
                this.tokens.get(i + 1).type() == N2TokenType.IDENTIFIER) {
                knownClasses.add(this.tokens.get(i + 1).text());
            }
        }
    }

    public N2AST.ProgramNode parse() {
        N2AST.ProgramNode program = new N2AST.ProgramNode(1, 1);

        while (!isAtEnd()) {
            if (match(N2TokenType.SEMICOLON)) {
                continue;
            }

            if (check(N2TokenType.KEYWORD_IMPORT) || check(N2TokenType.KEYWORD_FROM)) {
                program.imports.add(parseImport());
            } else if (check(N2TokenType.KEYWORD_CLASS)) {
                program.statements.add(parseClass());
            } else if (check(N2TokenType.KEYWORD_FUNC)) {
                program.statements.add(parseFunction());
            } else {
                program.statements.add(parseStatement());
            }
        }

        return program;
    }

    private N2AST.ImportNode parseImport() {
        N2Token first = advance();
        int line = first.line();
        int col = first.column();

        if (first.type() == N2TokenType.KEYWORD_FROM) {
            // from <lib/*> import Symbol1, Symbol2  OR  from "file.n2" import Symbol1
            String path;
            boolean isLibrary;
            if (check(N2TokenType.LIB_PATH)) {
                path = advance().text();
                isLibrary = true;
            } else if (check(N2TokenType.STRING)) {
                path = advance().text();
                if (path.startsWith("lib/")) {
                    throw new N2CompileException("System libraries must use angle brackets e.g. from <" + path + "> import ..., not quotes.", fileName, line, col);
                }
                isLibrary = false;
            } else if (check(N2TokenType.LT)) {
                throw new N2CompileException("Project files must be imported using quotes: from \"file\" import ..., not <>", fileName, line, col);
            } else {
                throw new N2CompileException("Expected library path '<lib/...>' or '\"project_file\"' after 'from'", fileName, line, col);
            }

            consume(N2TokenType.KEYWORD_IMPORT, "Expected 'import' after library path in 'from ... import'");

            List<String> symbols = new ArrayList<>();
            do {
                if (check(N2TokenType.STAR)) {
                    advance(); // wildcard from <lib/*> import *
                    symbols.clear();
                    break;
                }
                N2Token sym = consume(N2TokenType.IDENTIFIER, "Expected symbol name to import");
                symbols.add(sym.text());
            } while (match(N2TokenType.COMMA));

            return new N2AST.ImportNode(path, isLibrary, symbols, line, col);
        } else {
            // import <lib/*>  OR  import "project_file.n2"
            if (check(N2TokenType.LIB_PATH)) {
                N2Token libToken = advance();
                return new N2AST.ImportNode(libToken.text(), true, Collections.emptyList(), line, col);
            } else if (check(N2TokenType.STRING)) {
                N2Token strToken = advance();
                String path = strToken.text();
                if (path.startsWith("lib/")) {
                    throw new N2CompileException("System libraries must be imported using angle brackets: import <" + path + ">, not quotes.", fileName, line, col);
                }
                return new N2AST.ImportNode(path, false, Collections.emptyList(), line, col);
            } else if (check(N2TokenType.LT)) {
                // User attempted <file.n2>
                advance();
                StringBuilder sb = new StringBuilder();
                while (!isAtEnd() && !check(N2TokenType.GT)) {
                    sb.append(advance().text());
                }
                match(N2TokenType.GT);
                String attempted = sb.toString();
                throw new N2CompileException("Project files must be imported using quotes: import \"" + attempted + "\", not <>", fileName, line, col);
            } else {
                throw new N2CompileException("Expected '<lib/...>' or '\"project_file\"' after import", fileName, line, col);
            }
        }
    }

    private N2AST.ClassNode parseClass() {
        N2Token classKw = consume(N2TokenType.KEYWORD_CLASS, "Expected 'class'");
        N2Token nameToken = consume(N2TokenType.IDENTIFIER, "Expected class name");
        String className = nameToken.text();
        knownClasses.add(className);

        consume(N2TokenType.LBRACE, "Expected '{' after class name");
        N2AST.ClassNode classNode = new N2AST.ClassNode(className, classKw.line(), classKw.column());

        while (!check(N2TokenType.RBRACE) && !isAtEnd()) {
            if (match(N2TokenType.SEMICOLON)) continue;

            if (check(N2TokenType.KEYWORD_FUNC)) {
                classNode.methods.add(parseMethod());
            } else {
                throw new N2CompileException("Expected method declaration 'func ...' inside class body", fileName, peek().line(), peek().column());
            }
        }

        consume(N2TokenType.RBRACE, "Expected '}' closing class body");
        return classNode;
    }

    private N2AST.MethodNode parseMethod() {
        N2Token funcKw = consume(N2TokenType.KEYWORD_FUNC, "Expected 'func'");
        N2Token nameToken = consume(N2TokenType.IDENTIFIER, "Expected method name");
        String methodName = nameToken.text();

        consume(N2TokenType.LPAREN, "Expected '(' after method name");
        List<String> params = new ArrayList<>();
        if (!check(N2TokenType.RPAREN)) {
            do {
                N2Token p = consume(N2TokenType.IDENTIFIER, "Expected parameter name");
                params.add(p.text());
            } while (match(N2TokenType.COMMA));
        }
        consume(N2TokenType.RPAREN, "Expected ')' after parameters");

        List<N2AST.Node> body = new ArrayList<>();
        while (!check(N2TokenType.KEYWORD_END) && !isAtEnd()) {
            if (match(N2TokenType.SEMICOLON)) continue;
            body.add(parseStatement());
        }
        consume(N2TokenType.KEYWORD_END, "Expected 'end' closing method '" + methodName + "'");

        return new N2AST.MethodNode(methodName, params, body, funcKw.line(), funcKw.column());
    }

    private N2AST.FunctionNode parseFunction() {
        N2Token funcKw = consume(N2TokenType.KEYWORD_FUNC, "Expected 'func'");
        N2Token nameToken = consume(N2TokenType.IDENTIFIER, "Expected function name");
        String funcName = nameToken.text();

        consume(N2TokenType.LPAREN, "Expected '(' after function name");
        List<String> params = new ArrayList<>();
        if (!check(N2TokenType.RPAREN)) {
            do {
                N2Token p = consume(N2TokenType.IDENTIFIER, "Expected parameter name");
                params.add(p.text());
            } while (match(N2TokenType.COMMA));
        }
        consume(N2TokenType.RPAREN, "Expected ')' after parameters");

        List<N2AST.Node> body = new ArrayList<>();
        while (!check(N2TokenType.KEYWORD_END) && !isAtEnd()) {
            if (match(N2TokenType.SEMICOLON)) continue;
            body.add(parseStatement());
        }
        consume(N2TokenType.KEYWORD_END, "Expected 'end' closing function '" + funcName + "'");

        return new N2AST.FunctionNode(funcName, params, body, funcKw.line(), funcKw.column());
    }

    private N2AST.Node parseStatement() {
        if (check(N2TokenType.KEYWORD_LOCAL)) {
            return parseVarDecl(true);
        }
        if (check(N2TokenType.KEYWORD_GLOBAL)) {
            if (peekNext().type() == N2TokenType.IDENTIFIER) {
                advance(); // consume 'global' keyword
                return parseVarDecl(false);
            } else {
                N2Token gToken = advance();
                N2AST.VariableNode gVar = new N2AST.VariableNode("global", gToken.line(), gToken.column());
                if (checkAssignOp()) {
                    N2Token op = advance();
                    N2AST.ExprNode val = parseExpression();
                    return new N2AST.AssignNode(new N2AST.VarTarget("global", gToken.line(), gToken.column()), op.text(), val, gToken.line(), gToken.column());
                }
                return new N2AST.ExprStmtNode(gVar, gToken.line(), gToken.column());
            }
        }
        if (check(N2TokenType.KEYWORD_IF)) {
            return parseIf();
        }
        if (check(N2TokenType.KEYWORD_WHILE)) {
            return parseWhile();
        }
        if (check(N2TokenType.KEYWORD_FOR)) {
            return parseFor();
        }
        if (check(N2TokenType.KEYWORD_RETURN)) {
            return parseReturn();
        }
        if (check(N2TokenType.KEYWORD_BREAK)) {
            N2Token t = advance();
            return new N2AST.BreakNode(t.line(), t.column());
        }
        if (check(N2TokenType.KEYWORD_CONTINUE)) {
            N2Token t = advance();
            return new N2AST.ContinueNode(t.line(), t.column());
        }

        // Could be an assignment or an expression statement
        N2AST.ExprNode expr = parseExpression();

        // Check if this was an assignment target: ident = ..., obj.field = ..., container[idx] = ...
        if (checkAssignOp()) {
            N2Token op = advance();
            N2AST.AssignTarget target = convertExprToAssignTarget(expr);
            N2AST.ExprNode val = parseExpression();
            return new N2AST.AssignNode(target, op.text(), val, expr.getLine(), expr.getColumn());
        }

        return new N2AST.ExprStmtNode(expr, expr.getLine(), expr.getColumn());
    }

    private boolean checkAssignOp() {
        N2TokenType t = peek().type();
        return t == N2TokenType.ASSIGN || t == N2TokenType.PLUS_ASSIGN ||
               t == N2TokenType.MINUS_ASSIGN || t == N2TokenType.STAR_ASSIGN ||
               t == N2TokenType.SLASH_ASSIGN;
    }

    private N2AST.AssignTarget convertExprToAssignTarget(N2AST.ExprNode expr) {
        if (expr instanceof N2AST.VariableNode v) {
            return new N2AST.VarTarget(v.name, v.getLine(), v.getColumn());
        }
        if (expr instanceof N2AST.FieldAccessNode f) {
            return new N2AST.FieldTarget(f.object, f.fieldName, f.getLine(), f.getColumn());
        }
        if (expr instanceof N2AST.IndexAccessNode idx) {
            return new N2AST.IndexTarget(idx.container, idx.index, idx.getLine(), idx.getColumn());
        }
        throw new N2CompileException("Invalid assignment target: " + expr.getClass().getSimpleName(), fileName, expr.getLine(), expr.getColumn());
    }

    private N2AST.Node parseVarDecl(boolean isLocal) {
        N2Token kw = advance(); // 'local' or consumed 'global'
        N2Token nameToken = consume(N2TokenType.IDENTIFIER, "Expected variable name");
        String name = nameToken.text();

        N2AST.ExprNode init = null;
        if (match(N2TokenType.ASSIGN)) {
            init = parseExpression();
        }

        return new N2AST.VarDeclNode(name, isLocal, init, kw.line(), kw.column());
    }

    private N2AST.IfNode parseIf() {
        N2Token ifToken = consume(N2TokenType.KEYWORD_IF, "Expected 'if'");
        N2AST.ExprNode cond = parseExpression();
        match(N2TokenType.KEYWORD_THEN);
        match(N2TokenType.COLON);

        List<N2AST.Node> thenBody = new ArrayList<>();
        List<N2AST.ElseIfBranch> elseifs = new ArrayList<>();
        List<N2AST.Node> elseBody = new ArrayList<>();

        while (!check(N2TokenType.KEYWORD_ELSEIF) && !check(N2TokenType.KEYWORD_ELSE) &&
               !check(N2TokenType.KEYWORD_END) && !isAtEnd()) {
            if (match(N2TokenType.SEMICOLON)) continue;
            thenBody.add(parseStatement());
        }

        while (match(N2TokenType.KEYWORD_ELSEIF)) {
            N2AST.ExprNode elifCond = parseExpression();
            match(N2TokenType.KEYWORD_THEN);
            match(N2TokenType.COLON);
            List<N2AST.Node> elifBody = new ArrayList<>();
            while (!check(N2TokenType.KEYWORD_ELSEIF) && !check(N2TokenType.KEYWORD_ELSE) &&
                   !check(N2TokenType.KEYWORD_END) && !isAtEnd()) {
                if (match(N2TokenType.SEMICOLON)) continue;
                elifBody.add(parseStatement());
            }
            elseifs.add(new N2AST.ElseIfBranch(elifCond, elifBody));
        }

        if (match(N2TokenType.KEYWORD_ELSE)) {
            match(N2TokenType.COLON);
            while (!check(N2TokenType.KEYWORD_END) && !isAtEnd()) {
                if (match(N2TokenType.SEMICOLON)) continue;
                elseBody.add(parseStatement());
            }
        }

        consume(N2TokenType.KEYWORD_END, "Expected 'end' closing if statement");
        return new N2AST.IfNode(cond, thenBody, elseifs, elseBody, ifToken.line(), ifToken.column());
    }

    private N2AST.WhileNode parseWhile() {
        N2Token whileToken = consume(N2TokenType.KEYWORD_WHILE, "Expected 'while'");
        N2AST.ExprNode cond = parseExpression();
        match(N2TokenType.KEYWORD_DO);
        match(N2TokenType.COLON);

        List<N2AST.Node> body = new ArrayList<>();
        while (!check(N2TokenType.KEYWORD_END) && !isAtEnd()) {
            if (match(N2TokenType.SEMICOLON)) continue;
            body.add(parseStatement());
        }
        consume(N2TokenType.KEYWORD_END, "Expected 'end' closing while loop");

        return new N2AST.WhileNode(cond, body, whileToken.line(), whileToken.column());
    }

    private N2AST.ForRangeNode parseFor() {
        N2Token forToken = consume(N2TokenType.KEYWORD_FOR, "Expected 'for'");
        N2Token varToken = consume(N2TokenType.IDENTIFIER, "Expected loop variable name");
        String varName = varToken.text();

        consume(N2TokenType.ASSIGN, "Expected '=' in for loop, e.g. for i = 1, 10 do");
        N2AST.ExprNode start = parseExpression();
        consume(N2TokenType.COMMA, "Expected ',' between for loop range boundaries");
        N2AST.ExprNode end = parseExpression();

        N2AST.ExprNode step = null;
        if (match(N2TokenType.COMMA)) {
            step = parseExpression();
        }

        match(N2TokenType.KEYWORD_DO);
        match(N2TokenType.COLON);

        List<N2AST.Node> body = new ArrayList<>();
        while (!check(N2TokenType.KEYWORD_END) && !isAtEnd()) {
            if (match(N2TokenType.SEMICOLON)) continue;
            body.add(parseStatement());
        }
        consume(N2TokenType.KEYWORD_END, "Expected 'end' closing for loop");

        return new N2AST.ForRangeNode(varName, start, end, step, body, forToken.line(), forToken.column());
    }

    private N2AST.ReturnNode parseReturn() {
        N2Token retToken = consume(N2TokenType.KEYWORD_RETURN, "Expected 'return'");
        N2AST.ExprNode expr = null;
        if (!check(N2TokenType.KEYWORD_END) && !check(N2TokenType.KEYWORD_ELSE) &&
            !check(N2TokenType.KEYWORD_ELSEIF) && !check(N2TokenType.SEMICOLON) && !isAtEnd()) {
            expr = parseExpression();
        }
        return new N2AST.ReturnNode(expr, retToken.line(), retToken.column());
    }

    // --- Expression Parsing (Precedence climbing) ---

    public N2AST.ExprNode parseExpression() {
        return parseLogicalOr();
    }

    private N2AST.ExprNode parseLogicalOr() {
        N2AST.ExprNode expr = parseLogicalAnd();
        while (check(N2TokenType.KEYWORD_OR) || check(N2TokenType.PIPE)) {
            N2Token op = advance();
            N2AST.ExprNode right = parseLogicalAnd();
            expr = new N2AST.BinaryOpNode(op.text().toLowerCase(Locale.ROOT), expr, right, op.line(), op.column());
        }
        return expr;
    }

    private N2AST.ExprNode parseLogicalAnd() {
        N2AST.ExprNode expr = parseBitwiseOr();
        while (check(N2TokenType.KEYWORD_AND) || check(N2TokenType.AMP)) {
            N2Token op = advance();
            N2AST.ExprNode right = parseBitwiseOr();
            expr = new N2AST.BinaryOpNode(op.text().toLowerCase(Locale.ROOT), expr, right, op.line(), op.column());
        }
        return expr;
    }

    private N2AST.ExprNode parseBitwiseOr() {
        N2AST.ExprNode expr = parseBitwiseXor();
        while (check(N2TokenType.PIPE)) {
            N2Token op = advance();
            N2AST.ExprNode right = parseBitwiseXor();
            expr = new N2AST.BinaryOpNode("|", expr, right, op.line(), op.column());
        }
        return expr;
    }

    private N2AST.ExprNode parseBitwiseXor() {
        N2AST.ExprNode expr = parseBitwiseAnd();
        while (check(N2TokenType.CARET)) {
            N2Token op = advance();
            N2AST.ExprNode right = parseBitwiseAnd();
            expr = new N2AST.BinaryOpNode("^", expr, right, op.line(), op.column());
        }
        return expr;
    }

    private N2AST.ExprNode parseBitwiseAnd() {
        N2AST.ExprNode expr = parseEquality();
        while (check(N2TokenType.AMP)) {
            N2Token op = advance();
            N2AST.ExprNode right = parseEquality();
            expr = new N2AST.BinaryOpNode("&", expr, right, op.line(), op.column());
        }
        return expr;
    }

    private N2AST.ExprNode parseEquality() {
        N2AST.ExprNode expr = parseRelational();
        while (check(N2TokenType.EQ) || check(N2TokenType.NEQ)) {
            N2Token op = advance();
            N2AST.ExprNode right = parseRelational();
            expr = new N2AST.BinaryOpNode(op.text(), expr, right, op.line(), op.column());
        }
        return expr;
    }

    private N2AST.ExprNode parseRelational() {
        N2AST.ExprNode expr = parseShift();
        while (check(N2TokenType.LT) || check(N2TokenType.LTE) ||
               check(N2TokenType.GT) || check(N2TokenType.GTE)) {
            N2Token op = advance();
            N2AST.ExprNode right = parseShift();
            expr = new N2AST.BinaryOpNode(op.text(), expr, right, op.line(), op.column());
        }
        return expr;
    }

    private N2AST.ExprNode parseShift() {
        N2AST.ExprNode expr = parseAdditive();
        while (check(N2TokenType.SHL) || check(N2TokenType.SHR)) {
            N2Token op = advance();
            N2AST.ExprNode right = parseAdditive();
            expr = new N2AST.BinaryOpNode(op.text(), expr, right, op.line(), op.column());
        }
        return expr;
    }

    private N2AST.ExprNode parseAdditive() {
        N2AST.ExprNode expr = parseMultiplicative();
        while (check(N2TokenType.PLUS) || check(N2TokenType.MINUS)) {
            N2Token op = advance();
            N2AST.ExprNode right = parseMultiplicative();
            expr = new N2AST.BinaryOpNode(op.text(), expr, right, op.line(), op.column());
        }
        return expr;
    }

    private N2AST.ExprNode parseMultiplicative() {
        N2AST.ExprNode expr = parseUnary();
        while (check(N2TokenType.STAR) || check(N2TokenType.SLASH) || check(N2TokenType.PERCENT)) {
            N2Token op = advance();
            N2AST.ExprNode right = parseUnary();
            expr = new N2AST.BinaryOpNode(op.text(), expr, right, op.line(), op.column());
        }
        return expr;
    }

    private N2AST.ExprNode parseUnary() {
        if (check(N2TokenType.MINUS) || check(N2TokenType.KEYWORD_NOT) ||
            check(N2TokenType.TILDE) || check(N2TokenType.HASH)) {
            N2Token op = advance();
            N2AST.ExprNode operand = parseUnary();
            return new N2AST.UnaryOpNode(op.text(), operand, op.line(), op.column());
        }
        return parsePostfix();
    }

    private N2AST.ExprNode parsePostfix() {
        N2AST.ExprNode expr = parsePrimary();

        while (true) {
            if (match(N2TokenType.DOT)) {
                N2Token field = consume(N2TokenType.IDENTIFIER, "Expected field or method name after '.'");
                if (match(N2TokenType.LPAREN)) {
                    // Method call: obj.method(args)
                    List<N2AST.ExprNode> args = parseArgList();
                    consume(N2TokenType.RPAREN, "Expected ')' after method arguments");
                    expr = new N2AST.MethodCallNode(expr, field.text(), args, field.line(), field.column());
                } else {
                    // Field access: obj.field
                    expr = new N2AST.FieldAccessNode(expr, field.text(), field.line(), field.column());
                }
            } else if (match(N2TokenType.LBRACKET)) {
                // Index access: container[index]
                N2AST.ExprNode index = parseExpression();
                consume(N2TokenType.RBRACKET, "Expected ']' after index");
                expr = new N2AST.IndexAccessNode(expr, index, expr.getLine(), expr.getColumn());
            } else {
                break;
            }
        }

        return expr;
    }

    private N2AST.ExprNode parsePrimary() {
        N2Token token = peek();

        if (match(N2TokenType.NUMBER)) {
            return new N2AST.NumberNode((Integer) token.literal(), token.line(), token.column());
        }
        if (match(N2TokenType.STRING)) {
            return new N2AST.StringNode((String) token.literal(), token.line(), token.column());
        }
        if (match(N2TokenType.KEYWORD_TRUE)) {
            return new N2AST.BooleanNode(true, token.line(), token.column());
        }
        if (match(N2TokenType.KEYWORD_FALSE)) {
            return new N2AST.BooleanNode(false, token.line(), token.column());
        }
        if (match(N2TokenType.KEYWORD_NIL)) {
            return new N2AST.NilNode(token.line(), token.column());
        }
        if (match(N2TokenType.KEYWORD_THIS) || match(N2TokenType.KEYWORD_SELF)) {
            return new N2AST.ThisNode(token.line(), token.column());
        }

        // New Class instantiation: new Player(args)
        if (match(N2TokenType.KEYWORD_NEW)) {
            N2Token clsToken = consume(N2TokenType.IDENTIFIER, "Expected class name after 'new'");
            consume(N2TokenType.LPAREN, "Expected '(' after class name in instantiation");
            List<N2AST.ExprNode> args = parseArgList();
            consume(N2TokenType.RPAREN, "Expected ')' after constructor arguments");
            return new N2AST.NewNode(clsToken.text(), args, clsToken.line(), clsToken.column());
        }

        // List literal: [elem1, elem2, ...]
        if (match(N2TokenType.LBRACKET)) {
            int line = token.line();
            int col = token.column();
            List<N2AST.ExprNode> elems = new ArrayList<>();
            if (!check(N2TokenType.RBRACKET)) {
                do {
                    elems.add(parseExpression());
                } while (match(N2TokenType.COMMA));
            }
            consume(N2TokenType.RBRACKET, "Expected ']' closing list literal");
            return new N2AST.ListNode(elems, line, col);
        }

        // Map literal: { key: value, ... } or { key = value, ... }
        if (match(N2TokenType.LBRACE)) {
            int line = token.line();
            int col = token.column();
            List<N2AST.MapEntry> entries = new ArrayList<>();
            if (!check(N2TokenType.RBRACE)) {
                do {
                    N2AST.ExprNode key;
                    if (check(N2TokenType.IDENTIFIER) && peekNext().type() != N2TokenType.LPAREN && peekNext().type() != N2TokenType.DOT) {
                        N2Token id = advance();
                        key = new N2AST.StringNode(id.text(), id.line(), id.column());
                    } else {
                        key = parseExpression();
                    }

                    if (!match(N2TokenType.COLON) && !match(N2TokenType.ASSIGN)) {
                        throw new N2CompileException("Expected ':' or '=' between key and value in map literal", fileName, peek().line(), peek().column());
                    }
                    N2AST.ExprNode value = parseExpression();
                    entries.add(new N2AST.MapEntry(key, value));
                } while (match(N2TokenType.COMMA));
            }
            consume(N2TokenType.RBRACE, "Expected '}' closing map literal");
            return new N2AST.MapNode(entries, line, col);
        }

        // Identifier, function call, or ClassName(args) instantiation
        if (match(N2TokenType.IDENTIFIER)) {
            String name = token.text();
            if (match(N2TokenType.LPAREN)) {
                List<N2AST.ExprNode> args = parseArgList();
                consume(N2TokenType.RPAREN, "Expected ')' after function arguments");
                if (knownClasses.contains(name)) {
                    return new N2AST.NewNode(name, args, token.line(), token.column());
                }
                return new N2AST.CallNode(name, args, token.line(), token.column());
            }
            return new N2AST.VariableNode(name, token.line(), token.column());
        }

        if (match(N2TokenType.KEYWORD_GLOBAL)) {
            return new N2AST.VariableNode("global", token.line(), token.column());
        }

        // Parenthesized expression
        if (match(N2TokenType.LPAREN)) {
            N2AST.ExprNode inner = parseExpression();
            consume(N2TokenType.RPAREN, "Expected ')' closing parenthesized expression");
            return inner;
        }

        throw new N2CompileException("Unexpected token in expression: " + token.type() + " ('" + token.text() + "')", fileName, token.line(), token.column());
    }

    private List<N2AST.ExprNode> parseArgList() {
        List<N2AST.ExprNode> args = new ArrayList<>();
        if (!check(N2TokenType.RPAREN)) {
            do {
                args.add(parseExpression());
            } while (match(N2TokenType.COMMA));
        }
        return args;
    }

    private boolean isAtEnd() {
        return peek().type() == N2TokenType.EOF;
    }

    private N2Token peek() {
        if (current >= tokens.size()) return tokens.get(tokens.size() - 1);
        return tokens.get(current);
    }

    private N2Token peekNext() {
        if (current + 1 >= tokens.size()) return tokens.get(tokens.size() - 1);
        return tokens.get(current + 1);
    }

    private boolean check(N2TokenType type) {
        if (isAtEnd()) return type == N2TokenType.EOF;
        return peek().type() == type;
    }

    private N2Token advance() {
        if (!isAtEnd()) current++;
        return tokens.get(current - 1);
    }

    private boolean match(N2TokenType type) {
        if (check(type)) {
            advance();
            return true;
        }
        return false;
    }

    private N2Token consume(N2TokenType type, String errorMessage) {
        if (check(type)) return advance();
        throw new N2CompileException(errorMessage + " (got: '" + peek().text() + "')", fileName, peek().line(), peek().column());
    }
}
