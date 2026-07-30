package com.gamma.skybase.decoder.asn3;

public class ASNTag {

    private final int tagClass;
    private final boolean constructed;
    private final int tagNumber;

    public ASNTag(int tagClass, boolean constructed, int tagNumber) {
        this.tagClass = tagClass;
        this.constructed = constructed;
        this.tagNumber = tagNumber;
    }

    public boolean isConstructed() {
        return constructed;
    }

    public int getTagNumber() {
        return tagNumber;
    }

    @Override
    public String toString() {
        return "no: " + tagNumber + ", const: " + constructed + ", class: " + tagClass;
    }
}

