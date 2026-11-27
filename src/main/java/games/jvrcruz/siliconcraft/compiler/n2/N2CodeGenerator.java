package games.jvrcruz.siliconcraft.compiler.n2;

import games.jvrcruz.siliconcraft.compiler.InstructionValidator;

import java.util.*;

public class N2CodeGenerator {
    private final N2AST.ProgramNode program;
    private final String sourceFileName;

    private final List<String> lines = new ArrayList<>();
    private final List<String> dataLines = new ArrayList<>();
    private final Set<String> requiredInstructions = new LinkedHashSet<>();
    private final List<String> errors = new ArrayList<>();

    private int labelCounter = 0;
    private int stringCounter = 0;
    private boolean needsGpuTriScratch = false;
    private final Map<String, String> stringPool = new HashMap<>();

    // Scope and symbol resolution
    private final Set<String> globalVars = new HashSet<>();
    private final Set<String> currentLocals = new HashSet<>();
    private String currentFunction = null;
    private String currentClass = null;

    // Class metadata: ClassName -> (FieldName -> Offset)
    private final Map<String, Map<String, Integer>> classFieldOffsets = new HashMap<>();
    private final Map<String, Integer> classIds = new HashMap<>();
    private final Map<String, Set<String>> classMethods = new HashMap<>();
    private final Set<String> stringVars = new HashSet<>();
    private final Set<String> stringFields = new HashSet<>();

    // Imported symbols
    private final Map<String, N2Library.SymbolInfo> importedSymbols = new HashMap<>();

    private final Map<String, Integer> functionArities = new HashMap<>();
    private final Set<String> networkFunctions = new LinkedHashSet<>();
    private final Deque<String> networkLinkQueue = new ArrayDeque<>();
    private boolean linkingNetworkRuntime;

    public N2CodeGenerator(N2AST.ProgramNode program, String sourceFileName) {
        this.program = program;
        this.sourceFileName = sourceFileName != null ? sourceFileName : "main.n2";
    }

    public List<String> generate() {
        processImports();
        analyzeClassesAndFields();
        for (var node : program.statements) {
            if (node instanceof N2AST.FunctionNode function) functionArities.put(function.name, function.params.size());
        }

        // 1. Entry jump to start
        emit("JMP _start", "JMP");

        // 2b. Helper String Pointer Print Subroutine (_print_str)
        emitComment("--- String Pointer Print Subroutine ---");
        emitLabel("_print_str");
        emit("MOV B, A", "MOV");
        emitLabel("_print_str_loop");
        emit("MOV A, [B]", "MOV");
        emit("CMP A, 0", "CMP");
        emit("JZ _print_str_end", "JZ");
        emit("OUTC A", "OUTC");
        emit("INC B", "INC");
        emit("JMP _print_str_loop", "JMP");
        emitLabel("_print_str_end");
        emit("RET", "RET");

        // 3. Generate Classes and Methods
        for (N2AST.Node node : program.statements) {
            if (node instanceof N2AST.ClassNode cls) {
                generateClass(cls);
            }
        }

        // 4. Generate Top-level Functions
        for (N2AST.Node node : program.statements) {
            if (node instanceof N2AST.FunctionNode func) {
                generateFunction(func);
            }
        }

        // 5. Main Execution Entry Point (_start)
        emitComment("--- Execution Entry Point ---");
        emitLabel("_start");
        int heapInitialization = lines.size();
        emit("MOV [_this_ptr], 0", "MOV");

        int stringInitialization = lines.size();

        // Generate top-level statements
        for (N2AST.Node node : program.statements) {
            if (!(node instanceof N2AST.ClassNode) && !(node instanceof N2AST.FunctionNode)) {
                generateStatement(node);
            }
        }

        // If a 'main' function was defined, call it
        boolean hasMain = false;
        for (N2AST.Node node : program.statements) {
            if (node instanceof N2AST.FunctionNode fn && fn.name.equals("main")) {
                hasMain = true;
                break;
            }
        }
        if (hasMain) {
            emit("CALL fn_main", "CALL");
        }

        emit("HLT", "HLT");

        linkNetworkRuntime();
        initializeStrings(stringInitialization);
        if (lines.contains("CALL _alloc")) {
            int heapBase = Math.max(16384, 6 + globalVars.size() + dataLines.size());
            lines.add(heapInitialization, "GALLOC 0, " + heapBase);
            emitLabel("_alloc");
            emit("GALLOC A, " + heapBase, "GALLOC");
            emit("RET", "RET");
        }

        // 6. Data Section (Globals, Constants and Strings)
        emitComment("--- Data Section ---");
        emit("_this_ptr: DB 0");
        emit("_tmp_a: DB 0");
        emit("_tmp_b: DB 0");
        emit("_tmp_c: DB 0");
        emit("_tmp_d: DB 0");
        emit("_pcall_val: DB 0");
        if (needsGpuTriScratch) {
            for (int g = 0; g < 7; g++) {
                emit("_tmp_g" + g + ": DB 0");
            }
        }

        for (String g : globalVars) {
            emit("g_" + g + ": DB 0");
        }

        for (String d : dataLines) {
            emit(d);
        }

        return lines;
    }

    private void initializeStrings(int index) {
        if (stringPool.isEmpty()) return;
        var init = new ArrayList<String>();
        for (var entry : stringPool.entrySet()) {
            String text = entry.getKey();
            init.add("MOV A, " + (text.length() + 1));
            init.add("CALL _alloc");
            init.add("MOV [" + entry.getValue() + "], A");
            init.add("MOV B, A");
            for (int i = 0; i < text.length(); i++) {
                init.add("MOV [B], " + (int) text.charAt(i));
                init.add("INC B");
            }
            init.add("MOV [B], 0");
        }
        lines.addAll(index, init);
        requiredInstructions.addAll(List.of("MOV", "INC", "CALL"));
    }

    private void queueNetworkFunction(String name) {
        if (networkFunctions.add(name)) networkLinkQueue.addLast(name);
    }

    private void linkNetworkRuntime() {
        if (networkLinkQueue.isEmpty()) return;
        Map<String, N2AST.FunctionNode> functions;
        try {
            functions = N2NetworkRuntime.load();
        } catch (java.io.IOException error) {
            errors.add(error.getMessage());
            return;
        }
        for (String function : functions.keySet()) {
            if (functionArities.containsKey(function)) {
                errors.add("Function name '" + function + "' is reserved by the Network library");
                return;
            }
        }
        var userImports = new HashMap<>(importedSymbols);
        importedSymbols.putAll(N2Library.getLibrary("network"));
        importedSymbols.putAll(N2Library.getLibrary("std"));
        linkingNetworkRuntime = true;
        try {
            while (!networkLinkQueue.isEmpty()) {
                String name = networkLinkQueue.removeFirst();
                var function = functions.get(name);
                if (function == null) {
                    errors.add("Missing Network library function '" + name + "'");
                    continue;
                }
                generateFunction(function);
            }
        } finally {
            linkingNetworkRuntime = false;
            importedSymbols.clear();
            importedSymbols.putAll(userImports);
        }
    }

    public Set<String> getRequiredInstructions() {
        return requiredInstructions;
    }

    public List<String> getErrors() {
        return errors;
    }

    private void processImports() {
        for (N2AST.ImportNode imp : program.imports) {
            if (imp.isLibrary) {
                String libName = N2Library.normalizeLibPath(imp.path);
                Map<String, N2Library.SymbolInfo> lib = N2Library.getLibrary(libName);
                if (lib == null) {
                    errors.add("Unknown system library: <" + libName + ">. Available: <lib/std>, <lib/io>, <lib/math>, <lib/graphics>, <lib/audio>, <lib/input>, <lib/storage>, <lib/time>");
                    continue;
                }

                if (imp.symbols.isEmpty()) {
                    // Wildcard import: import all symbols from this library
                    importedSymbols.putAll(lib);
                } else {
                    // Specific imports: from <lib/...> import a, b
                    for (String sym : imp.symbols) {
                        if (lib.containsKey(sym)) {
                            importedSymbols.put(sym, lib.get(sym));
                        } else {
                            errors.add("Symbol '" + sym + "' not found in library <" + libName + ">");
                        }
                    }
                }
            }
        }
    }

    private void analyzeClassesAndFields() {
        int nextClassId = 1;
        for (N2AST.Node node : program.statements) {
            if (node instanceof N2AST.ClassNode cls) {
                classIds.put(cls.name, nextClassId++);
                Map<String, Integer> fieldMap = new LinkedHashMap<>();
                int offset = 1; // 0 is reserved for ClassID

                // Inspect methods for this.field assignments
                for (N2AST.MethodNode method : cls.methods) {
                    classMethods.computeIfAbsent(cls.name, k -> new HashSet<>()).add(method.name);
                    findThisFieldsInStatements(method.body, fieldMap);
                }
                for (String fieldName : fieldMap.keySet()) {
                    fieldMap.put(fieldName, offset++);
                }
                classFieldOffsets.put(cls.name, fieldMap);
            }
        }
        findStringVarsAndFields(program.statements);
        preScanStrings(program.statements);
    }

    private void preScanStrings(List<N2AST.Node> stmts) {
        for (N2AST.Node stmt : stmts) {
            scanStringsInNode(stmt);
        }
    }

