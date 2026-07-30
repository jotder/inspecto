package com.gamma.decoder.asn2.reader;

public enum TagClass {
    UNIVERSAL(0x00),
    APPLICATION(0x40),
    CONTEXT(0x80),
    PRIVATE(0xC0);

    private final int value;

    TagClass(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static TagClass fromValue(int value) {
        for (TagClass c : values()) {
            if (c.value == value) {
                return c;
            }
        }
        throw new IllegalArgumentException("Invalid TagClass value: " + value);
    }
}
