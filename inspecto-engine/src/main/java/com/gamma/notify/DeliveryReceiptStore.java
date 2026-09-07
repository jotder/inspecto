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

    /**
     * Distinct targets with at least one receipt carrying {@code status} — the candidate set the
     * suppression list is built from. ⚠ Candidates, not decisions: {@link SuppressionList} still runs the
     * authoritative per-target check, so an over-broad implementation here can only cost work, never
     * suppress something it should not.
     *
     * @since 4.0.0
     */
    default List<String> targetsWithStatus(DeliveryStatus status) {
        if (status == null) return List.of();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (DeliveryReceipt r : recent(SUPPRESSION_SCAN)) {
            if (r.target() != null && !r.target().isBlank() && r.statusAt().containsKey(status)) {
                out.add(r.target());
            }
        }
        return List.copyOf(out);
    }

    /**
     * Record an operator's decision to deliver to {@code target} again despite its history
     * (`DELETE /notifications/suppressions`, decided 2026-09-07).
     *
     * <p>🔴 <b>It forgives history up to {@code at} — it does not delete anything.</b> The bounce and
     * complaint receipts stay, because they are the audit trail that says why the address was suppressed
     * and that someone chose to re-enable it. A LATER suppressing event is therefore not forgiven and
     * re-suppresses on its own, with no clearing job to run and nothing to expire: that is the whole
     * mechanism behind "cleared by the next suppressing event", and it is a timestamp comparison rather
     * than state that can drift.
     *
     * <p>⚠ An address forgiven forever would be worse than no feature at all — it would permanently mask
     * a genuinely dead destination — which is exactly what deleting the receipts instead would have done.
     *
     * @return whether the override was recorded; {@code false} on a store that cannot hold one
     * @since 4.0.0
     */
    default boolean unsuppress(String target, long at, String actor) {
        return false;
    }

    /** When an operator last forgave {@code target}'s history, or empty. See {@link #unsuppress}. */
    default Optional<Long> unsuppressedAt(String target) {
        return Optional.empty();
    }

    @Override
    default void close() {}
}
