package com.gamma.etl.unpack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * The {@link DecompressorPlugin} resolver — the {@code CollectorConnectors.forConfig} house idiom:
 * a linear {@link ServiceLoader} scan, first match wins. Unlike the connector resolver there is no
 * fail-fast on "no match": a file no plugin claims is simply NOT compressed (or not supported) and
 * flows to the parsing engines unchanged, whose failure path already reports honestly.
 */
public final class Decompressors {

    /** How many leading bytes {@link DecompressorPlugin#supports} may inspect. */
    private static final int MAGIC_BYTES = 8;

    private Decompressors() {}

    /**
     * The discovered plugin claiming {@code file} (suffix AND magic), or empty.
     *
     * <p>⚠ Resolution is <b>longest-matching-suffix</b>, not first-match: {@code feed.tar.gz} is
     * claimed by both the tar.gz archive plugin and the plain gzip stream plugin (gzip magic, and
     * {@code .gz} IS a suffix of {@code .tar.gz}) — and expanding it as a stream would yield one
     * undifferentiated tar blob instead of its entries. Ordering the services file would also
     * work within one jar, but ServiceLoader's order across jars is unspecified, so a dropped-in
     * plugin must not be able to reorder this by accident.
     */
    public static Optional<DecompressorPlugin> forFile(Path file) throws IOException {
        String name = file.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        byte[] head = head(file);
        DecompressorPlugin best = null;
        int bestLen = -1;
        for (DecompressorPlugin p : ServiceLoader.load(DecompressorPlugin.class)) {
            if (!p.supports(name, head)) continue;
            int len = p.suffixes().stream()
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .filter(lower::endsWith)
                    .mapToInt(String::length).max().orElse(0);
            if (len > bestLen) { best = p; bestLen = len; }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Every suffix any discovered plugin owns, lower-case — the compression-suffix vocabulary
     * {@link LogicalNames} strips. Derived, never hand-mirrored (unpack-stage plan §2.3 rule 1).
     */
    public static List<String> knownSuffixes() {
        List<String> out = new ArrayList<>();
        for (DecompressorPlugin p : ServiceLoader.load(DecompressorPlugin.class))
            for (String s : p.suffixes()) out.add(s.toLowerCase(Locale.ROOT));
        return out;
    }

    private static byte[] head(Path file) throws IOException {
        byte[] buf = new byte[MAGIC_BYTES];
        try (InputStream is = Files.newInputStream(file)) {
            int off = 0, n;
            while (off < buf.length && (n = is.read(buf, off, buf.length - off)) > 0) off += n;
            if (off == buf.length) return buf;
            byte[] short_ = new byte[off];
            System.arraycopy(buf, 0, short_, 0, off);
            return short_;
        }
    }
}