    private void scanStringsInNode(N2AST.Node node) {
        if (node == null) return;
        if (node instanceof N2AST.ClassNode cn) {
            for (N2AST.MethodNode mn : cn.methods) {
                preScanStrings(mn.body);
            }
        } else if (node instanceof N2AST.FunctionNode fn) {
            preScanStrings(fn.body);
        } else if (node instanceof N2AST.VarDeclNode vd) {
            scanStringsInExpr(vd.initExpr);
        } else if (node instanceof N2AST.AssignNode an) {
            scanStringsInExpr(an.value);
        } else if (node instanceof N2AST.ExprStmtNode es) {
            scanStringsInExpr(es.expr);
        } else if (node instanceof N2AST.IfNode ifn) {
            scanStringsInExpr(ifn.condition);
            preScanStrings(ifn.thenBody);
            for (N2AST.ElseIfBranch elif : ifn.elseifs) {
                scanStringsInExpr(elif.condition);
                preScanStrings(elif.body);
            }
            preScanStrings(ifn.elseBody);
        } else if (node instanceof N2AST.WhileNode wn) {
            scanStringsInExpr(wn.condition);
            preScanStrings(wn.body);
        } else if (node instanceof N2AST.ForRangeNode fn) {
            scanStringsInExpr(fn.startExpr);
            scanStringsInExpr(fn.endExpr);
            if (fn.stepExpr != null) scanStringsInExpr(fn.stepExpr);
            preScanStrings(fn.body);
        } else if (node instanceof N2AST.ReturnNode rn) {
            scanStringsInExpr(rn.expr);
        }
    }

    private void scanStringsInExpr(N2AST.ExprNode expr) {
        if (expr == null) return;
        if (expr instanceof N2AST.StringNode str) {
            getStringLiteralLabel(str.value);
        } else if (expr instanceof N2AST.BinaryOpNode bin) {
            scanStringsInExpr(bin.left);
            scanStringsInExpr(bin.right);
        } else if (expr instanceof N2AST.UnaryOpNode un) {
            scanStringsInExpr(un.operand);
        } else if (expr instanceof N2AST.CallNode call) {
            for (N2AST.ExprNode a : call.args) scanStringsInExpr(a);
        } else if (expr instanceof N2AST.MethodCallNode mc) {
            scanStringsInExpr(mc.object);
            for (N2AST.ExprNode a : mc.args) scanStringsInExpr(a);
        } else if (expr instanceof N2AST.NewNode nw) {
            for (N2AST.ExprNode a : nw.args) scanStringsInExpr(a);
        } else if (expr instanceof N2AST.ListNode ln) {
            for (N2AST.ExprNode el : ln.elements) scanStringsInExpr(el);
        } else if (expr instanceof N2AST.MapNode mn) {
            for (N2AST.MapEntry me : mn.entries) {
                scanStringsInExpr(me.key);
                scanStringsInExpr(me.value);
            }
        } else if (expr instanceof N2AST.FieldAccessNode fa) {
            scanStringsInExpr(fa.object);
        } else if (expr instanceof N2AST.IndexAccessNode ia) {
            scanStringsInExpr(ia.container);
            scanStringsInExpr(ia.index);
        }
    }

    private void findStringVarsAndFields(List<N2AST.Node> stmts) {
        for (N2AST.Node stmt : stmts) {
            if (stmt instanceof N2AST.VarDeclNode vd && isTextExpression(vd.initExpr)) {
                stringVars.add(vd.name);
            } else if (stmt instanceof N2AST.AssignNode an) {
                if (an.target instanceof N2AST.VarTarget vt && isTextExpression(an.value)) {
                    stringVars.add(vt.name);
                } else if (an.target instanceof N2AST.FieldTarget ft) {
                    if (an.value instanceof N2AST.StringNode || (an.value instanceof N2AST.VariableNode vn && (vn.name.contains("name") || vn.name.contains("str") || stringVars.contains(vn.name)))) {
                        stringFields.add(ft.field);
                    }
                }
            } else if (stmt instanceof N2AST.ClassNode cn) {
                for (N2AST.MethodNode mn : cn.methods) {
                    findStringVarsAndFields(mn.body);
                }
            } else if (stmt instanceof N2AST.FunctionNode fn) {
                findStringVarsAndFields(fn.body);
            } else if (stmt instanceof N2AST.IfNode ifn) {
                findStringVarsAndFields(ifn.thenBody);
                findStringVarsAndFields(ifn.elseBody);
            } else if (stmt instanceof N2AST.WhileNode wn) {
                findStringVarsAndFields(wn.body);
            }
        }
    }

    private void findThisFieldsInStatements(List<N2AST.Node> stmts, Map<String, Integer> fieldMap) {
        for (N2AST.Node stmt : stmts) {
            if (stmt instanceof N2AST.AssignNode assign) {
                if (assign.target instanceof N2AST.FieldTarget ft && ft.object instanceof N2AST.ThisNode) {
                    fieldMap.putIfAbsent(ft.field, 0);
                }
            } else if (stmt instanceof N2AST.IfNode ifn) {
                findThisFieldsInStatements(ifn.thenBody, fieldMap);
                for (N2AST.ElseIfBranch e : ifn.elseifs) findThisFieldsInStatements(e.body, fieldMap);
                findThisFieldsInStatements(ifn.elseBody, fieldMap);
            } else if (stmt instanceof N2AST.WhileNode wn) {
                findThisFieldsInStatements(wn.body, fieldMap);
            } else if (stmt instanceof N2AST.ForRangeNode fn) {
                findThisFieldsInStatements(fn.body, fieldMap);
            }
        }
    }

    private void generateClass(N2AST.ClassNode cls) {
        currentClass = cls.name;
        emitComment("=== Class: " + cls.name + " ===");

        for (N2AST.MethodNode method : cls.methods) {
            generateMethod(cls.name, method);
        }

        currentClass = null;
    }

    private void generateMethod(String className, N2AST.MethodNode method) {
        String label = "m_" + className + "_" + method.name;
        emitComment("Method: " + className + "." + method.name);
        emitLabel(label);

        currentFunction = label;
        currentLocals.clear();

        // 1. Pop parameters from stack into local slots FIRST
        for (int i = method.params.size() - 1; i >= 0; i--) {
            String param = method.params.get(i);
            String slot = "v_" + label + "_" + param;
            dataLines.add(slot + ": DB 0");
            currentLocals.add(param);
            emit("POP B", "POP");
            emit("MOV [" + slot + "], B", "MOV");
        }

        // 2. Method prologue: save previous this pointer on stack and set this pointer
        emit("PUSH [_this_ptr]", "PUSH");
        emit("MOV [_this_ptr], A", "MOV"); // A was passed as 'this'

        // 3. Method body
        for (N2AST.Node stmt : method.body) {
            generateStatement(stmt);
        }

        // Default epilogue if no explicit return
        emitLabel(label + "_epilogue");
        clearLocalSlots();
        emit("POP [_this_ptr]", "POP");
        emit("RET", "RET");

        currentFunction = null;
        currentLocals.clear();
    }

    private void generateFunction(N2AST.FunctionNode func) {
        String label = "fn_" + func.name;
        emitComment("Function: " + func.name);
        emitLabel(label);

        currentFunction = label;
        currentLocals.clear();

        // Pop parameters from stack into local slots
        for (int i = func.params.size() - 1; i >= 0; i--) {
            String param = func.params.get(i);
            String slot = "v_" + label + "_" + param;
            dataLines.add(slot + ": DB 0");
            currentLocals.add(param);
            emit("POP B", "POP");
            emit("MOV [" + slot + "], B", "MOV");
        }

        // Function body
        for (N2AST.Node stmt : func.body) {
            generateStatement(stmt);
        }

        // Epilogue
        emitLabel(label + "_epilogue");
        clearLocalSlots();
        emit("RET", "RET");

        currentFunction = null;
        currentLocals.clear();
    }

    private void clearLocalSlots() {
        for (String name : currentLocals) {
            emit("MOV [v_" + currentFunction + "_" + name + "], 0", "MOV");
        }
    }

    private void generateStatement(N2AST.Node stmt) {
        if (stmt instanceof N2AST.VarDeclNode decl) {
            if (decl.isLocal && currentFunction != null) {
                currentLocals.add(decl.name);
                String slot = "v_" + currentFunction + "_" + decl.name;
                dataLines.add(slot + ": DB 0");
                for (String sec : decl.secondaryNames) {
                    currentLocals.add(sec);
                    dataLines.add("v_" + currentFunction + "_" + sec + ": DB 0");
                }
                if (decl.initExpr != null) {
                    generateExpression(decl.initExpr);
                    emit("MOV [" + slot + "], A", "MOV");
                    if (!decl.secondaryNames.isEmpty()) {
                        String secSlot = "v_" + currentFunction + "_" + decl.secondaryNames.get(0);
                        emit("MOV [" + secSlot + "], [_pcall_val]", "MOV");
                    }
                }
            } else {
                globalVars.add(decl.name);
                for (String sec : decl.secondaryNames) {
                    globalVars.add(sec);
                }
                if (decl.initExpr != null) {
                    generateExpression(decl.initExpr);
                    emit("MOV [g_" + decl.name + "], A", "MOV");
                    if (!decl.secondaryNames.isEmpty()) {
                        String secSlot = "g_" + decl.secondaryNames.get(0);
                        emit("MOV [" + secSlot + "], [_pcall_val]", "MOV");
                    }
                }
            }
        } else if (stmt instanceof N2AST.AssignNode assign) {
            generateAssign(assign);
        } else if (stmt instanceof N2AST.ExprStmtNode exprStmt) {
            generateExpression(exprStmt.expr);
        } else if (stmt instanceof N2AST.IfNode ifn) {
            generateIf(ifn);
        } else if (stmt instanceof N2AST.WhileNode wn) {
            generateWhile(wn);
        } else if (stmt instanceof N2AST.ForRangeNode fn) {
            generateFor(fn);
        } else if (stmt instanceof N2AST.ReturnNode ret) {
            if (ret.expr != null) {
                generateExpression(ret.expr);
            }
            if (currentFunction != null) {
                emit("JMP " + currentFunction + "_epilogue", "JMP");
            } else {
                emit("RET", "RET");
            }
        } else if (stmt instanceof N2AST.BreakNode) {
            // Handled via loop exit labels
        } else if (stmt instanceof N2AST.InlineAssemblyNode asmNode) {
            for (String asmLine : asmNode.lines) {
                emit(asmLine);
                String stripped = asmLine.trim();
                int space = stripped.indexOf(' ');
                String opcode = (space == -1 ? stripped : stripped.substring(0, space)).toUpperCase(Locale.ROOT);
                if (InstructionValidator.ALL_INSTRUCTIONS_SET.contains(opcode)) {
                    requiredInstructions.add(opcode);
                }
            }
        }
    }

