use crate::error::CompileError;
use crate::token::{Literal, Token, TokenType};

pub struct Lexer {
    chars: Vec<char>,
    index: usize,
    line: usize,
    column: usize,
    file_name: String,
}

impl Lexer {
    pub fn new(source: &str, file_name: Option<&str>) -> Self {
        Self {
            chars: source.chars().collect(),
            index: 0,
            line: 1,
            column: 1,
            file_name: file_name.unwrap_or("source.n2").to_string(),
        }
    }

    pub fn tokenize(&mut self) -> Result<Vec<Token>, CompileError> {
        let mut tokens = Vec::new();
        while !self.is_at_end() {
            self.skip_whitespace_and_comments();
            if self.is_at_end() {
                break;
            }

            let start_line = self.line;
            let start_col = self.column;
            let c = self.peek();

            // Library path syntax: <lib/...> or after import/from
            if c == '<' && self.match_lib_path(&tokens) {
                tokens.push(self.scan_lib_path(start_line, start_col)?);
                continue;
            }

            // String literals
            if c == '"' || c == '\'' {
                tokens.push(self.scan_string(c, start_line, start_col)?);
                continue;
            }

            // Number literals
            if c.is_ascii_digit()
                || (c == '-' && self.is_digit_at(self.index + 1) && Self::should_lex_negative_number(&tokens))
            {
                tokens.push(self.scan_number(start_line, start_col)?);
                continue;
            }

            // Identifiers and keywords
            if Self::is_identifier_start(c) {
                tokens.push(self.scan_identifier(start_line, start_col));
                continue;
            }

            // Operators and punctuation
            self.advance();
            let tok = match c {
                '(' => Token::new(TokenType::LParen, "(", start_line, start_col),
                ')' => Token::new(TokenType::RParen, ")", start_line, start_col),
                '[' => Token::new(TokenType::LBracket, "[", start_line, start_col),
                ']' => Token::new(TokenType::RBracket, "]", start_line, start_col),
                '{' => Token::new(TokenType::LBrace, "{", start_line, start_col),
                '}' => Token::new(TokenType::RBrace, "}", start_line, start_col),
                ',' => Token::new(TokenType::Comma, ",", start_line, start_col),
                '.' => Token::new(TokenType::Dot, ".", start_line, start_col),
                ':' => Token::new(TokenType::Colon, ":", start_line, start_col),
                ';' => Token::new(TokenType::Semicolon, ";", start_line, start_col),
                '#' => Token::new(TokenType::Hash, "#", start_line, start_col),
                '~' => {
                    if self.match_char('=') {
                        Token::new(TokenType::Neq, "~=", start_line, start_col)
                    } else {
                        Token::new(TokenType::Tilde, "~", start_line, start_col)
                    }
                }
                '+' => {
                    if self.match_char('=') {
                        Token::new(TokenType::PlusAssign, "+=", start_line, start_col)
                    } else {
                        Token::new(TokenType::Plus, "+", start_line, start_col)
                    }
                }
                '-' => {
                    if self.match_char('=') {
                        Token::new(TokenType::MinusAssign, "-=", start_line, start_col)
                    } else {
                        Token::new(TokenType::Minus, "-", start_line, start_col)
                    }
                }
                '*' => {
                    if self.match_char('=') {
                        Token::new(TokenType::StarAssign, "*=", start_line, start_col)
                    } else {
                        Token::new(TokenType::Star, "*", start_line, start_col)
                    }
                }
                '/' => {
                    if self.match_char('=') {
                        Token::new(TokenType::SlashAssign, "/=", start_line, start_col)
                    } else {
                        Token::new(TokenType::Slash, "/", start_line, start_col)
                    }
                }
                '%' => Token::new(TokenType::Percent, "%", start_line, start_col),
                '=' => {
                    if self.match_char('=') {
                        Token::new(TokenType::Eq, "==", start_line, start_col)
                    } else {
                        Token::new(TokenType::Assign, "=", start_line, start_col)
                    }
                }
                '!' => {
                    if self.match_char('=') {
                        Token::new(TokenType::Neq, "!=", start_line, start_col)
                    } else {
                        return Err(CompileError::new(
                            "Unexpected character '!' (did you mean '!=' or 'not'?)",
                            Some(&self.file_name),
                            start_line,
                            start_col,
                        ));
                    }
                }
                '<' => {
                    if self.match_char('<') {
                        Token::new(TokenType::Shl, "<<", start_line, start_col)
                    } else if self.match_char('=') {
                        Token::new(TokenType::Lte, "<=", start_line, start_col)
                    } else {
                        Token::new(TokenType::Lt, "<", start_line, start_col)
                    }
                }
                '>' => {
                    if self.match_char('>') {
                        Token::new(TokenType::Shr, ">>", start_line, start_col)
                    } else if self.match_char('=') {
                        Token::new(TokenType::Gte, ">=", start_line, start_col)
                    } else {
                        Token::new(TokenType::Gt, ">", start_line, start_col)
                    }
                }
                '&' => Token::new(TokenType::Amp, "&", start_line, start_col),
                '|' => Token::new(TokenType::Pipe, "|", start_line, start_col),
                '^' => Token::new(TokenType::Caret, "^", start_line, start_col),
                _ => {
                    return Err(CompileError::new(
                        format!("Unexpected character: '{}'", c),
                        Some(&self.file_name),
                        start_line,
                        start_col,
                    ));
                }
            };
            tokens.push(tok);
        }

        tokens.push(Token::new(TokenType::Eof, "", self.line, self.column));
        Ok(tokens)
    }

