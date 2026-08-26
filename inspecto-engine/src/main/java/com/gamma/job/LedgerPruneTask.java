package com.gamma.job;

import java.time.Duration;

/** The {@code ledger_prune} maintenance task: forget acquisition-ledger fingerprints older than
 *  {@code retention_days} (see {@link MaintenanceJob} class doc). */
final class LedgerPruneTask {

    private LedgerPruneTask() {}

    static JobResult run(JobConfig cfg, boolean dryRun) {
        long days = Long.parseLong(cfg.require("retention_days"));   // required: forgetting is deliberate
        if (days < 1) throw new IllegalArgumentException("ledger_prune retention_days must be >= 1");
        String source = cfg.opt("source", null);
        long t0 = System.nanoTime();
        long cutoff = System.currentTimeMillis() - Duration.ofDays(days).toMillis();
        var ledger = com.gamma.acquire.AcquisitionLedgers.shared();
        String scope = " older than " + days + "d" + (source != null ? " for source " + source : "");
        if (dryRun) {
            int would = ledger.countPrunable(cutoff, source);
            return JobResult.ok("ledger_prune[dry-run]: would remove " + would + " fingerprint(s)" + scope,
                    (System.nanoTime() - t0) / 1_000_000L);
        }
        int removed = ledger.prune(cutoff, source);
        return JobResult.ok("ledger_prune: removed " + removed + " fingerprint(s)" + scope,
                (System.nanoTime() - t0) / 1_000_000L);
    }
}