    private void generateAssign(N2AST.AssignNode assign) {
        if (assign.target instanceof N2AST.VarTarget vt) {
            String slot = resolveVarSlot(vt.name);
            generateExpression(assign.value);

            if (!assign.op.equals("=")) {
                emit("MOV B, [" + slot + "]", "MOV");
                switch (assign.op) {
                    case "+=" -> { emit("ADD B, A", "ADD"); emit("MOV A, B", "MOV"); }
                    case "-=" -> { emit("SUB B, A", "SUB"); emit("MOV A, B", "MOV"); }
                    case "*=" -> { emit("MUL B, A", "MUL"); emit("MOV A, B", "MOV"); }
                    case "/=" -> { emit("DIV B, A", "DIV"); emit("MOV A, B", "MOV"); }
                }
            }
            emit("MOV [" + slot + "], A", "MOV");
            if (!assign.secondaryTargets.isEmpty() && assign.secondaryTargets.get(0) instanceof N2AST.VarTarget secVt) {
                String secSlot = resolveVarSlot(secVt.name);
                emit("MOV [" + secSlot + "], [_pcall_val]", "MOV");
            }
        } else if (assign.target instanceof N2AST.FieldTarget ft) {
            // Field write: obj.field = val or this.field = val
            generateExpression(assign.value);
            emit("PUSH A", "PUSH"); // Save value

            if (ft.object instanceof N2AST.ThisNode) {
                emit("MOV B, [_this_ptr]", "MOV");
            } else {
                generateExpression(ft.object);
                emit("MOV B, A", "MOV");
            }

            int offset = resolveFieldOffset(ft.field);
            emit("ADD B, " + offset, "ADD");
            emit("POP A", "POP");
            emit("MOV [B], A", "MOV");
        } else if (assign.target instanceof N2AST.IndexTarget it) {
            // Index write: container[idx] = val
            generateExpression(assign.value);
            emit("PUSH A", "PUSH"); // Save value

            generateExpression(it.container);
            emit("PUSH A", "PUSH"); // Save container ptr

            generateExpression(it.index);
            emit("MOV C, A", "MOV"); // Index in C
            emit("POP B", "POP");    // Container ptr in B
            emit("ADD B, 2", "ADD"); // List elements start at offset 2
            emit("ADD B, C", "ADD");
            emit("POP A", "POP");    // Value in A
            emit("MOV [B], A", "MOV");
        }
    }

    private void generateIf(N2AST.IfNode ifn) {
        String elseLabel = "_else_" + (++labelCounter);
        String endLabel = "_endif_" + labelCounter;

        generateConditionJump(ifn.condition, elseLabel, false);

        for (N2AST.Node s : ifn.thenBody) {
            generateStatement(s);
        }
        emit("JMP " + endLabel, "JMP");

        emitLabel(elseLabel);
        for (N2AST.ElseIfBranch elif : ifn.elseifs) {
            String nextElif = "_elif_" + (++labelCounter);
            generateConditionJump(elif.condition, nextElif, false);
            for (N2AST.Node s : elif.body) generateStatement(s);
            emit("JMP " + endLabel, "JMP");
            emitLabel(nextElif);
        }

        for (N2AST.Node s : ifn.elseBody) {
            generateStatement(s);
        }

        emitLabel(endLabel);
    }

    private void generateWhile(N2AST.WhileNode wn) {
        String startLabel = "_while_start_" + (++labelCounter);
        String endLabel = "_while_end_" + labelCounter;

        emitLabel(startLabel);
        generateConditionJump(wn.condition, endLabel, false);

        for (N2AST.Node s : wn.body) {
            if (s instanceof N2AST.BreakNode) {
                emit("JMP " + endLabel, "JMP");
            } else if (s instanceof N2AST.ContinueNode) {
                emit("JMP " + startLabel, "JMP");
            } else {
                generateStatement(s);
            }
        }

        emit("JMP " + startLabel, "JMP");
        emitLabel(endLabel);
    }

    private void generateFor(N2AST.ForRangeNode fn) {
        String startLabel = "_for_start_" + (++labelCounter);
        String endLabel = "_for_end_" + labelCounter;

        String varSlot = resolveVarSlot(fn.varName);

        // Init start value
        generateExpression(fn.startExpr);
        emit("MOV [" + varSlot + "], A", "MOV");

        emitLabel(startLabel);

        // Check condition: var <= end
        emit("MOV A, [" + varSlot + "]", "MOV");
        emit("PUSH A", "PUSH");
        generateExpression(fn.endExpr);
        emit("MOV B, A", "MOV");
        emit("POP A", "POP");
        emit("CMP A, B", "CMP");
        emit("JG " + endLabel, "JG");

        for (N2AST.Node s : fn.body) {
            if (s instanceof N2AST.BreakNode) {
                emit("JMP " + endLabel, "JMP");
            } else if (s instanceof N2AST.ContinueNode) {
                // Continue steps to increment
            } else {
                generateStatement(s);
            }
        }

        // Step increment
        if (fn.stepExpr != null) {
            generateExpression(fn.stepExpr);
            emit("ADD [" + varSlot + "], A", "ADD");
        } else {
            emit("INC [" + varSlot + "]", "INC");
        }

        emit("JMP " + startLabel, "JMP");
        emitLabel(endLabel);
    }

    private void generateConditionJump(N2AST.ExprNode cond, String targetLabel, boolean jumpIfTrue) {
        if (cond instanceof N2AST.BinaryOpNode bin) {
            String op = bin.op;
            if (op.equals("==") || op.equals("!=") || op.equals("<") ||
                op.equals("<=") || op.equals(">") || op.equals(">=")) {
                generateExpression(bin.left);
                emit("PUSH A", "PUSH");
                generateExpression(bin.right);
                emit("MOV B, A", "MOV");
                emit("POP A", "POP");
                emit("CMP A, B", "CMP");

                if (jumpIfTrue) {
                    switch (op) {
                        case "==" -> emit("JZ " + targetLabel, "JZ");
                        case "!=" -> emit("JNZ " + targetLabel, "JNZ");
                        case "<"  -> emit("JL " + targetLabel, "JL");
                        case "<=" -> emit("JLE " + targetLabel, "JLE");
                        case ">"  -> emit("JG " + targetLabel, "JG");
                        case ">=" -> emit("JGE " + targetLabel, "JGE");
                    }
                } else {
                    switch (op) {
                        case "==" -> emit("JNZ " + targetLabel, "JNZ");
                        case "!=" -> emit("JZ " + targetLabel, "JZ");
                        case "<"  -> emit("JGE " + targetLabel, "JGE");
                        case "<=" -> emit("JG " + targetLabel, "JG");
                        case ">"  -> emit("JLE " + targetLabel, "JLE");
                        case ">=" -> emit("JL " + targetLabel, "JL");
                    }
                }
                return;
            }
        }

        // Generic condition evaluation (truthy: non-zero)
        generateExpression(cond);
        emit("CMP A, 0", "CMP");
        if (jumpIfTrue) {
            emit("JNZ " + targetLabel, "JNZ");
        } else {
            emit("JZ " + targetLabel, "JZ");
        }
    }

    // --- Expression Generation (Always leaves result in register A) ---

    private void generateExpression(N2AST.ExprNode expr) {
        if (expr instanceof N2AST.NumberNode num) {
            emit("MOV A, " + num.value, "MOV");
        } else if (expr instanceof N2AST.BooleanNode bool) {
            emit("MOV A, " + (bool.value ? 1 : 0), "MOV");
        } else if (expr instanceof N2AST.NilNode) {
            emit("MOV A, 0", "MOV");
        } else if (expr instanceof N2AST.StringNode str) {
            String label = getStringLiteralLabel(str.value);
            emit("MOV A, [" + label + "]", "MOV");
        } else if (expr instanceof N2AST.ThisNode) {
            emit("MOV A, [_this_ptr]", "MOV");
        } else if (expr instanceof N2AST.VariableNode var) {
            generateVariableRead(var.name, var.getLine(), var.getColumn());
        } else if (expr instanceof N2AST.BinaryOpNode bin) {
            generateBinaryOp(bin);
        } else if (expr instanceof N2AST.UnaryOpNode un) {
            generateUnaryOp(un);
        } else if (expr instanceof N2AST.CallNode call) {
            generateCall(call);
        } else if (expr instanceof N2AST.MethodCallNode mc) {
            generateMethodCall(mc);
        } else if (expr instanceof N2AST.FieldAccessNode fa) {
            generateFieldAccess(fa);
        } else if (expr instanceof N2AST.IndexAccessNode idx) {
            generateIndexAccess(idx);
        } else if (expr instanceof N2AST.NewNode nw) {
            generateNew(nw);
        } else if (expr instanceof N2AST.ListNode list) {
            generateListLiteral(list);
        } else if (expr instanceof N2AST.MapNode map) {
            generateMapLiteral(map);
        }
    }

