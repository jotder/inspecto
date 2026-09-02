package com.gamma.job;

import com.gamma.event.EventStore;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The {@code event_prune} maintenance task (COMPLY-3, controls-matrix gap G5): apply the audit-retention
 * window to this space's durable event store by dropping the {@code level/year/month/day} Parquet
 * partitions whose UTC day is older than {@code retention_days} (required). Retention here is a
 * <b>file delete by partition</b>, never a SQL {@code DELETE} — see {@link EventStore#prune}.
 *
 * <p>{@code retention_days} is required with no default, like every other prune: the operator's window is
 * one year (2026-08-30), and a window that silently defaults is a window the code does not apply. ⚠ Until
 * this task is scheduled the window is stated policy only — an auditor must not be told it is enforced.
 * Within the window nothing is touched, so the MNT-14 G3 stance (a purged Incident's history, purge record
 * included, stays retained) holds by construction.
 */
final class EventPruneTask {

    private EventPruneTask() {}

    static JobResult run(JobConfig cfg, JobService host, boolean dryRun) {
        long days = Long.parseLong(cfg.require("retention_days"));   // required: forgetting is deliberate
        if (days < 1) throw new IllegalArgumentException("event_prune retention_days must be >= 1");
        long t0 = System.nanoTime();
        var store = host == null ? java.util.Optional.<EventStore>empty() : host.eventStore();
        if (store.isEmpty())
            return JobResult.ok("event_prune: no event store attached — nothing to prune", 0L);
        // UTC, because ParquetEventStore partitions by the event's UTC day — the same frame it prunes in.
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(days);
        int n = store.get().prune(cutoff, dryRun);
        if (n < 0)
            return JobResult.ok("event_prune: the event store keeps nothing durable (-Devents.backend=memory) "
                    + "— nothing to prune", (System.nanoTime() - t0) / 1_000_000L);
        if (dryRun)
            return JobResult.ok("event_prune[dry-run]: would remove " + n + " day-partition(s) before " + cutoff
                    + " (retention " + days + "d)", (System.nanoTime() - t0) / 1_000_000L);
        return JobResult.ok("event_prune: removed " + n + " day-partition(s) before " + cutoff
                + " (retention " + days + "d)", (System.nanoTime() - t0) / 1_000_000L);
    }
}
