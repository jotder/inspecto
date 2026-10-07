package com.gamma.job;

import com.gamma.notify.DeliveryReceipt;
import com.gamma.notify.DeliveryReceiptStore;
import com.gamma.notify.NotificationService;

import java.util.List;
import java.util.Optional;

/**
 * The {@code soft_bounce_retry} maintenance task (BACKLOG D8, decided 2026-09-15: <b>soft-bounce retry
 * only</b> — the SES/SNS adapter stays filed with its own review).
 *
 * <p>A <b>soft</b> bounce is a transient failure against an address that is still good — a full mailbox, a
 * server briefly unavailable. {@code SuppressionList} deliberately never suppresses one
 * ({@code BOUNCED_SOFT} is excluded there on purpose), so until now the message was simply dropped: the
 * status was recorded, nothing retried, and the recipient never got it. This sweep is the missing half.
 *
 * <p>🔴 <b>Two clocks, and the distinction is the whole design.</b> {@code DeliveryReceipt.withStatus}
 * keeps the FIRST observation of each status, so {@code statusAt[BOUNCED_SOFT]} never advances however many
 * times an address bounces. A backoff measured from it would therefore measure from a fixed point in the
 * past and every remaining attempt would come due at once on the next sweep — a retry storm wearing the
 * shape of a backoff. The receipt carries {@code lastAttemptAt} as its own retry clock for exactly this.
 *
 * <p>⚠ <b>An attempt is counted whether or not it succeeded.</b> A transport that is permanently
 * unreachable must still exhaust {@code max_attempts}; counting only successes would spin forever on the
 * one case most likely to be broken.
 *
 * <p>⛔ <b>Not a second suppression list.</b> {@code NotificationService.retrySoftBounce} re-asks
 * {@code SuppressionList} at send time, because between the bounce and this sweep the same address may have
 * hard-bounced or complained on another message. Deciding that here would be a second, weaker answer to a
 * question that class owns.
 *
 * <p>Reached through the hosting {@link JobService}; no notification service or receipt store attached
 * retries nothing (fail-open, like {@code receipt_prune}).
 */
final class SoftBounceRetryTask {

    private SoftBounceRetryTask() {}

    /** Default attempt ceiling — small on purpose: a soft bounce that has not cleared in a few tries is
     *  usually not transient after all, and the receipt keeps the evidence either way. */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    /** Default wait before an address is tried again. */
    private static final long DEFAULT_BACKOFF_MINUTES = 60;

    static JobResult run(JobConfig cfg, JobService host, boolean dryRun) {
        long t0 = System.nanoTime();
        int maxAttempts = Integer.parseInt(cfg.opt("max_attempts", String.valueOf(DEFAULT_MAX_ATTEMPTS)));
        if (maxAttempts < 1) throw new IllegalArgumentException("soft_bounce_retry max_attempts must be >= 1");
        long backoffMinutes = Long.parseLong(cfg.opt("backoff_minutes", String.valueOf(DEFAULT_BACKOFF_MINUTES)));
        if (backoffMinutes < 0) throw new IllegalArgumentException("soft_bounce_retry backoff_minutes must be >= 0");

        Optional<DeliveryReceiptStore> store =
                host == null ? Optional.empty() : host.deliveryReceiptStore();
        Optional<NotificationService> notify =
                host == null ? Optional.empty() : host.notificationService();
        if (store.isEmpty())
            return JobResult.ok("soft_bounce_retry: no delivery receipt store attached — nothing to retry", 0L);
        if (notify.isEmpty())
            return JobResult.ok("soft_bounce_retry: no notification service attached — nothing to retry", 0L);

        long now = System.currentTimeMillis();
        long backoffMs = backoffMinutes * 60_000L;
        List<DeliveryReceipt> candidates = store.get().softBounceRetryCandidates(maxAttempts);

        // ⚠ The backoff is applied HERE, not in the store: "has enough time passed" is policy, and the
        // store seam deliberately returns candidates rather than decisions.
        List<DeliveryReceipt> due = candidates.stream().filter(r -> dueNow(r, now, backoffMs)).toList();

        if (dryRun) {
            return JobResult.ok("soft_bounce_retry[dry-run]: would retry " + due.size() + " of "
                    + candidates.size() + " soft-bounced delivery(ies) (max_attempts=" + maxAttempts
                    + ", backoff=" + backoffMinutes + "m)", ms(t0));
        }

        int sent = 0;
        for (DeliveryReceipt r : due) {
            boolean delivered = notify.get().retrySoftBounce(r);
            // Counted either way — see the class note: a failed attempt is still an attempt.
            store.get().recordAttempt(r.deliveryId(), System.currentTimeMillis());
            if (delivered) sent++;
        }
        return JobResult.ok("soft_bounce_retry: retried " + due.size() + " soft-bounced delivery(ies), "
                + sent + " handed to a transport (max_attempts=" + maxAttempts
                + ", backoff=" + backoffMinutes + "m)", ms(t0));
    }

    /**
     * Whether {@code r} has waited out the backoff.
     *
     * <p>⚠ A receipt never retried measures from when we LEARNED of the bounce, not from
     * {@code sentAt} — the gap between sending and the provider's callback is the provider's, not ours,
     * and charging it against the backoff would retry too early.
     */
    private static boolean dueNow(DeliveryReceipt r, long now, long backoffMs) {
        long since = r.lastAttemptAt() > 0
                ? r.lastAttemptAt()
                : r.statusAt().getOrDefault(com.gamma.notify.DeliveryStatus.BOUNCED_SOFT, r.sentAt());
        return now - since >= backoffMs;
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
    }
}
