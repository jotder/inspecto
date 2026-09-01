package com.gamma.job;

import com.gamma.consignment.DbDedupLedger;
import com.gamma.consignment.DedupLedgers;

import java.time.LocalDate;

/**
 * The {@code dedup_prune} maintenance task (D-9): advance the windowed record-dedup ledger by dropping
 * claims whose window started more than {@code retention_days} (required) ago — <b>aged by the record's
 * own event time</b> (the {@code window_start} each claim was filed under), never by file mtime, so a
 * late-arriving file cannot evict keys still inside their declared window (see {@link MaintenanceJob}
 * class doc and {@link DbDedupLedger#prune}).
 *
 * <p>{@code retention_days} is required with no default — the {@code retire_superseded} posture: a
 * window that silently defaults grows unbounded, which is exactly the "never faked with unbounded
 * history" refusal. Set it to at least the longest {@code scope: window(...)} any pipeline declares.
 */
final class DedupPruneTask {

    private DedupPruneTask() {}

    static JobResult run(JobConfig cfg, boolean dryRun) throws Exception {
        long days = Long.parseLong(cfg.require("retention_days"));   // required: forgetting is deliberate
        if (days < 1) throw new IllegalArgumentException("dedup_prune retention_days must be >= 1");
        long t0 = System.nanoTime();
        DbDedupLedger ledger = DedupLedgers.shared();
        if (ledger == null)
            return JobResult.ok("dedup_prune: no dedup ledger registered for this space "
                    + "(-Ddedup.ledger.backend=none) — nothing to prune", 0L);
        LocalDate cutoff = LocalDate.now().minusDays(days);
        if (dryRun)
            return JobResult.ok("dedup_prune[dry-run]: ledger holds " + ledger.size()
                    + " claim(s); would remove those whose window started before " + cutoff,
                    (System.nanoTime() - t0) / 1_000_000L);
        int removed = ledger.prune(cutoff);
        return JobResult.ok("dedup_prune: removed " + removed + " claim(s) whose window started before "
                + cutoff, (System.nanoTime() - t0) / 1_000_000L);
    }
}
