package com.gamma.asn.core;

public enum TagClass {
    UNIVERSAL(0),
    APPLICATION(1),
    CONTEXT(2),
    PRIVATE(3);

    private final int bits;

    TagClass(int bits) {
        this.bits = bits;
    }

    public int bits() {
        return bits;
    }

    public static TagClass fromBits(int twoBits) {
        return switch (twoBits & 0x3) {
            case 0 -> UNIVERSAL;
            case 1 -> APPLICATION;
            case 2 -> CONTEXT;
            default -> PRIVATE;
        };
    }
}
