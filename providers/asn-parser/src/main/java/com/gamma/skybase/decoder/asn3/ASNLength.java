package com.gamma.skybase.decoder.asn3;

public class ASNLength {

    private final int length;
    private final boolean indefinite;

    private ASNLength(int length, boolean indefinite) {
        this.length = length;
        this.indefinite = indefinite;
    }

    public static ASNLength definite(int len) {
        return new ASNLength(len, false);
    }

    public static ASNLength indefinite() {
        return new ASNLength(-1, true);
    }

    public boolean isIndefinite() {
        return indefinite;
    }

    public int getLength() {
        return length;
    }
}

