package com.gamma.service;

import com.gamma.consignment.DbFileStageStore;
import com.gamma.pipeline.exec.DbProvenanceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@code batch_id} → {@code consignment_id} open-time migrations (D-12, MoSCoW S4) on <b>real PostgreSQL</b>,
 * for the three stores that run one: {@link DbStatusStore}, {@link DbProvenanceStore}, {@link DbFileStageStore}.
 * Per store, two schemas of one database (two Spaces) both hold 3.x tables; {@value #NODES} nodes, each with a
 * connection of its own, open the store on the first schema at the same moment. All must open, the schema must end
 * migrated with every row exactly once, the second schema must keep its {@code batch_id} tables untouched, and a
 * reopen must change nothing. This is what proves {@code SchemaMigration}'s per-schema advisory lock and its
 * schema-scoped probes on Postgres — DuckDB holds its file in one process and needs neither. The same nodes opening
 * each store on a new, EMPTY schema must all open and leave every table and index exactly once.
 *
 * <p>Schema URLs come from {@link OperationalDb#withSchema}, the {@code currentSchema} rule boot applies to a Space.
 * Enabled by {@code INSPECTO_TEST_PG_URL} / {@code -Dinspecto.test.pg.url} (user and password in the URL's query);
 * skips PER TEST. Only throwaway {@code batchid_*} schemas (a {@code pg_} prefix is reserved) are created, and dropped afterwards.
 */
class PostgresBatchIdMigrationTest {

    private static final int NODES = 4;

    private static String adminUrl;
    private final List<String> schemas = new ArrayList<>();

    @BeforeAll
    static void server() {
        adminUrl = System.getProperty("inspecto.test.pg.url");
        if (adminUrl == null || adminUrl.isBlank()) adminUrl = System.getenv("INSPECTO_TEST_PG_URL");
    }

    @BeforeEach
    void requireServer() {
        assumeTrue(adminUrl != null && !adminUrl.isBlank(),
                "needs PostgreSQL: set INSPECTO_TEST_PG_URL or -Dinspecto.test.pg.url");
    }

    @AfterEach
    void drop() throws SQLException {
        if (schemas.isEmpty()) return;
        try (Connection c = DriverManager.getConnection(adminUrl); Statement st = c.createStatement()) {
            for (String s : schemas) st.execute("DROP SCHEMA IF EXISTS " + s + " CASCADE");
        }
    }

    // ── status store ─────────────────────────────────────────────────────────────

    private static final String[] STATUS_3X = {
            "CREATE TABLE inspecto_status_commits (pipeline VARCHAR, batch_id VARCHAR)",
            "CREATE TABLE inspecto_status_batches (pipeline VARCHAR, seq BIGINT, payload VARCHAR)",
            "CREATE TABLE inspecto_status_files (pipeline VARCHAR, seq BIGINT, payload VARCHAR)",
            "CREATE TABLE inspecto_status_lineage (pipeline VARCHAR, batch_id VARCHAR, seq BIGINT, payload VARCHAR)",
            "CREATE TABLE inspecto_status_quarantine (pipeline VARCHAR, seq BIGINT, payload VARCHAR)",
            "CREATE INDEX inspecto_status_commits_by_pipeline ON inspecto_status_commits (pipeline)",
            "CREATE INDEX inspecto_status_lineage_by_pipeline_batch ON inspecto_status_lineage (pipeline, batch_id, seq)",
            "INSERT INTO inspecto_status_commits VALUES ('p', 'b1'), ('p', 'b2')",
            "INSERT INTO inspecto_status_lineage VALUES ('p', 'b1', 0, '{\"batch_id\":\"b1\",\"rows\":\"3\"}')",
            "INSERT INTO inspecto_status_batches VALUES ('p', 0, '{\"batch_id\":\"b1\",\"note\":\"says \\\"batch_id\\\": in a value\"}')",
    };

    @Test
    void statusStore_concurrentNodesMigrateTheirSchemaOnce_andAnotherSchemaKeepsBatchId() throws Exception {
        String mine = schema("status_a"), other = schema("status_b");
        exec(mine, STATUS_3X);
        exec(other, STATUS_3X);

        openConcurrently(mine, DbStatusStore::new);
        assertStatusMigrated(mine, "after " + NODES + " concurrent opens");
        assertStatus3x(other, "after " + NODES + " concurrent opens");

        try (Connection c = connect(mine)) { new DbStatusStore(c).close(); }
        assertStatusMigrated(mine, "after a reopen");
        assertStatus3x(other, "after a reopen");
    }

    private static void assertStatusMigrated(String schema, String when) throws SQLException {
        assertEquals(List.of("inspecto_status_commits.consignment_id", "inspecto_status_lineage.consignment_id"),
                statusColumns(schema), when + ": no batch_id column is left");
        assertEquals(List.of("p|b1", "p|b2"),
                rows(schema, "SELECT pipeline || '|' || consignment_id FROM inspecto_status_commits ORDER BY 1"),
                when + ": every commit row, once");
        assertEquals(List.of("b1|0|{\"consignment_id\":\"b1\",\"rows\":\"3\"}"),
                rows(schema, "SELECT consignment_id || '|' || seq || '|' || payload FROM inspecto_status_lineage"),
                when + ": the lineage row, once, its payload key rewritten");
        assertEquals(List.of("{\"consignment_id\":\"b1\",\"note\":\"says \\\"batch_id\\\": in a value\"}"),
                rows(schema, "SELECT payload FROM inspecto_status_batches"),
                when + ": the key is rewritten, a value that merely contains it is not");
        assertEquals(List.of("inspecto_status_commits_by_pipeline", "inspecto_status_lineage_by_pipeline_consignment"),
                statusIndexes(schema), when + ": the old lineage index is gone, the new one is there");
    }

    private static void assertStatus3x(String schema, String when) throws SQLException {
        assertEquals(List.of("inspecto_status_commits.batch_id", "inspecto_status_lineage.batch_id"),
                statusColumns(schema), when + ": the other schema is not this store's");
        assertEquals(List.of("{\"batch_id\":\"b1\",\"note\":\"says \\\"batch_id\\\": in a value\"}"),
                rows(schema, "SELECT payload FROM inspecto_status_batches"), when + ": its payloads are not rewritten");
        assertEquals(List.of("b1|{\"batch_id\":\"b1\",\"rows\":\"3\"}"),
                rows(schema, "SELECT batch_id || '|' || payload FROM inspecto_status_lineage"), when);
        assertEquals(List.of("inspecto_status_commits_by_pipeline", "inspecto_status_lineage_by_pipeline_batch"),
                statusIndexes(schema), when + ": its indexes are not dropped");
        assertEquals(List.of("inspecto_status_batches", "inspecto_status_commits", "inspecto_status_files",
                        "inspecto_status_lineage", "inspecto_status_quarantine"),
                rows(schema, "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema() ORDER BY 1"),
                when + ": no table is created there");
    }

    private static List<String> statusColumns(String schema) throws SQLException {
        return rows(schema, "SELECT table_name || '.' || column_name FROM information_schema.columns "
                + "WHERE table_schema = current_schema() AND table_name LIKE 'inspecto_status_%' "
                + "AND column_name IN ('batch_id', 'consignment_id') ORDER BY 1");
    }

    private static List<String> statusIndexes(String schema) throws SQLException {
        return rows(schema, "SELECT indexname FROM pg_indexes WHERE schemaname = current_schema() "
                + "AND tablename IN ('inspecto_status_commits', 'inspecto_status_lineage') ORDER BY 1");
    }

    // ── provenance store ─────────────────────────────────────────────────────────

    private static final String[] PROVENANCE_3X = {
            "CREATE TABLE inspecto_pipeline_provenance (pipeline_id VARCHAR, batch_id VARCHAR, "
                    + "node_id VARCHAR, rel VARCHAR, row_count BIGINT, run_ts VARCHAR)",
            "INSERT INTO inspecto_pipeline_provenance VALUES ('p', 'old-1', 'src', 'data', 7, '2026-08-01T10:00:00Z'), "
                    + "('p', 'old-2', 'src', 'data', 9, '2026-08-02T10:00:00Z')",
    };

    @Test
    void provenanceStore_concurrentNodesMigrateTheirSchemaOnce_andAnotherSchemaKeepsBatchId() throws Exception {
        String mine = schema("prov_a"), other = schema("prov_b");
        exec(mine, PROVENANCE_3X);
        exec(other, PROVENANCE_3X);

        openConcurrently(mine, DbProvenanceStore::new);
        assertProvenanceMigrated(mine, "after " + NODES + " concurrent opens");
        assertProvenance3x(other, "after " + NODES + " concurrent opens");

        try (Connection c = connect(mine)) { new DbProvenanceStore(c).close(); }
        assertProvenanceMigrated(mine, "after a reopen");
        assertProvenance3x(other, "after a reopen");
    }

    private static void assertProvenanceMigrated(String schema, String when) throws SQLException {
        assertEquals(List.of("pipeline_id", "consignment_id", "node_id", "rel", "row_count", "run_ts", "simulated"),
                provenanceColumns(schema), when);
        assertEquals(List.of("p|old-1|7", "p|old-2|9"),
                rows(schema, "SELECT pipeline_id || '|' || consignment_id || '|' || row_count "
                        + "FROM inspecto_pipeline_provenance ORDER BY 1"), when + ": every row, once");
    }

    private static void assertProvenance3x(String schema, String when) throws SQLException {
        assertEquals(List.of("pipeline_id", "batch_id", "node_id", "rel", "row_count", "run_ts"),
                provenanceColumns(schema), when + ": the other schema is not this store's");
        assertEquals(List.of("p|old-1|7", "p|old-2|9"),
                rows(schema, "SELECT pipeline_id || '|' || batch_id || '|' || row_count "
                        + "FROM inspecto_pipeline_provenance ORDER BY 1"), when);
    }

    private static List<String> provenanceColumns(String schema) throws SQLException {
        return rows(schema, "SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema() "
                + "AND table_name = 'inspecto_pipeline_provenance' ORDER BY ordinal_position");
    }

    // ── file-stages store ────────────────────────────────────────────────────────

    /** The pre-constraint 3.x table, holding the duplicate the old schema allowed. */
    private static final String[] FILE_STAGES_3X = {
            "CREATE TABLE file_stages (source_id VARCHAR, relative_path VARCHAR, batch_id VARCHAR, "
                    + "stage VARCHAR, recorded_at VARCHAR)",
            "INSERT INTO file_stages VALUES ('src-1','a.csv','batch-1','REGISTERED','t1'),"
                    + "('src-1','a.csv','batch-1','REGISTERED','t2'),"
                    + "('src-1','a.csv','batch-1','MANIFESTED','t3')",
    };

    @Test
    void fileStageStore_concurrentNodesRebuildTheirSchemaOnce_andAnotherSchemaKeepsBatchId() throws Exception {
        String mine = schema("stages_a"), other = schema("stages_b");
        exec(mine, FILE_STAGES_3X);
        exec(other, FILE_STAGES_3X);

        openConcurrently(mine, DbFileStageStore::new);
        assertFileStagesRebuilt(mine, "after " + NODES + " concurrent opens");
        assertFileStages3x(other, "after " + NODES + " concurrent opens");
        String rebuilt = rows(mine, "SELECT 'file_stages'::regclass::oid::text").getFirst();

        try (Connection c = connect(mine)) { new DbFileStageStore(c).close(); }
        assertFileStagesRebuilt(mine, "after a reopen");
        assertFileStages3x(other, "after a reopen");
        assertEquals(List.of(rebuilt), rows(mine, "SELECT 'file_stages'::regclass::oid::text"),
                "a reopen does not rebuild the table again");
    }

    private static void assertFileStagesRebuilt(String schema, String when) throws SQLException {
        assertEquals(List.of("file_stages"), tables(schema), when + ": the renamed legacy table is dropped");
        assertEquals(List.of("consignment_id"), fileStageColumns(schema), when);
        assertEquals(List.of("src-1|a.csv|batch-1|MANIFESTED", "src-1|a.csv|batch-1|REGISTERED"),
                rows(schema, "SELECT source_id || '|' || relative_path || '|' || consignment_id || '|' || stage "
                        + "FROM file_stages ORDER BY 1"), when + ": every transition once, the duplicate collapsed");
        assertEquals(List.of("UNIQUE"), fileStageConstraints(schema), when + ": the key is enforced");
    }

    private static void assertFileStages3x(String schema, String when) throws SQLException {
        assertEquals(List.of("file_stages"), tables(schema), when);
        assertEquals(List.of("batch_id"), fileStageColumns(schema), when + ": the other schema is not this store's");
        assertEquals(List.of("batch-1|MANIFESTED|t3", "batch-1|REGISTERED|t1", "batch-1|REGISTERED|t2"),
                rows(schema, "SELECT batch_id || '|' || stage || '|' || recorded_at FROM file_stages ORDER BY 1"),
                when + ": its rows, duplicate included, are not touched");
        assertEquals(List.of(), fileStageConstraints(schema), when + ": it is not rebuilt");
    }

    private static List<String> fileStageColumns(String schema) throws SQLException {
        return rows(schema, "SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema() "
                + "AND table_name = 'file_stages' AND column_name IN ('batch_id', 'consignment_id')");
    }

    private static List<String> fileStageConstraints(String schema) throws SQLException {
        return rows(schema, "SELECT constraint_type FROM information_schema.table_constraints "
                + "WHERE table_schema = current_schema() AND table_name = 'file_stages' AND constraint_type = 'UNIQUE'");
    }

    private static List<String> tables(String schema) throws SQLException {
        return rows(schema, "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema() ORDER BY 1");
    }

    // ── a new, empty schema ──────────────────────────────────────────────────────
    // Nothing to migrate: what races here is the schema DDL itself. Postgres's IF NOT EXISTS is not atomic across
    // sessions, so each store runs its DDL in one transaction under its per-schema advisory lock.

    @Test
    void statusStore_concurrentNodesCreateANewSchemaOnce() throws Exception {
        String s = schema("status_new");
        openConcurrently(s, DbStatusStore::new);
        assertEquals(List.of("inspecto_status_batches", "inspecto_status_commits", "inspecto_status_files",
                "inspecto_status_lineage", "inspecto_status_quarantine", "inspecto_status_unpack"), tables(s));
        assertEquals(List.of("inspecto_status_batches_by_pipeline", "inspecto_status_commits_by_pipeline",
                        "inspecto_status_files_by_pipeline", "inspecto_status_lineage_by_pipeline_consignment",
                        "inspecto_status_quarantine_by_pipeline", "inspecto_status_unpack_by_pipeline"),
                rows(s, "SELECT indexname FROM pg_indexes WHERE schemaname = current_schema() ORDER BY 1"));
        assertEquals(List.of("inspecto_status_commits.consignment_id", "inspecto_status_lineage.consignment_id"),
                statusColumns(s));
    }

    @Test
    void provenanceStore_concurrentNodesCreateANewSchemaOnce() throws Exception {
        String s = schema("prov_new");
        openConcurrently(s, DbProvenanceStore::new);
        assertEquals(List.of("inspecto_pipeline_provenance"), tables(s));
        assertEquals(List.of("pipeline_id", "consignment_id", "node_id", "rel", "row_count", "run_ts", "simulated"),
                provenanceColumns(s));
    }

    @Test
    void fileStageStore_concurrentNodesCreateANewSchemaOnce() throws Exception {
        String s = schema("stages_new");
        openConcurrently(s, DbFileStageStore::new);
        assertEquals(List.of("file_stages"), tables(s));
        assertEquals(List.of("consignment_id"), fileStageColumns(s));
        assertEquals(List.of("UNIQUE"), fileStageConstraints(s));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Opens a store on a connection it then owns. */
    @FunctionalInterface
    private interface Opener {
        AutoCloseable open(Connection c) throws Exception;
    }

    /**
     * {@value #NODES} nodes open a store on {@code schema} at once: each holds its own connection, all are released
     * by one barrier, and every open must succeed (the failures are listed, not just the first).
     */
    private static void openConcurrently(String schema, Opener opener) throws Exception {
        CyclicBarrier go = new CyclicBarrier(NODES);
        ExecutorService pool = Executors.newFixedThreadPool(NODES);
        try {
            List<Future<?>> nodes = new ArrayList<>();
            for (int i = 0; i < NODES; i++)
                nodes.add(pool.submit((Callable<Void>) () -> {
                    try (Connection c = connect(schema)) {   // the store closes it too; a second close is a no-op
                        go.await(30, TimeUnit.SECONDS);
                        opener.open(c).close();
                    }
                    return null;
                }));
            List<String> failures = new ArrayList<>();
            for (Future<?> node : nodes) {
                try {
                    node.get(60, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    failures.add(causes(e.getCause()));
                }
            }
            assertEquals(List.of(), failures, "every one of " + NODES + " concurrent nodes opens the schema");
        } finally {
            pool.shutdownNow();
        }
    }

    private static String causes(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) sb.append(sb.isEmpty() ? "" : " <- ").append(c);
        return sb.toString();
    }

    /** A throwaway schema, created now and dropped after the test. */
    private String schema(String role) throws SQLException {
        String s = "batchid_" + Long.toHexString(System.nanoTime()) + "_" + role;
        schemas.add(s);
        try (Connection c = DriverManager.getConnection(adminUrl); Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA " + s);
        }
        return s;
    }

    private static Connection connect(String schema) throws SQLException {
        return DriverManager.getConnection(OperationalDb.withSchema(adminUrl, schema));
    }

    private static void exec(String schema, String... sql) throws SQLException {
        try (Connection c = connect(schema); Statement st = c.createStatement()) {
            for (String s : sql) st.execute(s);
        }
    }

    private static List<String> rows(String schema, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = connect(schema); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }
}
