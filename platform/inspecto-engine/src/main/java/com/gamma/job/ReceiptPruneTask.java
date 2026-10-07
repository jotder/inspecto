package com.gamma.job;

import com.gamma.notify.DeliveryReceiptStore;

import java.time.Duration;

/**
 * The {@code receipt_prune} maintenance task (BACKLOG D8): forget delivery receipts sent before
 * {@code retention_days} (required — deliberate forgetting, like {@code notification_prune}), whatever
 * status they carry. Receipts accumulate per <i>external delivery</i>, i.e. faster than notifications, so
 * this is the sweep that bounds them; the in-memory store's oldest-first cap is a backstop, not retention.
 * Reached through the hosting {@link JobService}; no store attached prunes nothing (fail-open).
 */
final class ReceiptPruneTask {

    private ReceiptPruneTask() {}

    static JobResult run(JobConfig cfg, JobService host, boolean dryRun) {
        long days = Long.parseLong(cfg.require("retention_days"));   // required: forgetting is deliberate
        if (days < 1) throw new IllegalArgumentException("receipt_prune retention_days must be >= 1");
        long t0 = System.nanoTime();
        var store = host == null ? java.util.Optional.<DeliveryReceiptStore>empty() : host.deliveryReceiptStore();
        if (store.isEmpty())
            return JobResult.ok("receipt_prune: no delivery receipt store attached — nothing to prune", 0L);
        long cutoff = System.currentTimeMillis() - Duration.ofDays(days).toMillis();
        if (dryRun) {
            int would = store.get().countPrunable(cutoff);
            return JobResult.ok("receipt_prune[dry-run]: would remove " + would
                    + " receipt(s) older than " + days + "d", (System.nanoTime() - t0) / 1_000_000L);
        }
        int removed = store.get().prune(cutoff);
        return JobResult.ok("receipt_prune: removed " + removed + " receipt(s) older than "
                + days + "d", (System.nanoTime() - t0) / 1_000_000L);
    }
}
