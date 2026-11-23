package games.jvrcruz.siliconcraft.compiler.n2;

import games.jvrcruz.siliconcraft.compiler.n2.N2AST.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves file-local module names before the assembly generator sees the linked project. */
final class N2ModuleAliases {
    private enum Kind { VARIABLE, FUNCTION, CLASS, SYSTEM_FUNCTION, CONSTANT }
    private record Export(String symbol, Kind kind, N2Library.SymbolInfo librarySymbol) {
        private Export(String symbol, Kind kind) {
            this(symbol, kind, null);
        }
    }

    private final String file;
    private final Map<String, Export> own;
    private final Map<String, Map<String, Export>> aliases;
    private final Set<String> locals = new HashSet<>();
    private boolean inFunction;

    private N2ModuleAliases(String file, Map<String, Export> own, Map<String, Map<String, Export>> aliases) {
        this.file = file;
        this.own = own;
        this.aliases = aliases;
    }

    static void merge(Map<String, ProgramNode> modules, Map<String, Map<String, String>> bindings,
                      Set<String> unqualified, ProgramNode merged, List<String> errors) {
        boolean hasLibraryAliases = modules.values().stream()
                .anyMatch(program -> program.imports.stream().anyMatch(imp -> imp.isLibrary && imp.alias != null));
        if ((!hasLibraryAliases && bindings.isEmpty()) || !errors.isEmpty()) {
            modules.values().forEach(module -> merged.statements.addAll(module.statements));
            return;
        }
        Map<String, Map<String, Export>> exports = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        modules.forEach((file, program) -> {
            var symbols = exports(program);
            exports.put(file, symbols);
            names.addAll(symbols.keySet());
        });
        int index = 0;
        for (var module : exports.entrySet()) {
            if (unqualified.contains(module.getKey())) continue;
            String prefix;
            do {
                prefix = "_n2module_" + index++ + "_";
            } while (module.getValue().keySet().stream().map((prefix)::concat).anyMatch(names::contains));
            String namespace = prefix;
            module.getValue().replaceAll((name, value) -> new Export(namespace + name, value.kind()));
        }

        for (var module : modules.entrySet()) {
            String file = module.getKey();
            Map<String, Map<String, Export>> aliases = new HashMap<>();
            bindings.getOrDefault(file, Map.of()).forEach((alias, target) -> aliases.put(alias, exports.get(target)));
            var lowerer = new N2ModuleAliases(file, exports.get(file), aliases);
            try {
                for (ImportNode imp : module.getValue().imports) {
                    if (imp.isLibrary && imp.alias != null) lowerer.bindLibrary(imp);
                }
                for (Node node : module.getValue().statements) {
                    merged.statements.add(lowerer.statement(node));
                }
            } catch (N2CompileException error) {
                errors.add(error.getMessage());
            }
        }
    }

    private void bindLibrary(ImportNode imp) {
        if (aliases.containsKey(imp.alias)) {
            throw error("Duplicate module alias '" + imp.alias + "'", imp.getLine(), imp.getColumn());
        }
        var library = N2Library.getLibrary(N2Library.normalizeLibPath(imp.path));
        if (library == null) throw error("Unknown system library: <" + imp.path + ">", imp.getLine(), imp.getColumn());
        Map<String, Export> members = new HashMap<>();
        library.forEach((name, symbol) -> members.put(name, new Export(name,
                symbol.type() == N2Library.SymbolType.CONSTANT ? Kind.CONSTANT : Kind.SYSTEM_FUNCTION, symbol)));
        aliases.put(imp.alias, members);
    }

    private static Map<String, Export> exports(ProgramNode module) {
        Map<String, Export> symbols = new LinkedHashMap<>();
        for (Node node : module.statements) {
            switch (node) {
                case FunctionNode function -> symbols.put(function.name, new Export(function.name, Kind.FUNCTION));
                case ClassNode type -> symbols.put(type.name, new Export(type.name, Kind.CLASS));
                case VarDeclNode variable -> {
                    symbols.put(variable.name, new Export(variable.name, Kind.VARIABLE));
                    for (String name : variable.secondaryNames) symbols.put(name, new Export(name, Kind.VARIABLE));
                }
                case AssignNode assignment -> {
                    collectVariable(assignment.target, symbols);
                    assignment.secondaryTargets.forEach(target -> collectVariable(target, symbols));
                }
                default -> {}
            }
        }
        return symbols;
    }

