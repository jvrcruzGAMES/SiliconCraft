package games.jvrcruz.siliconcraft.compiler.n2;

public enum N2TokenType {
    // Keywords
    KEYWORD_GLOBAL,
    KEYWORD_LOCAL,
    KEYWORD_FUNC,
    KEYWORD_END,
    KEYWORD_RETURN,
    KEYWORD_CLASS,
    KEYWORD_NEW,
    KEYWORD_THIS,
    KEYWORD_SELF,
    KEYWORD_IMPORT,
    KEYWORD_FROM,
    KEYWORD_IF,
    KEYWORD_THEN,
    KEYWORD_ELSEIF,
    KEYWORD_ELSE,
    KEYWORD_WHILE,
    KEYWORD_DO,
    KEYWORD_FOR,
    KEYWORD_IN,
    KEYWORD_BREAK,
    KEYWORD_CONTINUE,
    KEYWORD_TRUE,
    KEYWORD_FALSE,
    KEYWORD_NIL,
    KEYWORD_AND,
    KEYWORD_OR,
    KEYWORD_NOT,

    // Identifiers & Literals
    IDENTIFIER,
    NUMBER,
    STRING,
    LIB_PATH, // e.g. <lib/std>

    // Operators & Delimiters
    ASSIGN,        // =
    PLUS_ASSIGN,   // +=
    MINUS_ASSIGN,  // -=
    STAR_ASSIGN,   // *=
    SLASH_ASSIGN,  // /=
    PLUS,          // +
    MINUS,         // -
    STAR,          // *
    SLASH,         // /
    PERCENT,       // %
    EQ,            // ==
    NEQ,           // != or ~=
    LT,            // <
    LTE,           // <=
    GT,            // >
    GTE,           // >=
    AMP,           // &
    PIPE,          // |
    CARET,         // ^
    TILDE,         // ~
    SHL,           // <<
    SHR,           // >>
    HASH,          // # (length operator)
    DOT,           // .
    COMMA,         // ,
    COLON,         // :
    SEMICOLON,     // ;
    LPAREN,        // (
    RPAREN,        // )
    LBRACKET,      // [
    RBRACKET,      // ]
    LBRACE,        // {
    RBRACE,        // }

    EOF
}