    private void generateVariableRead(String name, int line, int col) {
        if (importedSymbols.containsKey(name)) {
            N2Library.SymbolInfo sym = importedSymbols.get(name);
            if (sym.type() == N2Library.SymbolType.CONSTANT) {
                emit("MOV A, " + sym.intValue(), "MOV");
                return;
            }
        }

        // Check if user forgot to import a library symbol
        String suggestedLib = N2Library.getSuggestedLibraryForSymbol(name);
        if (suggestedLib != null && !importedSymbols.containsKey(name) && !currentLocals.contains(name) && !globalVars.contains(name)) {
            errors.add("Line " + line + ":" + col + ": Undefined symbol '" + name + "'. Did you forget to 'import <" + suggestedLib + ">'?");
        }

        String slot = resolveVarSlot(name);
        emit("MOV A, [" + slot + "]", "MOV");
    }

    private void generateBinaryOp(N2AST.BinaryOpNode bin) {
        generateExpression(bin.left);
        emit("PUSH A", "PUSH");
        generateExpression(bin.right);
        emit("MOV B, A", "MOV");
        emit("POP A", "POP");

        switch (bin.op) {
            case "+" -> emit("ADD A, B", "ADD");
            case "-" -> emit("SUB A, B", "SUB");
            case "*" -> emit("MUL A, B", "MUL");
            case "/" -> emit("DIV A, B", "DIV");
            case "%" -> emit("MOD A, B", "MOD");
            case "&" -> emit("AND A, B", "AND");
            case "|" -> emit("OR A, B", "OR");
            case "^" -> emit("XOR A, B", "XOR");
            case "<<" -> emit("SHL A, B", "SHL");
            case ">>" -> emit("SHR A, B", "SHR");
            case "==" -> {
                String trueLbl = "_cmp_true_" + (++labelCounter);
                String endLbl = "_cmp_end_" + labelCounter;
                emit("CMP A, B", "CMP");
                emit("JZ " + trueLbl, "JZ");
                emit("MOV A, 0", "MOV");
                emit("JMP " + endLbl, "JMP");
                emitLabel(trueLbl);
                emit("MOV A, 1", "MOV");
                emitLabel(endLbl);
            }
            case "!=", "~=" -> {
                String trueLbl = "_cmp_true_" + (++labelCounter);
                String endLbl = "_cmp_end_" + labelCounter;
                emit("CMP A, B", "CMP");
                emit("JNZ " + trueLbl, "JNZ");
                emit("MOV A, 0", "MOV");
                emit("JMP " + endLbl, "JMP");
                emitLabel(trueLbl);
                emit("MOV A, 1", "MOV");
                emitLabel(endLbl);
            }
            case "<" -> {
                String trueLbl = "_cmp_true_" + (++labelCounter);
                String endLbl = "_cmp_end_" + labelCounter;
                emit("CMP A, B", "CMP");
                emit("JL " + trueLbl, "JL");
                emit("MOV A, 0", "MOV");
                emit("JMP " + endLbl, "JMP");
                emitLabel(trueLbl);
                emit("MOV A, 1", "MOV");
                emitLabel(endLbl);
            }
            case "<=" -> {
                String trueLbl = "_cmp_true_" + (++labelCounter);
                String endLbl = "_cmp_end_" + labelCounter;
                emit("CMP A, B", "CMP");
                emit("JLE " + trueLbl, "JLE");
                emit("MOV A, 0", "MOV");
                emit("JMP " + endLbl, "JMP");
                emitLabel(trueLbl);
                emit("MOV A, 1", "MOV");
                emitLabel(endLbl);
            }
            case ">" -> {
                String trueLbl = "_cmp_true_" + (++labelCounter);
                String endLbl = "_cmp_end_" + labelCounter;
                emit("CMP A, B", "CMP");
                emit("JG " + trueLbl, "JG");
                emit("MOV A, 0", "MOV");
                emit("JMP " + endLbl, "JMP");
                emitLabel(trueLbl);
                emit("MOV A, 1", "MOV");
                emitLabel(endLbl);
            }
            case ">=" -> {
                String trueLbl = "_cmp_true_" + (++labelCounter);
                String endLbl = "_cmp_end_" + labelCounter;
                emit("CMP A, B", "CMP");
                emit("JGE " + trueLbl, "JGE");
                emit("MOV A, 0", "MOV");
                emit("JMP " + endLbl, "JMP");
                emitLabel(trueLbl);
                emit("MOV A, 1", "MOV");
                emitLabel(endLbl);
            }
            case "and" -> emit("AND A, B", "AND");
            case "or"  -> emit("OR A, B", "OR");
        }
    }

    private void generateUnaryOp(N2AST.UnaryOpNode un) {
        generateExpression(un.operand);
        switch (un.op) {
            case "-" -> {
                emit("MOV B, A", "MOV");
                emit("MOV A, 0", "MOV");
                emit("SUB A, B", "SUB");
            }
            case "not" -> {
                String trueLbl = "_not_true_" + (++labelCounter);
                String endLbl = "_not_end_" + labelCounter;
                emit("CMP A, 0", "CMP");
                emit("JZ " + trueLbl, "JZ");
                emit("MOV A, 0", "MOV");
                emit("JMP " + endLbl, "JMP");
                emitLabel(trueLbl);
                emit("MOV A, 1", "MOV");
                emitLabel(endLbl);
            }
            case "~" -> emit("NOT A", "NOT");
            case "#" -> {
                // Length operator: [A + 0] is length
                emit("MOV A, [A]", "MOV");
            }
        }
    }

    private void generateCall(N2AST.CallNode call) {
        String name = call.callee;
        if (call instanceof N2AST.SystemCallNode system) {
            generateSystemLibCall(system.symbol, call.args, call.getLine(), call.getColumn());
            return;
        }
        if (linkingNetworkRuntime) {
            if (name.startsWith(N2NetworkRuntime.PREFIX)) {
                generateNetworkRuntimeCall(call);
                return;
            }
            if (importedSymbols.containsKey(name)) {
                generateSystemLibCall(importedSymbols.get(name), call.args, call.getLine(), call.getColumn());
                return;
            }
        }

        // Check if name is a known class (constructor call: ClassName(args))
        if (classFieldOffsets.containsKey(name)) {
            generateNew(new N2AST.NewNode(name, call.args, call.getLine(), call.getColumn()));
            return;
        }

        // Check if calling an imported system library function
        if (importedSymbols.containsKey(name)) {
            generateSystemLibCall(importedSymbols.get(name), call.args, call.getLine(), call.getColumn());
            return;
        }

        // Check if user forgot to import standard library function
        String suggestedLib = N2Library.getSuggestedLibraryForSymbol(name);
        if (suggestedLib != null) {
            errors.add("Line " + call.getLine() + ":" + call.getColumn() + ": Function '" + name + "' requires 'import <" + suggestedLib + ">'");
            return;
        }

        // User-defined function call: evaluate arguments and push to stack
        for (N2AST.ExprNode arg : call.args) {
            generateExpression(arg);
            emit("PUSH A", "PUSH");
        }

        emit("CALL fn_" + name, "CALL");
    }

    private void generateNetworkRuntimeCall(N2AST.CallNode call) {
        switch (call.callee) {
            case "__net_alloc" -> {
                generateExpression(call.args.getFirst());
                emit("CALL _alloc", "CALL");
            }
            case "__net_peek" -> {
                generateExpression(call.args.getFirst());
                emit("MOV A, [A]", "MOV");
            }
            case "__net_invoke" -> {
                for (var arg : call.args.subList(1, call.args.size())) {
                    generateExpression(arg);
                    emit("PUSH A", "PUSH");
                }
                generateExpression(call.args.getFirst());
                emit("CALL A", "CALL");
            }
            default -> {
                for (var arg : call.args) {
                    generateExpression(arg);
                    emit("PUSH A", "PUSH");
                }
                queueNetworkFunction(call.callee);
                emit("CALL fn_" + call.callee, "CALL");
            }
        }
    }

    private void generateHttpHelper(String name, N2NetworkRuntime.Api api, List<N2AST.ExprNode> args, int line, int col) {
        String location = "Line " + line + ":" + col + ": ";
        if (args.size() != api.arity()) {
            errors.add(location + name + " expects " + api.arity() + " arguments");
            return;
        }
        if (api.callbackArity() >= 0 && (!(args.getLast() instanceof N2AST.VariableNode callback)
                || !Objects.equals(functionArities.get(callback.name), api.callbackArity()))) {
            errors.add(location + name + " requires a named " + api.callbackArity() + "-parameter callback");
            return;
        }
        for (int i = 0; i < args.size(); i++) {
            if (api.callbackArity() >= 0 && i == args.size() - 1) {
                emit("MOV A, fn_" + ((N2AST.VariableNode) args.get(i)).name, "MOV");
            } else {
                generateExpression(args.get(i));
            }
            emit("PUSH A", "PUSH");
        }
        String target = N2NetworkRuntime.PREFIX + name;
        queueNetworkFunction(target);
        emit("CALL fn_" + target, "CALL");
    }

