package com.gamma.asn.schema;

/** Grammar-file error with the exact line/column — the parser fails loudly, never silently. */
public class Asn1ParseException extends RuntimeException {

    public Asn1ParseException(int line, int col, String message) {
        super("line " + line + ":" + col + ": " + message);
    }

    public Asn1ParseException(String message) {
        super(message);
    }
}
