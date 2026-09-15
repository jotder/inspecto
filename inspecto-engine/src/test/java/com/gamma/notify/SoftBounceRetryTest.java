package com.gamma.notify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Soft-bounce retry (BACKLOG D8, decided 2026-09-15: <b>soft-bounce retry only</b>).
 *
 * <p>A soft bounce is a transient failure against an address that is still good, and
 * {@link SuppressionList} deliberately never suppresses one — so before this the message was simply
 * dropped: status recorded, nothing retried, recipient never served.
 *
 * <p>🔴 The cases below are the ones where getting it wrong is <b>silent</b>: re-sending to someone who
 * already received the message, or retrying forever because the clock never moves.
 */
class SoftBounceRetryTest {

    private static DbDeliveryReceiptStore durable(Path dir) throws Exception {
        return DbDeliveryReceiptStore.open(
                "jdbc:duckdb:" + dir.resolve("r.db").toAbsolutePath().toString().replace('\\', '/'),
                null, null);
    }

    private static DeliveryReceipt receipt(String id, String target, long sentAt) {
        return new DeliveryReceipt(id, "n1", "c1", target, sentAt, Map.of(), null, false);
    }

    // ── which receipts are candidates at all ─────────────────────────────────

    @Test
    void anUnresolvedSoftBounceIsACandidate(@TempDir Path dir) throws Exception {
        try (DbDeliveryReceiptStore s = durable(dir)) {
            s.add(receipt("d1", "a@x.test", 1_000));
            s.stamp("d1", DeliveryStatus.BOUNCED_SOFT, 2_000, null);

            List<DeliveryReceipt> c = s.softBounceRetryCandidates(3);
            assertEquals(1, c.size());
            assertEquals("d1", c.get(0).deliveryId());
        }
    }

    /**
     * 🔴 <b>The silent-harm case.</b> {@code withStatus} keeps the FIRST observation of every status, so a
     * receipt that soft-bounced and then DELIVERED on retry carries both stamps forever. Selecting on a
     * bare {@code statusAt.containsKey(BOUNCED_SOFT)} would therefore re-send, every sweep, to a recipient
     * who already has the message — and nothing downstream would flag it.
     */
    @Test
    void aSoftBounceThatLaterDeliveredIsNotACandidate(@TempDir Path dir) throws Exception {
        try (DbDeliveryReceiptStore s = durable(dir)) {
            s.add(receipt("d1", "a@x.test", 1_000));
            s.stamp("d1", DeliveryStatus.BOUNCED_SOFT, 2_000, null);
            s.stamp("d1", DeliveryStatus.DELIVERED, 3_000, null);

            assertTrue(s.softBounceRetryCandidates(3).isEmpty(),
                    "a soft bounce the retry already resolved must not be retried again");
        }
    }

    /** A hard bounce settles the address; {@code SuppressionList} owns it from there, not this sweep. */
    @Test
    void aSoftBounceSupersededByAHardBounceIsNotACandidate(@TempDir Path dir) throws Exception {
        try (DbDeliveryReceiptStore s = durable(dir)) {
            s.add(receipt("d1", "a@x.test", 1_000));
            s.stamp("d1", DeliveryStatus.BOUNCED_SOFT, 2_000, null);
            s.stamp("d1", DeliveryStatus.BOUNCED_HARD, 3_000, null);

            assertTrue(s.softBounceRetryCandidates(3).isEmpty());
        }
    }

    /** ⛔ A complaint is the one case where re-sending actively makes things worse. */
    @Test
    void aSoftBounceSupersededByAComplaintIsNotACandidate(@TempDir Path dir) throws Exception {
        try (DbDeliveryReceiptStore s = durable(dir)) {
            s.add(receipt("d1", "a@x.test", 1_000));
            s.stamp("d1", DeliveryStatus.BOUNCED_SOFT, 2_000, null);
            s.stamp("d1", DeliveryStatus.COMPLAINED, 3_000, null);

            assertTrue(s.softBounceRetryCandidates(3).isEmpty());
        }
    }

