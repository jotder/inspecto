package com.gamma.asn.core;

/** Structural BER error, always reported with the absolute file offset where it was detected. */
public class BerParseException extends RuntimeException {

    private final long offset;

    public BerParseException(long offset, String message) {
        super(message + " (at offset " + offset + ")");
        this.offset = offset;
    }

    public long offset() {
        return offset;
    }
}
