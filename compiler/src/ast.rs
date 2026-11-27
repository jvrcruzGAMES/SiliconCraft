use crate::library::SymbolInfo;

#[derive(Debug, Clone)]
pub struct Program {
    pub imports: Vec<Import>,
    pub statements: Vec<Stmt>,
}

impl Program {
    pub fn new() -> Self {
        Self {
            imports: Vec::new(),
            statements: Vec::new(),
        }
    }
}

#[derive(Debug, Clone)]
pub struct Import {
    pub path: String,
    pub is_library: bool,
    pub symbols: Vec<String>,
    pub alias: Option<String>,
    pub line: usize,
    pub column: usize,
}

#[derive(Debug, Clone)]
pub struct MethodDecl {
    pub name: String,
    pub params: Vec<String>,
    pub body: Vec<Stmt>,
    pub line: usize,
    pub column: usize,
}

#[derive(Debug, Clone)]
pub struct FunctionDecl {
    pub name: String,
    pub params: Vec<String>,
    pub body: Vec<Stmt>,
    pub line: usize,
    pub column: usize,
}

#[derive(Debug, Clone)]
pub struct ClassDecl {
    pub name: String,
    pub methods: Vec<MethodDecl>,
    pub line: usize,
    pub column: usize,
}

#[derive(Debug, Clone)]
pub struct ElseIfBranch {
    pub condition: Expr,
    pub body: Vec<Stmt>,
}

#[derive(Debug, Clone)]
pub enum AssignTarget {
    Var {
        name: String,
        line: usize,
        column: usize,
    },
    Field {
        object: Box<Expr>,
        field: String,
        line: usize,
        column: usize,
    },
    Index {
        container: Box<Expr>,
        index: Box<Expr>,
        line: usize,
        column: usize,
    },
}

impl AssignTarget {
    pub fn pos(&self) -> (usize, usize) {
        match self {
            AssignTarget::Var { line, column, .. }
            | AssignTarget::Field { line, column, .. }
            | AssignTarget::Index { line, column, .. } => (*line, *column),
        }
    }
}

#[derive(Debug, Clone)]
pub enum Stmt {
    InlineAssembly {
        lines: Vec<String>,
        line: usize,
        column: usize,
    },
    Class(ClassDecl),
    Function(FunctionDecl),
    VarDecl {
        name: String,
        secondary_names: Vec<String>,
        is_local: bool,
        init_expr: Option<Expr>,
        line: usize,
        column: usize,
    },
    Assign {
        target: AssignTarget,
        secondary_targets: Vec<AssignTarget>,
        op: String,
        value: Expr,
        line: usize,
        column: usize,
    },
    ExprStmt {
        expr: Expr,
        line: usize,
        column: usize,
    },
    If {
        condition: Expr,
        then_body: Vec<Stmt>,
        elseifs: Vec<ElseIfBranch>,
        else_body: Vec<Stmt>,
        line: usize,
        column: usize,
    },
    While {
        condition: Expr,
        body: Vec<Stmt>,
        line: usize,
        column: usize,
    },
    ForRange {
        var_name: String,
        start_expr: Expr,
        end_expr: Expr,
        step_expr: Option<Expr>,
        body: Vec<Stmt>,
        line: usize,
        column: usize,
    },
    Return {
        expr: Option<Expr>,
        line: usize,
        column: usize,
    },
    Break {
        line: usize,
        column: usize,
    },
    Continue {
        line: usize,
        column: usize,
    },
}

#[derive(Debug, Clone)]
pub struct MapEntry {
    pub key: Expr,
    pub value: Expr,
}

#[derive(Debug, Clone)]
pub enum Expr {
    Number {
        value: i32,
        line: usize,
        column: usize,
    },
    String {
        value: String,
        line: usize,
        column: usize,
    },
    Boolean {
        value: bool,
        line: usize,
        column: usize,
    },
    Nil {
        line: usize,
        column: usize,
    },
    Variable {
        name: String,
        line: usize,
        column: usize,
    },
    This {
        line: usize,
        column: usize,
    },
    List {
        elements: Vec<Expr>,
        line: usize,
        column: usize,
    },
    Map {
        entries: Vec<MapEntry>,
        line: usize,
        column: usize,
    },
    UnaryOp {
        op: String,
        operand: Box<Expr>,
        line: usize,
        column: usize,
    },
    BinaryOp {
        op: String,
        left: Box<Expr>,
        right: Box<Expr>,
        line: usize,
        column: usize,
    },
    Call {
        callee: String,
        args: Vec<Expr>,
        line: usize,
        column: usize,
    },
    SystemCall {
        symbol: SymbolInfo,
        args: Vec<Expr>,
        line: usize,
        column: usize,
    },
    MethodCall {
        object: Box<Expr>,
        method_name: String,
        args: Vec<Expr>,
        line: usize,
        column: usize,
    },
    FieldAccess {
        object: Box<Expr>,
        field_name: String,
        line: usize,
        column: usize,
    },
    IndexAccess {
        container: Box<Expr>,
        index: Box<Expr>,
        line: usize,
        column: usize,
    },
    New {
        class_name: String,
        args: Vec<Expr>,
        line: usize,
        column: usize,
    },
}

impl Expr {
    pub fn pos(&self) -> (usize, usize) {
        match self {
            Expr::Number { line, column, .. }
            | Expr::String { line, column, .. }
            | Expr::Boolean { line, column, .. }
            | Expr::Nil { line, column }
            | Expr::Variable { line, column, .. }
            | Expr::This { line, column }
            | Expr::List { line, column, .. }
            | Expr::Map { line, column, .. }
            | Expr::UnaryOp { line, column, .. }
            | Expr::BinaryOp { line, column, .. }
            | Expr::Call { line, column, .. }
            | Expr::SystemCall { line, column, .. }
            | Expr::MethodCall { line, column, .. }
            | Expr::FieldAccess { line, column, .. }
            | Expr::IndexAccess { line, column, .. }
            | Expr::New { line, column, .. } => (*line, *column),
        }
    }
}
