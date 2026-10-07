package com.gamma.notify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `D8-SUPPRESS-1` — per-recipient suppression. The rules are short; the cases that matter are the ones
 * where getting them wrong is silent.
 */
class SuppressionListTest {

    private static DeliveryReceipt to(String target, long sentAt) {
        return new DeliveryReceipt(null, "n1", "c1", target, sentAt, Map.of(), null, false);
    }

    private static DbDeliveryReceiptStore durable(Path dir) throws Exception {
        return DbDeliveryReceiptStore.open(
                "jdbc:duckdb:" + dir.resolve("r.db").toAbsolutePath().toString().replace('\\', '/'),
                null, null);
    }

    @Test
    void aComplaintSuppressesPermanently(@TempDir Path dir) throws Exception {
        try (DbDeliveryReceiptStore store = durable(dir)) {
            DeliveryReceipt r = store.add(to("spam@x.com", 1_000L));
            store.stamp(r.deliveryId(), DeliveryStatus.COMPLAINED, 2_000L, null);

            SuppressionList list = SuppressionList.of(store, Duration.ofDays(30));
            // A decade later it is still suppressed — no elapsed time makes sending after a spam report OK.
            Optional<String> why = list.reasonToSuppress("spam@x.com", 2_000L + Duration.ofDays(3650).toMillis());
            assertTrue(why.isPresent());
            assertTrue(why.get().contains("permanent"), why.get());
        }
    }

    @Test
    void aHardBounceSuppressesUntilTheTtlExpiresAndThenStops(@TempDir Path dir) throws Exception {
        try (DbDeliveryReceiptStore store = durable(dir)) {
            DeliveryReceipt r = store.add(to("gone@x.com", 1_000L));
            store.stamp(r.deliveryId(), DeliveryStatus.BOUNCED_HARD, 2_000L, "550 no such user");

            SuppressionList list = SuppressionList.of(store, Duration.ofDays(30));
            long ttl = Duration.ofDays(30).toMillis();

            assertTrue(list.reasonToSuppress("gone@x.com", 2_000L + ttl - 1).isPresent(), "inside the window");
            // The boundary is exclusive on the far side: at exactly expiry the address is deliverable
            // again. Addresses do get recreated, which is why this is a TTL and not a permanent ban.
            assertTrue(list.reasonToSuppress("gone@x.com", 2_000L + ttl).isEmpty(), "at expiry");
            assertTrue(list.reasonToSuppress("gone@x.com", 2_000L + ttl + 1).isEmpty(), "past expiry");
        }
    }

    /**
     * ⛔ The distinction `DeliveryStatus` exists to preserve. A full mailbox is not a bad address, and
     * suppressing on it would silently stop mail to every recipient who was once over quota.
     */
    @Test
    void aSoftBounceNeverSuppresses(@TempDir Path dir) throws Exception {
        try (DbDeliveryReceiptStore store = durable(dir)) {
            DeliveryReceipt r = store.add(to("full@x.com", 1_000L));
            store.stamp(r.deliveryId(), DeliveryStatus.BOUNCED_SOFT, 2_000L, "452 over quota");

            assertTrue(SuppressionList.of(store, Duration.ofDays(30))
                    .reasonToSuppress("full@x.com", 3_000L).isEmpty());
        }
    }

    @Test
    void deliveredAndUnknownAddressesAreNeverSuppressed(@TempDir Path dir) throws Exception {
        try (DbDeliveryReceiptStore store = durable(dir)) {
            DeliveryReceipt r = store.add(to("ok@x.com", 1_000L));
            store.stamp(r.deliveryId(), DeliveryStatus.DELIVERED, 2_000L, null);

            SuppressionList list = SuppressionList.of(store, Duration.ofDays(30));
            assertTrue(list.reasonToSuppress("ok@x.com", 3_000L).isEmpty());
            assertTrue(list.reasonToSuppress("never-seen@x.com", 3_000L).isEmpty());
            assertTrue(list.reasonToSuppress(null, 3_000L).isEmpty(), "no target ⇒ nothing to match on");
            assertTrue(list.reasonToSuppress("  ", 3_000L).isEmpty());
        }
    }

    /**
     * 🔴 The failure this whole design guards against: suppression over an evicting cache answers "nothing
     * to suppress" for an address it simply forgot, which is indistinguishable from a clean address. So it
     * must not arm at all over a non-durable store.
     */
    @Test
    void doesNotArmOverAnInMemoryStore_becauseSuppressingNothingLooksLikeNothingToSuppress() {
        InMemoryDeliveryReceiptStore memory = new InMemoryDeliveryReceiptStore();
        DeliveryReceipt r = memory.add(to("gone@x.com", 1_000L));
        memory.stamp(r.deliveryId(), DeliveryStatus.BOUNCED_HARD, 2_000L, null);

        assertFalse(memory.durable());
        SuppressionList list = SuppressionList.fromProperties(memory);
        assertFalse(list.armed(), "an in-memory store cannot honour suppression");
        // …and a disarmed list is inert rather than throwing, so the caller needs no branch.
        assertTrue(list.reasonToSuppress("gone@x.com", 3_000L).isEmpty());
    }

