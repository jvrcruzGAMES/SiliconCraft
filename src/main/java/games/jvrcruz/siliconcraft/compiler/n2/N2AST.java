package games.jvrcruz.siliconcraft.compiler.n2;

import java.util.ArrayList;
import java.util.List;

public class N2AST {

    public interface Node {
        int getLine();
        int getColumn();
    }

    public static abstract class BaseNode implements Node {
        protected final int line;
        protected final int column;

        public BaseNode(int line, int column) {
            this.line = line;
            this.column = column;
        }

        @Override
        public int getLine() { return line; }
        @Override
        public int getColumn() { return column; }
    }

    public static abstract class ExprNode extends BaseNode {
        public ExprNode(int line, int column) {
            super(line, column);
        }
    }

    public static class ProgramNode extends BaseNode {
        public final List<ImportNode> imports = new ArrayList<>();
        public final List<Node> statements = new ArrayList<>();

        public ProgramNode(int line, int column) {
            super(line, column);
        }
    }

    public static class ImportNode extends BaseNode {
        public final String path;
        public final boolean isLibrary; // true for <lib/*>, false for "project_file"
        public final List<String> symbols; // empty list means wildcard / import all

        public ImportNode(String path, boolean isLibrary, List<String> symbols, int line, int column) {
            super(line, column);
            this.path = path;
            this.isLibrary = isLibrary;
            this.symbols = symbols != null ? symbols : new ArrayList<>();
        }
    }

    public static class InlineAssemblyNode extends BaseNode {
        public final List<String> lines;

        public InlineAssemblyNode(List<String> lines, int line, int column) {
            super(line, column);
            this.lines = lines != null ? lines : new ArrayList<>();
        }
    }

    public static class ClassNode extends BaseNode {
        public final String name;
        public final List<MethodNode> methods = new ArrayList<>();

        public ClassNode(String name, int line, int column) {
            super(line, column);
            this.name = name;
        }

        public MethodNode getConstructor() {
            for (MethodNode m : methods) {
                if (m.name.equals("init") || m.name.equals("constructor")) {
                    return m;
                }
            }
            return null;
        }
    }

    public static class MethodNode extends BaseNode {
        public final String name;
        public final List<String> params;
        public final List<Node> body;

        public MethodNode(String name, List<String> params, List<Node> body, int line, int column) {
            super(line, column);
            this.name = name;
            this.params = params != null ? params : new ArrayList<>();
            this.body = body != null ? body : new ArrayList<>();
        }
    }

    public static class FunctionNode extends BaseNode {
        public final String name;
        public final List<String> params;
        public final List<Node> body;

        public FunctionNode(String name, List<String> params, List<Node> body, int line, int column) {
            super(line, column);
            this.name = name;
            this.params = params != null ? params : new ArrayList<>();
            this.body = body != null ? body : new ArrayList<>();
        }
    }

    public static class VarDeclNode extends BaseNode {
        public final String name;
        public final List<String> secondaryNames;
        public final boolean isLocal;
        public final ExprNode initExpr;

        public VarDeclNode(String name, boolean isLocal, ExprNode initExpr, int line, int column) {
            this(name, List.of(), isLocal, initExpr, line, column);
        }

        public VarDeclNode(String name, List<String> secondaryNames, boolean isLocal, ExprNode initExpr, int line, int column) {
            super(line, column);
            this.name = name;
            this.secondaryNames = secondaryNames != null ? secondaryNames : List.of();
            this.isLocal = isLocal;
            this.initExpr = initExpr;
        }
    }

    public interface AssignTarget {
        int getLine();
        int getColumn();
    }

    public static class VarTarget implements AssignTarget {
        public final String name;
        public final int line;
        public final int column;

        public VarTarget(String name, int line, int column) {
            this.name = name;
            this.line = line;
            this.column = column;
        }

        @Override public int getLine() { return line; }
        @Override public int getColumn() { return column; }
    }

    public static class FieldTarget implements AssignTarget {
        public final ExprNode object;
        public final String field;
        public final int line;
        public final int column;

        public FieldTarget(ExprNode object, String field, int line, int column) {
            this.object = object;
            this.field = field;
            this.line = line;
            this.column = column;
        }

        @Override public int getLine() { return line; }
        @Override public int getColumn() { return column; }
    }

    public static class IndexTarget implements AssignTarget {
        public final ExprNode container;
        public final ExprNode index;
        public final int line;
        public final int column;

        public IndexTarget(ExprNode container, ExprNode index, int line, int column) {
            this.container = container;
            this.index = index;
            this.line = line;
            this.column = column;
        }

        @Override public int getLine() { return line; }
        @Override public int getColumn() { return column; }
    }

    public static class AssignNode extends BaseNode {
        public final AssignTarget target;
        public final List<AssignTarget> secondaryTargets;
        public final String op; // "=", "+=", "-=", etc.
        public final ExprNode value;

        public AssignNode(AssignTarget target, String op, ExprNode value, int line, int column) {
            this(target, List.of(), op, value, line, column);
        }

        public AssignNode(AssignTarget target, List<AssignTarget> secondaryTargets, String op, ExprNode value, int line, int column) {
            super(line, column);
            this.target = target;
            this.secondaryTargets = secondaryTargets != null ? secondaryTargets : List.of();
            this.op = op;
            this.value = value;
        }
    }

    public static class ExprStmtNode extends BaseNode {
        public final ExprNode expr;

        public ExprStmtNode(ExprNode expr, int line, int column) {
            super(line, column);
            this.expr = expr;
        }
    }

    public static class ElseIfBranch {
        public final ExprNode condition;
        public final List<Node> body;