    fn match_lib_path(&self, tokens: &[Token]) -> bool {
        if let Some(last) = tokens.last() {
            if matches!(last.kind, TokenType::KeywordImport | TokenType::KeywordFrom) {
                return true;
            }
        }
        self.starts_with("<lib/")
    }

    fn starts_with(&self, needle: &str) -> bool {
        let mut idx = self.index;
        for nc in needle.chars() {
            if idx >= self.chars.len() || self.chars[idx] != nc {
                return false;
            }
            idx += 1;
        }
        true
    }

    fn scan_lib_path(&mut self, start_line: usize, start_col: usize) -> Result<Token, CompileError> {
        self.advance(); // '<'
        let mut sb = String::new();
        while !self.is_at_end() && self.peek() != '>' {
            if self.peek() == '\n' {
                return Err(CompileError::new(
                    "Unterminated library path '<...>' before newline",
                    Some(&self.file_name),
                    start_line,
                    start_col,
                ));
            }
            sb.push(self.advance());
        }
        if self.is_at_end() {
            return Err(CompileError::new(
                "Unterminated library path '<...>'",
                Some(&self.file_name),
                start_line,
                start_col,
            ));
        }
        self.advance(); // '>'
        let path = sb.trim().to_string();
        Ok(Token::with_literal(
            TokenType::LibPath,
            path.clone(),
            start_line,
            start_col,
            Literal::Str(path),
        ))
    }

    fn scan_string(
        &mut self,
        quote: char,
        start_line: usize,
        start_col: usize,
    ) -> Result<Token, CompileError> {
        self.advance(); // opening quote
        let mut sb = String::new();
        while !self.is_at_end() && self.peek() != quote {
            let c = self.advance();
            if c == '\\' {
                if self.is_at_end() {
                    return Err(CompileError::new(
                        "Unterminated escape sequence in string",
                        Some(&self.file_name),
                        start_line,
                        start_col,
                    ));
                }
                let esc = self.advance();
                match esc {
                    'n' => sb.push('\n'),
                    'r' => sb.push('\r'),
                    't' => sb.push('\t'),
                    '0' => sb.push('\0'),
                    '\\' => sb.push('\\'),
                    '"' => sb.push('"'),
                    '\'' => sb.push('\''),
                    other => sb.push(other),
                }
            } else if c == '\n' {
                self.line += 1;
                self.column = 1;
                sb.push('\n');
            } else {
                sb.push(c);
            }
        }
        if self.is_at_end() {
            return Err(CompileError::new(
                "Unterminated string literal",
                Some(&self.file_name),
                start_line,
                start_col,
            ));
        }
        self.advance(); // closing quote
        Ok(Token::with_literal(
            TokenType::String,
            sb.clone(),
            start_line,
            start_col,
            Literal::Str(sb),
        ))
    }

