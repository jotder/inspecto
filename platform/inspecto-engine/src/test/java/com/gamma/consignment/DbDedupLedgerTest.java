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
     * PRUNE-PREVIEW-DRIFT-1: the dry run and the sweep are two call sites of ONE predicate. Before this
     * the maintenance task previewed {@code size()} — the whole ledger — so a preview of "3" could
     * precede a sweep that removed 1.
     */
    @Test
    void previewMatchesTheSweep() throws Exception {
        try (DbDedupLedger l = ledger()) {
            l.claim("p", W.minusDays(5), "old", List.of(DbDedupLedger.hash(List.of("old"))));
            l.claim("p", W.minusDays(1), "older", List.of(DbDedupLedger.hash(List.of("older"))));
            l.claim("p", W, "cur", List.of(DbDedupLedger.hash(List.of("cur"))));

            int preview = l.countPrunable(W);
            assertEquals(2, preview, "the preview counts only what the predicate matches, not size()");
            assertEquals(3L, l.size(), "a preview must not delete");
            assertEquals(preview, l.prune(W), "the sweep removes exactly what the preview promised");
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

    /**
     * 🔴 OPSTORE-BULK-INSERT-1: the DuckDB bulk claim (Appender + one {@code INSERT … RETURNING}) under real
     * concurrency, on a FILE-backed ledger. Four Consignments claim overlapping slices of one key pool at
     * once; every key must be won by exactly one of them, and the ledger must attribute it to that one. A
     * bulk path that reported its whole input (not only the inserted rows) fails the sum below.
     */
    @Test
    void concurrentBulkClaimsOfOverlappingKeysYieldOneWinnerPerKey(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        int pool = 3_000, threads = 4, slice = 2_000;
        List<String> keys = java.util.stream.IntStream.range(0, pool)
                .mapToObj(i -> DbDedupLedger.hash(List.of("k" + i))).toList();
        try (DbDedupLedger l = new DbDedupLedger("jdbc:duckdb:" + dir.resolve("dedup.duckdb").toAbsolutePath())) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var exec = java.util.concurrent.Executors.newFixedThreadPool(threads);
            try {
                List<java.util.concurrent.Future<Set<String>>> futures = new java.util.ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    int from = t * (pool - slice) / (threads - 1);   // slices overlap; together they cover the pool
                    List<String> mine = keys.subList(from, from + slice);
                    String cid = "c" + t;
                    futures.add(exec.submit(() -> { start.await(); return l.claim("p", W, cid, mine); }));
                }
                start.countDown();
                List<Set<String>> won = new java.util.ArrayList<>();
                for (var f : futures) won.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));

                java.util.Map<String, Integer> winners = new java.util.HashMap<>();
                for (Set<String> w : won) for (String k : w) winners.merge(k, 1, Integer::sum);
                assertEquals(pool, winners.size(), "every key in the pool was won by someone");
                assertTrue(winners.values().stream().allMatch(n -> n == 1), "no key was won twice");
                assertEquals(pool, won.stream().mapToInt(Set::size).sum(), "won sets are disjoint and cover the pool");
                assertEquals(pool, l.size());
                for (int t = 0; t < threads; t++)
                    assertEquals(won.get(t).size(), l.retract("c" + t), "the ledger attributes each key to its winner");
            } finally {
                exec.shutdownNow();
            }
        }
    }

    /** One window's list can repeat a hash (two event dates in one window): it is won once, not refused. */
    @Test
    void aHashRepeatedInOneClaimIsWonOnce() throws Exception {
        try (DbDedupLedger l = ledger()) {
            String a = DbDedupLedger.hash(List.of("a")), b = DbDedupLedger.hash(List.of("b"));
            assertEquals(List.of(a, b), List.copyOf(l.claim("p", W, "c1", List.of(a, b, a))),
                    "both won, once each, in the caller's order");
            assertEquals(2L, l.size());
        }
    }

    /** The per-call staging table is dropped after the claim, on success and when the claim fails. */
    @Test
    void theBulkClaimLeavesNoStagingTableBehind() throws Exception {
        java.sql.Connection conn = DriverManager.getConnection("jdbc:duckdb:");
        try (DbDedupLedger l = new DbDedupLedger(conn)) {
            l.claim("p", W, "c1", List.of(DbDedupLedger.hash(List.of("a"))));
            assertThrows(java.sql.SQLException.class, () -> l.claim(null, W, "c2", List.of(DbDedupLedger.hash(List.of("b")))),
                    "a NULL pipeline violates NOT NULL");
            try (var st = conn.createStatement();
                 var rs = st.executeQuery("SELECT count(*) FROM duckdb_tables() WHERE table_name LIKE 'dedup_stage_%'")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "no staging table survives a claim");
            }
            assertEquals(1L, l.size(), "the failed claim inserted nothing");
        }
    }
}
