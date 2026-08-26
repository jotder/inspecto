package com.gamma.etl.unpack;

import com.gamma.etl.DuckDbCsvIngester;
import com.gamma.etl.PipelineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Collector-level unpack stage (unpack-stage plan §2.0/§4 Phase 1): expands compressed inbox
 * candidates into plain files BEFORE {@code ConsignmentPlanner.plan} freezes the candidate list —
 * so every expanded file is an ordinary Consignment member from birth and nothing downstream of the
 * Collector changes ("keep the EL less complex", operator 2026-08-23).
 *
 * <p><b>Engine-aware:</b> a format the chosen lane already reads is left untouched — the DuckDB lane
 * auto-decompresses {@code .gz}/{@code .zst} inside {@code read_csv}, and the Java lane inflates
 * {@code .gz}/{@code .bz2}/{@code .zip} inline ({@code Compression}). Expanding those would pay a
 * decode to hand an engine what it decodes itself.
 *
 * <p><b>Failure is fail-open into the EXISTING failure path:</b> when expansion of a claimed file
 * fails (corrupt bytes, a breached {@link UnpackLimits} cap), the ORIGINAL stays in the candidate
 * list — the engine then fails to read it and the normal machinery quarantines it as
 * {@code unreadable} with a per-file audit row. Quarantining here at the Collector would bypass the
 * status ledger and lose exactly the per-source-file reporting the operator asked for.
 *
 * <p><b>Original ↔ actual:</b> each expansion is logged ({@code original= actual=} — the derived
 * value beside its input) and registered in {@link UnpackOrigins} so finalize/quarantine run their
 * poll-relative path math against the original.
 */
public final class UnpackStage {

    private static final Logger log = LoggerFactory.getLogger(UnpackStage.class);

    /** Expansion workspace under {@code dirs.temp} — swept of stale cycles on entry. */
    private static final String WORK_SUBDIR = "unpack";
    /** A crashed cycle's leftovers are reclaimed after this many millis (nothing references them). */
    private static final long STALE_WORK_MS = 24L * 3600_000L;

    private UnpackStage() {}

    /**
     * Expand every candidate a discovered {@link DecompressorPlugin} claims (and the chosen engine
     * lane cannot read itself). Returns the new candidate list; files no plugin claims, files the
     * lane handles natively, and files whose expansion FAILED pass through unchanged.
     */
    public static List<File> expand(PipelineConfig cfg, List<File> candidates) {
        PipelineConfig.Unpack opts = cfg.unpack();
        if (candidates.isEmpty() || !opts.enabled()) return candidates;
        Path workRoot = Paths.get(cfg.dirs().temp()).toAbsolutePath()
                .resolve(WORK_SUBDIR).resolve(cfg.identity().runTimestamp());
        sweepStale(Paths.get(cfg.dirs().temp()).toAbsolutePath().resolve(WORK_SUBDIR));

        UnpackLimits limits = new UnpackLimits(opts.maxEntries(), opts.maxEntryBytes(),
                opts.maxTotalBytes(), opts.maxRatio(), opts.depth());
        boolean nativeLane = DuckDbCsvIngester.usesDuckDb(cfg);

        // Decide FIRST (cheap: a suffix match plus an 8-byte read), then expand only the claimed
        // files — and expand those in parallel when asked. Deciding up front keeps the output order
        // deterministic regardless of thread scheduling: `out` is assembled from `plans` in the
        // original candidate order, so the planner sees the same list every run.
        List<Plan> plans = new ArrayList<>(candidates.size());
        for (File f : candidates) plans.add(plan(f, nativeLane));

        String runId = cfg.identity().runTimestamp();
        List<Plan> work = plans.stream().filter(p -> p.plugin != null).toList();
        if (work.size() > 1 && opts.threads() > 1)
            expandParallel(work, workRoot, limits, opts.threads(), runId);
        else for (Plan p : work) p.run(workRoot, limits, runId);

        List<File> out = new ArrayList<>(candidates.size());
        for (Plan p : plans) out.addAll(p.result());
        return out;
    }

