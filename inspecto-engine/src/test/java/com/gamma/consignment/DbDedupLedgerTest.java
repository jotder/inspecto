package com.gamma.consignment;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-9's ledger: the three answers D8 required, each pinned.
 *
 * <p>Every test opens its own in-memory DuckDB, so nothing leaks between them.
 */
class DbDedupLedgerTest {

    private static DbDedupLedger ledger() throws Exception {
        return new DbDedupLedger(DriverManager.getConnection("jdbc:duckdb:"));
    }

    private static final LocalDate W = LocalDate.of(2026, 9, 1);

    @Test
    void aKeyIsWonOnceAndRefusedThereafter() throws Exception {
        try (DbDedupLedger l = ledger()) {
            String k = DbDedupLedger.hash(List.of("MSISDN-1"));

            assertEquals(Set.of(k), l.claim("p", W, "c1", List.of(k)), "the first claim wins");
            assertEquals(Set.of(), l.claim("p", W, "c2", List.of(k)),
                    "a second Consignment must NOT win a key already claimed in this window");
            assertEquals(1L, l.size());
        }
    }

    /**
     * 🔴 The winner policy is "first COMMITTED wins", and it is the DATABASE that decides — not a read
     * followed by a write. Two Consignments ingest concurrently and nothing holds a cross-batch lock, so a
     * check-then-insert would let both observe "absent" and both admit the row. Claiming the same key from
     * two consignments in sequence models that race's resolution: exactly one set is non-empty.
     */
    @Test
    void exactlyOneClaimantWinsAContestedKey() throws Exception {
        try (DbDedupLedger l = ledger()) {
            String k = DbDedupLedger.hash(List.of("contested"));
            Set<String> first = l.claim("p", W, "c1", List.of(k));
            Set<String> second = l.claim("p", W, "c2", List.of(k));

            assertEquals(1, first.size() + second.size(), "exactly one claimant may win");
            assertEquals(List.of("c1"), l.claimants(), "and the ledger attributes it to the winner");
        }
    }

    @Test
    void theSameKeyInADifferentWindowOrPipelineIsANewClaim() throws Exception {
        try (DbDedupLedger l = ledger()) {
            String k = DbDedupLedger.hash(List.of("k"));
            assertEquals(Set.of(k), l.claim("p", W, "c1", List.of(k)));
            assertEquals(Set.of(k), l.claim("p", W.plusDays(1), "c1", List.of(k)),
                    "a window is the scope — the next window starts clean");
            assertEquals(Set.of(k), l.claim("other", W, "c1", List.of(k)),
                    "and one pipeline's keys never suppress another's");
            assertEquals(3L, l.size());
        }
    }

    /**
     * 🔴 The correctness risk this whole design turns on. A reprocess is a WHOLE-Consignment
     * supersede-and-re-ingest with no row-level retraction anywhere, so without this the re-ingested rows
     * would be answered "already seen" and dropped — silently and permanently.
     */
    @Test
    void aReprocessRetractsItsClaimsSoTheRowsCanBeReAdmitted() throws Exception {
        try (DbDedupLedger l = ledger()) {
            List<String> keys = List.of(DbDedupLedger.hash(List.of("a")), DbDedupLedger.hash(List.of("b")));
            assertEquals(2, l.claim("p", W, "doomed", keys).size());

            assertEquals(2, l.retract("doomed"), "retract reports what it actually released");
            assertEquals(0L, l.size());

            assertEquals(2, l.claim("p", W, "reprocessed", keys).size(),
                    "after the supersede the same keys must be winnable again");
        }
    }

    @Test
    void retractingAnUnknownConsignmentReleasesNothing() throws Exception {
        try (DbDedupLedger l = ledger()) {
            l.claim("p", W, "c1", List.of(DbDedupLedger.hash(List.of("k"))));
            assertEquals(0, l.retract("never-ran"));
            assertEquals(1L, l.size(), "and leaves the real claim alone");
        }
    }

    /** The window advances by the record's own event time — whole elapsed windows drop. */
    @Test
    void pruneDropsWindowsStrictlyBeforeTheCutoff() throws Exception {
        try (DbDedupLedger l = ledger()) {
            l.claim("p", W.minusDays(5), "old", List.of(DbDedupLedger.hash(List.of("old"))));
            l.claim("p", W, "cur", List.of(DbDedupLedger.hash(List.of("cur"))));

            assertEquals(1, l.prune(W), "only the elapsed window goes");
            assertEquals(1L, l.size());
            assertEquals(List.of("cur"), l.claimants());
        }
    }

    /**
     * 🔴 The separator is load-bearing, and I shipped this wrong once before catching it. Joining the key
     * values with "" would make {@code ["a","bc"]} and {@code ["ab","c"]} hash identically — two DIFFERENT
     * records silently deduplicating against each other. The unit separator cannot occur in a parsed field
     * value, so the composite key is unambiguous.
     */
    @Test
    void aCompositeKeyCannotCollideByRegrouping() throws Exception {
        assertNotEquals(DbDedupLedger.hash(List.of("a", "bc")), DbDedupLedger.hash(List.of("ab", "c")));
        assertEquals(DbDedupLedger.hash(List.of("a", "bc")), DbDedupLedger.hash(List.of("a", "bc")),
                "and the same key always hashes the same");
    }

    /** 🔴 Keys are HASHED, never stored verbatim — the ledger must not become a customer-data surface. */
    @Test
    void theLedgerNeverHoldsTheBusinessKeyItself() throws Exception {
        try (DbDedupLedger l = ledger()) {
            String secret = "447700900123";
            String k = DbDedupLedger.hash(List.of(secret));
            l.claim("p", W, "c1", List.of(k));

            assertFalse(k.contains(secret), "the hash must not embed the value");
            assertEquals(64, k.length(), "SHA-256 hex");
        }
    }

    @Test
    void claimingNothingIsANoOp() throws Exception {
        try (DbDedupLedger l = ledger()) {
            assertEquals(Set.of(), l.claim("p", W, "c1", List.of()));
            assertEquals(0L, l.size());
        }
    }
}
