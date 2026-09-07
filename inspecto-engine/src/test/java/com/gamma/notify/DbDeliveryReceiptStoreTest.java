package com.gamma.notify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DbDeliveryReceiptStore} — the durable twin, run against real DuckDB.
 *
 * <p>Most of the store's contract is already pinned for the in-memory implementation by
 * {@link DeliveryReceiptStoreTest}. ⚠ That is exactly why the cases here are the ones a SECOND
 * implementation can get wrong on its own: the JSON round trip of the status map, survival across a
 * reopen (the whole reason the store exists), an idempotent re-add over a primary key, and pruning by SQL
 * rather than by iteration. Re-testing the ordering rules would only re-test
 * {@link DeliveryReceipt#withStatus}, which both implementations share.
 */
class DbDeliveryReceiptStoreTest {

    private static DeliveryReceipt receipt(String id, String notificationId, long sentAt) {
        return new DeliveryReceipt(id, notificationId, "c1", "ops@x.com", sentAt, Map.of(), null, false);
    }

    private static String url(Path dir) {
        return "jdbc:duckdb:" + dir.resolve("receipts.db").toAbsolutePath().toString().replace('\\', '/');
    }

    /** The only reason this store exists: a bounce has to outlive the process that recorded it. */
    @Test
    void aStampedBounceSurvivesAReopen(@TempDir Path dir) throws Exception {
        try (DbDeliveryReceiptStore store = DbDeliveryReceiptStore.open(url(dir), null, null)) {
            store.add(receipt("d1", "n1", 1000L));
            store.stamp("d1", DeliveryStatus.BOUNCED_HARD, 2000L, "550 no such user");
        }
        try (DbDeliveryReceiptStore reopened = DbDeliveryReceiptStore.open(url(dir), null, null)) {
            DeliveryReceipt r = reopened.get("d1").orElseThrow();
            assertTrue(r.hardBounced(), "the hard bounce that must suppress this address survived");
            assertEquals(2000L, r.statusAt().get(DeliveryStatus.BOUNCED_HARD));
            assertEquals("550 no such user", r.providerRaw());
            assertEquals("ops@x.com", r.target());
            assertEquals("n1", r.notificationId());
            assertFalse(r.digest());
        }
    }

    /**
     * The status history is one JSON column, so the whole map has to survive the round trip — including
     * the delivered-then-complaint pair the map exists for.
     */
    @Test
    void everyStatusInTheMapRoundTripsThroughTheJsonColumn(@TempDir Path dir) throws Exception {
        try (DbDeliveryReceiptStore store = DbDeliveryReceiptStore.open(url(dir), null, null)) {
            store.add(receipt("d1", "n1", 1000L));
            store.stamp("d1", DeliveryStatus.DELIVERED, 2000L, null);
            store.stamp("d1", DeliveryStatus.COMPLAINED, 3000L, null);

            Map<DeliveryStatus, Long> back = store.get("d1").orElseThrow().statusAt();
            assertEquals(Map.of(DeliveryStatus.DELIVERED, 2000L, DeliveryStatus.COMPLAINED, 3000L), back);
        }
    }

    /**
     * ⚠ A receipt with no callback yet stores {@code {}} — not null, not a NULL column. `fromJson` on a
     * null would be the easy way to a NullPointerException on the first read of a fresh receipt.
     */
    @Test
    void aReceiptWithNoCallbackYetReadsBackAsAnEmptyMap(@TempDir Path dir) throws Exception {
        try (DbDeliveryReceiptStore store = DbDeliveryReceiptStore.open(url(dir), null, null)) {
            store.add(receipt("d1", "n1", 1000L));
            assertEquals(Map.of(), store.get("d1").orElseThrow().statusAt());
        }
    }

    /**
     * The in-memory store's {@code put} is idempotent; a primary key is not. Re-adding the same delivery
     * id must replace, not throw — otherwise a resend reusing an id takes the whole notification down.
     */
    @Test
    void reAddingTheSameDeliveryIdReplacesRatherThanThrowing(@TempDir Path dir) throws Exception {
        try (DbDeliveryReceiptStore store = DbDeliveryReceiptStore.open(url(dir), null, null)) {
            store.add(receipt("d1", "n1", 1000L));
            store.add(new DeliveryReceipt("d1", "n2", "c2", "other@x.com", 5000L, Map.of(), null, true));

            DeliveryReceipt r = store.get("d1").orElseThrow();
            assertEquals("n2", r.notificationId(), "the second add won");
            assertEquals("other@x.com", r.target());
            assertTrue(r.digest());
            assertEquals(1, store.recent(10).size(), "and left exactly one row");
        }
    }

    /**
     * The contract that keeps a provider from retrying forever: an unknown id is a normal miss, because
     * receipts are prunable and the callback may arrive after the receipt is gone.
     */
    @Test
    void stampingAnUnknownDeliveryIdIsAMissNotAnError(@TempDir Path dir) throws Exception {
        try (DbDeliveryReceiptStore store = DbDeliveryReceiptStore.open(url(dir), null, null)) {
            assertTrue(store.stamp("nope", DeliveryStatus.DELIVERED, 1L, null).isEmpty());
            assertEquals(0, store.recent(10).size(), "and wrote nothing");
        }
    }

    @Test
    void ordersNewestFirstAndScopesForNotification(@TempDir Path dir) throws Exception {
        try (DbDeliveryReceiptStore store = DbDeliveryReceiptStore.open(url(dir), null, null)) {
            store.add(receipt("d1", "n1", 1000L));
            store.add(receipt("d2", "n1", 3000L));
            store.add(receipt("d3", "n2", 2000L));

            assertEquals(List.of("d2", "d3", "d1"),
                    store.recent(10).stream().map(DeliveryReceipt::deliveryId).toList());
            assertEquals(List.of("d2", "d1"),
                    store.forNotification("n1").stream().map(DeliveryReceipt::deliveryId).toList());
            assertEquals(List.of(), store.forNotification(null));
            assertEquals(1, store.recent(1).size(), "limit is applied");
            assertEquals(List.of(), store.recent(0));
        }
    }

    /** `countPrunable` is the dry-run preview for the receipt_prune maintenance task; it must not delete. */
    @Test
    void countPrunablePreviewsAndPruneDeletes(@TempDir Path dir) throws Exception {
        try (DbDeliveryReceiptStore store = DbDeliveryReceiptStore.open(url(dir), null, null)) {
            store.add(receipt("old", "n1", 1000L));
            store.add(receipt("new", "n1", 9000L));

            assertEquals(1, store.countPrunable(5000L));
            assertEquals(2, store.recent(10).size(), "the preview deleted nothing");

            assertEquals(1, store.prune(5000L));
            assertEquals(List.of("new"), store.recent(10).stream().map(DeliveryReceipt::deliveryId).toList());
            assertEquals(0, store.prune(5000L), "pruning again removes nothing");
        }
    }
}
