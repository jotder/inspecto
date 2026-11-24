package com.gamma.asn.schema;

/** Grammar-file error with the exact line/column — the parser fails loudly, never silently. */
public class Asn1ParseException extends RuntimeException {

    private final int line;
    private final int col;

    public Asn1ParseException(int line, int col, String message) {
        super("line " + line + ":" + col + ": " + message);
        this.line = line;
        this.col = col;
    }

    public Asn1ParseException(String message) {
        super(message);
        this.line = 0;
        this.col = 0;
    }

    public int line() {
        return line;
    }

    public int col() {
        return col;
    }
}