    private void generateSystemLibCall(N2Library.SymbolInfo symbol, List<N2AST.ExprNode> args, int line, int col) {
        String name = symbol.name();
        if (symbol.library().equals("GameController")
                && (name.equals("gc_axis") || name.contains("stick") || name.contains("trigger"))
                && args.size() != symbol.arity()) {
            errors.add("Line " + line + ":" + col + ": " + name + " expects " + symbol.arity() + " arguments");
            return;
        }
        if (symbol.library().equals("Glass") && switch (name) {
            case "gfx_set_handler", "use_auto_gfx", "use_cpu", "use_gpu", "has_gpu", "gpu_available",
                 "gfx_handler", "is_using_gpu", "gfx_mode", "gpu_vram", "gpu_vram_kb",
                 "gpu_rect", "gpu_line", "draw_circle", "gpu_circle", "fill_circle", "gpu_fill_circle",
                 "fill_triangle", "gpu_triangle", "vram_upload", "gpu_blit", "gpu_swap", "gpu_sync" -> true;
            default -> false;
        } && args.size() != symbol.arity()) {
            errors.add("Line " + line + ":" + col + ": " + name + " expects " + symbol.arity() + " arguments");
            return;
        }
        var httpHelper = N2NetworkRuntime.APIS.get(name);
        if (httpHelper != null) {
            generateHttpHelper(name, httpHelper, args, line, col);
            return;
        }
        switch (name) {
            case "print", "println" -> {
                boolean isPrintln = true;
                if (!args.isEmpty()) {
                    N2AST.ExprNode arg = args.get(0);
                    if (arg instanceof N2AST.StringNode str) {
                        emit("OUT \"" + escapeString(str.value) + "\"", "OUT");
                    } else if (isStringValued(arg)) {
                        generateExpression(arg);
                        emit("CALL _print_str", "CALL");
                        emit("OUTC 10", "OUTC");
                    } else {
                        generateExpression(arg);
                        emit("OUT A", "OUT");
                    }
                } else {
                    emit("OUTC 10", "OUTC");
                }
            }
            case "write" -> {
                if (!args.isEmpty()) {
                    N2AST.ExprNode arg = args.get(0);
                    if (arg instanceof N2AST.StringNode str) {
                        emit("OUT \"" + escapeString(str.value) + "\", 0", "OUT");
                    } else if (isStringValued(arg)) {
                        generateExpression(arg);
                        emit("CALL _print_str", "CALL");
                    } else {
                        generateExpression(arg);
                        emit("OUT A, 0", "OUT");
                    }
                }
            }
            case "cls" -> emit("CLS", "CLS");
            case "time" -> emit("TIME A", "TIME");
            case "sleep" -> {
                if (!args.isEmpty()) {
                    generateExpression(args.get(0));
                    emit("SLEEP A", "SLEEP");
                }
            }
            case "set_timer" -> {
                if (!args.isEmpty()) {
                    generateExpression(args.get(0));
                    emit("TIMER A", "TIMER");
                }
            }
            case "rand" -> {
                if (!args.isEmpty()) {
                    generateExpression(args.get(0));
                    emit("RAND A, A", "RAND");
                }
            }
            case "abs" -> {
                if (!args.isEmpty()) {
                    generateExpression(args.get(0));
                    String posLbl = "_abs_pos_" + (++labelCounter);
                    emit("CMP A, 0", "CMP");
                    emit("JGE " + posLbl, "JGE");
                    emit("MUL A, -1", "MUL");
                    emitLabel(posLbl);
                }
            }
            case "min" -> {
                if (args.size() >= 2) {
                    generateExpression(args.get(0));
                    emit("PUSH A", "PUSH");
                    generateExpression(args.get(1));
                    emit("MOV B, A", "MOV");
                    emit("POP A", "POP");
                    String doneLbl = "_min_done_" + (++labelCounter);
                    emit("CMP A, B", "CMP");
                    emit("JLE " + doneLbl, "JLE");
                    emit("MOV A, B", "MOV");
                    emitLabel(doneLbl);
                }
            }
            case "max" -> {
                if (args.size() >= 2) {
                    generateExpression(args.get(0));
                    emit("PUSH A", "PUSH");
                    generateExpression(args.get(1));
                    emit("MOV B, A", "MOV");
                    emit("POP A", "POP");
                    String doneLbl = "_max_done_" + (++labelCounter);
                    emit("CMP A, B", "CMP");
                    emit("JGE " + doneLbl, "JGE");
                    emit("MOV A, B", "MOV");
                    emitLabel(doneLbl);
                }
            }
            case "clamp" -> {
                if (args.size() >= 3) {
                    generateExpression(args.get(0));
                    emit("PUSH A", "PUSH");
                    generateExpression(args.get(1));
                    emit("PUSH A", "PUSH");
                    generateExpression(args.get(2));
                    emit("MOV C, A", "MOV");
                    emit("POP B", "POP");
                    emit("POP A", "POP");
                    String notBelow = "_clamp_nb_" + (++labelCounter);
                    String clampDone = "_clamp_d_" + labelCounter;
                    emit("CMP A, B", "CMP");
                    emit("JGE " + notBelow, "JGE");
                    emit("MOV A, B", "MOV");
                    emit("JMP " + clampDone, "JMP");
                    emitLabel(notBelow);
                    emit("CMP A, C", "CMP");
                    emit("JLE " + clampDone, "JLE");
                    emit("MOV A, C", "MOV");
                    emitLabel(clampDone);
                }
            }
            case "assert" -> {
                if (!args.isEmpty()) {
                    generateExpression(args.get(0));
                    String passLbl = "_assert_pass_" + (++labelCounter);
                    emit("CMP A, 0", "CMP");
                    emit("JNE " + passLbl, "JNE");
                    emit("OUT \"Assertion failed at line " + line + "\", 1", "OUT");
                    emit("HLT", "HLT");
                    emitLabel(passLbl);
                }
            }
            case "error" -> {
                if (!args.isEmpty()) {
                    generateExpression(args.get(0));
                } else {
                    emit("MOV A, 4", "MOV");
                }
                emit("MOV [_pcall_val], A", "MOV");
                emit("INT 4", "INT");
            }
            case "pcall" -> {
                if (args.isEmpty()) {
                    errors.add("Line " + line + ":" + col + ": pcall requires a target function argument");
                    break;
                }
                N2AST.ExprNode fnArg = args.get(0);
                String targetFn = null;
                if (fnArg instanceof N2AST.VariableNode vn) {
                    targetFn = "fn_" + vn.name;
                } else if (fnArg instanceof N2AST.StringNode sn) {
                    targetFn = "fn_" + sn.value;
                }

                if (targetFn == null) {
                    errors.add("Line " + line + ":" + col + ": pcall first argument must be a function name");
                    break;
                }

                int id = ++labelCounter;
                String catchLbl = "_pcall_catch_" + id;
                String failLbl = "_pcall_fail_" + id;
                String endLbl = "_pcall_end_" + id;

                // Evaluate extra arguments to pass to targetFn
                for (int i = 1; i < args.size(); i++) {
                    generateExpression(args.get(i));
                    emit("PUSH A", "PUSH");
                }

                // Register exception vectors (0..4) to catch handler
                emit("MOV [_pcall_val], 0", "MOV");
                emit("MOV [_tmp_d], 0", "MOV");
                emit("IVEC 0, " + catchLbl, "IVEC");
                emit("IVEC 1, " + catchLbl, "IVEC");
                emit("IVEC 2, " + catchLbl, "IVEC");
                emit("IVEC 3, " + catchLbl, "IVEC");
                emit("IVEC 4, " + catchLbl, "IVEC");

                // Protected call
                emit("CALL " + targetFn, "CALL");

                // Check if exception occurred (_tmp_d == 1)
                emit("CMP [_tmp_d], 0", "CMP");
                emit("JNZ " + failLbl, "JNZ");

                // Normal completion: store return value into _pcall_val and set status A = 1 (true)
                emit("MOV [_pcall_val], A", "MOV");
                emit("MOV A, 1", "MOV");
                emit("JMP " + endLbl, "JMP");

                emitLabel(failLbl);
                emit("MOV A, 0", "MOV");
                emit("JMP " + endLbl, "JMP");

                // Exception catch handler
                emitLabel(catchLbl);
                emit("MOV [_tmp_d], 1", "MOV");
                String keepErrLbl = "_pcall_keep_" + id;
                emit("CMP [_pcall_val], 0", "CMP");
                emit("JNZ " + keepErrLbl, "JNZ");
                emit("MOV [_pcall_val], A", "MOV");
                emitLabel(keepErrLbl);
                emit("RET", "RET");

                emitLabel(endLbl);
            }
            case "sqrt" -> {
                if (!args.isEmpty()) {
                    generateExpression(args.get(0));
                    // Simple integer square root approximation
                    String loopLbl = "_sqrt_loop_" + (++labelCounter);
                    String endLbl = "_sqrt_end_" + labelCounter;
                    emit("MOV B, 0", "MOV");
                    emitLabel(loopLbl);
                    emit("MOV C, B", "MOV");
                    emit("MUL C, C", "MUL");
                    emit("CMP C, A", "CMP");
                    emit("JG " + endLbl, "JG");
                    emit("INC B", "INC");
                    emit("JMP " + loopLbl, "JMP");
                    emitLabel(endLbl);
                    emit("DEC B", "DEC");
                    emit("MOV A, B", "MOV");
                }
            }
            case "fb_mode", "set_mode" -> {
                if (!args.isEmpty()) {
                    generateExpression(args.get(0));
                    emit("FBMODE A", "FBMODE");
                }
            }
            case "fb_clear", "clear_screen" -> {
                if (!args.isEmpty()) {
                    generateExpression(args.get(0));
                    emit("FBCLR A", "FBCLR");
                } else {
                    emit("FBCLR 0", "FBCLR");
                }
            }
            case "fb_swap", "swap", "flip" -> emit("FBSWAP", "FBSWAP");
            case "fb_width", "width" -> emit("FBWIDTH A", "FBWIDTH");
            case "fb_height", "height" -> emit("FBHEIGHT A", "FBHEIGHT");
            case "fb_pixel", "set_pixel" -> {
                if (args.size() >= 3) {
                    generateExpression(args.get(0));
                    emit("PUSH A", "PUSH");
                    generateExpression(args.get(1));
                    emit("PUSH A", "PUSH");
                    generateExpression(args.get(2));
                    emit("MOV C, A", "MOV");
                    emit("POP B", "POP");
                    emit("POP A", "POP");
                    emit("FBSET A, B, C", "FBSET");
                }
            }
            case "fb_get", "get_pixel" -> {
                if (args.size() >= 2) {
                    generateExpression(args.get(0));
                    emit("PUSH A", "PUSH");
                    generateExpression(args.get(1));
                    emit("MOV B, A", "MOV");
                    emit("POP A", "POP");
                    emit("FBGET A, A, B", "FBGET");
                }
            }
            case "fb_rect", "draw_rect" -> {
                if (args.size() >= 5) {
                    for (N2AST.ExprNode arg : args) {
                        generateExpression(arg);
                        emit("PUSH A", "PUSH");
                    }
                    emit("POP [_tmp_d]", "POP");
                    emit("POP [_tmp_c]", "POP");
                    emit("POP [_tmp_b]", "POP");
                    emit("POP [_tmp_a]", "POP");
                    emit("POP A", "POP");
                    emit("FBRECT A, [_tmp_a], [_tmp_b], [_tmp_c], [_tmp_d]", "FBRECT");
                }
            }
            case "fb_line", "draw_line" -> {
                if (args.size() >= 5) {
                    for (N2AST.ExprNode arg : args) {
                        generateExpression(arg);
                        emit("PUSH A", "PUSH");
                    }
                    emit("POP [_tmp_d]", "POP");
                    emit("POP [_tmp_c]", "POP");
                    emit("POP [_tmp_b]", "POP");
                    emit("POP [_tmp_a]", "POP");
                    emit("POP A", "POP");
                    emit("FBLINE A, [_tmp_a], [_tmp_b], [_tmp_c], [_tmp_d]", "FBLINE");
                }
            }
            case "fb_blit", "blit" -> {
                if (args.size() >= 5) {
                    for (N2AST.ExprNode arg : args) {
                        generateExpression(arg);
                        emit("PUSH A", "PUSH");
                    }
                    emit("POP [_tmp_d]", "POP");
                    emit("POP [_tmp_c]", "POP");
                    emit("POP [_tmp_b]", "POP");
                    emit("POP [_tmp_a]", "POP");
                    emit("POP A", "POP");
                    emit("FBLIT A, [_tmp_a], [_tmp_b], [_tmp_c], [_tmp_d]", "FBLIT");
                }
            }
            case "gfx_set_handler" -> {
                if (!args.isEmpty()) {
                    generateExpression(args.get(0));
                    emit("GFXSEL A", "GFXSEL");
                }
            }
            case "use_auto_gfx" -> emit("GFXSEL 0", "GFXSEL");
            case "use_cpu" -> emit("GFXSEL 1", "GFXSEL");
            case "use_gpu" -> emit("GFXSEL 2", "GFXSEL");
            case "has_gpu", "gpu_available" -> emit("GFXSTAT A, 0", "GFXSTAT");
            case "gfx_handler", "is_using_gpu" -> emit("GFXSTAT A, 1", "GFXSTAT");
            case "gfx_mode" -> emit("GFXSTAT A, 2", "GFXSTAT");
            case "gpu_vram" -> emit("GFXSTAT A, 3", "GFXSTAT");
            case "gpu_vram_kb" -> emit("GFXSTAT A, 4", "GFXSTAT");
            case "gpu_clear" -> {
                if (!args.isEmpty()) {
                    generateExpression(args.get(0));
                    emit("GPUCLR A", "GPUCLR");
                } else {
                    emit("GPUCLR 0", "GPUCLR");
                }
            }
            case "gpu_rect" -> {
                if (args.size() >= 5) {
                    for (int i = 0; i < 5; i++) {
                        generateExpression(args.get(i));
                        emit("PUSH A", "PUSH");
                    }
                    emit("POP [_tmp_d]", "POP");
                    emit("POP [_tmp_c]", "POP");
                    emit("POP [_tmp_b]", "POP");
                    emit("POP [_tmp_a]", "POP");
                    emit("POP A", "POP");
                    emit("GPURECT A, [_tmp_a], [_tmp_b], [_tmp_c], [_tmp_d]", "GPURECT");
                }
            }
            case "gpu_line" -> {
                if (args.size() >= 5) {
                    for (int i = 0; i < 5; i++) {
                        generateExpression(args.get(i));
                        emit("PUSH A", "PUSH");
                    }
                    emit("POP [_tmp_d]", "POP");
                    emit("POP [_tmp_c]", "POP");
                    emit("POP [_tmp_b]", "POP");
                    emit("POP [_tmp_a]", "POP");
                    emit("POP A", "POP");
                    emit("GPULINE A, [_tmp_a], [_tmp_b], [_tmp_c], [_tmp_d]", "GPULINE");
                }
            }
            case "draw_circle", "gpu_circle", "fill_circle", "gpu_fill_circle" -> {
                if (args.size() >= 4) {
                    int filled = (name.equals("fill_circle") || name.equals("gpu_fill_circle")) ? 1 : 0;
                    for (int i = 0; i < 4; i++) {
                        generateExpression(args.get(i));
                        emit("PUSH A", "PUSH");
                    }
                    emit("POP [_tmp_c]", "POP");
                    emit("POP [_tmp_b]", "POP");
                    emit("POP [_tmp_a]", "POP");
                    emit("POP A", "POP");
                    emit("GPUCIRC A, [_tmp_a], [_tmp_b], [_tmp_c], " + filled, "GPUCIRC");
                }
            }
            case "fill_triangle", "gpu_triangle" -> {
                if (args.size() >= 7) {
                    needsGpuTriScratch = true;
                    for (int i = 0; i < 7; i++) {
                        generateExpression(args.get(i));
                        emit("PUSH A", "PUSH");
                    }
                    for (int i = 6; i >= 0; i--) {
                        emit("POP [_tmp_g" + i + "]", "POP");
                    }
                    emit("GPUTRI _tmp_g0", "GPUTRI");
                }
            }
            case "vram_upload" -> {
                if (args.size() >= 3) {
                    for (int i = 0; i < 3; i++) {
                        generateExpression(args.get(i));
                        emit("PUSH A", "PUSH");
                    }
                    emit("POP [_tmp_b]", "POP");
                    emit("POP [_tmp_a]", "POP");
                    emit("POP A", "POP");
                    emit("GPUVRAM A, [_tmp_a], [_tmp_b]", "GPUVRAM");
                }
            }
            case "gpu_blit" -> {
                if (args.size() >= 5) {
                    for (int i = 0; i < 5; i++) {
                        generateExpression(args.get(i));
                        emit("PUSH A", "PUSH");
                    }
                    emit("POP [_tmp_d]", "POP");
                    emit("POP [_tmp_c]", "POP");
                    emit("POP [_tmp_b]", "POP");
                    emit("POP [_tmp_a]", "POP");
                    emit("POP A", "POP");
                    emit("GPUBLIT A, [_tmp_a], [_tmp_b], [_tmp_c], [_tmp_d]", "GPUBLIT");
                }
            }
            case "gpu_swap" -> emit("GPUSWAP", "GPUSWAP");
            case "gpu_sync" -> emit("GPUSYNC", "GPUSYNC");
            case "tone", "play_tone" -> {
                if (args.size() >= 2) {
                    generateExpression(args.get(0));
                    emit("PUSH A", "PUSH");
                    generateExpression(args.get(1));
                    emit("MOV B, A", "MOV");
                    emit("POP A", "POP");
                    emit("ATONE A, B", "ATONE");
                }
            }
            case "play_audio", "audio_play" -> emit("APLAY", "APLAY");
            case "audio_clear" -> emit("ACLR", "ACLR");
            case "audio_status" -> emit("ASTAT A", "ASTAT");
            case "audio_push" -> {
                if (!args.isEmpty()) {
                    generateExpression(args.get(0));
                    emit("APUSH A", "APUSH");
                }
            }
            case "select_drive", "drive_select" -> {
                if (!args.isEmpty()) {
                    generateExpression(args.get(0));
                    emit("SSEL A", "SSEL");
                }
            }
            case "storage_size", "drive_size" -> {
                if (!args.isEmpty()) {
                    generateExpression(args.get(0));
                    emit("SSIZE A, A", "SSIZE");
                }
            }
            case "storage_read", "drive_read" -> {
                if (args.size() >= 2) {
                    generateExpression(args.get(0));
                    emit("PUSH A", "PUSH");
                    generateExpression(args.get(1));
                    emit("MOV B, A", "MOV");
                    emit("POP A", "POP");
                    emit("SREAD A, A, B", "SREAD");
                }
            }
            case "storage_write", "drive_write" -> {
                if (args.size() >= 3) {
                    generateExpression(args.get(0));
                    emit("PUSH A", "PUSH");
                    generateExpression(args.get(1));
                    emit("PUSH A", "PUSH");
                    generateExpression(args.get(2));
                    emit("MOV C, A", "MOV");
                    emit("POP B", "POP");
                    emit("POP A", "POP");
                    emit("SWRITE A, B, C", "SWRITE");
                }
            }
            case "drive_count" -> emit("SCOUNT A", "SCOUNT");
            case "network_open", "network_send", "network_receive", "network_close", "network_http",
                    "network_websocket", "network_send_text", "network_tls", "network_http3", "network_http_text", "network_http3_text",
                    "network_listen", "network_accept", "network_respond",
                    "network_status", "network_length", "network_result", "network_ready", "network_utf8", "network_respond_text" -> generateNetworkInstruction(name, symbol.arity(), args, line, col);
            case "peripheral_scan", "peripheral_info", "peripheral_wrap", "peripheral_call", "peripheral_call_string", "peripheral_status",
                    "peripheral_result", "peripheral_length", "peripheral_result_text", "peripheral_text", "peripheral_text_unit", "speaker_sample_rate", "speaker_tone", "note_block_get",
                    "note_block_set", "note_block_play", "note_block_play_instrument" -> generatePeripheralInstruction(name, symbol.arity(), args, line, col);
            // Keyboard handling (DeviceKit)
            case "read_key", "get_key" -> emit("INPAD A, -1", "INPAD");
            case "peek_key" -> emit("INPAD A, -2", "INPAD");
            case "key_available", "has_key" -> {
                emit("INPAD A, -2", "INPAD");
                String noKey = "_no_key_" + (++labelCounter);
                String keyDone = "_key_done_" + labelCounter;
                emit("CMP A, 0", "CMP");
                emit("JL " + noKey, "JL");
                emit("MOV A, 1", "MOV");
                emit("JMP " + keyDone, "JMP");
                emitLabel(noKey);
                emit("MOV A, 0", "MOV");
                emitLabel(keyDone);
            }
            case "on_key" -> {
                if (!args.isEmpty()) {
                    N2AST.ExprNode handlerArg = args.get(0);
                    String targetName = "";
                    if (handlerArg instanceof N2AST.VariableNode vn) {
                        targetName = vn.name;
                    } else if (handlerArg instanceof N2AST.StringNode sn) {
                        targetName = sn.value;
                    }
                    if (!targetName.isEmpty()) {
                        emit("IVEC KEYBOARD, fn_" + targetName, "IVEC");
                    }
                }
            }
            // GameController Handling
            case "read_gamepad", "poll_gamepad", "get_pad", "gc_poll" -> {
                if (!args.isEmpty()) {
                    generateExpression(args.get(0));
                    emit("INPAD A, A", "INPAD");
                } else {
                    emit("INPAD A, 0", "INPAD");
                }
            }
            case "is_gamepad_connected" -> emit("MOV A, 1", "MOV");
            case "gamepad_count", "gc_controllers" -> emit("MOV A, 1", "MOV");
            case "gc_current" -> emit("MOV A, 0", "MOV");
            case "gc_button_a" -> generateGamepadButtonCheck(args, 0x0080);
            case "gc_button_b" -> generateGamepadButtonCheck(args, 0x0040);
            case "gc_button_x" -> generateGamepadButtonCheck(args, 0x0200);
            case "gc_button_y" -> generateGamepadButtonCheck(args, 0x0100);
            case "gc_button_south" -> generateGamepadButtonCheck(args, 0x0040);
            case "gc_button_east" -> generateGamepadButtonCheck(args, 0x0080);
            case "gc_button_west" -> generateGamepadButtonCheck(args, 0x0100);
            case "gc_button_north" -> generateGamepadButtonCheck(args, 0x0200);
            case "gc_dpad_up" -> generateGamepadButtonCheck(args, 0x0008);
            case "gc_dpad_down" -> generateGamepadButtonCheck(args, 0x0004);
            case "gc_dpad_left" -> generateGamepadButtonCheck(args, 0x0002);
            case "gc_dpad_right" -> generateGamepadButtonCheck(args, 0x0001);
            case "gc_left_shoulder" -> generateGamepadButtonCheck(args, 0x0400);
            case "gc_right_shoulder" -> generateGamepadButtonCheck(args, 0x0800);
            case "gc_button_menu" -> generateGamepadButtonCheck(args, 0x0010);
            case "gc_button_options" -> generateGamepadButtonCheck(args, 0x0020);
            case "gc_left_trigger_pressed" -> generateGamepadButtonCheck(args, 0x1000);
            case "gc_right_trigger_pressed" -> generateGamepadButtonCheck(args, 0x2000);
            case "gc_left_stick_button" -> generateGamepadButtonCheck(args, 0x4000);
            case "gc_right_stick_button" -> generateGamepadButtonCheck(args, 0x8000);
            case "gc_left_stick_x" -> generateGamepadAxis(args, 0);
            case "gc_left_stick_y" -> generateGamepadAxis(args, 1);
            case "gc_right_stick_x" -> generateGamepadAxis(args, 2);
            case "gc_right_stick_y" -> generateGamepadAxis(args, 3);
            case "gc_left_trigger" -> generateGamepadAxis(args, 4);
            case "gc_right_trigger" -> generateGamepadAxis(args, 5);
            case "gc_axis" -> {
                generateExpression(args.get(0));
                emit("PUSH A", "PUSH");
                generateExpression(args.get(1));
                emit("POP B", "POP");
                emit("INAXIS A, B, A", "INAXIS");
            }
            default -> errors.add("Unimplemented library function: " + name);
        }
    }