    /** Which plugin (if any) owns a candidate — the whole decision, made before any expansion. */
    private static Plan plan(File f, boolean nativeLane) {
        DecompressorPlugin plugin;
        try {
            plugin = Decompressors.forFile(f.toPath()).orElse(null);
        } catch (IOException e) {
            log.warn("[UNPACK] Could not probe {} — leaving it to the engine: {}", f.getName(), e.getMessage());
            return new Plan(f, null);
        }
        // A format the lane decodes itself is left alone — but ONLY for stream kinds: a .zip the
        // Java lane "reads" is only ever its FIRST entry (Compression.firstEntry), so an archive
        // must always be expanded here or its remaining entries are silently dropped.
        if (plugin == null
                || (plugin.kind() == DecompressorPlugin.Kind.STREAM
                    && laneReadsItself(f.getName(), nativeLane)))
            return new Plan(f, null);
        return new Plan(f, plugin);
    }

    /**
     * Expand on a bounded pool. Unpack is pure file I/O with no DuckDB connection, so archives are
     * independent — but the pool is the stage's OWN (never the batch semaphore: unpack runs before
     * planning, so borrowing that permit would serialize it behind ingest for nothing), and the
     * logging MDC is propagated or per-space log routing breaks on the worker threads.
     */
    private static void expandParallel(List<Plan> work, Path workRoot, UnpackLimits limits,
                                       int threads, String runId) {
        Map<String, String> mdc = org.slf4j.MDC.getCopyOfContextMap();
        java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(threads);
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>(work.size());
            for (Plan p : work) {
                futures.add(pool.submit(() -> {
                    Map<String, String> prior = org.slf4j.MDC.getCopyOfContextMap();
                    if (mdc != null) org.slf4j.MDC.setContextMap(mdc);
                    permits.acquireUninterruptibly();
                    try {
                        p.run(workRoot, limits, runId);
                    } finally {
                        permits.release();
                        if (prior != null) org.slf4j.MDC.setContextMap(prior); else org.slf4j.MDC.clear();
                    }
                }));
            }
            for (var fut : futures) {
                try {
                    fut.get();
                } catch (Exception e) {   // a plan never throws (it fails open), so this is a defect
                    log.warn("[UNPACK] Expansion task failed unexpectedly: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * One candidate's expansion decision and outcome. Mutable by design and touched by exactly one
     * thread ({@link #run}), with {@link #result()} read only after every task has joined.
     */
    private static final class Plan {
        private final File source;
        private final DecompressorPlugin plugin;
        private List<File> expanded;

        Plan(File source, DecompressorPlugin plugin) {
            this.source = source;
            this.plugin = plugin;
        }

        void run(Path workRoot, UnpackLimits limits, String runId) {
            boolean archive = plugin.kind() == DecompressorPlugin.Kind.ARCHIVE;
            List<String> skipped = new ArrayList<>();
            try {
                Path work = workRoot.resolve(String.valueOf(Math.abs(source.getAbsolutePath().hashCode())));
                Files.createDirectories(work);
                List<Path> out = plugin.expand(source.toPath(), work, limits, skipped);
                List<File> files = new ArrayList<>(out.size());
                for (Path p : out) {
                    // Lineage records the ENTRY name for an archive entry — the workspace's
                    // NNNNN_ ordering prefix is an implementation detail that must not leak into
                    // filename_column/lineage. A stream expansion's name is already the real one.
                    String name = p.getFileName().toString();
                    UnpackOrigins.register(p, source,
                            archive ? ArchiveDecompressorPlugin.entryName(name) : name);
                    log.info("[UNPACK] {} original={} actual={}", plugin.id(), source.getName(), p);
                    files.add(p.toFile());
                }
                // Entries the walk had to skip (encrypted / unsupported method) are recorded against
                // the ORIGINAL; finalizeSource drains them into the manifest so a partial expansion
                // never looks like a clean success.
                UnpackOrigins.registerSkipped(source, skipped);
                expanded = files;

                // The archive's run-level ledger row (§2.2). ARCHIVE kind only: a 1→1 stream
                // expansion has no entries to roll up and its outcome is already fully described by
                // its single file's own status row — a row here would double-report it.
                if (archive)
                    UnpackLedger.expanded(runId, source, plugin.id(),
                            files.size() + skipped.size(), skipped.size(),
                            sizeOf(source.toPath()), bytesOf(out), false, "");
            } catch (IOException e) {
                // Fail-open: the original flows on and fails in the engine, where the per-file
                // status/quarantine machinery reports it (see the class comment).
                log.warn("[UNPACK] {} failed for {} — handing the original to the engine: {}",
                        plugin.id(), source.getName(), e.getMessage());
                // ⚠ A NoUsableEntriesException is NOT an expansion failure: the archive opened
                // cleanly and simply had nothing usable in it, which is EMPTY (zero entries) or
                // UNREADABLE (entries existed, none decodable) — distinct statuses per §6 Q1, told
                // apart by the count the exception carries, never by its message.
                if (archive) {
                    boolean noUsable = e instanceof NoUsableEntriesException;
                    int found = noUsable ? ((NoUsableEntriesException) e).entriesFound() : skipped.size();
                    UnpackLedger.expanded(runId, source, plugin.id(), found, skipped.size(),
                            sizeOf(source.toPath()), 0L, !noUsable, e.getMessage());
                }
            }
        }

        /** The expansion's files, or the original when it was never claimed / failed to expand. */
        List<File> result() {
            return expanded != null ? expanded : List.of(source);
        }
    }

    /** A file's size, or 0 when it cannot be stated — a ledger column is never worth failing a run. */
    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L;
        }
    }

    /** Total bytes written by an expansion. */
    private static long bytesOf(List<Path> out) {
        long total = 0;
        for (Path p : out) total += sizeOf(p);
        return total;
    }

    /** Whether the chosen lane already decodes this suffix itself (see class comment). */
    private static boolean laneReadsItself(String fileName, boolean nativeLane) {
        String n = fileName.toLowerCase(Locale.ROOT);
        return nativeLane
                ? n.endsWith(".gz") || n.endsWith(".zst")
                : n.endsWith(".gz") || n.endsWith(".bz2") || n.endsWith(".zip");
    }

    /** Reclaim expansion dirs from crashed cycles — anything older than {@link #STALE_WORK_MS}. */
    private static void sweepStale(Path unpackRoot) {
        if (!Files.isDirectory(unpackRoot)) return;
        long cutoff = Instant.now().toEpochMilli() - STALE_WORK_MS;
        try (var kids = Files.list(unpackRoot)) {
            for (Path dir : kids.toList()) {
                try {
                    if (Files.getLastModifiedTime(dir).toMillis() < cutoff) deleteTree(dir);
                } catch (IOException e) {
                    log.debug("[UNPACK] Could not sweep {}: {}", dir, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.debug("[UNPACK] Could not list {}: {}", unpackRoot, e.getMessage());
        }
    }

    /**
     * Delete the expanded temp file behind {@code actual} and count it done.
     *
     * @return the ORIGINAL inbox file when {@code actual} was its LAST outstanding expansion — the
     *         one moment the original's own side effects (backup, marker) may run — else null. With
     *         {@code batch.max_files: 1} (the default) an N-entry archive's members land in N
     *         separate batches, so this is what keeps the archive in the inbox until every entry
     *         has committed: marking it earlier would strand the entries still to come.
     */
    public static File cleanup(File actual) {
        if (!UnpackOrigins.isExpanded(actual)) return null;
        File lastOfOriginal = UnpackOrigins.consume(actual);
        try {
            Files.deleteIfExists(actual.toPath());
            Path dir = actual.toPath().toAbsolutePath().getParent();
            if (dir != null) Files.deleteIfExists(dir);   // the per-source dir; fails non-empty, fine
        } catch (IOException e) {
            log.debug("[UNPACK] Could not clean {}: {}", actual, e.getMessage());
        }
        return lastOfOriginal;
    }

    private static void deleteTree(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
