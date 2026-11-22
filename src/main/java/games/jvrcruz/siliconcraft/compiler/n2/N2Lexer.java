package games.jvrcruz.siliconcraft.compiler.n2;

import java.util.ArrayList;
import java.util.List;

public class N2Lexer {
    private final String source;
    private final int length;
    private int index = 0;
    private int line = 1;
    private int column = 1;

    private final String fileName;

    public N2Lexer(String source) {
        this(source, null);
    }

    public N2Lexer(String source, String fileName) {
        this.source = source != null ? source : "";
        this.length = this.source.length();
        this.fileName = fileName != null ? fileName : "source.n2";
    }

    public List<N2Token> tokenize() {
        List<N2Token> tokens = new ArrayList<>();
        while (!isAtEnd()) {
            skipWhitespaceAndComments();
            if (isAtEnd()) break;

            int startLine = line;
            int startCol = column;
            char c = peek();

            // Library path syntax: <lib/...>
            if (c == '<' && matchLibPath(tokens)) {
                tokens.add(scanLibPath(startLine, startCol));
                continue;
            }

            // String literals
            if (c == '"' || c == '\'') {
                tokens.add(scanString(c, startLine, startCol));
                continue;
            }

            // Number literals
            if (Character.isDigit(c) || (c == '-' && isDigitAt(index + 1) && shouldLexNegativeNumber(tokens))) {
                tokens.add(scanNumber(startLine, startCol));
                continue;
            }

            // Identifiers and keywords
            if (isIdentifierStart(c)) {
                tokens.add(scanIdentifier(startLine, startCol));
                continue;
            }

            // Operators and punctuation
            advance();
            switch (c) {
                case '(': tokens.add(new N2Token(N2TokenType.LPAREN, "(", startLine, startCol)); break;
                case ')': tokens.add(new N2Token(N2TokenType.RPAREN, ")", startLine, startCol)); break;
                case '[': tokens.add(new N2Token(N2TokenType.LBRACKET, "[", startLine, startCol)); break;
                case ']': tokens.add(new N2Token(N2TokenType.RBRACKET, "]", startLine, startCol)); break;
                case '{': tokens.add(new N2Token(N2TokenType.LBRACE, "{", startLine, startCol)); break;
                case '}': tokens.add(new N2Token(N2TokenType.RBRACE, "}", startLine, startCol)); break;
                case ',': tokens.add(new N2Token(N2TokenType.COMMA, ",", startLine, startCol)); break;
                case '.': tokens.add(new N2Token(N2TokenType.DOT, ".", startLine, startCol)); break;
                case ':': tokens.add(new N2Token(N2TokenType.COLON, ":", startLine, startCol)); break;
                case ';': tokens.add(new N2Token(N2TokenType.SEMICOLON, ";", startLine, startCol)); break;
                case '#': tokens.add(new N2Token(N2TokenType.HASH, "#", startLine, startCol)); break;
                case '~':
                    if (match('=')) {
                        tokens.add(new N2Token(N2TokenType.NEQ, "~=", startLine, startCol));
                    } else {
                        tokens.add(new N2Token(N2TokenType.TILDE, "~", startLine, startCol));
                    }
                    break;
                case '+':
                    if (match('=')) {
                        tokens.add(new N2Token(N2TokenType.PLUS_ASSIGN, "+=", startLine, startCol));
                    } else {
                        tokens.add(new N2Token(N2TokenType.PLUS, "+", startLine, startCol));
                    }
                    break;
                case '-':
                    if (match('=')) {
                        tokens.add(new N2Token(N2TokenType.MINUS_ASSIGN, "-=", startLine, startCol));
                    } else {
                        tokens.add(new N2Token(N2TokenType.MINUS, "-", startLine, startCol));
                    }
                    break;
                case '*':
                    if (match('=')) {
                        tokens.add(new N2Token(N2TokenType.STAR_ASSIGN, "*=", startLine, startCol));
                    } else {
                        tokens.add(new N2Token(N2TokenType.STAR, "*", startLine, startCol));
                    }
                    break;
                case '/':
                    if (match('=')) {
                        tokens.add(new N2Token(N2TokenType.SLASH_ASSIGN, "/=", startLine, startCol));
                    } else {
                        tokens.add(new N2Token(N2TokenType.SLASH, "/", startLine, startCol));
                    }
                    break;
                case '%': tokens.add(new N2Token(N2TokenType.PERCENT, "%", startLine, startCol)); break;
                case '=':
                    if (match('=')) {
                        tokens.add(new N2Token(N2TokenType.EQ, "==", startLine, startCol));
                    } else {
                        tokens.add(new N2Token(N2TokenType.ASSIGN, "=", startLine, startCol));
                    }
                    break;
                case '!':
                    if (match('=')) {
                        tokens.add(new N2Token(N2TokenType.NEQ, "!=", startLine, startCol));
                    } else {
                        throw new N2CompileException("Unexpected character '!' (did you mean '!=' or 'not'?)", startLine, startCol);
                    }
                    break;
                case '<':
                    if (match('<')) {
                        tokens.add(new N2Token(N2TokenType.SHL, "<<", startLine, startCol));
                    } else if (match('=')) {
                        tokens.add(new N2Token(N2TokenType.LTE, "<=", startLine, startCol));
                    } else {
                        tokens.add(new N2Token(N2TokenType.LT, "<", startLine, startCol));
                    }
                    break;
                case '>':
                    if (match('>')) {
                        tokens.add(new N2Token(N2TokenType.SHR, ">>", startLine, startCol));
                    } else if (match('=')) {
                        tokens.add(new N2Token(N2TokenType.GTE, ">=", startLine, startCol));
                    } else {
                        tokens.add(new N2Token(N2TokenType.GT, ">", startLine, startCol));
                    }
                    break;
                case '&': tokens.add(new N2Token(N2TokenType.AMP, "&", startLine, startCol)); break;
                case '|': tokens.add(new N2Token(N2TokenType.PIPE, "|", startLine, startCol)); break;
                case '^': tokens.add(new N2Token(N2TokenType.CARET, "^", startLine, startCol)); break;
                default:
                    throw new N2CompileException("Unexpected character: '" + c + "'", startLine, startCol);
            }
        }

        tokens.add(new N2Token(N2TokenType.EOF, "", line, column));
        return tokens;
    }