        public ElseIfBranch(ExprNode condition, List<Node> body) {
            this.condition = condition;
            this.body = body;
        }
    }

    public static class IfNode extends BaseNode {
        public final ExprNode condition;
        public final List<Node> thenBody;
        public final List<ElseIfBranch> elseifs;
        public final List<Node> elseBody;

        public IfNode(ExprNode condition, List<Node> thenBody, List<ElseIfBranch> elseifs, List<Node> elseBody, int line, int column) {
            super(line, column);
            this.condition = condition;
            this.thenBody = thenBody;
            this.elseifs = elseifs != null ? elseifs : new ArrayList<>();
            this.elseBody = elseBody != null ? elseBody : new ArrayList<>();
        }
    }

    public static class WhileNode extends BaseNode {
        public final ExprNode condition;
        public final List<Node> body;

        public WhileNode(ExprNode condition, List<Node> body, int line, int column) {
            super(line, column);
            this.condition = condition;
            this.body = body;
        }
    }

    public static class ForRangeNode extends BaseNode {
        public final String varName;
        public final ExprNode startExpr;
        public final ExprNode endExpr;
        public final ExprNode stepExpr;
        public final List<Node> body;

        public ForRangeNode(String varName, ExprNode startExpr, ExprNode endExpr, ExprNode stepExpr, List<Node> body, int line, int column) {
            super(line, column);
            this.varName = varName;
            this.startExpr = startExpr;
            this.endExpr = endExpr;
            this.stepExpr = stepExpr;
            this.body = body;
        }
    }

    public static class ReturnNode extends BaseNode {
        public final ExprNode expr;

        public ReturnNode(ExprNode expr, int line, int column) {
            super(line, column);
            this.expr = expr;
        }
    }

    public static class BreakNode extends BaseNode {
        public BreakNode(int line, int column) { super(line, column); }
    }

    public static class ContinueNode extends BaseNode {
        public ContinueNode(int line, int column) { super(line, column); }
    }

    // Expressions
    public static class NumberNode extends ExprNode {
        public final int value;

        public NumberNode(int value, int line, int column) {
            super(line, column);
            this.value = value;
        }
    }

    public static class StringNode extends ExprNode {
        public final String value;

        public StringNode(String value, int line, int column) {
            super(line, column);
            this.value = value;
        }
    }

    public static class BooleanNode extends ExprNode {
        public final boolean value;

        public BooleanNode(boolean value, int line, int column) {
            super(line, column);
            this.value = value;
        }
    }

    public static class NilNode extends ExprNode {
        public NilNode(int line, int column) { super(line, column); }
    }

    public static class VariableNode extends ExprNode {
        public final String name;

        public VariableNode(String name, int line, int column) {
            super(line, column);
            this.name = name;
        }
    }

    public static class ThisNode extends ExprNode {
        public ThisNode(int line, int column) { super(line, column); }
    }

    public static class ListNode extends ExprNode {
        public final List<ExprNode> elements;

        public ListNode(List<ExprNode> elements, int line, int column) {
            super(line, column);
            this.elements = elements != null ? elements : new ArrayList<>();
        }
    }

    public static class MapEntry {
        public final ExprNode key;
        public final ExprNode value;

        public MapEntry(ExprNode key, ExprNode value) {
            this.key = key;
            this.value = value;
        }
    }

    public static class MapNode extends ExprNode {
        public final List<MapEntry> entries;

        public MapNode(List<MapEntry> entries, int line, int column) {
            super(line, column);
            this.entries = entries != null ? entries : new ArrayList<>();
        }
    }

    public static class UnaryOpNode extends ExprNode {
        public final String op;
        public final ExprNode operand;

        public UnaryOpNode(String op, ExprNode operand, int line, int column) {
            super(line, column);
            this.op = op;
            this.operand = operand;
        }
    }

    public static class BinaryOpNode extends ExprNode {
        public final String op;
        public final ExprNode left;
        public final ExprNode right;

        public BinaryOpNode(String op, ExprNode left, ExprNode right, int line, int column) {
            super(line, column);
            this.op = op;
            this.left = left;
            this.right = right;
        }
    }

    public static class CallNode extends ExprNode {
        public final String callee;
        public final List<ExprNode> args;

        public CallNode(String callee, List<ExprNode> args, int line, int column) {
            super(line, column);
            this.callee = callee;
            this.args = args != null ? args : new ArrayList<>();
        }
    }

    public static class MethodCallNode extends ExprNode {
        public final ExprNode object;
        public final String methodName;
        public final List<ExprNode> args;

        public MethodCallNode(ExprNode object, String methodName, List<ExprNode> args, int line, int column) {
            super(line, column);
            this.object = object;
            this.methodName = methodName;
            this.args = args != null ? args : new ArrayList<>();
        }
    }

    public static class FieldAccessNode extends ExprNode {
        public final ExprNode object;
        public final String fieldName;

        public FieldAccessNode(ExprNode object, String fieldName, int line, int column) {
            super(line, column);
            this.object = object;
            this.fieldName = fieldName;
        }
    }

    public static class IndexAccessNode extends ExprNode {
        public final ExprNode container;
        public final ExprNode index;

        public IndexAccessNode(ExprNode container, ExprNode index, int line, int column) {
            super(line, column);
            this.container = container;
            this.index = index;
        }
    }

    public static class NewNode extends ExprNode {
        public final String className;
        public final List<ExprNode> args;

        public NewNode(String className, List<ExprNode> args, int line, int column) {
            super(line, column);
            this.className = className;
            this.args = args != null ? args : new ArrayList<>();
        }
    }
}
