package com.gamma.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The {@code retire_superseded} maintenance task: delete the <b>bytes</b> of output files the Consignment
 * catalog has marked unreadable and that are older than {@code retention_days} — the disk half of
 * addressing step 6.
 *
 * <p><b>Why this task has to exist.</b> Step 6 stopped full recomputes rewriting a path in place, which is
 * what let a concurrent read finish safely — but it means every recompute leaves a whole extra revision on
 * disk, where the old overwrite was O(1). Superseding alone would trade a correctness bug for an
 * out-of-disk one. The retention window is the delay that makes the original fix work: a read may finish on
 * a revision for that long after it stops being current.
 *
 * <p><b>Files go; rows stay.</b> A row for a deleted file is harmless — it excludes a path that no longer
 * exists — while deleting it would break the one rule the catalog has here: never drop a non-{@code LIVE}
 * row whose file is still on disk, because that row is the only thing keeping the file out of a glob. If a
 * pass is interrupted between the two, keeping rows means the worst case is a file deleted twice, not a
 * file resurrected into every read.
 *
 * <p>Age comes from the <b>file's own modification time</b>, not the catalog's {@code written_at}: the
 * question is how long these bytes have been sittable-on, and a row can outlive or predate the file it
 * names. {@code retention_days} is required rather than defaulted, following {@code ledger_prune} — deleting
 * data is deliberate.
 */
final class RetireSupersededTask {

    private static final Logger log = LoggerFactory.getLogger(RetireSupersededTask.class);

    private RetireSupersededTask() {}

    static JobResult run(JobConfig cfg, boolean dryRun) {
        long days = Long.parseLong(cfg.require("retention_days"));
        if (days < 1) throw new IllegalArgumentException("retire_superseded retention_days must be >= 1");
        long t0 = System.nanoTime();

        var registry = com.gamma.consignment.ConsignmentOutputStores.shared();
        if (registry == null)
            return JobResult.ok("retire_superseded: no output registry — nothing is marked, nothing to retire",
                    (System.nanoTime() - t0) / 1_000_000L);

        java.time.Instant cutoff = java.time.Instant.now().minus(java.time.Duration.ofDays(days));
        int retired = 0, held = 0, absent = 0, failed = 0;
        for (String path : registry.unreadablePaths()) {
            Path file = Path.of(path);
            try {
                if (!java.nio.file.Files.exists(file)) {
                    absent++;                       // already gone: a compacted-away file, or an earlier pass
                    continue;
                }
                if (java.nio.file.Files.getLastModifiedTime(file).toInstant().isAfter(cutoff)) {
                    held++;                         // inside the window a read may still be finishing on it
                    continue;
                }
                if (dryRun) retired++;
                else if (java.nio.file.Files.deleteIfExists(file)) retired++;
            } catch (IOException e) {
                // An open reader (on Windows, any open handle) fails the delete. Never fatal: the row keeps the
                // file out of every read, and the next pass retries it.
                failed++;
                log.warn("retire_superseded: could not delete {} (retrying next pass): {}", path, e.getMessage());
            }
        }
        String verb = dryRun ? "[dry-run] would retire " : "retired ";
        return JobResult.ok("retire_superseded: " + verb + retired + " superseded file(s) older than " + days
                + "d; " + held + " still inside the window, " + absent + " already gone, " + failed + " locked",
                (System.nanoTime() - t0) / 1_000_000L);
    }
}