    private void generateNetworkInstruction(String name, int arity, List<N2AST.ExprNode> args, int line, int col) {
        if (args.size() != arity) {
            errors.add("Line " + line + ":" + col + ": " + name + " expects " + arity + " arguments");
            return;
        }
        String opcode = switch (name) {
            case "network_open" -> "NOPEN";
            case "network_send" -> "NSEND";
            case "network_receive" -> "NRECV";
            case "network_close" -> "NCLOSE";
            case "network_http", "network_http_text" -> "NHTTP";
            case "network_websocket" -> "NWSOPEN";
            case "network_send_text" -> "NWSTEXT";
            case "network_tls" -> "NTLS";
            case "network_http3", "network_http3_text" -> "NHTTP3";
            case "network_listen" -> "NLISTEN";
            case "network_accept" -> "NACCEPT";
            case "network_respond" -> "NRESPOND";
            case "network_respond_text" -> "NRESPONDT";
            case "network_status" -> "NSTATUS";
            case "network_length" -> "NLEN";
            case "network_result" -> "NRESULT";
            case "network_ready" -> "NREADY";
            case "network_utf8" -> "NUTF8";
            default -> throw new IllegalArgumentException(name);
        };
        for (N2AST.ExprNode arg : args) {
            generateExpression(arg);
            emit("PUSH A", "PUSH");
        }
        for (int i = args.size() - 1; i >= 0; i--) emit("POP " + "ABCD".charAt(i), "POP");
        String[] suffix = {"", ", A", ", A, B", ", A, B, C", ", A, B, C, D"};
        String textFlag = name.equals("network_http_text") || name.equals("network_http3_text") ? ", 1" : "";
        emit(opcode + " A" + suffix[arity] + textFlag, opcode);
    }

