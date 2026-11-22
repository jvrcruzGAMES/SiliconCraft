package games.jvrcruz.siliconcraft.compiler.n2;

public record N2Token(N2TokenType type, String text, int line, int column, Object literal) {
    public N2Token(N2TokenType type, String text, int line, int column) {
        this(type, text, line, column, null);
    }

    @Override
    public String toString() {
        return type + "(" + text + ") at " + line + ":" + column;
    }
}
