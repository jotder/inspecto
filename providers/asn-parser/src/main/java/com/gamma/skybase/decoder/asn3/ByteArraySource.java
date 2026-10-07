package com.gamma.skybase.decoder.asn3;

public class ByteArraySource implements ByteSource {

    private final byte[] buffer;
    private int pos = 0;

    public ByteArraySource(byte[] data) {
        this.buffer = data;
    }

    public int read() {
        return buffer[pos++] & 0xFF;
    }

    public void skip(long n) {
        pos += n;
    }

    public long position() {
        return pos;
    }

    public long limit() {
        return buffer.length;
    }

    public boolean hasRemaining() {
        return pos < buffer.length;
    }

    public byte[] readBytes(int len) {
        byte[] out = new byte[len];
        System.arraycopy(buffer, pos, out, 0, len);
        pos += len;
        return out;
    }
}