    private void generatePeripheralInstruction(String name, int arity, List<N2AST.ExprNode> args, int line, int col) {
        if (args.size() != arity) {
            errors.add("Line " + line + ":" + col + ": " + name + " expects " + arity + " arguments");
            return;
        }
        switch (name) {
            case "peripheral_scan", "peripheral_info", "peripheral_wrap" -> {
                generateExpression(args.getFirst());
                String opcode = switch (name) {
                    case "peripheral_scan" -> "PSCAN";
                    case "peripheral_info" -> "PINFO";
                    default -> "PWRAP";
                };
                emit(opcode + " A, A", opcode);
            }
            case "peripheral_status" -> emit("PSTATUS A", "PSTATUS");
            case "peripheral_result" -> emit("PRESULT A", "PRESULT");
            case "peripheral_length" -> emit("PLEN A", "PLEN");
            case "peripheral_text" -> {
                emit("PLEN A", "PLEN");
                emit("INC A", "INC");
                emit("PUSH A", "PUSH");
                emit("CALL _alloc", "CALL");
                emit("POP B", "POP");
                emit("PRESULTS C, A, B", "PRESULTS");
            }
            case "peripheral_text_unit" -> {
                generateExpression(args.get(0));
                emit("PUSH A", "PUSH");
                generateExpression(args.get(1));
                emit("POP B", "POP");
                emit("ADD A, B", "ADD");
                emit("MOV A, [A]", "MOV");
            }
            case "peripheral_result_text" -> {
                generateExpression(args.get(0));
                emit("PUSH A", "PUSH");
                generateExpression(args.get(1));
                emit("POP B", "POP");
                emit("PRESULTS A, B, A", "PRESULTS");
            }
            case "speaker_sample_rate" -> generatePeripheralCall(args, "0x100", "PCALL");
            case "speaker_tone" -> generatePeripheralCall(args, "0x101", "PCALL");
            case "note_block_get" -> generatePeripheralCall(args, "0x200", "PCALL");
            case "note_block_set" -> generatePeripheralCall(args, "0x201", "PCALL");
            case "note_block_play" -> generatePeripheralCall(args, "0x202", "PCALL");
            case "note_block_play_instrument" -> generatePeripheralCall(args, "0x203", "PCALLS");
            case "peripheral_call" -> generatePeripheralCall(args, null, "PCALL");
            case "peripheral_call_string" -> generatePeripheralCall(args, null, "PCALLS");
        }
    }

    private void generatePeripheralCall(List<N2AST.ExprNode> args, String method, String opcode) {
        for (N2AST.ExprNode arg : args) {
            generateExpression(arg);
            emit("PUSH A", "PUSH");
        }
        for (int i = args.size() - 1; i >= 0; i--) {
            emit("POP " + "ABCD".charAt(i), "POP");
        }
        if (method == null) {
            emit(opcode + " A, A, B, C, D", opcode);
        } else {
            String arg0 = args.size() >= 2 ? "B" : "0";
            String arg1 = args.size() >= 3 ? "C" : "0";
            emit(opcode + " A, A, " + method + ", " + arg0 + ", " + arg1, opcode);
        }
    }