    fn scan_number(&mut self, start_line: usize, start_col: usize) -> Result<Token, CompileError> {
        let mut sb = String::new();
        if self.peek() == '-' {
            sb.push(self.advance());
        }

        if self.peek() == '0' && matches!(self.peek_next(), 'x' | 'X') {
            sb.push(self.advance());
            sb.push(self.advance());
            while !self.is_at_end() && self.peek().is_ascii_hexdigit() {
                sb.push(self.advance());
            }
            let val = i64::from_str_radix(&sb[2..], 16).map_err(|_| {
                CompileError::new(
                    format!("Invalid hex number: {}", sb),
                    Some(&self.file_name),
                    start_line,
                    start_col,
                )
            })? as i32;
            return Ok(Token::with_literal(
                TokenType::Number,
                sb,
                start_line,
                start_col,
                Literal::Int(val),
            ));
        }

        if self.peek() == '0' && matches!(self.peek_next(), 'b' | 'B') {
            sb.push(self.advance());
            sb.push(self.advance());
            while !self.is_at_end() && matches!(self.peek(), '0' | '1') {
                sb.push(self.advance());
            }
            let val = u32::from_str_radix(&sb[2..], 2).map_err(|_| {
                CompileError::new(
                    format!("Invalid binary number: {}", sb),
                    Some(&self.file_name),
                    start_line,
                    start_col,
                )
            })? as i32;
            return Ok(Token::with_literal(
                TokenType::Number,
                sb,
                start_line,
                start_col,
                Literal::Int(val),
            ));
        }

        while !self.is_at_end() && self.peek().is_ascii_digit() {
            sb.push(self.advance());
        }
        let val = sb.parse::<i64>().map_err(|_| {
            CompileError::new(
                format!("Invalid decimal number: {}", sb),
                Some(&self.file_name),
                start_line,
                start_col,
            )
        })? as i32;
        Ok(Token::with_literal(
            TokenType::Number,
            sb,
            start_line,
            start_col,
            Literal::Int(val),
        ))
    }

    fn should_lex_negative_number(tokens: &[Token]) -> bool {
        let Some(last) = tokens.last() else {
            return true;
        };
        matches!(
            last.kind,
            TokenType::Assign
                | TokenType::PlusAssign
                | TokenType::MinusAssign
                | TokenType::StarAssign
                | TokenType::SlashAssign
                | TokenType::Comma
                | TokenType::LParen
                | TokenType::LBracket
                | TokenType::LBrace
                | TokenType::Colon
                | TokenType::KeywordReturn
                | TokenType::Eq
                | TokenType::Neq
                | TokenType::Lt
                | TokenType::Lte
                | TokenType::Gt
                | TokenType::Gte
        )
    }