    private static void collectVariable(AssignTarget target, Map<String, Export> symbols) {
        if (target instanceof VarTarget variable) {
            symbols.putIfAbsent(variable.name, new Export(variable.name, Kind.VARIABLE));
        }
    }

    private String symbol(String name, Kind kind) {
        Export export = own.get(name);
        return export != null && export.kind() == kind ? export.symbol() : name;
    }

    private String variable(String name, int line, int column) {
        if (locals.contains(name)) return name;
        if (aliases.containsKey(name)) {
            throw error("Module alias '" + name + "' requires a member name", line, column);
        }
        return symbol(name, Kind.VARIABLE);
    }

    private Map<String, Export> module(ExprNode object) {
        if (object instanceof VariableNode variable && !locals.contains(variable.name)) {
            return aliases.get(variable.name);
        }
        return null;
    }

    private Export member(Map<String, Export> module, String name, int line, int column) {
        Export export = module.get(name);
        if (export == null) throw error("Module has no export named '" + name + "'", line, column);
        return export;
    }

    private Export field(Map<String, Export> module, String name, int line, int column) {
        Export export = member(module, name, line, column);
        if (export.kind() != Kind.VARIABLE && export.kind() != Kind.CONSTANT) {
            throw error("Module member '" + name + "' must be called", line, column);
        }
        return export;
    }

    private N2CompileException error(String message, int line, int column) {
        return new N2CompileException(message, file, line, column);
    }

    private List<Node> body(List<Node> nodes) {
        return nodes.stream().map(this::statement).toList();
    }

    private List<Node> functionBody(List<String> params, List<Node> nodes) {
        inFunction = true;
        locals.addAll(params);
        try {
            return body(nodes);
        } finally {
            locals.clear();
            inFunction = false;
        }
    }

    private Node statement(Node node) {
        int line = node.getLine();
        int col = node.getColumn();
        return switch (node) {
            case FunctionNode fn -> new FunctionNode(symbol(fn.name, Kind.FUNCTION), fn.params,
                    functionBody(fn.params, fn.body), line, col);
            case ClassNode cls -> {
                var result = new ClassNode(symbol(cls.name, Kind.CLASS), line, col);
                for (MethodNode method : cls.methods) {
                    result.methods.add(new MethodNode(method.name, method.params, functionBody(method.params, method.body),
                            method.getLine(), method.getColumn()));
                }
                yield result;
            }
            case VarDeclNode decl -> {
                if (decl.isLocal && inFunction) {
                    locals.add(decl.name);
                    locals.addAll(decl.secondaryNames);
                }
                yield new VarDeclNode(variable(decl.name, line, col),
                        decl.secondaryNames.stream().map(name -> variable(name, line, col)).toList(),
                        decl.isLocal, expression(decl.initExpr), line, col);
            }
            case AssignNode assignment -> new AssignNode(target(assignment.target),
                    assignment.secondaryTargets.stream().map(this::target).toList(), assignment.op,
                    expression(assignment.value), line, col);
            case ExprStmtNode stmt -> new ExprStmtNode(expression(stmt.expr), line, col);
            case IfNode stmt -> new IfNode(expression(stmt.condition), body(stmt.thenBody),
                    stmt.elseifs.stream().map(branch -> new ElseIfBranch(expression(branch.condition), body(branch.body))).toList(),
                    body(stmt.elseBody), line, col);
            case WhileNode stmt -> new WhileNode(expression(stmt.condition), body(stmt.body), line, col);
            case ForRangeNode stmt -> {
                ExprNode start = expression(stmt.startExpr), end = expression(stmt.endExpr), step = expression(stmt.stepExpr);
                yield new ForRangeNode(variable(stmt.varName, line, col), start, end, step, body(stmt.body), line, col);
            }
            case ReturnNode stmt -> new ReturnNode(expression(stmt.expr), line, col);
            default -> node;
        };
    }

