package com.gamma.notify;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * In-memory {@link DeliveryReceiptStore} — the lean default, mirroring {@link InMemoryNotificationStore}
 * (bounded map keyed by delivery id, all access {@code synchronized}).
 *
 * <p>The bound matters more here than for the notification feed: one notification writes a receipt per
 * external destination, so receipts accumulate faster than notifications. Eviction is oldest-first, which
 * is exactly why {@link #stamp} treats an unknown delivery id as a normal miss — a callback can legitimately
 * arrive after we have forgotten its receipt.
 *
 * @since 4.9.0
 */
public final class InMemoryDeliveryReceiptStore implements DeliveryReceiptStore {

    private static final int MAX_ENTRIES = 5000;

    private final LinkedHashMap<String, DeliveryReceipt> byDeliveryId = new LinkedHashMap<>();

    @Override
    public synchronized DeliveryReceipt add(DeliveryReceipt receipt) {
        byDeliveryId.put(receipt.deliveryId(), receipt);
        if (byDeliveryId.size() > MAX_ENTRIES) {
            var it = byDeliveryId.keySet().iterator();
            if (it.hasNext()) { it.next(); it.remove(); }   // evict oldest (insertion order)
        }
        return receipt;
    }

    @Override
    public synchronized Optional<DeliveryReceipt> get(String deliveryId) {
        return Optional.ofNullable(byDeliveryId.get(deliveryId));
    }

    @Override
    public synchronized Optional<DeliveryReceipt> stamp(String deliveryId, DeliveryStatus status, long ts,
                                                        String providerRaw) {
        DeliveryReceipt existing = byDeliveryId.get(deliveryId);
        if (existing == null) return Optional.empty();
        DeliveryReceipt updated = existing.withStatus(status, ts, providerRaw);
        byDeliveryId.put(deliveryId, updated);   // in-place replace, as markRead/archive do
        return Optional.of(updated);
    }

    @Override
    public synchronized List<DeliveryReceipt> forNotification(String notificationId) {
        List<DeliveryReceipt> out = new ArrayList<>();
        for (DeliveryReceipt r : byDeliveryId.values()) {
            if (notificationId != null && notificationId.equals(r.notificationId())) out.add(r);
        }
        out.sort(Comparator.comparingLong(DeliveryReceipt::sentAt).reversed());
        return out;
    }

    @Override
    public synchronized List<DeliveryReceipt> recent(int limit) {
        List<DeliveryReceipt> out = new ArrayList<>(byDeliveryId.values());
        out.sort(Comparator.comparingLong(DeliveryReceipt::sentAt).reversed());
        return out.size() > limit ? new ArrayList<>(out.subList(0, Math.max(limit, 0))) : out;
    }

    @Override
    public synchronized int countPrunable(long cutoffMs) {
        int n = 0;
        for (DeliveryReceipt r : byDeliveryId.values()) if (r.sentAt() < cutoffMs) n++;
        return n;
    }

    @Override
    public synchronized int prune(long cutoffMs) {
        int before = byDeliveryId.size();
        byDeliveryId.values().removeIf(r -> r.sentAt() < cutoffMs);
        return before - byDeliveryId.size();
    }
}