    @Test
    void fromPropertiesArmsOverADurableStoreAndHonoursTheOffSwitch(@TempDir Path dir) throws Exception {
        try (DbDeliveryReceiptStore store = durable(dir)) {
            assertTrue(store.durable());
            assertTrue(SuppressionList.fromProperties(store).armed());

            System.setProperty(SuppressionList.ENABLED_PROPERTY, "off");
            try {
                assertFalse(SuppressionList.fromProperties(store).armed(), "-Dnotify.suppression=off");
            } finally {
                System.clearProperty(SuppressionList.ENABLED_PROPERTY);
            }
            assertFalse(SuppressionList.fromProperties(null).armed(), "no store at all");
        }
    }

    /**
     * ⚠ A malformed window must not fail the boot — a deliverability nicety is never a reason a service
     * does not start — and must not degrade to "no suppression" either, which would be the silent failure
     * again. It falls back to the documented default.
     */
    @Test
    void anUnparseableTtlFallsBackToTheDefaultRatherThanDisablingSuppression(@TempDir Path dir)
            throws Exception {
        try (DbDeliveryReceiptStore store = durable(dir)) {
            System.setProperty(SuppressionList.TTL_PROPERTY, "not-a-period");
            try {
                SuppressionList list = SuppressionList.fromProperties(store);
                assertTrue(list.armed());
                assertEquals(Duration.ofDays(30), list.hardBounceTtl());
            } finally {
                System.clearProperty(SuppressionList.TTL_PROPERTY);
            }

            System.setProperty(SuppressionList.TTL_PROPERTY, "P2D");
            try {
                assertEquals(Duration.ofDays(2), SuppressionList.fromProperties(store).hardBounceTtl());
            } finally {
                System.clearProperty(SuppressionList.TTL_PROPERTY);
            }
        }
    }

    /** A complaint outranks a still-live hard bounce: the message must say permanent, not "until …". */
    @Test
    void aComplaintOutranksAHardBounceOnTheSameAddress(@TempDir Path dir) throws Exception {
        try (DbDeliveryReceiptStore store = durable(dir)) {
            DeliveryReceipt bounced = store.add(to("both@x.com", 1_000L));
            store.stamp(bounced.deliveryId(), DeliveryStatus.BOUNCED_HARD, 2_000L, null);
            DeliveryReceipt complained = store.add(to("both@x.com", 3_000L));
            store.stamp(complained.deliveryId(), DeliveryStatus.COMPLAINED, 4_000L, null);

            String why = SuppressionList.of(store, Duration.ofDays(30))
                    .reasonToSuppress("both@x.com", 5_000L).orElseThrow();
            assertTrue(why.contains("permanent"), why);
        }
    }

    /** The lookup the policy runs must find the receipt for THIS address, not merely the newest one. */
    @Test
    void latestWithStatusIsScopedToTheAddress(@TempDir Path dir) throws Exception {
        try (DbDeliveryReceiptStore store = durable(dir)) {
            DeliveryReceipt a = store.add(to("a@x.com", 1_000L));
            store.stamp(a.deliveryId(), DeliveryStatus.BOUNCED_HARD, 2_000L, null);
            store.add(to("b@x.com", 9_000L));   // newer, clean, different address

            assertTrue(store.latestWithStatus("a@x.com", DeliveryStatus.BOUNCED_HARD).isPresent());
            assertTrue(store.latestWithStatus("b@x.com", DeliveryStatus.BOUNCED_HARD).isEmpty());
            assertTrue(store.latestWithStatus("a@x.com", DeliveryStatus.COMPLAINED).isEmpty());
            assertTrue(store.latestWithStatus(null, DeliveryStatus.COMPLAINED).isEmpty());
        }
    }

    /**
     * The interface default (used by any store that does not override it) must agree with the SQL
     * override — otherwise suppression would depend on which store is wired.
     */
    @Test
    void theInterfaceDefaultLookupAgreesWithTheSqlOverride() {
        InMemoryDeliveryReceiptStore memory = new InMemoryDeliveryReceiptStore();
        DeliveryReceipt a = memory.add(to("a@x.com", 1_000L));
        memory.stamp(a.deliveryId(), DeliveryStatus.BOUNCED_HARD, 2_000L, null);
        memory.add(to("b@x.com", 9_000L));

        assertTrue(memory.latestWithStatus("a@x.com", DeliveryStatus.BOUNCED_HARD).isPresent());
        assertTrue(memory.latestWithStatus("b@x.com", DeliveryStatus.BOUNCED_HARD).isEmpty());
        assertTrue(memory.latestWithStatus("a@x.com", DeliveryStatus.COMPLAINED).isEmpty());
    }
}