    fn scan_identifier(&mut self, start_line: usize, start_col: usize) -> Token {
        let mut text = String::new();
        while !self.is_at_end() && Self::is_identifier_part(self.peek()) {
            text.push(self.advance());
        }
        let kind = match text.as_str() {
            "global" => TokenType::KeywordGlobal,
            "local" => TokenType::KeywordLocal,
            "func" | "function" => TokenType::KeywordFunc,
            "end" => TokenType::KeywordEnd,
            "return" => TokenType::KeywordReturn,
            "class" => TokenType::KeywordClass,
            "new" => TokenType::KeywordNew,
            "this" => TokenType::KeywordThis,
            "self" => TokenType::KeywordSelf,
            "import" => TokenType::KeywordImport,
            "from" => TokenType::KeywordFrom,
            "if" => TokenType::KeywordIf,
            "then" => TokenType::KeywordThen,
            "elseif" => TokenType::KeywordElseIf,
            "else" => TokenType::KeywordElse,
            "while" => TokenType::KeywordWhile,
            "do" => TokenType::KeywordDo,
            "for" => TokenType::KeywordFor,
            "in" => TokenType::KeywordIn,
            "break" => TokenType::KeywordBreak,
            "continue" => TokenType::KeywordContinue,
            "true" => TokenType::KeywordTrue,
            "false" => TokenType::KeywordFalse,
            "nil" | "null" | "none" => TokenType::KeywordNil,
            "and" => TokenType::KeywordAnd,
            "or" => TokenType::KeywordOr,
            "not" => TokenType::KeywordNot,
            _ => TokenType::Identifier,
        };
        let literal = match kind {
            TokenType::KeywordTrue => Literal::Bool(true),
            TokenType::KeywordFalse => Literal::Bool(false),
            _ => Literal::None,
        };
        Token::with_literal(kind, text, start_line, start_col, literal)
    }

    fn skip_whitespace_and_comments(&mut self) {
        while !self.is_at_end() {
            let c = self.peek();
            if matches!(c, ' ' | '\t' | '\r') {
                self.advance();
            } else if c == '\n' {
                self.advance();
                self.line += 1;
                self.column = 1;
            } else if c == '-' && self.peek_next() == '-' {
                self.advance();
                self.advance();
                if self.peek() == '[' && self.peek_next() == '[' {
                    self.advance();
                    self.advance();
                    while !self.is_at_end() && !(self.peek() == ']' && self.peek_next() == ']') {
                        if self.peek() == '\n' {
                            self.line += 1;
                            self.column = 1;
                        }
                        self.advance();
                    }
                    if !self.is_at_end() {
                        self.advance();
                        self.advance();
                    }
                } else {
                    while !self.is_at_end() && self.peek() != '\n' {
                        self.advance();
                    }
                }
            } else if c == '#' {
                while !self.is_at_end() && self.peek() != '\n' {
                    self.advance();
                }
            } else if c == '/' && self.peek_next() == '/' {
                self.advance();
                self.advance();
                while !self.is_at_end() && self.peek() != '\n' {
                    self.advance();
                }
            } else if c == '/' && self.peek_next() == '*' {
                self.advance();
                self.advance();
                while !self.is_at_end() && !(self.peek() == '*' && self.peek_next() == '/') {
                    if self.peek() == '\n' {
                        self.line += 1;
                        self.column = 1;
                    }
                    self.advance();
                }
                if !self.is_at_end() {
                    self.advance();
                    self.advance();
                }
            } else {
                break;
            }
        }
    }

    fn is_at_end(&self) -> bool {
        self.index >= self.chars.len()
    }

    fn peek(&self) -> char {
        self.chars.get(self.index).copied().unwrap_or('\0')
    }

    fn peek_next(&self) -> char {
        self.chars.get(self.index + 1).copied().unwrap_or('\0')
    }

    fn advance(&mut self) -> char {
        if self.is_at_end() {
            return '\0';
        }
        let c = self.chars[self.index];
        self.index += 1;
        self.column += 1;
        c
    }

    fn match_char(&mut self, expected: char) -> bool {
        if self.is_at_end() || self.chars[self.index] != expected {
            return false;
        }
        self.index += 1;
        self.column += 1;
        true
    }

    fn is_digit_at(&self, idx: usize) -> bool {
        self.chars.get(idx).map(|c| c.is_ascii_digit()).unwrap_or(false)
    }

    fn is_identifier_start(c: char) -> bool {
        c.is_alphabetic() || c == '_'
    }

    fn is_identifier_part(c: char) -> bool {
        c.is_alphanumeric() || c == '_'
    }
}
