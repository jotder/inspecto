package com.gamma.acquire;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Retention pruning must never delete a source's <b>resume position</b>
 * ({@code LEDGER-PRUNE-EATS-RESUME-STATE-1}, decided 2026-09-15: <em>never prune below the high watermark —
 * floor the sweep</em>).
 *
 * <p>🔴 <b>Why this is not obvious.</b> {@link AcquisitionLedger#highWatermark} is <em>derived</em> from the
 * very rows {@code prune} deletes — there is no separate watermark column — so an age-based sweep old enough
 * to reach the frontier used to reset the source to "never seen", and every file would re-ingest as NEW. The
 * invariant these tests pin is therefore not a row count but <b>the watermark is identical before and after a
 * prune</b>.
 *
 * <p>⚠ The two clocks are deliberately given <em>different</em> values here: {@code processedAt} drives the age
 * test, {@code lastModified} drives the floor. A test that set them equal would pass against an implementation
 * that floored on the wrong field.
 */
class AcquisitionLedgerPruneTest {

    /** {@code lastModified} and {@code processedAt} set independently — the whole point (see class note). */
    private static LedgerEntry entry(String src, String rel, long mtime, long processedAt) {
        return LedgerEntry.metadata(src, rel, rel, 1L, mtime, processedAt);
    }

    // ── the invariant ────────────────────────────────────────────────────────

    @Test
    void inMemoryPruneNeverDropsTheWatermarkRow() {
        InMemoryAcquisitionLedger l = new InMemoryAcquisitionLedger();
        // every row is old enough to sweep; the frontier row is the NEWEST by mtime but just as OLD by
        // processedAt, so an age-only predicate would delete it along with the rest.
        l.record(entry("S", "a", 100, 10));
        l.record(entry("S", "b", 200, 10));
        l.record(entry("S", "frontier", 300, 10));
        assertEquals(OptionalLong.of(300), l.highWatermark("S"));

        assertEquals(2, l.prune(1_000, "S"), "the two rows below the frontier go");
        assertEquals(OptionalLong.of(300), l.highWatermark("S"),
                "the resume position must survive a sweep that covered every row by age");
    }

    @Test
    void dbPruneNeverDropsTheWatermarkRow() throws Exception {
        try (DbAcquisitionLedger l = DbAcquisitionLedger.open("jdbc:duckdb:", null, null)) {
            l.record(entry("S", "a", 100, 10));
            l.record(entry("S", "b", 200, 10));
            l.record(entry("S", "frontier", 300, 10));
            assertEquals(OptionalLong.of(300), l.highWatermark("S"));

            assertEquals(2, l.prune(1_000, "S"));
            assertEquals(OptionalLong.of(300), l.highWatermark("S"),
                    "the correlated MAX floor must keep the frontier row");
        }
    }

    // ── the floor is PER SOURCE, not one global value ────────────────────────

    /**
     * A global sweep ({@code sourceId == null}) must floor each source at its <em>own</em> frontier. One shared
     * floor would let a busy source's high watermark protect a quiet source's rows, or — worse — let the quiet
     * source's low watermark expose the busy one's resume position.
     */
    @Test
    void globalSweepFloorsEachSourceIndependently() {
        InMemoryAcquisitionLedger l = new InMemoryAcquisitionLedger();
        l.record(entry("BUSY", "a", 1_000, 10));
        l.record(entry("BUSY", "frontier", 9_000, 10));
        l.record(entry("QUIET", "a", 5, 10));
        l.record(entry("QUIET", "frontier", 7, 10));

        assertEquals(2, l.prune(1_000, null), "one row from each source, not four and not zero");
        assertEquals(OptionalLong.of(9_000), l.highWatermark("BUSY"));
        assertEquals(OptionalLong.of(7), l.highWatermark("QUIET"));
    }

    @Test
    void dbGlobalSweepFloorsEachSourceIndependently() throws Exception {
        try (DbAcquisitionLedger l = DbAcquisitionLedger.open("jdbc:duckdb:", null, null)) {
            l.record(entry("BUSY", "a", 1_000, 10));
            l.record(entry("BUSY", "frontier", 9_000, 10));
            l.record(entry("QUIET", "a", 5, 10));
            l.record(entry("QUIET", "frontier", 7, 10));

            assertEquals(2, l.prune(1_000, null));
            assertEquals(OptionalLong.of(9_000), l.highWatermark("BUSY"));
            assertEquals(OptionalLong.of(7), l.highWatermark("QUIET"));
        }
    }

    // ── a lone row is pure resume state ──────────────────────────────────────

    /**
     * A source with ONE recorded file is entirely resume position: that row is its own watermark, so an
     * age-based sweep must remove nothing at all. This is the case that used to silently zero a source.
     */
    @Test
    void aSingleRowSourceIsNeverPruned() {
        InMemoryAcquisitionLedger l = new InMemoryAcquisitionLedger();
        l.record(entry("S", "only", 42, 1));
        assertEquals(0, l.prune(Long.MAX_VALUE, "S"));
        assertEquals(OptionalLong.of(42), l.highWatermark("S"));
    }

    @Test
    void dbSingleRowSourceIsNeverPruned() throws Exception {
        try (DbAcquisitionLedger l = DbAcquisitionLedger.open("jdbc:duckdb:", null, null)) {
            l.record(entry("S", "only", 42, 1));
            assertEquals(0, l.prune(Long.MAX_VALUE, "S"));
            assertEquals(OptionalLong.of(42), l.highWatermark("S"));
        }
    }

    // ── one row is protected, not every row tied at the maximum ──────────────

    /**
     * 🔴 <b>The regression a first implementation shipped and every other test in this class missed.</b>
     * A floor written as {@code last_modified < MAX(last_modified)} protects EVERY row tied at the maximum.
     * Where a source's files share one mtime — a coarse clock, a bulk copy, a generated feed — that is the
     * whole history, so retention silently stops working while still reporting success.
     *
     * <p>⚠ Every other test here uses distinct mtimes and passes under both implementations. Only a fixture
     * with a TIE tells them apart, which is why this one exists.
     */
    @Test
    void rowsTiedAtTheWatermarkAreStillPrunedDownToOne() {
        InMemoryAcquisitionLedger l = new InMemoryAcquisitionLedger();
        l.record(entry("S", "a", 500, 10));
        l.record(entry("S", "b", 500, 20));
        l.record(entry("S", "c", 500, 30));

        assertEquals(2, l.prune(1_000, "S"), "history must still shrink when every mtime is identical");
        assertEquals(OptionalLong.of(500), l.highWatermark("S"), "and the watermark must survive it");
    }

    @Test
    void dbRowsTiedAtTheWatermarkAreStillPrunedDownToOne() throws Exception {
        try (DbAcquisitionLedger l = DbAcquisitionLedger.open("jdbc:duckdb:", null, null)) {
            l.record(entry("S", "a", 500, 10));
            l.record(entry("S", "b", 500, 20));
            l.record(entry("S", "c", 500, 30));

            assertEquals(2, l.prune(1_000, "S"));
            assertEquals(OptionalLong.of(500), l.highWatermark("S"));
        }
    }

    /**
     * The two backends must protect the SAME row, or a deployment that switches ledger implementations would
     * see its retention behaviour change. The tie-break order is {@code lastModified}, {@code processedAt},
     * {@code relativePath} — here all three rows tie on mtime, so {@code processedAt} decides and {@code c}
     * survives in both.
     */
    @Test
    void bothBackendsProtectTheSameRow() throws Exception {
        InMemoryAcquisitionLedger mem = new InMemoryAcquisitionLedger();
        try (DbAcquisitionLedger db = DbAcquisitionLedger.open("jdbc:duckdb:", null, null)) {
            for (AcquisitionLedger l : java.util.List.of(mem, db)) {
                l.record(entry("S", "a", 500, 10));
                l.record(entry("S", "b", 500, 30));
                l.record(entry("S", "c", 500, 20));
                l.prune(1_000, "S");
            }
            assertTrue(mem.find("S", "b").isPresent(), "in-memory keeps the greatest processedAt");
            assertTrue(db.find("S", "b").isPresent(), "and so does the DB backend");
            assertTrue(mem.find("S", "a").isEmpty());
            assertTrue(db.find("S", "a").isEmpty());
        }
    }

    // ── age still governs BELOW the floor ────────────────────────────────────

    /** The floor must not become a licence to keep everything: a young row below the frontier still stays. */
    @Test
    void ageStillGovernsBeneathTheFloor() {
        InMemoryAcquisitionLedger l = new InMemoryAcquisitionLedger();
        l.record(entry("S", "old", 100, 10));       // below the floor AND old   ⇒ pruned
        l.record(entry("S", "young", 200, 5_000));  // below the floor but young ⇒ kept
        l.record(entry("S", "frontier", 300, 10));  // the floor                 ⇒ kept

        assertEquals(1, l.prune(1_000, "S"));
        assertTrue(l.find("S", "young").isPresent(), "retention is still age-based below the watermark");
        assertTrue(l.find("S", "old").isEmpty());
        assertEquals(OptionalLong.of(300), l.highWatermark("S"));
    }

    // ── preview and sweep must agree (PRUNE-PREVIEW-DRIFT-1) ─────────────────

    /**
     * {@code countPrunable} is the {@code ledger_prune} dry run. It and {@code prune} are two call sites of one
     * predicate now; this pins that they cannot drift back apart — a preview that ignored the floor would
     * promise deletions the real sweep refuses to make.
     */
    @Test
    void previewMatchesTheSweepInMemory() {
        InMemoryAcquisitionLedger l = new InMemoryAcquisitionLedger();
        l.record(entry("S", "a", 100, 10));
        l.record(entry("S", "b", 200, 10));
        l.record(entry("S", "frontier", 300, 10));

        int predicted = l.countPrunable(1_000, "S");
        assertEquals(2, predicted, "the preview must respect the floor too");
        assertEquals(predicted, l.prune(1_000, "S"));
    }

    @Test
    void previewMatchesTheSweepOnDb() throws Exception {
        try (DbAcquisitionLedger l = DbAcquisitionLedger.open("jdbc:duckdb:", null, null)) {
            l.record(entry("S", "a", 100, 10));
            l.record(entry("S", "b", 200, 10));
            l.record(entry("S", "frontier", 300, 10));

            int predicted = l.countPrunable(1_000, "S");
            assertEquals(2, predicted);
            assertEquals(predicted, l.prune(1_000, "S"));
        }
    }

    // ── the separate row-level export watermark stays untouched ──────────────

    /**
     * {@code dbWatermark} lives in its own table and was never swept. The decision deliberately kept these two
     * designs for one idea rather than unifying them (no schema change, no migration), so this pins that prune
     * still does not reach it — a future "tidy-up" that unified them would be reopening the decision.
     */
    @Test
    void pruneDoesNotTouchTheRowLevelExportWatermark() throws Exception {
        try (DbAcquisitionLedger l = DbAcquisitionLedger.open("jdbc:duckdb:", null, null)) {
            l.record(entry("S", "a", 100, 10));
            l.record(entry("S", "frontier", 300, 10));
            l.recordDbWatermark("profile-1", "2026-01-01T00:00:00Z");

            l.prune(Long.MAX_VALUE, null);
            assertEquals("2026-01-01T00:00:00Z", l.dbWatermark("profile-1").orElse(null));
        }
    }
}
