package com.gamma.consignment;

import com.gamma.consignment.ConsignmentOutput.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DbConsignmentOutputStore} (consignment-elt plan §11.3): the durable per-output-file registry.
 * Mostly in-memory DuckDB ({@code jdbc:duckdb:}) like {@link com.gamma.job.DbJobRunStoreTest}, except the
 * durability test, which needs a real file to close and reopen.
 */
class DbConsignmentOutputStoreTest {

    /**
     * {@code isReadable} follows the same per-PATH rule {@code unreadablePaths} does, and for the same
     * reason: output naming is not one-file-per-Consignment, so a full recompute rewrites a stable path in
     * place and that path legitimately owns an old SUPERSEDED row beside a current LIVE one. Judging by row
     * would call live data unreadable.
     */
    @Test
    void isReadableIsPerPathNotPerRow() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            String path = "/w/cdr/dt=2026-08-04/stable.parquet";
            db.record(List.of(out("c1", path, 10, State.SUPERSEDED), out("c2", path, 20, State.LIVE)));

            assertTrue(db.isReadable(path), "one LIVE row makes the PATH readable, whatever else it carries");

            db.record(List.of(out("c3", "/w/cdr/gone.parquet", 5, State.COMPACTED_AWAY)));
            assertFalse(db.isReadable("/w/cdr/gone.parquet"), "no LIVE row — not readable");
            assertFalse(db.isReadable("/w/cdr/never-registered.parquet"), "unregistered is not readable");
            assertFalse(db.isReadable(null));
            assertFalse(db.isReadable("  "));
        }
    }

    // ── daily volume (completeness KPI, K1) ──────────────────────────────────

    /** One LIVE output for {@code producer} on {@code day}. */
    private static ConsignmentOutput vol(String consignment, String producer, String day, String path,
                                         long rows, State state) {
        return new ConsignmentOutput(consignment, "run-1", "cdr", "dt=" + day, day, path, rows,
                rows * 100, day + "T10:00:00Z", 1, state, null, null, producer);
    }

    /**
     * 🔴 The trap this query exists to avoid. Row state is per-REGISTRATION while a file is one PATH, so a
     * full recompute rewrites a stable path in place and that path owns a SUPERSEDED row beside its LIVE one.
     * Summing every row would report a recomputed day as having received MORE data — a volume increase
     * manufactured by reprocessing, which is precisely the false signal a completeness KPI must not emit.
     */
    @Test
    void aRecomputedDayIsNotCountedTwice() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            String path = "/w/cdr/dt=2026-08-04/stable.parquet";
            db.record(List.of(
                    vol("c1", "cdr_pipeline", "2026-08-04", path, 100, State.SUPERSEDED),
                    vol("c2", "cdr_pipeline", "2026-08-04", path, 120, State.LIVE)));

            List<DbConsignmentOutputStore.DailyVolume> v =
                    db.dailyVolume("cdr_pipeline", "2026-08-01", "2026-08-31");

            assertEquals(1, v.size());
            assertEquals(120, v.get(0).rows(), "the LIVE registration only — not 220");
            assertEquals(1, v.get(0).files(), "one PATH, however many registrations it carries");
        }
    }

    /** COMPACTED_AWAY rows live on inside the compacted file, which carries its own LIVE row. */
    @Test
    void compactedAwayRowsAreNotCounted() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    vol("c1", "p", "2026-08-04", "/w/small-1.parquet", 50, State.COMPACTED_AWAY),
                    vol("c1", "p", "2026-08-04", "/w/small-2.parquet", 50, State.COMPACTED_AWAY),
                    vol("c2", "p", "2026-08-04", "/w/merged.parquet", 100, State.LIVE)));

            List<DbConsignmentOutputStore.DailyVolume> v = db.dailyVolume("p", "2026-08-01", "2026-08-31");
            assertEquals(1, v.size());
            assertEquals(100, v.get(0).rows(), "compaction moved rows, it did not add any");
            assertEquals(1, v.get(0).files());
        }
    }

    /**
     * 🔴 Unknown is not zero and not empty. A file whose record_day was never established (no event time
     * materialised, no date partition, or every event time unparseable) must reach the KPI as its own
     * bucket — folding it into a day invents an attribution, dropping it hides arrived rows, and either way
     * the KPI reports a deviation that is an artifact of its own bookkeeping.
     */
    @Test
    void theUnknownDayBucketSurvivesAndIsNotADay() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    vol("c1", "p", "2026-08-04", "/w/known.parquet", 10, State.LIVE),
                    new ConsignmentOutput("c2", "run-1", "cdr", null, null, "/w/unknown.parquet", 7, 700,
                            "2026-08-04T10:00:00Z", 1, State.LIVE, null, null, "p")));

            List<DbConsignmentOutputStore.DailyVolume> v = db.dailyVolume("p", "2026-08-01", "2026-08-31");

            assertEquals(2, v.size());
            assertEquals("2026-08-04", v.get(0).recordDay());
            assertNull(v.get(1).recordDay(), "the unknown bucket sorts last and keeps a null day");
            assertEquals(7, v.get(1).rows(), "its rows are reported, not discarded");
        }
    }

    @Test
    void scopesToOneProducerAndRefusesToAggregateAcrossAll() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    vol("c1", "pipeline_a", "2026-08-04", "/w/a.parquet", 10, State.LIVE),
                    vol("c2", "pipeline_b", "2026-08-04", "/w/b.parquet", 99, State.LIVE)));

            List<DbConsignmentOutputStore.DailyVolume> v = db.dailyVolume("pipeline_a", "2026-08-01", "2026-08-31");
            assertEquals(1, v.size());
            assertEquals(10, v.get(0).rows(), "pipeline_b's rows are not this pipeline's volume");

            assertThrows(IllegalArgumentException.class, () -> db.dailyVolume("  ", "2026-08-01", "2026-08-31"),
                    "a blank producer must refuse, never silently sum every pipeline");
        }
    }

    /** ⚠ A day with no registered output is ABSENT, not zero — only the caller knows the expected calendar. */
    @Test
    void daysOutsideTheRangeAreExcludedAndMissingDaysAreAbsent() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    vol("c1", "p", "2026-08-04", "/w/in.parquet", 10, State.LIVE),
                    vol("c2", "p", "2026-09-04", "/w/out.parquet", 10, State.LIVE)));

            List<DbConsignmentOutputStore.DailyVolume> v = db.dailyVolume("p", "2026-08-01", "2026-08-31");
            assertEquals(1, v.size(), "September is outside the range");
            assertEquals("2026-08-04", v.get(0).recordDay());
            // 2026-08-05 received nothing and simply does not appear.
            assertTrue(v.stream().noneMatch(d -> "2026-08-05".equals(d.recordDay())));
        }
    }

    private static ConsignmentOutput out(String consignment, String path, long rows, State state) {
        return new ConsignmentOutput(consignment, "run-1", "cdr", "dt=2026-08-04", "2026-08-04",
                path, rows, rows * 100, "2026-08-04T10:00:00Z", 1, state);
    }

    @Test
    void registersEveryFieldAndReadsItBack() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(out("c1", "/w/cdr/dt=2026-08-04/c1.parquet", 42, State.LIVE)));

            List<ConsignmentOutput> rows = db.outputs("c1");
            assertEquals(1, rows.size());
            ConsignmentOutput o = rows.get(0);
            assertEquals("c1", o.consignmentId());
            assertEquals("run-1", o.runId());
            assertEquals("cdr", o.tableName());
            assertEquals("dt=2026-08-04", o.partitionKey());
            assertEquals("2026-08-04", o.recordDay());
            assertEquals("/w/cdr/dt=2026-08-04/c1.parquet", o.path());
            assertEquals(42L, o.rows(), "row_count must survive — it is the field PartitionOutput cannot supply");
            assertEquals(4200L, o.bytes());
            assertEquals("2026-08-04T10:00:00Z", o.writtenAt());
            assertEquals(1, o.generation());
            assertEquals(State.LIVE, o.state());
        }
    }

    /** §5.3: the registry's whole point is answering "every file C wrote, across ALL partitions". */
    @Test
    void returnsEveryPartitionOfOneConsignmentAndNothingFromAnother() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    out("c1", "/w/cdr/dt=2026-08-03/c1.parquet", 5, State.LIVE),
                    out("c1", "/w/cdr/dt=2026-08-04/c1.parquet", 7, State.LIVE),
                    out("c2", "/w/cdr/dt=2026-08-04/c2.parquet", 9, State.LIVE)));

            assertEquals(2, db.outputs("c1").size(), "a multi-day Consignment must return both partitions");
            assertEquals(1, db.outputs("c2").size());
            assertTrue(db.outputs("nope").isEmpty());
        }
    }

    /**
     * Non-LIVE rows must come back too. Filtering them would hide the exact case the registry exists to
     * expose: reprocessing a Consignment whose output was compacted away has to take the partition-rewrite
     * path (§6.2), not a no-op unlink that silently duplicates rows.
     */
    @Test
    void listsSupersededAndCompactedRowsNotJustLive() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    out("c1", "/w/cdr/dt=2026-08-04/live.parquet", 1, State.LIVE),
                    out("c1", "/w/cdr/dt=2026-08-04/old.parquet", 2, State.SUPERSEDED),
                    out("c1", "/w/cdr/dt=2026-08-04/gone.parquet", 3, State.COMPACTED_AWAY)));

            List<State> states = db.outputs("c1").stream().map(ConsignmentOutput::state).toList();
            assertEquals(3, states.size());
            assertTrue(states.containsAll(List.of(State.LIVE, State.SUPERSEDED, State.COMPACTED_AWAY)));
        }
    }

    @Test
    void survivesCloseAndReopen(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("outputs.duckdb");
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open(url)) {
            db.record(List.of(out("c1", "/w/cdr/dt=2026-08-04/c1.parquet", 42, State.LIVE)));
        }
        try (DbConsignmentOutputStore reopened = DbConsignmentOutputStore.open(url)) {
            List<ConsignmentOutput> rows = reopened.outputs("c1");
            assertEquals(1, rows.size(), "the registry must be durable — an in-memory one cannot replace the manifest");
            assertEquals(42L, rows.get(0).rows());
        }
    }

    /** initSchema is re-run on every open, so a second open of the same file must not duplicate or fail. */
    @Test
    void schemaCreationIsIdempotent(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("outputs.duckdb");
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open(url)) {
            db.record(List.of(out("c1", "/w/a.parquet", 1, State.LIVE)));
        }
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open(url)) {
            db.record(List.of(out("c1", "/w/b.parquet", 2, State.LIVE)));
            assertEquals(2, db.outputs("c1").size());
        }
    }

    /**
     * A row written by a newer build carrying an unknown state must not make an older build unable to list a
     * Consignment's files at all — it degrades to LIVE. Written via raw SQL because the enum cannot express it.
     */
    @Test
    void unknownStateDegradesToLiveRatherThanThrowing() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            Connection raw = db.browseConnection();   // borrowed, NOT owned — closing it would close the store
            try (Statement st = raw.createStatement()) {
                // Columns named, not positional: this row exists to exercise `state`, and an unrelated
                // additive migration must not turn it into an arity error.
                st.execute("INSERT INTO consignment_outputs (consignment_id, run_id, table_name, "
                        + "partition_key, record_day, path, row_count, bytes, written_at, generation, "
                        + "state, schema_fingerprint) VALUES "
                        + "('c1','run-1','cdr','dt=2026-08-04','2026-08-04','/w/x.parquet',1,100,"
                        + "'2026-08-04T10:00:00Z',1,'QUARANTINED_BY_A_FUTURE_BUILD',NULL)");
            } catch (SQLException e) {
                fail("raw insert should succeed: " + e.getMessage());
            }
            List<ConsignmentOutput> rows = db.outputs("c1");
            assertEquals(1, rows.size());
            assertEquals(State.LIVE, rows.get(0).state());
        }
    }

    /** A write failure must be logged, never thrown — the data has already landed. */
    @Test
    void recordIsBestEffortAndNeverThrows() throws Exception {
        DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:");
        db.close();
        assertDoesNotThrow(() -> db.record(List.of(out("c1", "/w/a.parquet", 1, State.LIVE))));
        assertDoesNotThrow(() -> db.outputs("c1"));
    }

    @Test
    void emptyAndNullRecordAreNoOps() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            assertDoesNotThrow(() -> db.record(List.of()));
            assertDoesNotThrow(() -> db.record(null));
            assertTrue(db.outputs("c1").isEmpty());
        }
    }

    // ── schema fingerprint (ELT amendment §3.4.3) ─────────────────────────────────

    @Test
    void schemaFingerprintRoundTripsAndAbsentStaysNull() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    new ConsignmentOutput("c1", "run-1", "cdr", "dt=2026-08-04", "2026-08-04",
                            "/w/a.parquet", 1, 100, "2026-08-04T10:00:00Z", 1, State.LIVE, "abc123"),
                    out("c1", "/w/b.parquet", 2, State.LIVE)));   // fingerprint-less form → null

            List<ConsignmentOutput> rows = db.outputs("c1");
            assertEquals("abc123", rows.stream().filter(o -> o.path().endsWith("a.parquet"))
                    .findFirst().orElseThrow().schemaFingerprint());
            assertNull(rows.stream().filter(o -> o.path().endsWith("b.parquet"))
                    .findFirst().orElseThrow().schemaFingerprint(),
                    "write paths carrying no pipeline schema record null, never a fabricated hash");
        }
    }

    /**
     * A registry file created before the column existed must gain it on reopen — CREATE TABLE IF NOT EXISTS
     * never widens an existing table, so initSchema's ADD COLUMN IF NOT EXISTS is the migration. Pre-migration
     * rows read back with a null fingerprint (the "old row ⇒ unknown, don't throw" rule the state column set).
     */
    @Test
    void preFingerprintRegistryGainsTheColumnOnReopen(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("outputs.duckdb");
        try (Connection legacy = com.gamma.util.JdbcDrivers.connect(url);
             Statement st = legacy.createStatement()) {
            st.execute("CREATE TABLE consignment_outputs ("
                    + "consignment_id VARCHAR, run_id VARCHAR, table_name VARCHAR, "
                    + "partition_key VARCHAR, record_day VARCHAR, path VARCHAR, "
                    + "row_count BIGINT, bytes BIGINT, written_at VARCHAR, "
                    + "generation INTEGER, state VARCHAR)");
            st.execute("INSERT INTO consignment_outputs VALUES "
                    + "('c0','run-0','cdr','dt=2026-08-01','2026-08-01','/w/old.parquet',9,900,"
                    + "'2026-08-01T10:00:00Z',0,'LIVE')");
        }
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open(url)) {
            List<ConsignmentOutput> old = db.outputs("c0");
            assertEquals(1, old.size(), "pre-migration rows stay readable");
            assertNull(old.get(0).schemaFingerprint());

            db.record(List.of(new ConsignmentOutput("c1", "run-1", "cdr", "dt=2026-08-04", "2026-08-04",
                    "/w/new.parquet", 1, 100, "2026-08-04T10:00:00Z", 1, State.LIVE, "fp-1")));
            assertEquals("fp-1", db.outputs("c1").get(0).schemaFingerprint());
        }
    }

    // ── state transitions (§6.3 / §5.3) ──────────────────────────────────────────

    @Test
    void supersedeMovesOnlyThisConsignmentsLiveRows() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    out("c1", "/w/a.parquet", 1, State.LIVE),
                    out("c1", "/w/b.parquet", 2, State.LIVE),
                    out("c2", "/w/c.parquet", 3, State.LIVE)));

            assertEquals(2, db.supersede("c1"));
            assertEquals(List.of(State.SUPERSEDED, State.SUPERSEDED),
                    db.outputs("c1").stream().map(ConsignmentOutput::state).toList());
            assertEquals(State.LIVE, db.outputs("c2").get(0).state(), "another Consignment is untouched");
        }
    }

    /**
     * The transition that must NOT happen. A {@code COMPACTED_AWAY} row is the evidence that this file's rows
     * now live inside a merged file; overwriting it with {@code SUPERSEDED} would lose the one fact that tells
     * a reprocess to rewrite the partition (§6.2) instead of unlinking a path that is already gone.
     */
    @Test
    void supersedeLeavesCompactedAwayRowsAlone() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    out("c1", "/w/live.parquet", 1, State.LIVE),
                    out("c1", "/w/gone.parquet", 2, State.COMPACTED_AWAY)));

            assertEquals(1, db.supersede("c1"), "only the LIVE row moves");
            assertEquals(State.COMPACTED_AWAY, db.outputs("c1").stream()
                    .filter(o -> o.path().endsWith("gone.parquet")).findFirst().orElseThrow().state());
        }
    }

    @Test
    void markCompactedAwayFlipsExactlyTheGivenPaths() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    out("c1", "/w/dt=1/a.parquet", 1, State.LIVE),
                    out("c2", "/w/dt=1/b.parquet", 2, State.LIVE),
                    out("c3", "/w/dt=2/c.parquet", 3, State.LIVE)));

            // A merged file spans Consignments — which is why this is keyed by path, not by consignment_id.
            assertEquals(2, db.markCompactedAway(List.of("/w/dt=1/a.parquet", "/w/dt=1/b.parquet")));
            assertEquals(State.COMPACTED_AWAY, db.outputs("c1").get(0).state());
            assertEquals(State.COMPACTED_AWAY, db.outputs("c2").get(0).state());
            assertEquals(State.LIVE, db.outputs("c3").get(0).state(), "a different partition is untouched");
        }
    }

    /** Compaction legitimately merges files older than the registry, so an unmatched path is not an error. */
    @Test
    void markCompactedAwayIgnoresPathsItDoesNotKnow() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(out("c1", "/w/a.parquet", 1, State.LIVE)));
            assertEquals(0, db.markCompactedAway(List.of("/w/never-registered.parquet")));
            assertEquals(State.LIVE, db.outputs("c1").get(0).state());
        }
    }

    /**
     * The silent-failure guard: a relative spelling on the write side and an absolute one on the compaction side
     * (or the reverse) must still match, or a state flip would report success having changed nothing.
     */
    @Test
    void markCompactedAwayMatchesAcrossRelativeAndAbsoluteSpellings() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(out("c1", "work/cdr/dt=1/a.parquet", 1, State.LIVE)));

            String absolute = Path.of("work/cdr/dt=1/a.parquet").toAbsolutePath().normalize().toString();
            assertEquals(1, db.markCompactedAway(List.of(absolute)),
                    "an absolute probe must find a row stored relative");
            assertEquals(State.COMPACTED_AWAY, db.outputs("c1").get(0).state());
            assertEquals("work/cdr/dt=1/a.parquet", db.outputs("c1").get(0).path(),
                    "and the stored spelling is left alone — normalising it would make the row cwd-dependent");
        }
    }

    /** Idempotent: re-running compaction over the same directory must not report phantom changes. */
    @Test
    void markCompactedAwayIsIdempotent() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(out("c1", "/w/a.parquet", 1, State.LIVE)));
            assertEquals(1, db.markCompactedAway(List.of("/w/a.parquet")));
            assertEquals(0, db.markCompactedAway(List.of("/w/a.parquet")), "already gone — nothing to change");
        }
    }

    /** Same fail-open contract as record(): a failed state flip is logged, never thrown. */
    @Test
    void stateTransitionsAreBestEffortAndNeverThrow() throws Exception {
        DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:");
        db.close();
        assertEquals(0, db.supersede("c1"));
        assertEquals(0, db.markCompactedAway(List.of("/w/a.parquet")));
        assertEquals(0, db.markCompactedAway(List.of()));
        assertEquals(0, db.markCompactedAway(null));
    }

    // ── per-producer high water (consignment addressing §3.6) ────────────────────

    private static ConsignmentOutput by(String producer, String path, String eventTimeMax,
                                        String writtenAt, State state) {
        return new ConsignmentOutput("c-" + path, "run-1", "cdr", "dt=2026-08-10", "2026-08-10", path,
                1, 100, writtenAt, 0, state, null,
                eventTimeMax == null ? null : new EventTimeBounds("2026-08-10T00:00:00", eventTimeMax, 0),
                producer);
    }

    private static ProducerHighWater find(List<ProducerHighWater> all, String producer) {
        return all.stream().filter(p -> java.util.Objects.equals(p.producer(), producer))
                .findFirst().orElseThrow(() -> new AssertionError("no group for producer " + producer));
    }

    /** As {@link #by} but with both ends of the range explicit — {@code bounds()} folds min as well as max. */
    private static ConsignmentOutput ranged(String path, String min, String max, State state) {
        return new ConsignmentOutput("c-" + path, "run-1", "cdr", "dt=2026-08-10", "2026-08-10", path,
                1, 100, "2026-08-10T12:00:00Z", 0, state, null, new EventTimeBounds(min, max, 0), "north");
    }

    /**
     * §5-B — the min half {@code producerHighWater} does not expose, folded across every live file so a
     * downstream Job can bind the window its predecessor actually wrote.
     */
    @Test
    void boundsFoldTheWholeLiveRangeOfOneStream() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    ranged("/w/a.parquet", "2026-08-10T04:00:00", "2026-08-10T06:00:00", State.LIVE),
                    ranged("/w/b.parquet", "2026-08-10T02:00:00", "2026-08-10T05:00:00", State.LIVE),
                    ranged("/w/c.parquet", "2026-08-10T07:00:00", "2026-08-10T09:00:00", State.LIVE)));

            EventTimeBounds b = db.bounds("cdr").orElseThrow();
            assertEquals("2026-08-10T02:00:00", b.min(), "the earliest min across files, not the first row's");
            assertEquals("2026-08-10T09:00:00", b.max());
            assertEquals(7 * 3_600_000L, b.spreadMs(), "spread spans the fold, not any single file");
            assertTrue(db.bounds("other_table").isEmpty(), "the range is per stream");
        }
    }

    /**
     * The point of returning two scalars instead of one {@code "<min>..<max>"} string: each end is a value
     * {@code SqlParamScanner} can substitute straight into a predicate. It wraps a resolved value in one SQL
     * string literal, so what matters is that the engine accepts <em>that literal</em> where a timestamp is
     * expected — which it does, ISO {@code T} separator and all. The old composite could not: nothing split
     * it, so it substituted whole and produced a malformed literal.
     */
    @Test
    void eachEndSubstitutesIntoSqlAsATimestampLiteral() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    ranged("/w/a.parquet", "2026-08-10T02:00:00", "2026-08-10T09:00:00", State.LIVE)));
            EventTimeBounds b = db.bounds("cdr").orElseThrow();

            try (Connection c = java.sql.DriverManager.getConnection("jdbc:duckdb:");
                 Statement st = c.createStatement()) {
                // exactly the shape SqlParamScanner produces: the resolved value inside single quotes
                var rs = st.executeQuery("SELECT CAST('" + b.min() + "' AS TIMESTAMP) < CAST('" + b.max()
                        + "' AS TIMESTAMP) AS ok");
                assertTrue(rs.next());
                assertTrue(rs.getBoolean("ok"), "both ends must cast, and min must precede max");
            }
        }
    }

    /** The same predicate {@code producerHighWater} uses, and for the same reasons — pinned so it stays that way. */
    @Test
    void boundsExcludeSupersededButKeepCompactedAway() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    ranged("/w/live.parquet", "2026-08-10T04:00:00", "2026-08-10T06:00:00", State.LIVE),
                    ranged("/w/merged.parquet", "2026-08-10T07:00:00", "2026-08-10T09:00:00",
                            State.COMPACTED_AWAY),
                    ranged("/w/old.parquet", "2026-08-01T00:00:00", "2026-08-01T23:00:00", State.SUPERSEDED)));

            EventTimeBounds b = db.bounds("cdr").orElseThrow();
            assertEquals("2026-08-10T04:00:00", b.min(),
                    "a superseded revision must not widen the window — its rows were replaced");
            assertEquals("2026-08-10T09:00:00", b.max(),
                    "compacted rows were genuinely delivered and still count");
        }
    }

    /** Empty rather than half a window: a caller given one end would silently scan to the epoch. */
    @Test
    void boundsAreEmptyWhenNoLiveFileCarriesThem() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    by("north", "/w/n1.parquet", null, "2026-08-10T07:00:00Z", State.LIVE),
                    ranged("/w/old.parquet", "2026-08-01T00:00:00", "2026-08-01T23:00:00", State.SUPERSEDED)));

            assertTrue(db.bounds("cdr").isEmpty(), "no live bounds ⇒ unknown, not a range from the superseded row");
            assertTrue(db.bounds("never_written").isEmpty());
        }
    }

    @Test
    void producerHighWaterGroupsByProducerAndKeepsTheirNewestEventTime() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    by("north", "/w/n1.parquet", "2026-08-10T06:00:00", "2026-08-10T07:00:00Z", State.LIVE),
                    by("north", "/w/n2.parquet", "2026-08-10T11:00:00", "2026-08-10T12:00:00Z", State.LIVE),
                    by("south", "/w/s1.parquet", "2026-08-10T09:00:00", "2026-08-10T10:00:00Z", State.LIVE),
                    by(null, "/w/x1.parquet", null, "2026-08-10T10:30:00Z", State.LIVE)));

            List<ProducerHighWater> all = db.producerHighWater("cdr");
            assertEquals(3, all.size(), "one group per producer, and the unattributed rows are one of them");
            assertEquals("2026-08-10T11:00:00", find(all, "north").eventTimeMax());
            assertEquals("2026-08-10T09:00:00", find(all, "south").eventTimeMax());
            assertNull(find(all, null).eventTimeMax(), "rows with no bounds report unknown, not a value");
            assertTrue(db.producerHighWater("other_table").isEmpty(), "grouping is per stream");
        }
    }

    /**
     * SUPERSEDED rows are excluded, COMPACTED_AWAY rows are not. Their data still exists inside a merged file,
     * so dropping them would make the watermark travel backwards the moment a partition is compacted.
     */
    @Test
    void producerHighWaterExcludesSupersededButKeepsCompactedAway() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    by("north", "/w/live.parquet", "2026-08-10T06:00:00", "2026-08-10T07:00:00Z", State.LIVE),
                    by("north", "/w/merged.parquet", "2026-08-10T08:00:00", "2026-08-10T09:00:00Z",
                            State.COMPACTED_AWAY),
                    by("north", "/w/old.parquet", "2026-08-10T23:00:00", "2026-08-10T23:30:00Z",
                            State.SUPERSEDED)));

            assertEquals("2026-08-10T08:00:00", find(db.producerHighWater("cdr"), "north").eventTimeMax(),
                    "the compacted row counts; the superseded one must not claim delivery that was replaced");
        }
    }

    /**
     * {@code written_at} is {@code Instant.toString()}, whose fractional digits vary — so its lexicographic
     * order is not its chronological one, and a plain {@code max()} over the text would pick the earlier row.
     */
    @Test
    void producerLastSeenIsChronologicalNotLexicographic() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    by("north", "/w/a.parquet", "2026-08-10T06:00:00", "2026-08-10T10:00:00Z", State.LIVE),
                    by("north", "/w/b.parquet", "2026-08-10T07:00:00", "2026-08-10T10:00:00.500Z", State.LIVE)));

            assertEquals(java.time.Instant.parse("2026-08-10T10:00:00.500Z"),
                    find(db.producerHighWater("cdr"), "north").lastSeen(),
                    "text max would answer 10:00:00Z here, because '.' sorts before 'Z'");
        }
    }

    /** One unreadable timestamp must not fail the whole query — it reads back as an unknown last-seen, which
     *  the fold treats as in-horizon rather than dropping the producer. */
    @Test
    void unreadableWrittenAtDegradesToUnknownLastSeen() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(by("north", "/w/a.parquet", "2026-08-10T06:00:00", "not-a-timestamp", State.LIVE)));

            ProducerHighWater p = find(db.producerHighWater("cdr"), "north");
            assertNull(p.lastSeen());
            assertEquals("2026-08-10T06:00:00", p.eventTimeMax(), "the event time is still usable");
        }
    }

    // ── step 6: a full recompute supersedes earlier revisions of one table ───────

    @Test
    void supersedeOtherRevisionsSparesTheRevisionThatJustLanded() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(by("north", "/w/rev1.parquet", null, "2026-08-09T10:00:00Z", State.LIVE)));
            db.record(List.of(by("north", "/w/rev2.parquet", null, "2026-08-10T10:00:00Z", State.LIVE)));
            // a different table's rows must not move
            db.record(List.of(new ConsignmentOutput("c-other", "run-1", "sms", "", null, "/w/sms.parquet",
                    1, 100, "2026-08-10T10:00:00Z", 0, State.LIVE)));

            assertEquals(1, db.supersedeOtherRevisions("cdr", "c-/w/rev2.parquet"));
            assertEquals(List.of("/w/rev1.parquet"), db.unreadablePaths());
            assertEquals(State.LIVE, db.outputs("c-other").get(0).state(), "another table is untouched");
        }
    }

    /** A null keep would mark the recompute's own freshly written files stale and empty every read. */
    @Test
    void supersedeOtherRevisionsRefusesToRunWithoutAConsignmentToKeep() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            assertThrows(IllegalArgumentException.class, () -> db.supersedeOtherRevisions("cdr", null));
            assertThrows(IllegalArgumentException.class, () -> db.supersedeOtherRevisions(null, "c1"));
        }
    }

    @Test
    void supersedeOtherRevisionsIsANoOpOnATablesFirstRevision() throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(by("north", "/w/rev1.parquet", null, "2026-08-10T10:00:00Z", State.LIVE)));
            assertEquals(0, db.supersedeOtherRevisions("cdr", "c-/w/rev1.parquet"));
            assertTrue(db.unreadablePaths().isEmpty());
        }
    }

    @Test
    void producerHighWaterIsBestEffortAndNeverThrows() throws Exception {
        DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:");
        db.close();
        assertTrue(db.producerHighWater("cdr").isEmpty());
    }
}
