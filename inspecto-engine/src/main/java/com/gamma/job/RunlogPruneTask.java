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
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The {@code runlog_prune} maintenance task (MNT-2a): forget Run history older than
 * {@code retention_days} (required — forgetting is deliberate, like {@code ledger_prune}): per-run JSONL
 * files under {@code <auditDir>/runlog/} and {@code <auditDir>/artifacts/} (by file mtime ≈ run end
 * time), plus rows of the optional {@code inspecto_job_runs} DuckDB projection. An optional
 * {@code max_count} additionally caps each JSONL dir to its newest N files regardless of age (0/absent =
 * no cap; the count cap does not apply to the DB rows — retention governs those).
 */
final class RunlogPruneTask {

    private static final Logger log = LoggerFactory.getLogger(RunlogPruneTask.class);

    private RunlogPruneTask() {}

    static JobResult run(JobConfig cfg, String auditDir, DbJobRunStore runStore, boolean dryRun) {
        long days = Long.parseLong(cfg.require("retention_days"));   // required: forgetting is deliberate
        if (days < 1) throw new IllegalArgumentException("runlog_prune retention_days must be >= 1");
        int maxCount = Integer.parseInt(cfg.opt("max_count", "0"));
        if (maxCount < 0) throw new IllegalArgumentException("runlog_prune max_count must be >= 0");
        long t0 = System.nanoTime();
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        Path auditRoot = auditDir == null ? null : Path.of(auditDir);
        int logs      = pruneJsonl(auditRoot == null ? null : auditRoot.resolve("runlog"), cutoff, maxCount, dryRun);
        int artifacts = pruneJsonl(auditRoot == null ? null : auditRoot.resolve("artifacts"), cutoff, maxCount, dryRun);
        String dbCutoff = java.time.LocalDateTime.now().minusDays(days)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        int rows = runStore == null ? 0
                : (dryRun ? runStore.countPrunable(dbCutoff) : runStore.prune(dbCutoff));
        String verb = dryRun ? "runlog_prune[dry-run]: would remove " : "runlog_prune: removed ";
        return JobResult.ok(verb + logs + " run log(s), " + artifacts + " artifact file(s), "
                + rows + " projected run row(s) older than " + days + "d"
                + (maxCount > 0 ? " (max_count=" + maxCount + ")" : ""),
                (System.nanoTime() - t0) / 1_000_000L);
    }

    /** Prune one JSONL dir: everything older than {@code cutoff}, plus (when {@code maxCount} > 0)
     *  everything beyond the newest {@code maxCount} files. Returns the affected count. */
    private static int pruneJsonl(Path dir, Instant cutoff, int maxCount, boolean dryRun) {
        if (dir == null || !Files.isDirectory(dir)) return 0;
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = new ArrayList<>(s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList());
        } catch (IOException e) {
            throw new UncheckedIOException("runlog_prune list failed under " + dir, e);
        }
        files.sort(Comparator.comparing(MaintenanceJob::mtime).reversed());   // newest first
        int affected = 0;
        for (int i = 0; i < files.size(); i++) {
            Path p = files.get(i);
            boolean victim = MaintenanceJob.mtime(p).isBefore(cutoff) || (maxCount > 0 && i >= maxCount);
            if (!victim) continue;
            try {
                if (!dryRun) Files.delete(p);
                affected++;
            } catch (IOException e) {
                log.warn("runlog_prune: could not delete {}: {}", p, e.getMessage());
            }
        }
        return affected;
    }
}
