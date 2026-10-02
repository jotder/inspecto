package com.gamma.la.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Versioned index directories for ONE (dataset, mapping) pair: {@code <root>/<datasetId>/<mappingHash>/vNNNNNN/}, with a
 * {@code CURRENT} pointer file naming the live version.
 *
 * <p>Lifecycle: {@link #stage()} creates {@code vNNNNNN.tmp}; the builder fills it; {@link #publish(Path)} renames it to
 * {@code vNNNNNN} (the version is complete before it has its final name) and only then moves a new CURRENT into place
 * with ATOMIC_MOVE, so a reader that resolves {@link #current()} once sees a whole version, old or new, never a partial
 * one. {@link #gc(Duration)} keeps the live version plus the newest {@code keepVersions}, never deletes anything younger
 * than the caller's minimum age (a reader may still hold an older version open), and removes crashed stages.
 * Everything is {@link Path}-based; no path is ever built from text containing a separator.
 */
public final class IndexStore {

    public static final String CURRENT = "CURRENT";
    public static final int DEFAULT_KEEP_VERSIONS = 2;
    private static final Pattern VERSION = Pattern.compile("v(\\d{6,})");
    private static final Pattern STAGE = Pattern.compile("v(\\d{6,})\\.tmp");
    private static final Pattern CURRENT_TMP = Pattern.compile("CURRENT\\.tmp-\\d+");
    /**
     * A stage whose directory was modified within this window is IN FLIGHT and is never deleted, whatever minimum age the
     * caller asks for (not even ZERO). The builder keeps it alive with {@link #heartbeat(Path)}.
     */
    public static final Duration STAGE_LIVENESS = Duration.ofMinutes(5);
    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
    private static final Pattern ILLEGAL_CHARS = Pattern.compile("[<>:\"|?*\\x00-\\x1F]");
    /** One lock per store directory, so two publishes in this JVM never interleave; the file lock covers other processes. */
    private static final Map<Path, Object> PUBLISH_LOCKS = new ConcurrentHashMap<>();

    private final Path dir;
    private final int keepVersions;
    private final Clock clock;

    public IndexStore(Path root, String datasetId, String mappingHash) {
        this(root, datasetId, mappingHash, DEFAULT_KEEP_VERSIONS, Clock.systemUTC());
    }

    public IndexStore(Path root, String datasetId, String mappingHash, int keepVersions, Clock clock) {
        if (keepVersions < 1) throw new IllegalArgumentException("keepVersions must be >= 1");
        this.dir = root.resolve(segment(datasetId)).resolve(segment(mappingHash));
        this.keepVersions = keepVersions;
        this.clock = clock;
    }

    /**
     * A single path segment, safe on Linux AND Windows: non-empty, no separators, no NUL or control characters, none of
     * {@code < > : " | ? *}, not . or .., no trailing dot or space, not a Windows reserved device name (CON, PRN, AUX, NUL,
     * COM1-9, LPT1-9, with or without an extension, any case). Non-ASCII and inner spaces are fine.
     *
     * <p><b>Case-folding choice.</b> The returned name is NFC-normalised and CASE-FOLDED (upper then lower, locale-free), and
     * the directory is created under that name. So {@code Ds}, {@code ds}, {@code DS} and a composed or decomposed
     * {@code e-acute} are ONE index on every file system: Linux (case-sensitive) can never hold two indexes that Windows
     * (case-insensitive) would merge. The original id is kept in the manifest. Checks run on the folded name.
     */
    private static String segment(String s) {
        if (s == null || s.isEmpty()) throw new IllegalArgumentException("not a safe path segment: '" + s + "'");
        String f = Normalizer.normalize(s, Normalizer.Form.NFC).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
        f = Normalizer.normalize(f, Normalizer.Form.NFC);
        int dot = f.indexOf('.');
        String stem = dot < 0 ? f : f.substring(0, dot);
        if (f.equals(".") || f.equals("..") || f.indexOf('/') >= 0 || f.indexOf('\\') >= 0 || ILLEGAL_CHARS.matcher(f).find()
                || f.endsWith(".") || f.endsWith(" ") || RESERVED.contains(stem.stripTrailing().toUpperCase(Locale.ROOT)))
            throw new IllegalArgumentException("not a safe path segment: '" + s + "'");
        return f;
    }

    public Path directory() { return dir; }

    /**
     * The mapping hashes that have a directory under {@code datasetId} in {@code root} (sorted; empty when the Dataset has
     * none) - what {@code GET /inv/index} walks. A hash is hex, so each can be passed back to the constructor.
     */
    public static List<String> mappingHashes(Path root, String datasetId) {
        Path datasetDir = new IndexStore(root, datasetId, "0").dir.getParent();
        if (!Files.isDirectory(datasetDir)) return List.of();
        try (Stream<Path> stream = Files.list(datasetDir)) {
            return stream.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The live version directory, or empty when nothing is published. Reads CURRENT once. */
    public Optional<Path> current() {
        try {
            String name = Files.readString(dir.resolve(CURRENT), StandardCharsets.UTF_8).trim();
            if (!VERSION.matcher(name).matches()) return Optional.empty();
            Path v = dir.resolve(name);
            return Files.isDirectory(v) ? Optional.of(v) : Optional.empty();
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Creates the next {@code vNNNNNN.tmp}; first removes crashed stages older than one hour. */
    public Path stage() throws IOException {
        return stage(Duration.ofHours(1));
    }

    /**
     * As {@link #stage()}, removing stages older than {@code staleStageAge} first. A stage still inside
     * {@link #STAGE_LIVENESS} is in flight and is kept even for ZERO.
     */
    public Path stage(Duration staleStageAge) throws IOException {
        Files.createDirectories(dir);
        deleteStaleStages(staleStageAge);
        // createDirectory is the atomic claim: a concurrent stage() that took the same number makes this one lose
        // with FileAlreadyExistsException, so recompute the next number and try again.
        while (true) {
            Path p = dir.resolve(String.format("v%06d.tmp", maxVersionNumber() + 1));
            try {
                Files.createDirectory(p);
                return p;
            } catch (java.nio.file.FileAlreadyExistsException lostRace) {
                // another stage() won this number; the next maxVersionNumber() sees it
            }
        }
    }

    /** Marks {@code staged} alive now (its directory mtime): call it periodically while a build runs, so {@link #gc} spares it. */
    public void heartbeat(Path staged) throws IOException {
        Files.setLastModifiedTime(staged, FileTime.from(clock.instant()));
    }

    /** Deletes a stage of THIS store (a failed or cancelled build). A stage that is already gone is fine. */
    public void discard(Path staged) throws IOException {
        Path s = staged.toAbsolutePath().normalize();
        if (s.getFileName() == null || !STAGE.matcher(s.getFileName().toString()).matches() || !dir.toAbsolutePath().normalize().equals(s.getParent()))
            throw new IllegalArgumentException("not a staged version of this index: " + staged);
        if (Files.exists(s)) deleteTree(s);
    }

    /** Publishes a stage made by {@link #stage()} of THIS store: rename to vNNNNNN, then atomically repoint CURRENT. */
    public Path publish(Path staged) throws IOException {
        Path s = staged.toAbsolutePath().normalize();
        Matcher m = STAGE.matcher(s.getFileName() == null ? "" : s.getFileName().toString());
        if (!m.matches() || !dir.toAbsolutePath().normalize().equals(s.getParent()) || !Files.isDirectory(s))
            throw new IllegalArgumentException("not a staged version of this index: " + staged);
        Path target = dir.resolve("v" + m.group(1));
        long number = Long.parseLong(m.group(1));
        synchronized (PUBLISH_LOCKS.computeIfAbsent(dir.toAbsolutePath().normalize(), k -> new Object())) {
            try (FileChannel lock = FileChannel.open(dir.resolve(".publish.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = lock.lock()) {
                long live = liveNumber();
                if (number < live)
                    throw new IllegalStateException("refusing to publish v" + String.format("%06d", number)
                            + ": CURRENT is already at v" + String.format("%06d", live) + " (a later stage was published first)");
                if (Files.exists(target)) throw new IllegalStateException("version already published: " + target.getFileName());
                Files.move(s, target, StandardCopyOption.ATOMIC_MOVE);
                Path ptr = dir.resolve(CURRENT + ".tmp-" + m.group(1));
                try (FileChannel ch = FileChannel.open(ptr, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    ch.write(ByteBuffer.wrap((target.getFileName() + "\n").getBytes(StandardCharsets.UTF_8)));
                    ch.force(true);   // a crash after the move must not leave an empty CURRENT
                }
                try {
                    moveCurrent(ptr);
                } catch (IOException e) {
                    Files.deleteIfExists(ptr);
                    throw e;
                }
            }
        }
        return target;
    }

    /** The number of the version CURRENT names, or 0 when there is none. */
    private long liveNumber() {
        return current().map(IndexStore::number).orElse(0L);
    }

    /**
     * ATOMIC_MOVE over CURRENT. On Windows the replace can be refused with AccessDeniedException for a few
     * milliseconds while a reader has CURRENT open; that is transient, so retry briefly before giving up.
     */
    private void moveCurrent(Path ptr) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                Files.move(ptr, dir.resolve(CURRENT), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException e) {
                if (attempt >= 200) throw e;
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * Deletes published versions beyond the live one + newest {@code keepVersions}, and crashed stages, skipping
     * anything modified within {@code minAge}. Returns the names deleted.
     */
    public List<String> gc(Duration minAge) throws IOException {
        List<String> deleted = new ArrayList<>();
        if (!Files.isDirectory(dir)) return deleted;
        Instant cutoff = clock.instant().minus(minAge);
        String live = current().map(p -> p.getFileName().toString()).orElse(null);
        List<Path> versions = list(VERSION);
        versions.sort(Comparator.comparingLong(IndexStore::number).reversed());
        for (int i = keepVersions; i < versions.size(); i++) {
            Path v = versions.get(i);
            if (v.getFileName().toString().equals(live)) continue;
            if (Files.getLastModifiedTime(v).toInstant().isAfter(cutoff)) continue;
            deleteTree(v);
            deleted.add(v.getFileName().toString());
        }
        deleted.addAll(deleteStaleStages(minAge));
        return deleted;
    }

    private List<String> deleteStaleStages(Duration age) throws IOException {
        List<String> deleted = new ArrayList<>();
        Instant cutoff = clock.instant().minus(age.compareTo(STAGE_LIVENESS) < 0 ? STAGE_LIVENESS : age);
        for (Path t : list(STAGE)) {
            if (Files.getLastModifiedTime(t).toInstant().isAfter(cutoff)) continue;
            deleteTree(t);
            deleted.add(t.getFileName().toString());
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (!CURRENT_TMP.matcher(p.getFileName().toString()).matches() || !Files.isRegularFile(p)) continue;
                if (Files.getLastModifiedTime(p).toInstant().isAfter(cutoff)) continue;
                Files.deleteIfExists(p);
                deleted.add(p.getFileName().toString());
            }
        }
        return deleted;
    }

    private long maxVersionNumber() throws IOException {
        long max = 0;
        for (Path p : list(VERSION)) max = Math.max(max, number(p));
        for (Path p : list(STAGE)) max = Math.max(max, number(p));
        return max;
    }

    private static long number(Path p) {
        String n = p.getFileName().toString();
        return Long.parseLong(n.substring(1, n.endsWith(".tmp") ? n.length() - 4 : n.length()));
    }

    private List<Path> list(Pattern pattern) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds)
                if (pattern.matcher(p.getFileName().toString()).matches() && Files.isDirectory(p)) out.add(p);
        }
        return out;
    }

    private static void deleteTree(Path p) throws IOException {
        List<Path> all;
        try (Stream<Path> w = Files.walk(p)) {
            all = w.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path x : all) Files.delete(x);
    }
}
