package com.gamma.la.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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

    /** A single path segment: non-empty, no separators, not . or .., no NUL. Non-ASCII and spaces are fine. */
    private static String segment(String s) {
        if (s == null || s.isEmpty() || s.equals(".") || s.equals("..") || s.indexOf('/') >= 0
                || s.indexOf('\\') >= 0 || s.indexOf('\0') >= 0)
            throw new IllegalArgumentException("not a safe path segment: '" + s + "'");
        return s;
    }

    public Path directory() { return dir; }

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

    /** As {@link #stage()}, removing stages older than {@code staleStageAge} first (ZERO removes every other stage). */
    public Path stage(Duration staleStageAge) throws IOException {
        Files.createDirectories(dir);
        deleteStaleStages(staleStageAge);
        Path p = dir.resolve(String.format("v%06d.tmp", maxVersionNumber() + 1));
        Files.createDirectory(p);
        return p;
    }

    /** Publishes a stage made by {@link #stage()} of THIS store: rename to vNNNNNN, then atomically repoint CURRENT. */
    public Path publish(Path staged) throws IOException {
        Path s = staged.toAbsolutePath().normalize();
        Matcher m = STAGE.matcher(s.getFileName() == null ? "" : s.getFileName().toString());
        if (!m.matches() || !dir.toAbsolutePath().normalize().equals(s.getParent()) || !Files.isDirectory(s))
            throw new IllegalArgumentException("not a staged version of this index: " + staged);
        Path target = dir.resolve("v" + m.group(1));
        if (Files.exists(target)) throw new IllegalStateException("version already published: " + target.getFileName());
        Files.move(s, target, StandardCopyOption.ATOMIC_MOVE);
        Path ptr = dir.resolve(CURRENT + ".tmp-" + m.group(1));
        Files.writeString(ptr, target.getFileName() + "\n", StandardCharsets.UTF_8);
        try {
            moveCurrent(ptr);
        } catch (IOException e) {
            Files.deleteIfExists(ptr);
            throw e;
        }
        return target;
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
        Instant cutoff = clock.instant().minus(age);
        for (Path t : list(STAGE)) {
            if (Files.getLastModifiedTime(t).toInstant().isAfter(cutoff)) continue;
            deleteTree(t);
            deleted.add(t.getFileName().toString());
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
