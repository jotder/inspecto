package com.gamma.service;

import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the M5 database-backed {@link DbStatusStore} against a real DuckDB in-memory
 * engine (the bundled JDBC driver — no external server needed). The same engine-neutral
 * JDBC code path runs on PostgreSQL in production; these tests pin the SQL + the
 * file→DB projection ({@link DbStatusStore#sync}) and prove the DB read surface matches
 * the file-backed store the Control API/observability used before M5.
 */
class DbStatusStoreTest {

    private Connection conn;
    private DbStatusStore db;

    @BeforeEach
    void openDb() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        conn = DriverManager.getConnection("jdbc:duckdb:");   // in-memory, lives with this connection
        db = new DbStatusStore(conn);
    }

    @AfterEach
    void closeDb() {
        if (db != null) db.close();
    }

    /** Run a real Stage-1 batch so the on-disk audit artifacts exist to project from. */
    private PipelineConfig runOnePipeline(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Path inbox = dir.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"),
                "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n2,20,2020-01-01\n3,30,2020-02-05\n");
        try (CollectorService svc = new CollectorService(List.of(toon), 3600, 1)) {
            assertEquals(0, svc.runAllOnce().failed(), "the seeded batch should commit cleanly");
        }
        return PipelineConfig.load(toon.toString());
    }

    @Test
    void syncProjectsFileAuditAndReadsMatchTheFileStore(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = runOnePipeline(dir);
        FileStatusStore file = new FileStatusStore();

        db.sync(file, List.of(cfg));

        // committed batches round-trip exactly
        Set<String> fileCommits = file.committedBatches(cfg);
        assertFalse(fileCommits.isEmpty(), "the run committed at least one batch");
        assertEquals(fileCommits, db.committedBatches(cfg), "DB commits match the commit log");

        // batch / file / lineage row counts and content match the file store
        assertEquals(file.batches(cfg).size(), db.batches(cfg).size(), "batch row count matches");
        assertEquals(file.files(cfg).size(),   db.files(cfg).size(),   "file row count matches");
        assertEquals(file.lineage(cfg, null),  db.lineage(cfg, null),  "lineage rows match verbatim");
        assertFalse(db.batches(cfg).isEmpty(), "batch audit projected");

        // a projected row preserves its columns (ordered map → JSON → ordered map)
        Map<String, String> first = db.batches(cfg).get(0);
        assertEquals(file.batches(cfg).get(0), first, "row map round-trips faithfully");
    }

    /**
     * The unpack ledger rides the same projection: sync copies the file store's rows into
     * {@code inspecto_status_unpack} and the DB read matches the file read verbatim.
     */
    @Test
    void syncProjectsTheUnpackLedger(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = runOnePipeline(dir);
        FileStatusStore file = new FileStatusStore();

        // The seeded run has no archives, so write one archive's row the way the engine does.
        Path statusDir = Path.of(cfg.dirs().statusFilePath()).toAbsolutePath().getParent();
        String name = cfg.identity().pipelineName();
        java.io.File archive = dir.resolve("inbox").resolve("data.zip").toFile();
        String runId = "UNPACK_SYNC_TEST";
        com.gamma.etl.unpack.UnpackLedger.expanded(runId, archive, "zip", 2, 0, 10, 20, false, "");
        com.gamma.etl.unpack.UnpackLedger.entryOutcome(runId, archive, "B1", true);
        com.gamma.etl.unpack.UnpackLedger.entryOutcome(runId, archive, "B1", true);
        com.gamma.etl.unpack.UnpackLedger.flush(runId,
                statusDir.resolve(name + "_unpack_TEST.csv").toString(), dir.resolve("inbox"));

        db.sync(file, List.of(cfg));

        assertEquals(file.unpack(cfg), db.unpack(cfg), "unpack rows project verbatim");
        assertEquals(1, db.unpack(cfg).size());
        assertEquals("UNPACKED", db.unpack(cfg).get(0).get("status"));
    }

    @Test
    void reSyncIsIdempotent(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = runOnePipeline(dir);
        FileStatusStore file = new FileStatusStore();

        db.sync(file, List.of(cfg));
        int batches = db.batches(cfg).size();
        int commits = db.committedBatches(cfg).size();

        db.sync(file, List.of(cfg));   // refresh — DELETE-then-INSERT, no duplication
        assertEquals(batches, db.batches(cfg).size(), "re-sync does not duplicate batch rows");
        assertEquals(commits, db.committedBatches(cfg).size(), "re-sync does not duplicate commits");
    }

    @Test
    void lineageFiltersByBatchId(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = runOnePipeline(dir);
        FileStatusStore file = new FileStatusStore();
        db.sync(file, List.of(cfg));

        String batchId = db.committedBatches(cfg).iterator().next();
        List<Map<String, String>> filtered = db.lineage(cfg, batchId);
        assertFalse(filtered.isEmpty(), "lineage rows exist for the committed batch");
        assertTrue(filtered.stream().allMatch(r -> batchId.equals(r.get("consignment_id"))),
                "every filtered lineage row belongs to the requested batch");
        // an unknown batch yields nothing
        assertTrue(db.lineage(cfg, "no-such-batch").isEmpty());
    }

    @Test
    void collectorServiceWithDbBackendServesDataThroughTheReadSurface(@TempDir Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Path inbox = dir.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"),
                "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n2,20,2020-01-01\n3,30,2020-02-05\n");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        // a dedicated connection: CollectorService.close() owns and closes the DB store
        Class.forName("org.duckdb.DuckDBDriver");
        Connection svcConn = DriverManager.getConnection("jdbc:duckdb:");
        DbStatusStore backend = new DbStatusStore(svcConn);
        try (CollectorService svc =
                     new CollectorService(List.of(toon), List.of(), 3600, 1, backend)) {
            assertEquals(0, svc.runAllOnce().failed());
            // runAllOnce projects the new commit into the DB; the API/observability read here
            assertSame(backend, svc.statusStore(), "the DB store is the service's read surface");
            assertFalse(svc.statusStore().committedBatches(cfg).isEmpty(),
                    "committed batch is queryable from the DB after the cycle");
            assertFalse(svc.statusStore().batches(cfg).isEmpty(), "batch audit projected to the DB");
            // observability reads committed count through the same surface
            assertEquals(1, svc.pipelines().get(0).committedBatches());
        }
    }

    @Test
    void duckDbFilePersistsAcrossReconnect(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = runOnePipeline(dir);
        FileStatusStore file = new FileStatusStore();
        String url = "jdbc:duckdb:" + dir.resolve("status.db").toString().replace('\\', '/');

        // first connection: open the primary DuckDB file backend, sync, close it
        Set<String> committed;
        try (DbStatusStore first = DbStatusStore.open(url, null, null)) {
            first.sync(file, List.of(cfg));
            committed = first.committedBatches(cfg);
            assertFalse(committed.isEmpty());
        }

        // reopen the same file: the projected status survived (durable, not in-memory)
        try (DbStatusStore reopened = DbStatusStore.open(url, null, null)) {
            assertEquals(committed, reopened.committedBatches(cfg), "commits survived reconnect");
            assertEquals(file.batches(cfg).size(), reopened.batches(cfg).size(),
                    "batch audit survived reconnect");
        }
    }

    @Test
    void freshStoreReadsEmptyBeforeAnySync(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = runOnePipeline(dir);
        // schema initialised, but no sync yet → all reads empty, never throwing
        assertTrue(db.committedBatches(cfg).isEmpty());
        assertTrue(db.batches(cfg).isEmpty());
        assertTrue(db.files(cfg).isEmpty());
        assertTrue(db.lineage(cfg, null).isEmpty());
        assertTrue(db.quarantine(cfg).isEmpty());
    }

    /** A pipeline rename's identity migration (T3, plan §3): every table's `pipeline` column moves together. */
    @Test
    void renamePipelineMovesAllRowsAtomically(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = runOnePipeline(dir);
        FileStatusStore file = new FileStatusStore();
        db.sync(file, List.of(cfg));
        String oldName = cfg.identity().pipelineName();
        int batchesBefore = db.batches(cfg).size();
        int commitsBefore = db.committedBatches(cfg).size();
        assertTrue(batchesBefore > 0 && commitsBefore > 0, "the run left rows to move");

        db.renamePipeline(oldName, "renamed_id");

        assertTrue(db.batches(cfg).isEmpty(), "the old id has no rows left in any table");
        assertTrue(db.committedBatches(cfg).isEmpty());
        assertEquals(batchesBefore, countRows("inspecto_status_batches", "renamed_id"), "batches moved");
        assertEquals(commitsBefore, countRows("inspecto_status_commits", "renamed_id"), "commits moved");
    }

    private int countRows(String table, String pipeline) throws Exception {
        try (var ps = conn.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE pipeline = ?")) {
            ps.setString(1, pipeline);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Pre-rebrand ucc_status_* tables are renamed on connect and their rows survive. */
    @Test
    void migratesLegacyUccTablesPreservingRows() throws Exception {
        Connection legacy = DriverManager.getConnection("jdbc:duckdb:");
        try (var st = legacy.createStatement()) {
            st.execute("CREATE TABLE ucc_status_commits (pipeline VARCHAR, batch_id VARCHAR)");
            st.execute("INSERT INTO ucc_status_commits VALUES ('p1', 'b1')");
        }
        try (DbStatusStore store = new DbStatusStore(legacy)) {
            assertNotNull(store);
            try (var st = legacy.createStatement();
                 var rs = st.executeQuery("SELECT consignment_id FROM inspecto_status_commits")) {
                assertTrue(rs.next(), "migrated row present, under the 4.0.0 column name");
                assertEquals("b1", rs.getString(1));
            }
            try (var st = legacy.createStatement();
                 var rs = st.executeQuery(
                         "SELECT 1 FROM information_schema.tables WHERE table_name = 'ucc_status_commits'")) {
                assertFalse(rs.next(), "legacy table gone after rename");
            }
        }
    }

    /**
     * {@code DB-STATUS-INDEX-1}: every list predicate this store issues has a backing index, and the
     * indexed columns are the ones actually filtered/ordered on — not a guess. Asserting the exact
     * (table → columns) set means adding a predicate without an index, or an index without a predicate,
     * both fail here.
     */
    /** X5 L4: one Consignment's ledger row and member files by query — the same rows the whole-ledger reads hold. */
    @Test
    void readsOneConsignmentsRowAndFilesById(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = runOnePipeline(dir);
        FileStatusStore file = new FileStatusStore();
        db.sync(file, List.of(cfg));

        String id = db.committedBatches(cfg).iterator().next();
        Map<String, String> expected = db.batches(cfg).stream()
                .filter(r -> id.equals(r.get("consignment_id"))).findFirst().orElseThrow();
        assertEquals(expected, db.consignment(cfg, id).orElseThrow(), "the row the ledger holds");
        assertEquals(file.consignment(cfg, id).orElseThrow(), db.consignment(cfg, id).orElseThrow(),
                "the DB query and the file store's default agree");
        List<Map<String, String>> members = db.consignmentFiles(cfg, id, 10);
        assertFalse(members.isEmpty());
        assertEquals(file.consignmentFiles(cfg, id, 10), members, "the DB query and the file store's default agree");
        try (var st = conn.createStatement()) {   // a second member row for the same Consignment
            st.execute("INSERT INTO inspecto_status_files SELECT * FROM inspecto_status_files");
        }
        assertEquals(2 * members.size(), db.consignmentFiles(cfg, id, 10).size());
        assertEquals(1, db.consignmentFiles(cfg, id, 1).size(), "LIMIT applies");
        assertTrue(db.consignment(cfg, "no-such").isEmpty());
        assertTrue(db.consignment(cfg, id.substring(0, id.length() - 1) + "%").isEmpty(),
                "a LIKE wildcard in the id is a literal");
        assertTrue(db.consignmentFiles(cfg, "no-such", 10).isEmpty());
    }

    @Test
    void everyListPredicateHasABackingIndexOnTheColumnsItFilters() throws Exception {
        Map<String, String> byTable = new java.util.TreeMap<>();
        try (var st = conn.createStatement();
             var rs = st.executeQuery("SELECT table_name, sql FROM duckdb_indexes()")) {
            while (rs.next()) byTable.put(rs.getString(1), rs.getString(2).toLowerCase(java.util.Locale.ROOT));
        }

        // commits is looked up by pipeline only (committedBatches); the other five also ORDER BY seq,
        // and lineage additionally filters consignment_id.
        assertTrue(byTable.get("inspecto_status_commits").contains("(pipeline)"),
                "commits: WHERE pipeline = ?");
        for (String t : List.of("inspecto_status_batches", "inspecto_status_files",
                                "inspecto_status_quarantine", "inspecto_status_unpack")) {
            assertTrue(byTable.get(t) != null && byTable.get(t).contains("(pipeline, seq)"),
                    t + ": WHERE pipeline = ? ORDER BY seq — got " + byTable.get(t));
        }
        assertTrue(byTable.get("inspecto_status_lineage").contains("(pipeline, consignment_id, seq)"),
                "lineage: WHERE pipeline = ? AND consignment_id = ? ORDER BY seq");

        assertEquals(Set.of("inspecto_status_commits", "inspecto_status_batches", "inspecto_status_files",
                            "inspecto_status_lineage", "inspecto_status_quarantine", "inspecto_status_unpack"),
                byTable.keySet(), "no index without a predicate: an unused index is dead weight on every sync");
    }

    /** D-12 (MoSCoW S4): a new status DB names the Consignment column {@code consignment_id}, never {@code batch_id}. */
    @Test
    void aNewDatabaseNamesTheConsignmentColumnConsignmentId() throws Exception {
        assertEquals(List.of("inspecto_status_commits.consignment_id", "inspecto_status_lineage.consignment_id"),
                consignmentColumns(conn));
    }

    /**
     * D-12 (MoSCoW S4): a status DB written by 3.x — {@code batch_id} columns, the old lineage index, payloads
     * carrying the {@code "batch_id"} key — is renamed when 4.0.0 opens it, and serves every row it held under the
     * new names. A second open finds nothing to do and changes nothing.
     */
    @Test
    void aPre400DatabaseIsRenamedOnOpenAndStillServesItsRows(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(
                TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write().toString());
        String p = cfg.identity().pipelineName();
        String url = "jdbc:duckdb:" + dir.resolve("status-3x.duckdb");
        try (Connection old = DriverManager.getConnection(url); var st = old.createStatement()) {
            st.execute("CREATE TABLE inspecto_status_commits (pipeline VARCHAR, batch_id VARCHAR)");
            st.execute("CREATE TABLE inspecto_status_batches (pipeline VARCHAR, seq BIGINT, payload VARCHAR)");
            st.execute("CREATE TABLE inspecto_status_files (pipeline VARCHAR, seq BIGINT, payload VARCHAR)");
            st.execute("CREATE TABLE inspecto_status_lineage (pipeline VARCHAR, batch_id VARCHAR, seq BIGINT, payload VARCHAR)");
            st.execute("CREATE TABLE inspecto_status_quarantine (pipeline VARCHAR, seq BIGINT, payload VARCHAR)");
            st.execute("CREATE INDEX inspecto_status_commits_by_pipeline ON inspecto_status_commits (pipeline)");
            st.execute("CREATE INDEX inspecto_status_lineage_by_pipeline_batch ON inspecto_status_lineage (pipeline, batch_id, seq)");
            st.execute("INSERT INTO inspecto_status_commits VALUES ('" + p + "', 'b1')");
            st.execute("INSERT INTO inspecto_status_lineage VALUES ('" + p + "', 'b1', 0, '{\"batch_id\":\"b1\",\"rows\":\"3\"}')");
            st.execute("INSERT INTO inspecto_status_batches VALUES ('" + p + "', 0, "
                    + "'{\"batch_id\":\"b1\",\"note\":\"says \\\"batch_id\\\": in a value\"}')");
        }

        for (int open = 1; open <= 2; open++) {
            try (Connection c = DriverManager.getConnection(url); DbStatusStore store = new DbStatusStore(c)) {
                assertEquals(List.of("inspecto_status_commits.consignment_id", "inspecto_status_lineage.consignment_id"),
                        consignmentColumns(c), "open " + open + ": no batch_id column is left");
                assertEquals(Set.of("b1"), store.committedBatches(cfg), "open " + open);
                List<Map<String, String>> lineage = store.lineage(cfg, "b1");
                assertEquals(1, lineage.size(), "open " + open + ": the lineage filter reads the renamed column");
                assertEquals(Map.of("consignment_id", "b1", "rows", "3"), lineage.get(0), "open " + open);
                Map<String, String> batch = store.batches(cfg).get(0);
                assertEquals("b1", batch.get("consignment_id"), "open " + open + ": the payload key was rewritten");
                assertFalse(batch.containsKey("batch_id"), "open " + open);
                assertEquals("says \"batch_id\": in a value", batch.get("note"),
                        "open " + open + ": a value that merely contains the old key is left alone");
            }
        }
    }

    /**
     * Review fix (Postgres schema-per-Space): another schema of the same database still holding 3.x tables must not
     * be seen by this schema's migration. A new store, and then the migrated one reopened, open cleanly in
     * {@code main}, and the other schema keeps its {@code batch_id} columns and payloads untouched.
     */
    @Test
    void anotherSchemasLegacyTablesAreNeitherSeenNorTouched(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("two-schemas.duckdb");
        try (Connection c = DriverManager.getConnection(url); var st = c.createStatement()) {
            st.execute("CREATE SCHEMA other");
            st.execute("CREATE TABLE other.inspecto_status_commits (pipeline VARCHAR, batch_id VARCHAR)");
            st.execute("CREATE TABLE other.inspecto_status_lineage (pipeline VARCHAR, batch_id VARCHAR, seq BIGINT, payload VARCHAR)");
            st.execute("CREATE TABLE other.inspecto_status_batches (pipeline VARCHAR, seq BIGINT, payload VARCHAR)");
            st.execute("INSERT INTO other.inspecto_status_batches VALUES ('p', 0, '{\"batch_id\":\"b1\"}')");
        }
        String tables = "'inspecto_status_commits', 'inspecto_status_lineage'";
        for (int open = 1; open <= 2; open++) {
            try (Connection c = DriverManager.getConnection(url)) {
                assertDoesNotThrow(() -> new DbStatusStore(c).close(), "open " + open);
            }
            try (Connection c = DriverManager.getConnection(url)) {
                assertEquals(List.of("inspecto_status_commits.consignment_id", "inspecto_status_lineage.consignment_id"),
                        columnsIn(c, "main", tables), "open " + open);
                assertEquals(List.of("inspecto_status_commits.batch_id", "inspecto_status_lineage.batch_id"),
                        columnsIn(c, "other", tables), "open " + open + ": the other schema is not this store's");
                try (var st = c.createStatement(); var rs = st.executeQuery("SELECT payload FROM other.inspecto_status_batches")) {
                    assertTrue(rs.next());
                    assertEquals("{\"batch_id\":\"b1\"}", rs.getString(1), "open " + open + ": its payloads are not rewritten");
                }
            }
        }
    }

    /** The Consignment columns of {@code schema}'s tables named in {@code tables}, as {@code table.column}. */
    private static List<String> columnsIn(Connection c, String schema, String tables) throws Exception {
        List<String> out = new java.util.ArrayList<>();
        try (var st = c.createStatement(); var rs = st.executeQuery("SELECT table_name, column_name "
                + "FROM information_schema.columns WHERE table_schema = '" + schema + "' AND table_name IN (" + tables + ") "
                + "AND column_name IN ('batch_id', 'consignment_id') ORDER BY 1, 2")) {
            while (rs.next()) out.add(rs.getString(1) + "." + rs.getString(2));
        }
        return out;
    }

    /** The {@code table.column} pairs of the status tables whose column names the Consignment, either spelling. */
    private static List<String> consignmentColumns(Connection c) throws Exception {
        List<String> out = new java.util.ArrayList<>();
        try (var st = c.createStatement(); var rs = st.executeQuery("SELECT table_name, column_name "
                + "FROM information_schema.columns WHERE table_schema = current_schema() AND table_name LIKE 'inspecto_status_%' "
                + "AND column_name IN ('batch_id', 'consignment_id') ORDER BY 1, 2")) {
            while (rs.next()) out.add(rs.getString(1) + "." + rs.getString(2));
        }
        return out;
    }

    /** The indexes are created by a guarded DDL, so re-opening the same database must not fail. */
    @Test
    void reopeningAnExistingDatabaseIsIdempotent() {
        assertDoesNotThrow(() -> new DbStatusStore(conn), "CREATE INDEX IF NOT EXISTS re-runs cleanly");
    }

    /** A source of {@code n} batch rows and one unpack row, for the bulk-path tests below. */
    private static com.gamma.etl.StatusStore source(int n) {
        return new com.gamma.etl.StatusStore() {
            @Override public Set<String> committedBatches(PipelineConfig c) { return Set.of("b0"); }
            @Override public List<Map<String, String>> batches(PipelineConfig c) {
                return java.util.stream.IntStream.range(0, n)
                        .mapToObj(i -> Map.of("consignment_id", "b" + i, "status", "SUCCESS")).toList();
            }
            @Override public List<Map<String, String>> files(PipelineConfig c) { return List.of(); }
            @Override public List<Map<String, String>> lineage(PipelineConfig c, String batchId) {
                return List.of(Map.of("rows", "3"));   // no consignment_id: the column is NULL
            }
            @Override public List<Map<String, String>> quarantine(PipelineConfig c) { return List.of(); }
            @Override public List<Map<String, String>> unpack(PipelineConfig c) { return List.of(Map.of("member", "m")); }
        };
    }

    /**
     * OPSTORE-BULK-INSERT-1: on DuckDB the sync writes through an Appender, which must stay inside the sync's
     * transaction. A failure on the LAST table (an unpack table with a column the Appender does not fill)
     * must roll back the earlier tables' DELETE and appended rows alike: the old projection survives intact.
     */
    @Test
    void aFailedBulkSyncRollsBackEveryTable(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(
                TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write().toString());
        db.sync(source(2), List.of(cfg));
        assertEquals(2, db.batches(cfg).size());
        assertNull(db.lineage(cfg, null).get(0).get("consignment_id"), "a row with no batch id projects as NULL");

        try (var st = conn.createStatement()) {
            st.execute("DROP TABLE inspecto_status_unpack");
            st.execute("CREATE TABLE inspecto_status_unpack (pipeline VARCHAR, seq BIGINT, payload VARCHAR, extra VARCHAR NOT NULL)");
        }
        db.sync(source(5), List.of(cfg));   // fails on the unpack table: logged, rolled back

        assertEquals(2, db.batches(cfg).size(), "the earlier tables' rows were rolled back with the failure");
        assertEquals(Set.of("b0"), db.committedBatches(cfg));
    }

    /** The bulk path at a size the old per-row insert took seconds over: rows land in order, a re-sync replaces them. */
    @Test
    void aLargeSyncRoundTripsAndAReSyncReplaces(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(
                TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write().toString());
        db.sync(source(20_000), List.of(cfg));
        List<Map<String, String>> rows = db.batches(cfg);
        assertEquals(20_000, rows.size());
        assertEquals("b19999", rows.get(19_999).get("consignment_id"), "seq order is the source order");
        db.sync(source(3), List.of(cfg));
        assertEquals(3, db.batches(cfg).size(), "a re-sync replaces, never appends");
    }
}
