package com.gamma.exchange;

import com.gamma.util.AtomicFiles;
import com.gamma.util.ToonHelper;
import dev.toonformat.jtoon.JToon;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * On-disk layout + pointer discipline for snapshot-mode sharing (S2). An owner's refreshed Dataset lands
 * under {@code <_shared>/exchange/<owner>/<item>/<version>/snapshot.parquet}; a sibling {@code current.toon}
 * names the live version. Consumers only ever read through {@code current.toon} → the {@code <version>/}
 * dir, so a refresh is an <b>atomic pointer flip</b> — a reader never observes a half-written version.
 *
 * <p>Freshness ({@link SnapshotMeta}: version, row count, refresh time, Result Set columns) travels in
 * {@code current.toon} so a consumer sees it in the Exchange catalog without touching the owner's tree.
 */
public final class ExchangeSnapshots {

    private ExchangeSnapshots() {}

    /** The directory holding an offered item's versioned snapshots + {@code current.toon}. */
    public static Path itemDir(Path sharedDir, String owner, String item) {
        return sharedDir.resolve("exchange").resolve(owner).resolve(item);
    }

    private static Path currentFile(Path itemDir) {
        return itemDir.resolve("current.toon");
    }

    /** Freshness metadata for the live snapshot (the {@code current.toon} contents). */
    public record SnapshotMeta(String version, long rows, String refreshedAt, List<Map<String, Object>> columns) {

        public SnapshotMeta {
            columns = columns == null ? List.of() : List.copyOf(columns);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", version);
            m.put("rows", rows);
            m.put("refreshedAt", refreshedAt);
            m.put("columns", columns);
            return m;
        }

        @SuppressWarnings("unchecked")
        static SnapshotMeta fromMap(Map<String, Object> m) {
            List<Map<String, Object>> cols = new ArrayList<>();
            if (m.get("columns") instanceof List<?> list)
                for (Object o : list) if (o instanceof Map<?, ?> c) cols.add((Map<String, Object>) c);
            return new SnapshotMeta(Ledger.str(m, "version"), Ledger.asLong(m.get("rows")),
                    Ledger.str(m, "refreshedAt"), cols);
        }
    }