    private boolean matchLibPath(List<N2Token> tokens) {
        // If the last non-empty token was 'import' or 'from', or if starts with <lib/
        if (!tokens.isEmpty()) {
            N2Token last = tokens.get(tokens.size() - 1);
            if (last.type() == N2TokenType.KEYWORD_IMPORT || last.type() == N2TokenType.KEYWORD_FROM) {
                return true;
            }
        }
        if (source.startsWith("<lib/", index)) {
            return true;
        }
        return false;
    }

    private N2Token scanLibPath(int startLine, int startCol) {
        advance(); // consume '<'
        StringBuilder sb = new StringBuilder();
        while (!isAtEnd() && peek() != '>') {
            if (peek() == '\n') {
                throw new N2CompileException("Unterminated library path '<...>' before newline", startLine, startCol);
            }
            sb.append(advance());
        }
        if (isAtEnd()) {
            throw new N2CompileException("Unterminated library path '<...>'", startLine, startCol);
        }
        advance(); // consume '>'
        String path = sb.toString().trim();
        return new N2Token(N2TokenType.LIB_PATH, path, startLine, startCol, path);
    }

    private N2Token scanString(char quote, int startLine, int startCol) {
        advance(); // consume opening quote
        StringBuilder sb = new StringBuilder();
        while (!isAtEnd() && peek() != quote) {
            char c = advance();
            if (c == '\\') {
                if (isAtEnd()) throw new N2CompileException("Unterminated escape sequence in string", startLine, startCol);
                char esc = advance();
                switch (esc) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '0': sb.append('\0'); break;
                    case '\\': sb.append('\\'); break;
                    case '"': sb.append('"'); break;
                    case '\'': sb.append('\''); break;
                    default: sb.append(esc); break;
                }
            } else if (c == '\n') {
                line++;
                column = 1;
                sb.append('\n');
            } else {
                sb.append(c);
            }
        }
        if (isAtEnd()) {
            throw new N2CompileException("Unterminated string literal", startLine, startCol);
        }
        advance(); // consume closing quote
        String val = sb.toString();
        return new N2Token(N2TokenType.STRING, val, startLine, startCol, val);
    }

    private N2Token scanNumber(int startLine, int startCol) {
        StringBuilder sb = new StringBuilder();
        if (peek() == '-') {
            sb.append(advance());
        }

        if (peek() == '0' && (peekNext() == 'x' || peekNext() == 'X')) {
            sb.append(advance()); // '0'
            sb.append(advance()); // 'x'
            while (!isAtEnd() && isHexDigit(peek())) {
                sb.append(advance());
            }
            String text = sb.toString();
            try {
                long parsed = Long.parseLong(text.substring(2), 16);
                int val = (int) parsed;
                return new N2Token(N2TokenType.NUMBER, text, startLine, startCol, val);
            } catch (Exception e) {
                throw new N2CompileException("Invalid hex number: " + text, startLine, startCol);
            }
        }

        if (peek() == '0' && (peekNext() == 'b' || peekNext() == 'B')) {
            sb.append(advance()); // '0'
            sb.append(advance()); // 'b'
            while (!isAtEnd() && (peek() == '0' || peek() == '1')) {
                sb.append(advance());
            }
            String text = sb.toString();
            try {
                int val = Integer.parseInt(text.substring(2), 2);
                return new N2Token(N2TokenType.NUMBER, text, startLine, startCol, val);
            } catch (Exception e) {
                throw new N2CompileException("Invalid binary number: " + text, startLine, startCol);
            }
        }

        while (!isAtEnd() && Character.isDigit(peek())) {
            sb.append(advance());
        }
        String text = sb.toString();
        try {
            int val = (int) Long.parseLong(text);
            return new N2Token(N2TokenType.NUMBER, text, startLine, startCol, val);
        } catch (Exception e) {
            throw new N2CompileException("Invalid decimal number: " + text, startLine, startCol);
        }
    }

    private boolean shouldLexNegativeNumber(List<N2Token> tokens) {
        if (tokens.isEmpty()) return true;
        N2TokenType last = tokens.get(tokens.size() - 1).type();
        return last == N2TokenType.ASSIGN || last == N2TokenType.PLUS_ASSIGN ||
               last == N2TokenType.MINUS_ASSIGN || last == N2TokenType.STAR_ASSIGN ||
               last == N2TokenType.SLASH_ASSIGN || last == N2TokenType.COMMA ||
               last == N2TokenType.LPAREN || last == N2TokenType.LBRACKET ||
               last == N2TokenType.LBRACE || last == N2TokenType.COLON ||
               last == N2TokenType.KEYWORD_RETURN || last == N2TokenType.EQ ||
               last == N2TokenType.NEQ || last == N2TokenType.LT ||
               last == N2TokenType.LTE || last == N2TokenType.GT ||
               last == N2TokenType.GTE;
    }

    private N2Token scanIdentifier(int startLine, int startCol) {
        StringBuilder sb = new StringBuilder();
        while (!isAtEnd() && isIdentifierPart(peek())) {
            sb.append(advance());
        }
        String text = sb.toString();
        N2TokenType type = switch (text) {
            case "global" -> N2TokenType.KEYWORD_GLOBAL;
            case "local" -> N2TokenType.KEYWORD_LOCAL;
            case "func", "function" -> N2TokenType.KEYWORD_FUNC;
            case "end" -> N2TokenType.KEYWORD_END;
            case "return" -> N2TokenType.KEYWORD_RETURN;
            case "class" -> N2TokenType.KEYWORD_CLASS;
            case "new" -> N2TokenType.KEYWORD_NEW;
            case "this" -> N2TokenType.KEYWORD_THIS;
            case "self" -> N2TokenType.KEYWORD_SELF;
            case "import" -> N2TokenType.KEYWORD_IMPORT;
            case "from" -> N2TokenType.KEYWORD_FROM;
            case "if" -> N2TokenType.KEYWORD_IF;
            case "then" -> N2TokenType.KEYWORD_THEN;
            case "elseif" -> N2TokenType.KEYWORD_ELSEIF;
            case "else" -> N2TokenType.KEYWORD_ELSE;
            case "while" -> N2TokenType.KEYWORD_WHILE;
            case "do" -> N2TokenType.KEYWORD_DO;
            case "for" -> N2TokenType.KEYWORD_FOR;
            case "in" -> N2TokenType.KEYWORD_IN;
            case "break" -> N2TokenType.KEYWORD_BREAK;
            case "continue" -> N2TokenType.KEYWORD_CONTINUE;
            case "true" -> N2TokenType.KEYWORD_TRUE;
            case "false" -> N2TokenType.KEYWORD_FALSE;
            case "nil", "null", "none" -> N2TokenType.KEYWORD_NIL;
            case "and" -> N2TokenType.KEYWORD_AND;
            case "or" -> N2TokenType.KEYWORD_OR;
            case "not" -> N2TokenType.KEYWORD_NOT;
            default -> N2TokenType.IDENTIFIER;
        };

        Object lit = null;
        if (type == N2TokenType.KEYWORD_TRUE) lit = Boolean.TRUE;
        if (type == N2TokenType.KEYWORD_FALSE) lit = Boolean.FALSE;

        return new N2Token(type, text, startLine, startCol, lit);
    }

    private void skipWhitespaceAndComments() {
        while (!isAtEnd()) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\r') {
                advance();
            } else if (c == '\n') {
                advance();
                line++;
                column = 1;
            } else if (c == '-' && peekNext() == '-') {
                // Lua style comment: -- or --[[ ... ]]
                advance();
                advance();
                if (peek() == '[' && peekNext() == '[') {
                    advance();
                    advance();
                    while (!isAtEnd() && !(peek() == ']' && peekNext() == ']')) {
                        if (peek() == '\n') {
                            line++;
                            column = 1;
                        }
                        advance();
                    }
                    if (!isAtEnd()) {
                        advance();
                        advance();
                    }
                } else {
                    while (!isAtEnd() && peek() != '\n') {
                        advance();
                    }
                }
            } else if (c == '#') {
                // Python style comment
                while (!isAtEnd() && peek() != '\n') {
                    advance();
                }
            } else if (c == '/' && peekNext() == '/') {
                // C style single line comment
                advance();
                advance();
                while (!isAtEnd() && peek() != '\n') {
                    advance();
                }
            } else if (c == '/' && peekNext() == '*') {
                // C style block comment
                advance();
                advance();
                while (!isAtEnd() && !(peek() == '*' && peekNext() == '/')) {
                    if (peek() == '\n') {
                        line++;
                        column = 1;
                    }
                    advance();
                }
                if (!isAtEnd()) {
                    advance();
                    advance();
                }
            } else {
                break;
            }
        }
    }

    private boolean isAtEnd() {
        return index >= length;
    }

    private char peek() {
        if (isAtEnd()) return '\0';
        return source.charAt(index);
    }

    private char peekNext() {
        if (index + 1 >= length) return '\0';
        return source.charAt(index + 1);
    }

    private char advance() {
        if (isAtEnd()) return '\0';
        char c = source.charAt(index++);
        column++;
        return c;
    }

    private boolean match(char expected) {
        if (isAtEnd() || source.charAt(index) != expected) return false;
        index++;
        column++;
        return true;
    }

    private boolean isDigitAt(int i) {
        if (i >= length) return false;
        return Character.isDigit(source.charAt(i));
    }

    private boolean isHexDigit(char c) {
        return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
