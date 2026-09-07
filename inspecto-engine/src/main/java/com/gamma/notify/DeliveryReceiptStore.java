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

    /**
     * The most recent receipt for {@code target} that carries {@code status}, or empty. The lookup
     * per-recipient suppression is built on (D8-SUPPRESS-1): "has this address ever complained", "has it
     * hard-bounced lately".
     *
     * <p><b>A default rather than an abstract method</b>, so an existing implementation keeps compiling:
     * the scan below is correct for any store, and {@link DbDeliveryReceiptStore} overrides it with a
     * targeted query. ⚠ The scan is bounded by {@link #SUPPRESSION_SCAN} — a bound is exactly what makes
     * this safe on the in-memory store (itself capped at 5000) and exactly what makes it WRONG on a large
     * durable one, which is why the durable store must not inherit it.
     *
     * @since 4.0.0
     */
    default Optional<DeliveryReceipt> latestWithStatus(String target, DeliveryStatus status) {
        if (target == null || target.isBlank() || status == null) return Optional.empty();
        for (DeliveryReceipt r : recent(SUPPRESSION_SCAN)) {          // already newest-first
            if (target.equals(r.target()) && r.statusAt().containsKey(status)) return Optional.of(r);
        }
        return Optional.empty();
    }

    /** How far back {@link #latestWithStatus}'s default scan looks. See its note. */
    int SUPPRESSION_SCAN = 5000;

    /**
     * Whether this store persists receipts beyond the process, and beyond a bound.
     *
     * <p>Exists so {@link SuppressionList} can ARM ITSELF only where suppression can be honest. The
     * in-memory store is capped and evicts oldest-first, so asking it "has this address ever bounced"
     * returns "no" both when the address is clean and when the evidence was evicted — and those two
     * answers must not be conflated into a silent "deliver". ⚠ Default {@code false}: a new implementation
     * is assumed non-durable until it says otherwise, because the failure of guessing wrong in that
     * direction is a suppression list that quietly does nothing.
     *
     * @since 4.0.0
     */
    default boolean durable() {
        return false;
    }

    @Override
    default void close() {}
}
