package com.gamma.job;

import com.gamma.notify.NotificationStore;

import java.time.Duration;

/**
 * The {@code notification_prune} maintenance task: forget in-app notifications older than
 * {@code retention_days} (required — deliberate forgetting, like {@code ledger_prune}), whatever their
 * read/archived state. The feed is per-space and reached through the hosting {@link JobService}; a
 * bare/lazily-created host with no feed attached prunes nothing (fail-open — never throws).
 */
final class NotificationPruneTask {

    private NotificationPruneTask() {}

    static JobResult run(JobConfig cfg, JobService host, boolean dryRun) {
        long days = Long.parseLong(cfg.require("retention_days"));   // required: forgetting is deliberate
        if (days < 1) throw new IllegalArgumentException("notification_prune retention_days must be >= 1");
        long t0 = System.nanoTime();
        var store = host == null ? java.util.Optional.<NotificationStore>empty() : host.notificationStore();
        if (store.isEmpty())
            return JobResult.ok("notification_prune: no notification feed attached — nothing to prune", 0L);
        long cutoff = System.currentTimeMillis() - Duration.ofDays(days).toMillis();
        if (dryRun) {
            int would = store.get().countPrunable(cutoff);
            return JobResult.ok("notification_prune[dry-run]: would remove " + would
                    + " notification(s) older than " + days + "d", (System.nanoTime() - t0) / 1_000_000L);
        }
        int removed = store.get().prune(cutoff);
        return JobResult.ok("notification_prune: removed " + removed + " notification(s) older than "
                + days + "d", (System.nanoTime() - t0) / 1_000_000L);
    }
}
