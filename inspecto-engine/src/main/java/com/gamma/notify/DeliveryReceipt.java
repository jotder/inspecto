package com.gamma.notify;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * One attempted delivery of a {@link Notification} to one external destination, and what the provider
 * later said about it (BACKLOG D8).
 *
 * <p><b>Why this is a separate record and not fields on {@link Notification}.</b> One notification fans
 * out to several destinations, so status is per <i>delivery</i>, not per notification — putting it on the
 * notification could only ever record one destination's fate. {@link Notification} stays an immutable
 * record with no new fields.
 *
 * <p><b>Why {@code statusAt} is a map and not one status field.</b> D8 decided per-status timestamps for a
 * concrete reason: a spam-button click produces {@code delivered} <i>then</i> {@code complaint} for the
 * same message, and a single mutable enum loses that ordering — the later status would erase the earlier.
 * Each callback stamps its own slot instead, so the history is additive.
 *
 * @param deliveryId      the correlation id we mint and embed in the outbound message
 * @param notificationId  the notification delivered, or the digest id for a batched send (see below)
 * @param channelConfigId the persisted {@link ChannelConfig} destination, or {@code null} for an
 *                        SPI-configured channel with no managed destination
 * @param target          the address / URL delivered to
 * @param sentAt          when we handed the message to the transport, epoch millis
 * @param statusAt        each observed status and when it happened; empty until a callback arrives
 * @param providerRaw     the raw payload of the first unclassifiable event, else {@code null}
 * @param digest          {@code true} when this receipt covers a <b>digest</b> delivery batching several
 *                        notifications into one message. This is the one place the per-delivery model is
 *                        lossy: a bounce tells us the digest bounced, not which notification was in it.
 * @param attemptCount    how many times delivery has been ATTEMPTED — {@code 0} for a receipt never
 *                        retried, so the original send is not counted. Only the soft-bounce retry sweep
 *                        increments it.
 * @param lastAttemptAt   when the most recent attempt was made, epoch millis; {@code 0} when never
 *                        retried.
 *                        <p>🔴 <b>This cannot be derived from {@link #statusAt}, and that is why it
 *                        exists.</b> {@link #withStatus} keeps the FIRST observation of each status on
 *                        purpose, so {@code statusAt.get(BOUNCED_SOFT)} stays pinned at the first bounce
 *                        however many times the address bounces again. Computing a backoff from it would
 *                        therefore measure from a fixed point in the past, and every remaining attempt
 *                        would come due at once on the next sweep — a retry storm wearing the shape of a
 *                        backoff. The retry clock has to be its own field.
 * @since 4.0.0
 */
public record DeliveryReceipt(String deliveryId, String notificationId, String channelConfigId,
                              String target, long sentAt, Map<DeliveryStatus, Long> statusAt,
                              String providerRaw, boolean digest,
                              int attemptCount, long lastAttemptAt) {

    /**
     * The pre-retry shape ({@code attemptCount = 0}, {@code lastAttemptAt = 0}) — every send path opens a
     * receipt that has not been retried, so this is what they all call.
     *
     * <p>⚠ Kept as a convenience constructor rather than widening the 15 existing construction sites: a
     * receipt with no retry history is the normal case, and making every caller say {@code 0, 0} would add
     * noise at fifteen places to serve one.
     */
    public DeliveryReceipt(String deliveryId, String notificationId, String channelConfigId,
                           String target, long sentAt, Map<DeliveryStatus, Long> statusAt,
                           String providerRaw, boolean digest) {
        this(deliveryId, notificationId, channelConfigId, target, sentAt, statusAt, providerRaw, digest, 0, 0);
    }

    public DeliveryReceipt {
        if (deliveryId == null || deliveryId.isBlank()) deliveryId = newDeliveryId();
        if (sentAt == 0) sentAt = System.currentTimeMillis();
        statusAt = statusAt == null || statusAt.isEmpty()
                ? Map.of()
                : Map.copyOf(statusAt);
    }

    /** A fresh correlation id. Hyphen-free so it survives an SMTP {@code Message-ID} unaltered. */
    public static String newDeliveryId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** This receipt with {@code status} stamped at {@code ts}, keeping every status already recorded. */
    public DeliveryReceipt withStatus(DeliveryStatus status, long ts, String raw) {
        Map<DeliveryStatus, Long> merged = new EnumMap<>(DeliveryStatus.class);
        merged.putAll(statusAt);
        // First observation of a status wins: a provider retrying the same callback must not shift the
        // recorded time, which is what makes the delivered-then-complaint ordering stable.
        merged.putIfAbsent(status, ts);
        return new DeliveryReceipt(deliveryId, notificationId, channelConfigId, target, sentAt, merged,
                providerRaw != null ? providerRaw : raw, digest, attemptCount, lastAttemptAt);
    }

    /**
     * This receipt with one more delivery attempt recorded at {@code at} — the soft-bounce retry sweep's
     * only mutation ({@code D8} soft-bounce retry).
     *
     * <p>⚠ Deliberately separate from {@link #withStatus}: a retry is something WE did, a status is
     * something the provider told us. Folding the attempt into the status map would both lose the count
     * (one slot per status) and let a provider callback move the retry clock.
     */
    public DeliveryReceipt withAttempt(long at) {
        return new DeliveryReceipt(deliveryId, notificationId, channelConfigId, target, sentAt, statusAt,
                providerRaw, digest, attemptCount + 1, at);
    }

    /**
     * Whether this receipt is currently soft-bounced — the latest word from the provider is a transient
     * failure, with no later {@code DELIVERED} or hard outcome superseding it.
     *
     * <p>🔴 A plain {@code containsKey(BOUNCED_SOFT)} is NOT enough: a receipt that soft-bounced and then
     * delivered on retry keeps BOTH stamps forever (first-observation-wins), so retrying on the bare
     * presence of the key would re-send a message the recipient already has.
     */
    public boolean softBouncedAndUnresolved() {
        Long soft = statusAt.get(DeliveryStatus.BOUNCED_SOFT);
        if (soft == null) return false;
        for (DeliveryStatus s : RESOLVES_A_SOFT_BOUNCE) {
            Long at = statusAt.get(s);
            if (at != null && at >= soft) return false;
        }
        return true;
    }

    /**
     * The statuses that settle a soft bounce, so retrying would be wrong.
     *
     * <p>{@code DELIVERED} — it got through. {@code BOUNCED_HARD} — the address is dead, and
     * {@code SuppressionList} owns it from there. {@code COMPLAINED} — the recipient called it spam, and
     * re-sending is the one action guaranteed to make that worse.
     *
     * <p>⛔ {@code UNKNOWN} is deliberately NOT here: an event the adapter could not classify tells us
     * nothing about delivery, so treating it as a resolution would silently abandon a retryable message.
     */
    private static final java.util.EnumSet<DeliveryStatus> RESOLVES_A_SOFT_BOUNCE = java.util.EnumSet.of(
            DeliveryStatus.DELIVERED, DeliveryStatus.BOUNCED_HARD, DeliveryStatus.COMPLAINED);

    /** Whether the destination is known bad — a hard bounce only, never a soft one. */
    public boolean hardBounced() {
        return statusAt.containsKey(DeliveryStatus.BOUNCED_HARD);
    }
}
