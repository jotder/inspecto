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
 * @since 4.9.0
 */
public record DeliveryReceipt(String deliveryId, String notificationId, String channelConfigId,
                              String target, long sentAt, Map<DeliveryStatus, Long> statusAt,
                              String providerRaw, boolean digest) {

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
                providerRaw != null ? providerRaw : raw, digest);
    }

    /** Whether the destination is known bad — a hard bounce only, never a soft one. */
    public boolean hardBounced() {
        return statusAt.containsKey(DeliveryStatus.BOUNCED_HARD);
    }
}
