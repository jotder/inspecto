package com.gamma.la.api;

import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.entitystore.MaskTokens;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Entity masking for the STATELESS exploration reads — {@code /inv/projection}, {@code /neighbors}, {@code /multi},
 * {@code /traversal/recursive-paths}, {@code /pattern/*} and {@code /value-measures} — which have no Investigation
 * (DR-D2, operator 2026-10-10: <i>mask the same way everywhere</i>).
 *
 * <p><b>Rule</b> — the Space's {@code maskingMode}, judged from the read's bound Dataset and endpoint columns:
 * {@code none} shows raw; {@code all} masks every id the read returns; {@code typed} (the default) masks every id when a
 * bound endpoint column's classification is claimed by a masked Entity Type (the same
 * {@link EntityMasking#maskedColumns} an Investigation applies, so a read and the Investigation over the same columns
 * agree on WHETHER to mask). An Investigation additionally masks a seed's explicit {@code entityType}; a stateless read
 * carries none.
 *
 * <p><b>Alias</b> — {@code masked:<16 hex>} under the Space's ONE key (the identity fact log's {@code mask.key}, the key
 * an Entity List member uses), so an id has one alias across every exploration read and every Entity List.
 * An Investigation's key is its own (§3.4: unlinkability between Investigations), so a seeded entity gains a SECOND
 * alias there; the Working Set carries the exploration alias beside it ({@code exploreAlias}) so the two stay one entity
 * in the UI.
 *
 * <p><b>Resolution</b> — a client hands an alias back (an expand's {@code value}, a traversal's {@code startNode}, an
 * Investigation seed). It is resolved from {@link #BOOK}: the alias → raw pairs this server has minted for this Space,
 * kept in memory (bounded, least-recently-used out) and appended to the Space's book file in the secrets directory beside
 * the root ({@code .exploration-alias-book}, outside every exported tree; LA-EDITIONS-RESIDUALS-1 4c), so an alias
 * still resolves after a restart or an eviction: an in-memory miss scans that file, unless the
 * same alias already missed on a book of the same size ({@link #MISSES}). Nothing raw is ever sent to a
 * client. ⚠ An alias never minted, or whose book file was removed, is answered 422 {@code "alias not known"}.
 */
final class ExplorationMasking {

    private static final int BOOK_MAX = 500_000;
    private static final Map<String, String> BOOK = Collections.synchronizedMap(new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > BOOK_MAX;
        }
    });

    /**
     * Aliases a scan of the book did NOT find, with the book file's size at that scan: a repeated miss on an unchanged
     * book answers null without reading it again (an Investigation pseudonym or a stale alias misses as a normal path).
     * A changed size (a mint appended) invalidates the entry. Bounded, least-recently-used out.
     */
    private static final int MISSES_MAX = 100_000;
    private static final Map<String, Long> MISSES = Collections.synchronizedMap(new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MISSES_MAX;
        }
    });

    /** Book-file scans since start (test seam). */
    static final java.util.concurrent.atomic.AtomicInteger SCANS = new java.util.concurrent.atomic.AtomicInteger();

    /** Serialises appends to a book file (one lock for all Spaces: an append is one short write). */
    private static final Object FILE_LOCK = new Object();

    private final Path root;
    private final String mode;
    private final boolean active;
    private final String basis;
    private byte[] key;

    private ExplorationMasking(Path root, String mode, boolean active, String basis) {
        this.root = root;
        this.mode = mode;
        this.active = active;
        this.basis = basis;
    }

    /** The masking in force for a read over {@code dataset}; {@code roles} holds its {@code sourceCol}/{@code targetCol}. */
    static ExplorationMasking of(Path writeRoot, String dataset, Map<String, ?> roles) {
        LinkAnalysisSettings settings = LinkAnalysisSettings.forRoot(writeRoot);
        String mode = settings.effectiveMaskingMode();
        switch (mode) {
            case "none":
                return new ExplorationMasking(writeRoot, mode, false, "masking is off for this Space (maskingMode none)");
            case "all":
                return new ExplorationMasking(writeRoot, mode, true, "every entity id (maskingMode all)");
            default: {
                List<EntityTypes.EntityType> types = settings.effectiveEntityTypes();
                Map<String, String> cols = EntityMasking.maskedColumns(writeRoot, dataset, roles, types);
                return cols.isEmpty()
                        ? new ExplorationMasking(writeRoot, mode, false, "typed: no bound column of '" + dataset
                                + "' is classified as a masked Entity Type, so its ids are shown as they are")
                        : new ExplorationMasking(writeRoot, mode, true, "typed: bound column(s) " + List.copyOf(cols.keySet())
                                + " are classified as masked Entity Type(s) " + cols.values().stream().distinct().toList()
                                + ", so every entity id is masked");
            }
        }
    }

    /** An ACTIVE masking under the Space's key whatever the column rule says - for a read whose caller already holds
     *  aliases, or a classified non-endpoint column ({@code /inv/reference-rows}). */
    static ExplorationMasking forced(Path writeRoot, String mode, String basis) {
        return new ExplorationMasking(writeRoot, mode, true, basis);
    }

    boolean active() {
        return active;
    }

    /** The id as the caller sees it: its alias when masking is active, else unchanged. Null stays null. */
    String out(String raw) {
        if (!active || raw == null) return raw;
        String alias = MaskTokens.token(key(), raw);
        remember(root, alias, raw);
        return alias;
    }

    /** {@link #out} for a cell value that may not be a String. */
    Object out(Object raw) {
        return raw == null || !active ? raw : out(String.valueOf(raw));
    }

    /** The raw id behind an alias this Space served; anything else unchanged. 422 for an alias this server does not know. */
    String in(String id) {
        if (id == null || !id.startsWith(MaskTokens.TOKEN_PREFIX)) return id;
        String raw = lookup(root, id);
        if (raw == null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "alias '" + id + "' is not known to this server "
                    + "(it was never minted here, or its book was removed) - run the query again to mint it, then pick the node");
        return raw;
    }

    /** The raw id behind an alias served for this Space's exploration reads, or null. */
    static String lookup(Path writeRoot, String alias) {
        String raw = BOOK.get(writeRoot + "\u0000" + alias);
        if (raw != null) return raw;
        String k = writeRoot + "\u0000" + alias;
        Path f = bookFile(writeRoot);
        long size;
        try {
            size = Files.isRegularFile(f) ? Files.size(f) : -1;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Long missedAt = MISSES.get(k);
        if (missedAt != null && missedAt == size) return null;
        raw = size < 0 ? null : indexed(writeRoot, f, alias, size);
        if (raw != null) {
            BOOK.put(k, raw);
            MISSES.remove(k);
        } else {
            MISSES.put(k, size);
        }
        return raw;
    }

    /**
     * The exploration alias of {@code raw} under this Space's key - what a Working Set row carries beside its own. An alias handed
     * out is one the server can resolve: it enters the {@link #BOOK} like {@link #out} does, so the Neighbours list of an
     * Investigation entity (LA-EXPAND-PICK-NEIGHBOURS-1) can send it back without a prior exploration read.
     */
    static String alias(Path writeRoot, String raw) {
        String alias = MaskTokens.token(spaceKey(writeRoot), raw);
        remember(writeRoot, alias, raw);
        return alias;
    }

    /** Puts the pair in the book; a pair this process did not hold yet is appended to the Space's book file. */
    private static void remember(Path writeRoot, String alias, String raw) {
        if (BOOK.put(writeRoot + "\u0000" + alias, raw) != null) return;
        String line = alias + "\t" + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)) + "\n";
        Path f = bookFile(writeRoot);
        synchronized (FILE_LOCK) {
            appendLine(f, line);
        }
    }

    /**
     * Appends one line to the book with a single write on an APPEND channel, which the OS places at the end atomically
     * even when another process appends too (the in-process lock does not reach other processes). A torn last line
     * (no newline) is ended first, inside that same write, so it cannot swallow this one; the last-byte check is a
     * separate read, and a race there costs at most an empty line, which a scan skips.
     */
    static void appendLine(Path f, String line) {
        try {
            Files.createDirectories(f.getParent());
            if (!Files.exists(f)) createOwnerOnly(f);
            try (var r = Files.newByteChannel(f, StandardOpenOption.READ)) {
                long end = r.size();
                java.nio.ByteBuffer last = java.nio.ByteBuffer.allocate(1);
                if (end > 0 && r.position(end - 1).read(last) == 1 && last.get(0) != '\n') line = "\n" + line;
            }
            try (var w = Files.newByteChannel(f, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(java.nio.ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void createOwnerOnly(Path f) throws IOException {
        boolean posix = f.getFileSystem().supportedFileAttributeViews().contains("posix");
        try {
            if (posix) Files.createFile(f, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            else {
                Files.createFile(f);
                com.gamma.util.SpaceSecretKeys.ownerOnlyAcl(f);
            }
        } catch (FileAlreadyExistsException raced) {
            // another process created it first
        }
    }

    /**
     * The raw id behind {@code alias} in the Space's book file, or null. Read on every in-memory miss, so an alias evicted
     * from {@link #BOOK} resolves again and concurrent first lookups after a restart each find it. A line is believed only
     * when the alias really is the token of its raw value, so a line torn by a crash mid-append (whose truncated base64
     * may still decode, to a shorter raw) is skipped.
     */
    private static String scan(Path writeRoot, String alias) {
        Path f = bookFile(writeRoot);
        if (!Files.isRegularFile(f)) return null;
        SCANS.incrementAndGet();
        String prefix = alias + "\t";
        byte[] key = null;
        try (var lines = Files.lines(f, StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (!line.startsWith(prefix)) continue;
                String raw;
                try {
                    raw = new String(Base64.getDecoder().decode(line.substring(prefix.length())), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException torn) {
                    continue;
                }
                if (key == null) key = spaceKey(writeRoot);
                if (MaskTokens.token(key, raw).equals(alias)) return raw;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return null;
    }

    private static Path bookFile(Path writeRoot) {
        return com.gamma.util.SpaceSecretKeys.keyFile(writeRoot, ".exploration-alias-book");
    }

    /**
     * Book-file offset index, per book file: the line start of each alias (64 bits of its hex token, first line wins),
     * built by ONE pass over the file and then only extended over the bytes appended since, so resolving N evicted aliases
     * costs one pass plus N single-line reads instead of N half-file scans (measured 2026-10-20: 109 ms per alias on a
     * 1 M-line book, so a 1000-node graph after a restart took 110 s). Open addressing over two {@code long[]} (about 32
     * bytes per alias). A book of more than {@link #INDEX_MAX} lines stops being indexed and falls back to the file scan.
     */
    private static final int INDEX_MAX = 4_000_000;
    private static final Map<Path, Index> INDEXES = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class Index {
        long[] keys = new long[1 << 12];
        long[] offs = new long[1 << 12];
        int count;
        long scanned;      // the file is indexed up to here (just after the last newline read)
        boolean full;

        Index() {
            java.util.Arrays.fill(offs, -1);
        }

        private int slot(long key, int mask) {
            return (int) (key ^ (key >>> 32)) & mask;
        }

        long get(long key) {
            int mask = keys.length - 1;
            for (int i = slot(key, mask); offs[i] >= 0; i = (i + 1) & mask) if (keys[i] == key) return offs[i];
            return -1;
        }

        void putFirst(long key, long off) {
            if (count * 2 >= keys.length) grow();
            int mask = keys.length - 1;
            int i = slot(key, mask);
            for (; offs[i] >= 0; i = (i + 1) & mask) if (keys[i] == key) return;
            keys[i] = key;
            offs[i] = off;
            count++;
        }

        private void grow() {
            long[] k = keys, o = offs;
            keys = new long[k.length * 2];
            offs = new long[o.length * 2];
            java.util.Arrays.fill(offs, -1);
            count = 0;
            for (int i = 0; i < k.length; i++) if (o[i] >= 0) putFirst(k[i], o[i]);
        }

        void reset() {
            keys = new long[1 << 12];
            offs = new long[1 << 12];
            java.util.Arrays.fill(offs, -1);
            count = 0;
            scanned = 0;
            full = false;
        }

        /** Indexes the complete lines appended since the last pass. */
        void catchUp(Path f, long size) throws IOException {
            if (size < scanned) reset();
            if (size == scanned || full) return;
            SCANS.incrementAndGet();
            byte[] head = new byte[24];
            try (var ch = java.nio.channels.FileChannel.open(f, StandardOpenOption.READ)) {
                ch.position(scanned);
                var in = new java.io.BufferedInputStream(java.nio.channels.Channels.newInputStream(ch), 1 << 20);
                long pos = scanned, lineStart = scanned;
                int inLine = 0, b;
                while ((b = in.read()) >= 0) {
                    pos++;
                    if (b == '\n') {
                        if (inLine >= 24 && head[23] == '\t') {
                            try {
                                putFirst(Long.parseUnsignedLong(new String(head, 7, 16, StandardCharsets.ISO_8859_1), 16), lineStart);
                            } catch (NumberFormatException skip) {
                                // not a line of this book
                            }
                        }
                        scanned = pos;
                        lineStart = pos;
                        inLine = 0;
                        if (count >= INDEX_MAX) {
                            full = true;
                            return;
                        }
                    } else {
                        if (inLine < 24) head[inLine] = (byte) b;
                        inLine++;
                    }
                }
            }
        }

        /** The line at {@code off} without its newline. */
        static String lineAt(Path f, long off) throws IOException {
            try (var r = new java.io.RandomAccessFile(f.toFile(), "r")) {
                r.seek(off);
                var out = new java.io.ByteArrayOutputStream(96);
                byte[] buf = new byte[512];
                int n;
                outer:
                while ((n = r.read(buf)) > 0) {
                    for (int i = 0; i < n; i++) {
                        if (buf[i] == '\n') break outer;
                        out.write(buf[i]);
                    }
                }
                return out.toString(StandardCharsets.UTF_8);
            }
        }
    }

    /** The raw id behind {@code alias} via the offset index, or null; falls back to {@link #scan} when the index cannot answer. */
    private static String indexed(Path writeRoot, Path f, String alias, long size) {
        if (!alias.startsWith(MaskTokens.TOKEN_PREFIX) || alias.length() != MaskTokens.TOKEN_PREFIX.length() + 16)
            return scan(writeRoot, alias);
        long key;
        try {
            key = Long.parseUnsignedLong(alias.substring(MaskTokens.TOKEN_PREFIX.length()), 16);
        } catch (NumberFormatException e) {
            return scan(writeRoot, alias);
        }
        Index ix = INDEXES.computeIfAbsent(f, x -> new Index());
        try {
            String line;
            synchronized (ix) {
                ix.catchUp(f, size);
                if (ix.full) return scan(writeRoot, alias);
                long off = ix.get(key);
                if (off < 0) return null;
                line = Index.lineAt(f, off);
            }
            String raw = believe(writeRoot, alias, line);
            return raw != null ? raw : scan(writeRoot, alias);   // the first line of that alias was torn: a later one may be good
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The raw id of a book line for {@code alias}, only when the alias really is the token of that raw value. */
    private static String believe(Path writeRoot, String alias, String line) {
        String prefix = alias + "\t";
        if (!line.startsWith(prefix)) return null;
        String raw;
        try {
            raw = new String(Base64.getDecoder().decode(line.substring(prefix.length())), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException torn) {
            return null;
        }
        return MaskTokens.token(spaceKey(writeRoot), raw).equals(alias) ? raw : null;
    }

    /** Test seam: drops the in-memory book, as a restart does. */
    static void forgetInMemoryForTest() {
        BOOK.clear();
        MISSES.clear();
        INDEXES.clear();
    }

    /** Test seam: drops one entry from the in-memory book, as an LRU eviction does. */
    static void evictForTest(Path writeRoot, String alias) {
        BOOK.remove(writeRoot + "\u0000" + alias);
    }

    private static byte[] spaceKey(Path writeRoot) {
        try {
            return MaskTokens.spaceKey(writeRoot);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What the response says about masking. */
    Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode);
        m.put("masked", active);
        m.put("basis", basis);
        return m;
    }

    private byte[] key() {
        if (key == null) key = spaceKey(root);
        return key;
    }
}
