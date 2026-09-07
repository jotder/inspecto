package com.gamma.service;

import com.gamma.acquire.DbAcquisitionLedger;
import com.gamma.acquire.LedgerEntry;
import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.consignment.DbFileStageStore;
import com.gamma.consignment.FileStage;
import com.gamma.consignment.FileStageRecord;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.StatusStore;
import com.gamma.etl.TestConfigs;
import com.gamma.job.DbJobRunStore;
import com.gamma.job.JobRun;
import com.gamma.ops.DbObjectStore;
import com.gamma.ops.ObjectQuery;
import com.gamma.objects.ObjectType;
import com.gamma.ops.OperationalObject;
import com.gamma.objects.AnnotationKinds;
import com.gamma.ops.link.DbLinkStore;
import com.gamma.ops.link.ObjectLink;
import com.gamma.ops.tag.DbTagAssignmentStore;
import com.gamma.objects.TagAssignment;
import com.gamma.ops.note.DbNoteStore;
import com.gamma.ops.note.ObjectNote;
import com.gamma.ops.note.NoteKind;
import com.gamma.pipeline.exec.DbProvenanceStore;
import com.gamma.pipeline.exec.ProvenanceRow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DAT-6 — proves the ten JDBC-backed stores actually run on <b>real PostgreSQL</b>, not just the
 * bundled DuckDB. Every store opens against one database, runs {@code initSchema}, and does a
 * write→read round-trip. The stores use distinct table names, so they coexist without collision.
 *
 * <p>The critical case is {@link DbJobRunStore#metrics} (p50/p95): those percentiles are the one piece of
 * non-portable SQL — DuckDB's {@code quantile_cont} vs Postgres's {@code percentile_cont(..) WITHIN GROUP}
 * — and this test exercises them against the real engine to pin the dialect fix.
 *
 * <p><b>It runs against an EXISTING server</b> (operator decision 2026-09-07). The embedded-Postgres
 * harness and its per-platform binaries were removed: only the JDBC <em>client driver</em> stays, so
 * Postgres is installed separately or pointed at. Enable with either
 * {@code -Dinspecto.test.pg.url=jdbc:postgresql://host:5432/db?user=…&password=…} or the environment
 * variable {@code INSPECTO_TEST_PG_URL}; with neither, the class SKIPS.
 *
 * <p>⚠ Skipping means this coverage does not run by default — including in CI. That is the deliberate
 * cost of dropping the embedded engine, and it is the shape
 * {@code okf/backend/build-run/guard-coverage.md} warns about, so the skip message names exactly how to
 * turn it back on rather than passing quietly.
 *
 * <p>🔴 The assertions are exact counts ({@code total == 6}, {@code all.size() == 2}), so they need a
 * clean database — which a shared server is not. Each run therefore creates its own uniquely-named
 * SCHEMA, points the stores at it through {@code currentSchema}, and drops it afterwards. Never let this
 * test write into the caller's default schema: it would pass once and then fail forever.
 */
class PostgresStateStoreTest {

    /** System property (or {@code INSPECTO_TEST_PG_URL} env var) naming the server to test against. */
    private static final String URL_PROPERTY = "inspecto.test.pg.url";

    private static String adminUrl;
    private static String schema;
    private static String url;

    @BeforeAll
    static void connectToAnExistingPostgres() throws Exception {
        adminUrl = System.getProperty(URL_PROPERTY);
        if (adminUrl == null || adminUrl.isBlank()) adminUrl = System.getenv("INSPECTO_TEST_PG_URL");
        if (adminUrl == null || adminUrl.isBlank()) return;   // unconfigured: @BeforeEach reports the skip

        // One throwaway schema per run: the assertions below count rows exactly, so they need a clean
        // database, and a server someone else supplied is not one.
        schema = "inspecto_test_" + Long.toHexString(System.nanoTime());
        try (Connection c = DriverManager.getConnection(adminUrl); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        url = adminUrl + (adminUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    /**
     * The skip lives HERE, not in {@link #connectToAnExistingPostgres()}, and that is deliberate.
     *
     * <p>An {@code assumeTrue} inside {@code @BeforeAll} aborts the whole container, and surefire then
     * reports <b>"Tests run: 0, Skipped: 0"</b> — the class disappears from the totals with no trace of
     * why, which is the silently-disarmed guard this repo keeps paying for
     * ({@code okf/backend/build-run/guard-coverage.md}). Per-test, every method is reported as SKIPPED
     * <em>with this message</em>, so the absent coverage is visible in the count and in the report.
     */
    @BeforeEach
    void requireAConfiguredServer() {
        assumeTrue(url != null,
                "DAT-6 needs a PostgreSQL server: pass -D" + URL_PROPERTY
                        + "=jdbc:postgresql://host:5432/db?user=…&password=… or set INSPECTO_TEST_PG_URL. "
                        + "The embedded harness was removed 2026-09-07 — only the JDBC client driver ships.");
    }

    @AfterAll
    static void dropTheThrowawaySchema() throws Exception {
        if (schema == null || adminUrl == null) return;
        try (Connection c = DriverManager.getConnection(adminUrl); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void jobRunStore_metricsUsePostgresPercentile() throws Exception {
        try (DbJobRunStore store = DbJobRunStore.open(url)) {
            for (long d : new long[] {100, 200, 300, 400, 500}) {
                store.record(new JobRun("run-" + d, "nightly", "PIPELINE", "SCHEDULE",
                        "2026-07-07T00:00:00Z", "2026-07-07T00:01:00Z", "SUCCESS", d, "ok"));
            }
            store.record(new JobRun("run-fail", "nightly", "PIPELINE", "SCHEDULE",
                    "2026-07-07T00:00:00Z", "2026-07-07T00:01:00Z", "FAILED", 999, "boom"));

            Map<String, Object> m = store.metrics("nightly");
            assertEquals(6L, m.get("total"), "all runs counted");
            assertEquals(5L, m.get("success"), "success FILTER worked on Postgres");
            assertEquals(1L, m.get("failed"), "failed FILTER worked on Postgres");
            // percentile_cont(0.5) WITHIN GROUP over {100,200,300,400,500,999} = 350.0
            assertEquals(350.0, (double) m.get("p50Ms"), 0.001, "Postgres percentile_cont p50");
            double p95 = (double) m.get("p95Ms");
            assertTrue(p95 > 500.0 && p95 <= 999.0, "p95 lands in the upper tail: " + p95);

            assertFalse(store.recentRuns(10, "nightly").isEmpty(), "recent runs read back");
            assertFalse(store.failureTrend(7).isEmpty(), "failure trend groups by day on Postgres");
        }
    }

    @Test
    void objectStore_createUpdateQueryRoundTrip() throws Exception {
        try (DbObjectStore store = DbObjectStore.open(url, null, null)) {
            OperationalObject obj = OperationalObject.builder(ObjectType.ALERT)
                    .id("PG-ALERT-1").title("disk full").status("OPEN").severity("HIGH")
                    .owner("ops").attr("rule", "disk>90").build();
            store.create(obj);

            Optional<OperationalObject> got = store.get("PG-ALERT-1");
            assertTrue(got.isPresent(), "object read back from Postgres");
            assertEquals("disk full", got.get().title());
            assertEquals("disk>90", got.get().attributes().get("rule"), "JSON attributes survived");

            store.update(got.get().withStatus("RESOLVED", System.currentTimeMillis(), true));
            assertEquals("RESOLVED", store.get("PG-ALERT-1").orElseThrow().status());

            List<OperationalObject> hits = store.query(
                    new ObjectQuery(ObjectType.ALERT, "RESOLVED", null, null, null, null, null, 10, 0));
            assertTrue(hits.stream().anyMatch(o -> o.id().equals("PG-ALERT-1")), "query filter matched");
        }
    }

    @Test
    void linkStore_appendAndReadRoundTrip() throws Exception {
        try (DbLinkStore store = DbLinkStore.open(url, null, null)) {
            store.add(ObjectLink.of("CASE-1", ObjectType.CASE, "INC-1", ObjectType.INCIDENT, "CONTAINS"));
            store.add(ObjectLink.of("INC-1", ObjectType.INCIDENT, "ALERT-1", ObjectType.ALERT, "ESCALATED_FROM"));

            List<ObjectLink> incident = store.incident("INC-1");
            assertEquals(2, incident.size(), "both edges touching INC-1 read back from Postgres");
            assertTrue(store.all(10).size() >= 2);
        }
    }

    @Test
    void noteStore_appendAndReadRoundTrip() throws Exception {
        try (DbNoteStore store = DbNoteStore.open(url, null, null)) {
            store.add(ObjectNote.comment("PG-ALERT-1", "alice", "looking into it"));
            store.add(ObjectNote.attachment("PG-ALERT-1", "bob", "log.txt", "text/plain", "s3://x", "the log"));

            List<ObjectNote> all = store.forObject("PG-ALERT-1", null);
            assertEquals(2, all.size(), "both notes read back from Postgres");
            List<ObjectNote> comments = store.forObject("PG-ALERT-1", NoteKind.COMMENT);
            assertEquals(1, comments.size(), "kind filter worked");
            assertEquals("the log", store.forObject("PG-ALERT-1", NoteKind.ATTACHMENT).get(0).body());
        }
    }

    /**
     * D10 on Postgres: the {@code target_kind} column + backfill land through the same
     * {@code ADD COLUMN IF NOT EXISTS} migration, a legacy row reads back as {@code object}, and two
     * families sharing an id stay separated.
     */
    @Test
    void noteStore_mixedTargetKindsAndLegacyRowMigration() throws Exception {
        String legacyTable = "inspecto_ops_notes";
        try (java.sql.Connection conn = com.gamma.util.JdbcDrivers.connect(url, null, null);
             java.sql.Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + legacyTable);
            st.execute("CREATE TABLE " + legacyTable + " (id VARCHAR PRIMARY KEY, object_id VARCHAR, "
                    + "kind VARCHAR, author VARCHAR, body VARCHAR, attributes VARCHAR, created_at BIGINT)");
            st.execute("INSERT INTO " + legacyTable
                    + " VALUES ('PG-OLD','PG-VIEW-1','COMMENT','alice','legacy note','',50)");
        }

        try (DbNoteStore store = DbNoteStore.open(url, null, null)) {          // ← runs the migration
            List<ObjectNote> migrated = store.forObject("PG-VIEW-1", null);
            assertEquals(1, migrated.size(), "legacy row survives the migration");
            assertEquals("object", migrated.get(0).targetKind(), "and is backfilled to 'object'");

            store.add(ObjectNote.comment("link-analysis-view", "PG-VIEW-1", "bob", "odd cluster"));
            assertEquals(1, store.forObject("PG-VIEW-1", null).size(), "same id, other family — no bleed");
            List<ObjectNote> onView = store.forTarget("link-analysis-view", "PG-VIEW-1", null);
            assertEquals(1, onView.size());
            assertEquals("odd cluster", onView.get(0).body());
        }
    }

    @Test
    void provenanceStore_recordAndQueryRoundTrip() throws Exception {
        try (DbProvenanceStore store = DbProvenanceStore.open(url)) {
            store.record(List.of(
                    new ProvenanceRow("flow-1", "batch-1", "parse", "data", 100, "2026-07-07T00:00:00Z"),
                    new ProvenanceRow("flow-1", "batch-1", "parse", "invalid", 5, "2026-07-07T00:00:00Z")));

            List<Map<String, Object>> rows = store.query("flow-1", "batch-1");
            assertEquals(2, rows.size(), "both provenance cells read back from Postgres");
            assertFalse(store.batches("flow-1", 10).isEmpty(), "batch summary (max/sum/group by) works on Postgres");
        }
    }

    @Test
    void acquisitionLedger_recordFindAndWatermarkRoundTrip() throws Exception {
        try (DbAcquisitionLedger ledger = DbAcquisitionLedger.open(url, null, null)) {
            ledger.record(new LedgerEntry("sftp-src", "2026/07/data.csv", "data.csv", 4096,
                    "sha256:abc", "etag-1", "v3", 1_000L, 2_000L, LedgerEntry.PROCESSED));

            Optional<LedgerEntry> got = ledger.find("sftp-src", "2026/07/data.csv");
            assertTrue(got.isPresent(), "ledger entry read back from Postgres");
            assertEquals(4096, got.get().size(), "size round-tripped");
            assertEquals("etag-1", got.get().etag(), "etag round-tripped");
            assertEquals("v3", got.get().version(), "object_version round-tripped (reserved-word-safe column)");

            OptionalLong hw = ledger.highWatermark("sftp-src");
            assertTrue(hw.isPresent() && hw.getAsLong() == 1_000L, "MAX(last_modified) watermark on Postgres");
            assertTrue(ledger.highWatermark("nobody").isEmpty(), "MAX over an empty set is SQL NULL → empty");

            ledger.recordDbWatermark("jdbc-src", "2026-07-17T00:00:00Z");
            assertEquals(Optional.of("2026-07-17T00:00:00Z"), ledger.dbWatermark("jdbc-src"),
                    "db-watermark upsert round-tripped through Postgres");
        }
    }

    @Test
    void statusStore_syncAndReadRoundTrip(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(
                TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write().toString());

        // A tiny in-memory source: DbStatusStore.sync projects these rows into Postgres.
        StatusStore source = new StatusStore() {
            @Override public Set<String> committedBatches(PipelineConfig c) { return Set.of("b1", "b2"); }
            @Override public List<Map<String, String>> batches(PipelineConfig c) {
                return List.of(Map.of("consignment_id", "b1", "status", "SUCCESS"),
                               Map.of("consignment_id", "b2", "status", "SUCCESS"));
            }
            @Override public List<Map<String, String>> files(PipelineConfig c) {
                return List.of(Map.of("file", "data.csv"));
            }
            @Override public List<Map<String, String>> lineage(PipelineConfig c, String batchId) {
                return List.of(Map.of("consignment_id", "b1", "rows", "3"));
            }
            @Override public List<Map<String, String>> quarantine(PipelineConfig c) { return List.of(); }
        };

        try (DbStatusStore db = DbStatusStore.open(url, null, null)) {
            db.sync(source, List.of(cfg));
            assertEquals(Set.of("b1", "b2"), db.committedBatches(cfg), "commits projected to Postgres");
            assertEquals(2, db.batches(cfg).size(), "batch rows round-tripped through Postgres");
            assertEquals(1, db.files(cfg).size());
            assertEquals("b1", db.lineage(cfg, "b1").get(0).get("consignment_id"), "lineage filter by batch on Postgres");
            assertTrue(db.quarantine(cfg).isEmpty());
        }
    }

    /**
     * ⚠ The three tests below close a coverage gap the postgres-multi-user plan recorded as "8/8, only
     * `DbTagAssignmentStore` missing". That was mis-sized: the family is <b>ten</b> stores and <b>three</b>
     * were uncovered here.
     *
     * <p>⚠ Two of the three ({@link DbConsignmentOutputStore#record}, {@link DbFileStageStore#record}) are
     * documented <b>best-effort: a write failure is logged, never thrown</b>, because they index data that
     * has already committed. That makes a Postgres dialect break in them <b>completely silent</b> — the write
     * would vanish into a WARN and the batch would report success. So these must assert the READ BACK, never
     * merely that {@code record} returned.
     */
    @Test
    void tagAssignmentStore_addIsIdempotentAndReadsBack() throws Exception {
        try (DbTagAssignmentStore store = DbTagAssignmentStore.open(url, null, null)) {
            TagAssignment first = store.add(
                    new TagAssignment("urgent", AnnotationKinds.OBJECT, "obj-1", "alice", 1_000L));
            assertEquals("alice", first.actor(), "the stored edge round-tripped from Postgres");

            // Re-tagging returns the ALREADY-STORED edge — the checked-then-inserted path, not a rewrite.
            TagAssignment again = store.add(
                    new TagAssignment("urgent", AnnotationKinds.OBJECT, "obj-1", "bob", 9_000L));
            assertEquals("alice", again.actor(), "re-tagging must not rewrite who applied it");
            assertEquals(1_000L, again.createdAt(), "nor when");

            assertEquals(List.of("urgent"), store.tagsOf(AnnotationKinds.OBJECT, "obj-1"),
                    "SELECT DISTINCT … ORDER BY tag works on Postgres");
            assertEquals(1, store.forTag("urgent").size(), "forTag read back through Postgres");

            assertTrue(store.remove("urgent", AnnotationKinds.OBJECT, "obj-1"), "delete reports a hit");
            assertTrue(store.tagsOf(AnnotationKinds.OBJECT, "obj-1").isEmpty(), "and the edge is gone");
        }
    }

    @Test
    void consignmentOutputStore_recordAndQueryRoundTrip() throws Exception {
        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open(url)) {
            store.record(List.of(new ConsignmentOutput("cons-1", "run-1", "cdr", "day=2026-08-15",
                    "2026-08-15", "/data/cdr/day=2026-08-15/part-0.parquet", 42L, 4096L,
                    "2026-08-15T00:00:00Z", 1, ConsignmentOutput.State.LIVE)));

            List<ConsignmentOutput> got = store.outputs("cons-1");
            assertEquals(1, got.size(),
                    "the row read back — record() swallows SQLException, so only this proves it wrote");
            assertEquals(42L, got.get(0).rows(), "row_count round-tripped");
            assertEquals(4096L, got.get(0).bytes(), "bytes round-tripped");
            assertEquals(ConsignmentOutput.State.LIVE, got.get(0).state());
            // initSchema runs ALTER TABLE … ADD COLUMN IF NOT EXISTS five times; Postgres must accept it.
            assertNull(got.get(0).schemaFingerprint(), "an unset additive column reads back NULL, not a throw");
        }
    }

    @Test
    void fileStageStore_recordAndStagesRoundTrip() throws Exception {
        try (DbFileStageStore store = DbFileStageStore.open(url)) {
            store.record(List.of(
                    new FileStageRecord("sftp-src", "2026/08/a.csv", "b1", FileStage.REGISTERED,
                            "2026-08-15T00:00:00Z"),
                    new FileStageRecord("sftp-src", "2026/08/a.csv", "b1", FileStage.MANIFESTED,
                            "2026-08-15T01:00:00Z")));

            List<FileStageRecord> stages = store.stages("sftp-src", "2026/08/a.csv");
            assertEquals(2, stages.size(),
                    "both rows read back — record() swallows SQLException, so only this proves it wrote");
            assertEquals(FileStage.REGISTERED, stages.get(0).stage(), "ORDER BY recorded_at, oldest first");
            assertEquals(FileStage.MANIFESTED, stages.get(1).stage());
            assertTrue(store.stages("sftp-src", "nope.csv").isEmpty(), "an unknown file has no stages");
        }
    }
}
