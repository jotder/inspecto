package com.gamma.notify;

/**
 * One normalised provider callback event (BACKLOG D8) — the adapter's output, already mapped out of the
 * provider's own vocabulary so nothing downstream knows which provider it came from.
 *
 * @param deliveryId  our correlation id, echoed back by the provider (see
 *                    {@link DeliveryStatusAdapter} — we mint this, we do not capture provider ids)
 * @param status      the normalised status
 * @param ts          when the provider says the event happened, epoch millis
 * @param providerRaw the raw provider payload for this event, kept only to make a
 *                    {@link DeliveryStatus#UNKNOWN} diagnosable; {@code null} for a classified event
 * @since 4.0.0
 */
public record DeliveryEvent(String deliveryId, DeliveryStatus status, long ts, String providerRaw) {

    public DeliveryEvent {
        if (status == null) status = DeliveryStatus.UNKNOWN;
        if (ts <= 0) ts = System.currentTimeMillis();
    }
}