    private AssignTarget target(AssignTarget target) {
        int line = target.getLine();
        int col = target.getColumn();
        return switch (target) {
            case VarTarget var -> new VarTarget(variable(var.name, line, col), line, col);
            case FieldTarget access -> {
                var module = module(access.object);
                if (module == null) yield new FieldTarget(expression(access.object), access.field, line, col);
                var export = field(module, access.field, line, col);
                if (export.kind() == Kind.CONSTANT) throw error("Cannot assign to library constant '" + access.field + "'", line, col);
                yield new VarTarget(export.symbol(), line, col);
            }
            case IndexTarget access -> new IndexTarget(expression(access.container), expression(access.index), line, col);
            default -> target;
        };
    }

    private List<ExprNode> arguments(List<ExprNode> args) {
        return args.stream().map(this::expression).toList();
    }

    private ExprNode expression(ExprNode expr) {
        if (expr == null) return null;
        int line = expr.getLine();
        int col = expr.getColumn();
        return switch (expr) {
            case VariableNode var -> new VariableNode(variable(var.name, line, col), line, col);
            case UnaryOpNode unary -> new UnaryOpNode(unary.op, expression(unary.operand), line, col);
            case BinaryOpNode binary -> new BinaryOpNode(binary.op, expression(binary.left), expression(binary.right), line, col);
            case CallNode call -> {
                if (aliases.containsKey(call.callee) && !locals.contains(call.callee)) {
                    throw error("Module alias '" + call.callee + "' requires a member name", line, col);
                }
                String name = symbol(call.callee, Kind.FUNCTION);
                if (name.equals(call.callee)) name = symbol(name, Kind.CLASS);
                yield new CallNode(name, arguments(call.args), line, col);
            }
            case MethodCallNode call -> {
                var module = module(call.object);
                if (module == null) yield new MethodCallNode(expression(call.object), call.methodName, arguments(call.args), line, col);
                var export = member(module, call.methodName, line, col);
                yield switch (export.kind()) {
                    case FUNCTION -> new CallNode(export.symbol(), arguments(call.args), line, col);
                    case CLASS -> new NewNode(export.symbol(), arguments(call.args), line, col);
                    case SYSTEM_FUNCTION -> new SystemCallNode(export.librarySymbol(), arguments(call.args), line, col);
                    case VARIABLE, CONSTANT -> throw error("Module variable '" + call.methodName + "' is not callable", line, col);
                };
            }
            case NewNode create -> {
                String name = create.className;
                int separator = name.indexOf('.');
                if (separator >= 0) {
                    var module = aliases.get(name.substring(0, separator));
                    if (module == null) throw error("Unknown module alias '" + name.substring(0, separator) + "'", line, col);
                    var export = member(module, name.substring(separator + 1), line, col);
                    if (export.kind() != Kind.CLASS) throw error("'" + name + "' is not a class", line, col);
                    name = export.symbol();
                } else {
                    name = symbol(name, Kind.CLASS);
                }
                yield new NewNode(name, arguments(create.args), line, col);
            }
            case FieldAccessNode access -> {
                var module = module(access.object);
                if (module == null) yield new FieldAccessNode(expression(access.object), access.fieldName, line, col);
                var export = field(module, access.fieldName, line, col);
                yield export.kind() == Kind.CONSTANT ? new NumberNode(export.librarySymbol().intValue(), line, col)
                        : new VariableNode(export.symbol(), line, col);
            }
            case IndexAccessNode access -> new IndexAccessNode(expression(access.container), expression(access.index), line, col);
            case ListNode list -> new ListNode(arguments(list.elements), line, col);
            case MapNode map -> new MapNode(map.entries.stream().map(e -> new MapEntry(expression(e.key), expression(e.value))).toList(), line, col);
            default -> expr;
        };
    }
}
