package com.gamma.decoder.asn2.reader;

public enum UniversalTag {
    BOOLEAN(0x01),
    INTEGER(0x02),
    BIT_STRING(0x03),
    OCTET_STRING(0x04),
    NULL(0x05),
    OBJECT_IDENTIFIER(0x06),
    REAL(0x09),
    SEQUENCE(0x10),
    SET(0x11),
    NUMERIC_STRING(0x12),
    PRINTABLE_STRING(0x13),
    T61_STRING(0x14),
    VIDEOTEX_STRING(0x15),
    IA5_STRING(0x16),
    GRAPHIC_STRING(0x19),
    ISO646_STRING(0x1A),
    GENERAL_STRING(0x1B),
    UNIVERSAL_STRING(0x1C),
    BMP_STRING(0x1E),
    UTC_TIME(0x17),
    GENERALIZED_TIME(0x18);

    private final int value;

    UniversalTag(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static UniversalTag fromValue(int value) {
        for (UniversalTag t : values()) {
            if (t.value == value) {
                return t;
            }
        }
        throw new IllegalArgumentException("Invalid UniversalTag value: " + value);
    }
}
