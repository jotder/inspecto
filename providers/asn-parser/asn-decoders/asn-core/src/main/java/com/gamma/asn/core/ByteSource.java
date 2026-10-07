package com.gamma.asn.core;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;

/**
 * Random-access byte abstraction over decoder input. All reads are offset-based;
 * implementations never hand out per-level byte-array copies of nested content.
 */
public interface ByteSource extends AutoCloseable {

    /** Total number of readable bytes. */
    long size();

    /** Unsigned byte (0..255) at {@code offset}. */
    int byteAt(long offset);

    /** Copies {@code length} bytes starting at {@code offset} into {@code dst}. */
    void copyTo(long offset, byte[] dst, int dstOffset, int length);

    /** Convenience copy of a bounded slice (for decoded leaf values, never whole subtrees). */
    default byte[] bytes(long offset, int length) {
        byte[] out = new byte[length];
        copyTo(offset, out, 0, length);
        return out;
    }

    @Override
    default void close() {
        // heap sources have nothing to release
    }

    static ByteSource of(byte[] bytes) {
        return new HeapSource(bytes);
    }

    /** Memory-maps {@code file} via an FFM shared {@link Arena}; supports files larger than 2 GB. */
    static ByteSource map(Path file) throws IOException {
        return new MappedSource(file);
    }
}
