package com.gamma.asn.core;

final class HeapSource implements ByteSource {

    private final byte[] bytes;

    HeapSource(byte[] bytes) {
        this.bytes = bytes;
    }

    @Override
    public long size() {
        return bytes.length;
    }

    @Override
    public int byteAt(long offset) {
        return bytes[Math.toIntExact(offset)] & 0xFF;
    }

    @Override
    public void copyTo(long offset, byte[] dst, int dstOffset, int length) {
        System.arraycopy(bytes, Math.toIntExact(offset), dst, dstOffset, length);
    }
}