    private void generateGamepadAxis(List<N2AST.ExprNode> args, int axis) {
        generateExpression(args.get(0));
        emit("INAXIS A, A, " + axis, "INAXIS");
    }

    private void generateGamepadButtonCheck(List<N2AST.ExprNode> args, int mask) {
        if (!args.isEmpty()) {
            generateExpression(args.get(0));
            emit("INPAD A, A", "INPAD");
        } else {
            emit("INPAD A, 0", "INPAD");
        }
        emit("AND A, " + mask, "AND");
        String notPressed = "_gc_np_" + (++labelCounter);
        String done = "_gc_d_" + labelCounter;
        emit("JZ " + notPressed, "JZ");
        emit("MOV A, 1", "MOV");
        emit("JMP " + done, "JMP");
        emitLabel(notPressed);
        emit("MOV A, 0", "MOV");
        emitLabel(done);
    }

    private void generateMethodCall(N2AST.MethodCallNode mc) {
        // Built-in list methods
        if (mc.methodName.equals("push") || mc.methodName.equals("append")) {
            generateExpression(mc.object);
            emit("PUSH A", "PUSH"); // List ptr
            generateExpression(mc.args.get(0));
            emit("MOV C, A", "MOV"); // Element in C
            emit("POP B", "POP");    // List ptr in B
            emit("MOV A, [B]", "MOV"); // Current length in A
            emit("ADD B, 2", "ADD");
            emit("ADD B, A", "ADD");
            emit("MOV [B], C", "MOV"); // Write element
            emit("SUB B, 2", "SUB");
            emit("SUB B, A", "SUB");
            emit("INC [B]", "INC");   // Increment length
            return;
        }
        if (mc.methodName.equals("pop")) {
            generateExpression(mc.object);
            emit("MOV B, A", "MOV");
            emit("DEC [B]", "DEC"); // Decrement length
            emit("MOV A, [B]", "MOV"); // New length
            emit("ADD B, 2", "ADD");
            emit("ADD B, A", "ADD");
            emit("MOV A, [B]", "MOV"); // Return popped element
            return;
        }
        if (mc.methodName.equals("length") || mc.methodName.equals("len") || mc.methodName.equals("size")) {
            generateExpression(mc.object);
            emit("MOV A, [A]", "MOV");
            return;
        }

        // OOP Class Method Call: Object.method(args)
        // 1. Evaluate arguments and push
        for (N2AST.ExprNode arg : mc.args) {
            generateExpression(arg);
            emit("PUSH A", "PUSH");
        }

        // 2. Evaluate target object (passes as 'this' in register A)
        generateExpression(mc.object);

        // 3. Find and call the method
        String targetMethod = findMethodLabel(mc.methodName);
        emit("CALL " + targetMethod, "CALL");
    }

    private String findMethodLabel(String methodName) {
        if (currentClass != null && classMethods.containsKey(currentClass) && classMethods.get(currentClass).contains(methodName)) {
            return "m_" + currentClass + "_" + methodName;
        }
        for (Map.Entry<String, Set<String>> entry : classMethods.entrySet()) {
            if (entry.getValue().contains(methodName)) {
                return "m_" + entry.getKey() + "_" + methodName;
            }
        }
        for (String cls : classFieldOffsets.keySet()) {
            return "m_" + cls + "_" + methodName;
        }
        return "m_Player_" + methodName;
    }

    private void generateFieldAccess(N2AST.FieldAccessNode fa) {
        if (fa.object instanceof N2AST.ThisNode) {
            emit("MOV A, [_this_ptr]", "MOV");
        } else {
            generateExpression(fa.object);
        }
        int offset = resolveFieldOffset(fa.fieldName);
        emit("ADD A, " + offset, "ADD");
        emit("MOV A, [A]", "MOV");
    }

    private void generateIndexAccess(N2AST.IndexAccessNode idx) {
        generateExpression(idx.container);
        emit("PUSH A", "PUSH");
        generateExpression(idx.index);
        emit("MOV B, A", "MOV");
        emit("POP A", "POP");
        emit("ADD A, 2", "ADD"); // Offset 2 past length and capacity
        emit("ADD A, B", "ADD");
        emit("MOV A, [A]", "MOV");
    }

    private void generateNew(N2AST.NewNode nw) {
        String className = nw.className;
        Map<String, Integer> fields = classFieldOffsets.getOrDefault(className, Collections.emptyMap());
        int size = 1 + fields.size();

        // 1. Allocate object on heap
        emit("MOV A, " + size, "MOV");
        emit("CALL _alloc", "CALL");
        emit("MOV [_tmp_a], A", "MOV");

        // 2. Set Class ID at offset 0
        int clsId = classIds.getOrDefault(className, 1);
        emit("MOV D, [_tmp_a]", "MOV");
        emit("MOV [D], " + clsId, "MOV");

        // 3. Push instance pointer so we can restore it after constructor
        emit("PUSH [_tmp_a]", "PUSH");

        // 4. Evaluate constructor arguments and push to stack
        for (N2AST.ExprNode arg : nw.args) {
            generateExpression(arg);
            emit("PUSH A", "PUSH");
        }

        // 5. Call constructor: m_ClassName_init
        emit("MOV A, [_tmp_a]", "MOV"); // Pass instance as 'this' in A
        emit("CALL m_" + className + "_init", "CALL");

        // 6. Return object instance pointer in A
        emit("POP A", "POP");
    }

    private void generateListLiteral(N2AST.ListNode list) {
        int count = list.elements.size();
        int size = 2 + count;

        emit("MOV A, " + size, "MOV");
        emit("CALL _alloc", "CALL");
        emit("PUSH A", "PUSH"); // Save list ptr

        // Set length and capacity
        emit("MOV D, A", "MOV");
        emit("MOV [D], " + count, "MOV");
        emit("ADD D, 1", "ADD");
        emit("MOV [D], " + count, "MOV");

        // Write elements
        for (int i = 0; i < count; i++) {
            generateExpression(list.elements.get(i));
            emit("MOV C, A", "MOV");
            emit("POP B", "POP");
            emit("PUSH B", "PUSH");
            emit("MOV D, B", "MOV");
            emit("ADD D, " + (2 + i), "ADD");
            emit("MOV [D], C", "MOV");
        }

        emit("POP A", "POP");
    }

    private void generateMapLiteral(N2AST.MapNode map) {
        int count = map.entries.size();
        int size = 2 + (count * 2);

        emit("MOV A, " + size, "MOV");
        emit("CALL _alloc", "CALL");
        emit("PUSH A", "PUSH");

        emit("MOV D, A", "MOV");
        emit("MOV [D], " + count, "MOV");
        emit("ADD D, 1", "ADD");
        emit("MOV [D], " + count, "MOV");

        for (int i = 0; i < count; i++) {
            N2AST.MapEntry entry = map.entries.get(i);
            generateExpression(entry.key);
            emit("PUSH A", "PUSH");
            generateExpression(entry.value);
            emit("MOV C, A", "MOV");
            emit("POP B", "POP");   // Key
            emit("POP D", "POP");   // Map ptr
            emit("PUSH D", "PUSH");
            emit("MOV [_tmp_a], D", "MOV");
            emit("MOV D, [_tmp_a]", "MOV");
            emit("ADD D, " + (2 + i * 2), "ADD");
            emit("MOV [D], B", "MOV");
            emit("MOV D, [_tmp_a]", "MOV");
            emit("ADD D, " + (3 + i * 2), "ADD");
            emit("MOV [D], C", "MOV");
        }

        emit("POP A", "POP");
    }

    private boolean isTextExpression(N2AST.ExprNode expression) {
        return expression instanceof N2AST.StringNode || expression instanceof N2AST.CallNode call
                && (call instanceof N2AST.SystemCallNode || importedSymbols.containsKey(call.callee)) && (call.callee.equals("http_text") || call.callee.equals("http_body")
                || call.callee.equals("peripheral_text"));
    }

    private boolean isStringValued(N2AST.ExprNode arg) {
        if (isTextExpression(arg)) return true;
        if (arg instanceof N2AST.VariableNode v) {
            return stringVars.contains(v.name);
        }
        if (arg instanceof N2AST.FieldAccessNode fa) {
            return stringFields.contains(fa.fieldName);
        }
        return false;
    }

    private String escapeString(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else sb.append(c);
        }
        return sb.toString();
    }

    private String resolveVarSlot(String name) {
        if (currentLocals.contains(name) && currentFunction != null) {
            return "v_" + currentFunction + "_" + name;
        }
        globalVars.add(name);
        return "g_" + name;
    }

    private int resolveFieldOffset(String fieldName) {
        if (currentClass != null && classFieldOffsets.containsKey(currentClass)) {
            Map<String, Integer> map = classFieldOffsets.get(currentClass);
            if (map.containsKey(fieldName)) return map.get(fieldName);
        }
        for (Map<String, Integer> map : classFieldOffsets.values()) {
            if (map.containsKey(fieldName)) return map.get(fieldName);
        }
        return 1;
    }

    private String getStringLiteralLabel(String value) {
        if (stringPool.containsKey(value)) {
            return stringPool.get(value);
        }
        String label = "_str_" + (++stringCounter);
        stringPool.put(value, label);
        dataLines.add(label + ": DB 0");
        return label;
    }

    private void emit(String line) {
        lines.add(line);
    }

    private void emit(String line, String requiredOpcode) {
        lines.add(line);
        if (requiredOpcode != null) {
            requiredInstructions.add(requiredOpcode);
        }
    }

    private void emitLabel(String label) {
        lines.add(label + ":");
    }

    private void emitComment(String comment) {
        lines.add("; " + comment);
    }
}
