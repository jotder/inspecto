package com.gamma.asn.schema.ast;

/** X.680 builtin types the CDR grammars use, with their UNIVERSAL tag numbers. */
public enum BuiltinKind {
    BOOLEAN(1, false),
    INTEGER(2, false),
    BIT_STRING(3, false),
    OCTET_STRING(4, false),
    NULL(5, false),
    OBJECT_IDENTIFIER(6, false),
    REAL(9, false),
    ENUMERATED(10, false),
    UTF8_STRING(12, false),
    SEQUENCE(16, true),
    SET(17, true),
    NUMERIC_STRING(18, false),
    PRINTABLE_STRING(19, false),
    TELETEX_STRING(20, false),
    VIDEOTEX_STRING(21, false),
    IA5_STRING(22, false),
    UTC_TIME(23, false),
    GENERALIZED_TIME(24, false),
    GRAPHIC_STRING(25, false),
    VISIBLE_STRING(26, false),
    GENERAL_STRING(27, false),
    UNIVERSAL_STRING(28, false),
    BMP_STRING(30, false),
    /** ANY / open type: matches whatever tag arrives. */
    ANY(-1, false);

    private final int universalTag;
    private final boolean constructed;

    BuiltinKind(int universalTag, boolean constructed) {
        this.universalTag = universalTag;
        this.constructed = constructed;
    }

    public int universalTag() {
        return universalTag;
    }

    public boolean constructed() {
        return constructed;
    }
}
