package com.gamma.job;

import com.gamma.audit.EventStore;

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
 *
 * <p><b>Legal hold</b> (LEGAL-HOLD-SCOPE-1): an Incident's audit history is part of its evidence, and this store can
 * only drop whole days. So while anything in the Space is held ({@code ObjectAccess#legalHolds}), the cutoff is pulled
 * back to the UTC day the earliest held evidence began, and no partition from that day on is removed. Releasing the
 * last hold restores the plain window on the next run. A hold that cannot be read refuses the run: nothing is pruned.
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
        LocalDate window = LocalDate.now(ZoneOffset.UTC).minusDays(days);
        LocalDate cutoff = window;
        String heldNote = "";
        if (host.objects().isPresent()) {
            java.util.Map<String, Long> holds;
            try {
                holds = host.objects().get().legalHolds();
            } catch (RuntimeException e) {
                throw new IllegalStateException("event_prune: could not read the legal holds of this Space, so nothing was pruned: "
                        + e.getMessage(), e);
            }
            if (!holds.isEmpty()) {
                LocalDate floor = java.time.Instant.ofEpochMilli(java.util.Collections.min(holds.values()))
                        .atZone(ZoneOffset.UTC).toLocalDate();
                if (floor.isBefore(cutoff)) cutoff = floor;
                heldNote = "; " + holds.size() + " object(s) under legal hold keep every day from " + floor;
            }
        }
        final LocalDate prunedBefore = cutoff;
        int n = store.get().prune(cutoff, dryRun);
        if (n < 0)
            return JobResult.ok("event_prune: the event store keeps nothing durable (-Devents.backend=memory) "
                    + "— nothing to prune", (System.nanoTime() - t0) / 1_000_000L);
        if (dryRun)
            return JobResult.ok("event_prune[dry-run]: would remove " + n + " day-partition(s) before " + cutoff
                    + " (retention " + days + "d" + heldNote + ")", (System.nanoTime() - t0) / 1_000_000L);
        // ASSURE-AUDIT-CHAIN-1: record what was ACTUALLY removed, as an AUDIT row on the chain (so it is linked,
        // anchored and verified like every other). /audit/verify accepts anchored rows being gone ONLY for days
        // before the cutoff a verified prune record names — a configured-but-unrun job accounts for nothing.
        String held = heldNote;
        host.attachedEventLog().ifPresent(log -> log.emit(com.gamma.audit.Event.builder(com.gamma.audit.EventType.AUDIT)
                .source("job").message("event_prune removed " + n + " day-partition(s) before " + prunedBefore + held)
                .actor("job:" + cfg.name()).actorType("system")
                .action("events.pruned").actionCategory("retention")
                .target("job", cfg.name())
                .attr("prune_before", prunedBefore).attr("partitions_removed", n).attr("retention_days", days)
                .attr("legal_hold_floor", prunedBefore.equals(window) ? null : prunedBefore)));
        return JobResult.ok("event_prune: removed " + n + " day-partition(s) before " + cutoff
                + " (retention " + days + "d" + heldNote + ")", (System.nanoTime() - t0) / 1_000_000L);
    }
}
