package com.gamma.skybase.decoder.asn3;

interface ByteSource {
    int read(); // read 1 byte

    void skip(long n); // move forward

    long position(); // current offset

    long limit(); // total size

    byte[] readBytes(int len); // read block

    boolean hasRemaining();
}