    /**
     * ⚠ {@code UNKNOWN} must NOT settle a soft bounce: an event the adapter could not classify says
     * nothing about delivery, so treating it as resolution silently abandons a retryable message.
     */
    @Test
    void anUnclassifiableEventDoesNotResolveASoftBounce(@TempDir Path dir) throws Exception {
        try (DbDeliveryReceiptStore s = durable(dir)) {
            s.add(receipt("d1", "a@x.test", 1_000));
            s.stamp("d1", DeliveryStatus.BOUNCED_SOFT, 2_000, null);
            s.stamp("d1", DeliveryStatus.UNKNOWN, 3_000, null);

            assertEquals(1, s.softBounceRetryCandidates(3).size());
        }
    }

    // ── the attempt ceiling and the retry clock ──────────────────────────────

    @Test
    void aReceiptAtTheAttemptCeilingIsNoLongerACandidate(@TempDir Path dir) throws Exception {
        try (DbDeliveryReceiptStore s = durable(dir)) {
            s.add(receipt("d1", "a@x.test", 1_000));
            s.stamp("d1", DeliveryStatus.BOUNCED_SOFT, 2_000, null);

            s.recordAttempt("d1", 3_000);
            assertEquals(1, s.softBounceRetryCandidates(3).size(), "one of three used");
            s.recordAttempt("d1", 4_000);
            s.recordAttempt("d1", 5_000);
            assertTrue(s.softBounceRetryCandidates(3).isEmpty(), "three of three used ⇒ done");
        }
    }

    /**
     * 🔴 <b>The retry-storm case.</b> {@code statusAt[BOUNCED_SOFT]} is pinned at the FIRST bounce by
     * {@code withStatus}'s first-observation-wins rule, so a backoff measured from it would measure from a
     * fixed point in the past: after the first retry every remaining attempt would be "overdue" and fire
     * in the same sweep. {@code lastAttemptAt} must move with each attempt — this is what pins that.
     */
    @Test
    void recordingAnAttemptMovesTheRetryClockThatStatusAtCannot(@TempDir Path dir) throws Exception {
        try (DbDeliveryReceiptStore s = durable(dir)) {
            s.add(receipt("d1", "a@x.test", 1_000));
            s.stamp("d1", DeliveryStatus.BOUNCED_SOFT, 2_000, null);

            s.recordAttempt("d1", 10_000);
            DeliveryReceipt after1 = s.get("d1").orElseThrow();
            assertEquals(1, after1.attemptCount());
            assertEquals(10_000, after1.lastAttemptAt());

            s.recordAttempt("d1", 20_000);
            DeliveryReceipt after2 = s.get("d1").orElseThrow();
            assertEquals(2, after2.attemptCount());
            assertEquals(20_000, after2.lastAttemptAt(), "the clock advances with each attempt…");
            assertEquals(2_000L, after2.statusAt().get(DeliveryStatus.BOUNCED_SOFT),
                    "…while the bounce stamp deliberately does not, which is the whole reason for the field");
        }
    }

    /** The attempt survives a round trip through the DB columns, not just the in-memory object. */
    @Test
    void attemptStateIsDurable(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("r.db").toAbsolutePath().toString().replace('\\', '/');
        try (DbDeliveryReceiptStore s = DbDeliveryReceiptStore.open(url, null, null)) {
            s.add(receipt("d1", "a@x.test", 1_000));
            s.stamp("d1", DeliveryStatus.BOUNCED_SOFT, 2_000, null);
            s.recordAttempt("d1", 9_000);
        }
        try (DbDeliveryReceiptStore reopened = DbDeliveryReceiptStore.open(url, null, null)) {
            DeliveryReceipt r = reopened.get("d1").orElseThrow();
            assertEquals(1, r.attemptCount());
            assertEquals(9_000, r.lastAttemptAt());
        }
    }

    /**
     * ⚠ A receipt written before these columns existed reads back as never-retried rather than failing.
     * Simulated by the in-memory store, whose receipts are built through the 8-arg convenience shape.
     */
    @Test
    void aReceiptWithNoRetryHistoryReadsAsNeverRetried() {
        InMemoryDeliveryReceiptStore s = new InMemoryDeliveryReceiptStore();
        s.add(receipt("d1", "a@x.test", 1_000));
        DeliveryReceipt r = s.get("d1").orElseThrow();
        assertEquals(0, r.attemptCount());
        assertEquals(0, r.lastAttemptAt());
    }
}
