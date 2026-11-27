use std::fmt;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TokenType {
    // Keywords
    KeywordGlobal,
    KeywordLocal,
    KeywordFunc,
    KeywordEnd,
    KeywordReturn,
    KeywordClass,
    KeywordNew,
    KeywordThis,
    KeywordSelf,
    KeywordImport,
    KeywordFrom,
    KeywordIf,
    KeywordThen,
    KeywordElseIf,
    KeywordElse,
    KeywordWhile,
    KeywordDo,
    KeywordFor,
    KeywordIn,
    KeywordBreak,
    KeywordContinue,
    KeywordTrue,
    KeywordFalse,
    KeywordNil,
    KeywordAnd,
    KeywordOr,
    KeywordNot,

    // Identifiers & Literals
    Identifier,
    Number,
    String,
    LibPath,

    // Operators & Delimiters
    Assign,      // =
    PlusAssign,  // +=
    MinusAssign, // -=
    StarAssign,  // *=
    SlashAssign, // /=
    Plus,        // +
    Minus,       // -
    Star,        // *
    Slash,       // /
    Percent,     // %
    Eq,          // ==
    Neq,         // != or ~=
    Lt,          // <
    Lte,         // <=
    Gt,          // >
    Gte,         // >=
    Amp,         // &
    Pipe,        // |
    Caret,       // ^
    Tilde,       // ~
    Shl,         // <<
    Shr,         // >>
    Hash,        // #
    Dot,         // .
    Comma,       // ,
    Colon,       // :
    Semicolon,   // ;
    LParen,      // (
    RParen,      // )
    LBracket,    // [
    RBracket,    // ]
    LBrace,      // {
    RBrace,      // }

    Eof,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Literal {
    None,
    Int(i32),
    Str(String),
    Bool(bool),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Token {
    pub kind: TokenType,
    pub text: String,
    pub line: usize,
    pub column: usize,
    pub literal: Literal,
}

impl Token {
    pub fn new(kind: TokenType, text: impl Into<String>, line: usize, column: usize) -> Self {
        Self {
            kind,
            text: text.into(),
            line,
            column,
            literal: Literal::None,
        }
    }

    pub fn with_literal(
        kind: TokenType,
        text: impl Into<String>,
        line: usize,
        column: usize,
        literal: Literal,
    ) -> Self {
        Self {
            kind,
            text: text.into(),
            line,
            column,
            literal,
        }
    }
}

impl fmt::Display for Token {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{:?}({}) at {}:{}", self.kind, self.text, self.line, self.column)
    }
}
