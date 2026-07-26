package com.gamma.notify;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The D8 delivery-receipt model and its in-memory store: the additive {@code statusAt} map (the whole
 * reason a single mutable enum was rejected), the unknown-id miss that keeps a pruned receipt from
 * turning into an infinite provider retry, and the store's ordering/pruning contract.
 */
class DeliveryReceiptStoreTest {

    private static DeliveryReceipt receipt(String id, String notificationId, long sentAt) {
        return new DeliveryReceipt(id, notificationId, "c1", "ops@x.com", sentAt, Map.of(), null, false);
    }

    @Test
    void deliveredThenComplainedKeepsBothWithTheirOwnTimestamps() {
        DeliveryReceiptStore store = new InMemoryDeliveryReceiptStore();
        store.add(receipt("d1", "n1", 1000L));

        store.stamp("d1", DeliveryStatus.DELIVERED, 2000L, null);
        DeliveryReceipt after = store.stamp("d1", DeliveryStatus.COMPLAINED, 3000L, null).orElseThrow();

        // The point of the map: a spam-button click arrives as delivered THEN complaint, and the later
        // status must not erase the earlier one.
        assertEquals(2000L, after.statusAt().get(DeliveryStatus.DELIVERED));
        assertEquals(3000L, after.statusAt().get(DeliveryStatus.COMPLAINED));
        assertFalse(after.hardBounced(), "a complaint is not a hard bounce");
    }

    @Test
    void aRetriedCallbackDoesNotShiftTheRecordedTime() {
        DeliveryReceiptStore store = new InMemoryDeliveryReceiptStore();
        store.add(receipt("d1", "n1", 1000L));

        store.stamp("d1", DeliveryStatus.DELIVERED, 2000L, null);
        DeliveryReceipt after = store.stamp("d1", DeliveryStatus.DELIVERED, 9000L, null).orElseThrow();

        assertEquals(2000L, after.statusAt().get(DeliveryStatus.DELIVERED),
                "first observation wins — providers retry, and a shifting time would destabilise ordering");
    }

    @Test
    void hardBounceIsFlaggedAndASoftBounceIsNot() {
        DeliveryReceiptStore store = new InMemoryDeliveryReceiptStore();
        store.add(receipt("soft", "n1", 1000L));
        store.add(receipt("hard", "n1", 1000L));

        assertFalse(store.stamp("soft", DeliveryStatus.BOUNCED_SOFT, 2000L, null).orElseThrow().hardBounced(),
                "a full mailbox is not a dead address");
        assertTrue(store.stamp("hard", DeliveryStatus.BOUNCED_HARD, 2000L, null).orElseThrow().hardBounced());
    }

    @Test
    void anUnknownEventKeepsItsRawPayload() {
        DeliveryReceiptStore store = new InMemoryDeliveryReceiptStore();
        store.add(receipt("d1", "n1", 1000L));

        DeliveryReceipt after = store.stamp("d1", DeliveryStatus.UNKNOWN, 2000L, "{\"event\":\"weird\"}")
                .orElseThrow();

        assertEquals("{\"event\":\"weird\"}", after.providerRaw(),
                "an unrecognised event is recorded with its payload, never dropped or guessed at");
    }

    @Test
    void stampingAnUnknownDeliveryIdIsAMissNotAFailure() {
        DeliveryReceiptStore store = new InMemoryDeliveryReceiptStore();

        assertTrue(store.stamp("pruned-away", DeliveryStatus.DELIVERED, 2000L, null).isEmpty(),
                "a callback for a receipt we already forgot must be answerable with a 2xx, not an error");
        assertTrue(store.get("pruned-away").isEmpty());
    }

    @Test
    void recentAndForNotificationAreNewestSendFirst() {
        DeliveryReceiptStore store = new InMemoryDeliveryReceiptStore();
        store.add(receipt("old", "n1", 1000L));
        store.add(receipt("new", "n1", 3000L));
        store.add(receipt("other", "n2", 2000L));

        assertEquals(List.of("new", "other", "old"),
                store.recent(10).stream().map(DeliveryReceipt::deliveryId).toList());
        assertEquals(List.of("new", "old"),
                store.forNotification("n1").stream().map(DeliveryReceipt::deliveryId).toList());
        assertEquals(1, store.recent(1).size(), "limit applies after ordering");
    }

    @Test
    void pruneRemovesOnlyReceiptsSentBeforeTheCutoff() {
        DeliveryReceiptStore store = new InMemoryDeliveryReceiptStore();
        store.add(receipt("old", "n1", 1000L));
        store.add(receipt("new", "n1", 5000L));

        assertEquals(1, store.countPrunable(2000L), "the dry-run preview counts without removing");
        assertEquals(2, store.recent(10).size());
        assertEquals(1, store.prune(2000L));
        assertEquals(List.of("new"), store.recent(10).stream().map(DeliveryReceipt::deliveryId).toList());
    }

    @Test
    void aReceiptMintsItsOwnIdAndSentAtWhenNotGiven() {
        DeliveryReceipt r = new DeliveryReceipt(null, "n1", null, null, 0L, null, null, false);

        assertNotNull(r.deliveryId());
        assertFalse(r.deliveryId().contains("-"), "hyphen-free so it survives an SMTP Message-ID unaltered");
        assertTrue(r.sentAt() > 0);
        assertTrue(r.statusAt().isEmpty(), "no status until a callback arrives");
    }
}
