package com.gamma.notify;

import java.util.List;
import java.util.Optional;

/**
 * Persistence seam for {@link DeliveryReceipt}s (BACKLOG D8). Mirrors {@link NotificationStore}: mutable
 * records with real state transitions (a receipt gains statuses over its life), thread-safe because
 * receipts are written from the event dispatcher and stamped from HTTP callback threads.
 *
 * @since 4.0.0
 */
public interface DeliveryReceiptStore extends AutoCloseable {

    /** Record an attempted delivery, before the transport is called. */
    DeliveryReceipt add(DeliveryReceipt receipt);

    /** The receipt with this delivery id, or empty — an unknown id is expected (see below), not an error. */
    Optional<DeliveryReceipt> get(String deliveryId);

    /**
     * Stamp a status onto a receipt, keeping any status already recorded.
     *
     * @return the updated receipt, or <b>empty when {@code deliveryId} is unknown</b>. That is a normal
     *         outcome, not a failure: receipts are prunable, so a callback for one we have forgotten must
     *         be accepted rather than rejected — providers retry on a non-2xx forever.
     */
    Optional<DeliveryReceipt> stamp(String deliveryId, DeliveryStatus status, long ts, String providerRaw);

    /** Receipts for one notification, newest send first. */
    List<DeliveryReceipt> forNotification(String notificationId);

    /** The newest {@code limit} receipts, newest send first. */
    List<DeliveryReceipt> recent(int limit);

    /** Count receipts sent before {@code cutoffMs} — the prune dry-run preview. */
    int countPrunable(long cutoffMs);

    /** Permanently forget receipts sent before {@code cutoffMs}; returns how many were removed. */
    int prune(long cutoffMs);

    @Override
    default void close() {}
}