    /** The live snapshot's freshness, or empty when nothing has been published for the item yet. */
    public static Optional<SnapshotMeta> readCurrent(Path itemDir) {
        Path file = currentFile(itemDir);
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(SnapshotMeta.fromMap(ToonHelper.load(file.toString())));
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + file, e);
        }
    }

    /** The live snapshot's version directory (what a consumer's query globs), if a snapshot exists. */
    public static Optional<Path> currentDir(Path itemDir) {
        return readCurrent(itemDir).map(meta -> itemDir.resolve(meta.version()));
    }

    /**
     * Atomically flip {@code current.toon} to {@code meta} (the reveal step of a refresh) - unless it already
     * names a newer version (a peer JVM published later), so the pointer never moves backwards.
     */
    static synchronized void writeCurrent(Path itemDir, SnapshotMeta meta) {
        try {
            Optional<SnapshotMeta> live = readCurrentQuietly(itemDir);
            if (live.isPresent() && compareVersions(live.get().version(), meta.version()) > 0) return;
            AtomicFiles.write(currentFile(itemDir),
                    JToon.encode(meta.toMap()).getBytes(StandardCharsets.UTF_8), ".current-");
        } catch (IOException e) {
            throw new UncheckedIOException("writing current.toon in " + itemDir, e);
        }
    }

    private static Optional<SnapshotMeta> readCurrentQuietly(Path itemDir) {
        try {
            return readCurrent(itemDir);
        } catch (RuntimeException torn) {
            return Optional.empty();
        }
    }

    /** A new version id: {@code v<epochMillis>-<8 hex>} - time-ordered, unique per publish. */
    static String newVersion(long nowMillis) {
        return "v" + nowMillis + "-" + String.format("%08x", java.util.concurrent.ThreadLocalRandom.current().nextInt());
    }

    /**
     * Epoch millis a version id was minted at - accepts the current {@code v<millis>-<suffix>} and the legacy
     * {@code v<millis>} shape; -1 when the name is not a version id.
     */
    static long versionMillis(String version) {
        if (version == null || version.length() < 2 || version.charAt(0) != 'v') return -1;
        int dash = version.indexOf('-');
        String digits = dash < 0 ? version.substring(1) : version.substring(1, dash);
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Time order of two version ids (numeric millis, then the suffix; a legacy id sorts before suffixed ones). */
    static int compareVersions(String a, String b) {
        int byTime = Long.compare(versionMillis(a), versionMillis(b));
        return byTime != 0 ? byTime : a.compareTo(b);
    }

    private static Path lastRefreshFile(Path itemDir) {
        return itemDir.resolve("last-refresh.toon");
    }

    /**
     * The outcome of the item's most recent publish attempt — {@code at} (epoch millis), {@code trigger}
     * ({@code manual}|{@code approve}|{@code schedule}), {@code status} ({@code published}|{@code failed}) and
     * {@code version} or {@code error}. Empty when nothing was ever attempted. A failure never moves
     * {@code current.toon}, so this is the only place a failed refresh is visible.
     */
    public static Optional<Map<String, Object>> readLastRefresh(Path itemDir) {
        Path file = lastRefreshFile(itemDir);
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(new LinkedHashMap<>(ToonHelper.load(file.toString())));
        } catch (IOException e) {
            return Optional.empty();   // a torn sidecar only loses the outcome, never the snapshot
        }
    }

    /**
     * Record a publish attempt's outcome (atomic; best-effort - never fails the publish it describes). Keyed by
     * the attempt's start time {@code at}: an outcome older than the recorded one is dropped, and at an equal
     * {@code at} a failure never replaces a success - so with two JVMs racing, a slower OLDER attempt's
     * failure cannot overwrite a newer success. Between two JVMs a last-writer-wins race remains only inside
     * the tiny read-then-rename window (the sidecar is advisory; {@code current.toon} is the truth).
     */
    static synchronized void writeLastRefresh(Path itemDir, Map<String, Object> outcome) {
        try {
            Files.createDirectories(itemDir);
            Map<String, Object> prev = readLastRefresh(itemDir).orElse(null);
            if (prev != null) {
                long prevAt = Ledger.asLong(prev.get("at"));
                long at = Ledger.asLong(outcome.get("at"));
                if (prevAt > at) return;
                if (prevAt == at && "published".equals(prev.get("status"))
                        && !"published".equals(outcome.get("status"))) return;
            }
            AtomicFiles.write(lastRefreshFile(itemDir),
                    JToon.encode(outcome).getBytes(StandardCharsets.UTF_8), ".last-");
        } catch (IOException ignore) {
            // best-effort
        }
    }

    /**
     * A non-live version younger than this is never pruned - it may be a peer JVM's publish still being written
     * or not yet revealed. Far above any publish's duration, and below the shortest cadence (hourly).
     */
    static final long PRUNE_GRACE_MILLIS = 30 * 60_000L;

    /**
     * Delete all but the {@code keep} newest version directories - bounds disk growth across refreshes.
     * Safe with several JVMs sharing the store: a directory is deleted only when it is (1) a version id
     * ({@link #versionMillis} &ge; 0 - anything else is left alone), (2) neither {@code keepVersion} nor the
     * version {@code current.toon} names now, (3) outside the {@code keep} newest by version time, and (4)
     * minted more than {@link #PRUNE_GRACE_MILLIS} before {@code nowMillis}. Rule (4) is what spares a peer's
     * in-flight or just-completed (not yet pointed-at) version. Best-effort: a failed delete retries next time.
     */
    static void prune(Path itemDir, String keepVersion, int keep, long nowMillis) {
        List<Path> versions;
        try (Stream<Path> s = Files.list(itemDir)) {
            versions = s.filter(Files::isDirectory)
                    .filter(p -> versionMillis(p.getFileName().toString()) >= 0)
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString(),
                            ExchangeSnapshots::compareVersions).reversed())
                    .toList();
        } catch (IOException e) {
            return;   // best-effort
        }
        String live = readCurrentQuietly(itemDir).map(SnapshotMeta::version).orElse(null);
        int kept = 0;
        for (Path v : versions) {
            String name = v.getFileName().toString();
            if (name.equals(keepVersion) || name.equals(live) || kept < keep) {
                kept++;
                continue;
            }
            if (nowMillis - versionMillis(name) < PRUNE_GRACE_MILLIS) continue;
            deleteRecursively(v);
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // best-effort prune
                }
            });
        } catch (IOException ignore) {
            // best-effort prune
        }
    }
}
