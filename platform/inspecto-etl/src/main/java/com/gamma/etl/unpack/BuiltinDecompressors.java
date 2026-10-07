package com.gamma.etl.unpack;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.z.ZCompressorInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * The zero-new-dependency {@link DecompressorPlugin.Kind#STREAM} built-ins, registered in
 * {@code META-INF/services} like any other plugin — the resolver treats built-in and dropped-in
 * identically. xz and zstd are deliberately absent: their codecs (org.tukaani:xz, zstd-jni) are not
 * on this project's classpath, and DuckDB reads {@code .zst} natively anyway — each is one plugin
 * jar away, which is the point of the SPI.
 */
public final class BuiltinDecompressors {

    private BuiltinDecompressors() {}

    /** {@code .gz} — JDK inflater; magic {@code 1f 8b}. */
    public static final class Gzip extends StreamDecompressorPlugin {
        public Gzip() { super("gzip", ".gz", new byte[] {(byte) 0x1f, (byte) 0x8b}); }
        @Override protected InputStream decode(InputStream raw) throws IOException {
            return new GZIPInputStream(raw);
        }
    }

    /** {@code .bz2} — commons-compress; magic {@code BZh}. */
    public static final class Bzip2 extends StreamDecompressorPlugin {
        public Bzip2() { super("bzip2", ".bz2", new byte[] {'B', 'Z', 'h'}); }
        @Override protected InputStream decode(InputStream raw) throws IOException {
            return new BZip2CompressorInputStream(raw);
        }
    }

    /** {@code .Z} — Unix compress (LZW), commons-compress; magic {@code 1f 9d}. */
    public static final class LzwZ extends StreamDecompressorPlugin {
        public LzwZ() { super("compress-z", ".z", new byte[] {(byte) 0x1f, (byte) 0x9d}); }
        @Override protected InputStream decode(InputStream raw) throws IOException {
            return new ZCompressorInputStream(raw);
        }
    }
}
