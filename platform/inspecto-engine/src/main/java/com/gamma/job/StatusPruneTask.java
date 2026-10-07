package com.gamma.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The {@code status_prune} maintenance task (STATUS-CSV-RETENTION-1): forget the per-run status CSVs a
 * pipeline writes, older than {@code retention_days} (required — forgetting is deliberate, like every
 * other prune on this Job Type).
 *
 * <p><b>What it prunes.</b> Every run of a pipeline with a {@code dirs.status_dir} writes four
 * run-timestamped siblings into it ({@code PipelineConfigParser}): {@code <pipeline>_status_<ts>.csv} plus
 * the {@code _batches_}, {@code _lineage_} and {@code _unpack_} ledgers. Nothing has ever removed them —
 * {@code ledger_prune} prunes the acquisition ledger in the DB, {@code runlog_prune} the Run history under
 * the audit root; these files are under the DATA root and were unbounded.
 *
 * <p>⛔ <b>What it must never prune.</b> {@code <pipeline>_commits.log} is <b>not</b> run-timestamped: it is
 * the append-only ledger of committed batches across every run, the durable answer to "did this batch
 * finish". The {@code manifests/} subdirectory is per-batch, not per-run. The file filter is therefore
 * positive — the four run-timestamped {@code .csv} families by name — and the walk is one level deep, so
 * neither can be reached even by accident.
 *
 * <p><b>Scope.</b> The Space's data root, whose layout is {@code <dataDir>/<pipeline>/status/} — so one
 * job covers every pipeline in the Space, which is the reason this is not simply a {@code cleanup} job:
 * {@code cleanup} takes a single {@code dir} and would need one job per pipeline, silently missing every
 * pipeline added afterwards. No data root configured ⇒ nothing to do.
 */
final class StatusPruneTask {

    private static final Logger log = LoggerFactory.getLogger(StatusPruneTask.class);

    /** The four run-timestamped families, and only those — `_commits.log` is deliberately unmatched. */
    private static final Pattern RUN_SCOPED =
            Pattern.compile(".+_(status|batches|lineage|unpack)_.+\\.csv");

    private StatusPruneTask() {}

    static JobResult run(JobConfig cfg, String dataDir, boolean dryRun) {
        long days = Long.parseLong(cfg.require("retention_days"));   // required: forgetting is deliberate
        if (days < 1) throw new IllegalArgumentException("status_prune retention_days must be >= 1");
        long t0 = System.nanoTime();
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        int files = 0, dirs = 0;
        long bytes = 0;
        for (Path statusDir : statusDirs(dataDir)) {
            dirs++;
            for (Path p : runScopedFiles(statusDir)) {
                if (!MaintenanceJob.olderThan(p, cutoff)) continue;   // unreadable mtime = never pruned
                try {
                    long size = Files.size(p);
                    if (!dryRun) Files.delete(p);
                    files++;
                    bytes += size;
                } catch (IOException e) {
                    log.warn("status_prune: could not delete {}: {}", p, e.getMessage());
                }
            }
        }
        String verb = dryRun ? "status_prune[dry-run]: would remove " : "status_prune: removed ";
        return JobResult.ok(verb + files + " run status file(s), " + bytes + " byte(s) older than "
                + days + "d across " + dirs + " status directory(ies)",
                (System.nanoTime() - t0) / 1_000_000L);
    }

    /** {@code <dataDir>/<pipeline>/status} for every pipeline that has one. */
    private static List<Path> statusDirs(String dataDir) {
        if (dataDir == null || dataDir.isBlank()) return List.of();
        Path root = Path.of(dataDir);
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> s = Files.list(root)) {
            return new ArrayList<>(s.filter(Files::isDirectory)
                    .map(p -> p.resolve("status"))
                    .filter(Files::isDirectory)
                    .toList());
        } catch (IOException e) {
            throw new UncheckedIOException("status_prune list failed under " + root, e);
        }
    }

    /** The run-timestamped files directly in one status dir — never {@code manifests/}, never the commit log. */
    private static List<Path> runScopedFiles(Path statusDir) {
        try (Stream<Path> s = Files.list(statusDir)) {
            return new ArrayList<>(s.filter(Files::isRegularFile)
                    .filter(p -> RUN_SCOPED.matcher(p.getFileName().toString()).matches())
                    .toList());
        } catch (IOException e) {
            throw new UncheckedIOException("status_prune list failed under " + statusDir, e);
        }
    }
}
