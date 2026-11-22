package games.jvrcruz.siliconcraft.compiler.n2;

public class N2CompileException extends RuntimeException {
    private final int line;
    private final int column;
    private final String file;

    public N2CompileException(String message, int line, int column) {
        this(message, null, line, column);
    }

    public N2CompileException(String message, String file, int line, int column) {
        super(formatMessage(message, file, line, column));
        this.line = line;
        this.column = column;
        this.file = file;
    }

    private static String formatMessage(String message, String file, int line, int column) {
        String loc = (file != null && !file.isEmpty()) ? (file + ":" + line + ":" + column) : ("Line " + line + ":" + column);
        return loc + ": " + message;
    }

    public int getLine() { return line; }
    public int getColumn() { return column; }
    public String getFile() { return file; }
}
