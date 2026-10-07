package com.gamma.etl.unpack;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Base for the 1→1 {@link DecompressorPlugin.Kind#STREAM} built-ins: suffix + magic-number match, and
 * a shared copy loop that enforces {@link UnpackLimits#maxEntryBytes()} and
 * {@link UnpackLimits#maxRatio()} while writing — the caps trip DURING the copy, never after the disk
 * is already full.
 *
 * <p>The output filename is the source name with this plugin's suffix stripped (once):
 * {@code feed.csv.gz → feed.csv}, {@code data.Z → data}. A name that somehow lacks the suffix keeps
 * its name with {@code .out} appended rather than colliding with the source.
 */
abstract class StreamDecompressorPlugin implements DecompressorPlugin {

    private final String id;
    private final String suffix;
    private final byte[] magic;

    StreamDecompressorPlugin(String id, String suffix, byte[] magic) {
        this.id = id;
        this.suffix = suffix;
        this.magic = magic;
    }

    /** Open the decoding stream over the raw compressed bytes. */
    protected abstract InputStream decode(InputStream raw) throws IOException;

    @Override public String id() { return id; }
    @Override public List<String> suffixes() { return List.of(suffix); }
    @Override public Kind kind() { return Kind.STREAM; }

    @Override
    public boolean supports(String fileName, byte[] head) {
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(suffix)) return false;
        if (head.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) if (head[i] != magic[i]) return false;
        return true;
    }

    @Override
    public List<Path> expand(Path source, Path workDir, UnpackLimits limits) throws IOException {
        String name = source.getFileName().toString();
        String outName = name.toLowerCase(Locale.ROOT).endsWith(suffix)
                ? name.substring(0, name.length() - suffix.length())
                : name + ".out";
        Path out = workDir.resolve(outName);
        long in = Files.size(source);
        long written = 0;
        try (InputStream is = decode(Files.newInputStream(source));
             OutputStream os = Files.newOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) {
                written += n;
                if (written > limits.maxEntryBytes())
                    throw new IOException(id + ": decompressed output exceeds max_entry_bytes ("
                            + limits.maxEntryBytes() + "): " + name);
                if (written > limits.maxTotalBytes())
                    throw new IOException(id + ": decompressed output exceeds max_total_bytes ("
                            + limits.maxTotalBytes() + "): " + name);
                if (limits.maxRatio() > 0 && in > 0 && written > in * limits.maxRatio())
                    throw new IOException(id + ": decompression ratio exceeds max_ratio ("
                            + (long) limits.maxRatio() + ":1): " + name);
                os.write(buf, 0, n);
            }
        } catch (IOException e) {
            Files.deleteIfExists(out);   // never leave a partial expansion behind
            throw e;
        }
        return List.of(out);
    }
}
