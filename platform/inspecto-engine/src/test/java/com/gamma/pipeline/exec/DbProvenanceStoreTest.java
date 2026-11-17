package com.gamma.pipeline.exec;

import com.gamma.util.JdbcDrivers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code GET /provenance/batches} is the Pipeline editor's run picker (PIPELINE-RUN-HISTORY-OVERLAY-1), so
 * each batch says whether it was a dry run before its counts are fetched (DRYRUN-INVISIBLE-ON-FLAT-LANE-1 b).
 */
class DbProvenanceStoreTest {

    @Test
    void batchesAreNewestFirstAndCarryTheDryRunFlag() throws Exception {
        try (DbProvenanceStore store = DbProvenanceStore.open("jdbc:duckdb:")) {
            store.record(List.of(
                    new ProvenanceRow("p", "real-1", "src", "data", 3, "2026-09-20T10:00:00Z"),
                    new ProvenanceRow("p", "real-1", "out", "data", 2, "2026-09-20T10:00:00Z")));
            store.record(List.of(
                    new ProvenanceRow("p", "dry-1", "src", "data", 4, "2026-09-21T10:00:00Z", true)));

            List<Map<String, Object>> batches = store.batches("p", 10);

            assertEquals(2, batches.size(), batches.toString());
            assertEquals("dry-1", batches.get(0).get("consignmentId"), "newest first");
            assertEquals(Boolean.TRUE, batches.get(0).get("simulated"), "a dry run's batch is marked");
            assertEquals("real-1", batches.get(1).get("consignmentId"));
            assertEquals(Boolean.FALSE, batches.get(1).get("simulated"), "a real run's batch is not");
            assertEquals(5L, ((Number) batches.get(1).get("totalRows")).longValue());
        }
    }

    /**
     * X5-a read alias: rows written before 4.0.0 under the bare reject spellings read as {@code reject:<reason>};
     * a canonical row, the trunk and a route pass through. The stored values are not rewritten.
     */
    @Test
    void legacyRejectSpellingsReadCanonicalAndNothingIsRewritten() throws Exception {
        try (DbProvenanceStore store = DbProvenanceStore.open("jdbc:duckdb:")) {
            List<ProvenanceRow> rows = new ArrayList<>();
            for (String rel : List.of("dropped", "invalid", "duplicate", "unmatched", "data", "route:hi", "reject:bad_score"))
                rows.add(new ProvenanceRow("p", "c-1", "n_" + rel, rel, 1, "2026-08-01T10:00:00Z"));
            store.record(rows);

            Map<String, String> relByNode = new java.util.TreeMap<>();
            for (Map<String, Object> r : store.query("p", "c-1")) relByNode.put((String) r.get("nodeId"), (String) r.get("rel"));
            assertEquals(Map.of("n_dropped", "reject:dropped", "n_invalid", "reject:invalid",
                    "n_duplicate", "reject:duplicate", "n_unmatched", "reject:unmatched", "n_data", "data",
                    "n_route:hi", "route:hi", "n_reject:bad_score", "reject:bad_score"), relByNode);

            List<String> stored = store.browseSource().with(conn -> {
                List<String> out = new ArrayList<>();
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT rel FROM inspecto_pipeline_provenance WHERE node_id = 'n_dropped'")) {
                    while (rs.next()) out.add(rs.getString(1));
                }
                return out;
            });
            assertEquals(List.of("dropped"), stored, "the read maps; the stored row keeps its old spelling");
        }
    }

    /**
     * D-12 (MoSCoW S4): the API field is {@code consignmentId}; {@code batchId} rides along as its deprecated
     * alias until 5.0.0, carrying the same value. And a new store names the column {@code consignment_id}.
     */
    @Test
    void aNewStoreWritesConsignmentIdAndTheApiKeepsTheDeprecatedAlias() throws Exception {
        try (DbProvenanceStore store = DbProvenanceStore.open("jdbc:duckdb:")) {
            store.record(List.of(new ProvenanceRow("p", "c-1", "src", "data", 3, "2026-09-20T10:00:00Z")));

            Map<String, Object> batch = store.batches("p", 10).getFirst();
            assertEquals("c-1", batch.get("consignmentId"));
            assertEquals("c-1", batch.get("batchId"), "the deprecated alias carries the same value");
            assertEquals(List.of("consignment_id"), consignmentColumns(store));
        }
    }

    /**
     * D-12 (MoSCoW S4): a provenance DB written by 3.x names the column {@code batch_id}. Opening it renames the
     * column, the run it recorded is still found by its id, and a second open changes nothing.
     */
    @Test
    void aPre400StoreIsRenamedOnOpenAndStillAnswersItsRuns(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("provenance-3x.duckdb");
        try (Connection c = JdbcDrivers.connect(url); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE inspecto_pipeline_provenance (pipeline_id VARCHAR, batch_id VARCHAR, "
                    + "node_id VARCHAR, rel VARCHAR, row_count BIGINT, run_ts VARCHAR)");
            st.execute("INSERT INTO inspecto_pipeline_provenance VALUES ('p', 'old-1', 'src', 'data', 7, '2026-08-01T10:00:00Z')");
        }
        for (int open = 1; open <= 2; open++) {
            try (DbProvenanceStore store = DbProvenanceStore.open(url)) {
                assertEquals(List.of("consignment_id"), consignmentColumns(store), "open " + open);
                assertEquals("old-1", store.batches("p", 10).getFirst().get("consignmentId"), "open " + open);
                List<Map<String, Object>> counts = store.query("p", "old-1");
                assertEquals(1, counts.size(), "open " + open + ": " + counts);
                assertEquals(7L, ((Number) counts.getFirst().get("rowCount")).longValue());
            }
        }
    }

    /** Review fix (Postgres schema-per-Space): another schema's 3.x table is neither seen nor touched. */
    @Test
    void anotherSchemasLegacyTableIsNeitherSeenNorTouched(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("two-schemas.duckdb");
        try (Connection c = JdbcDrivers.connect(url); Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA other");
            st.execute("CREATE TABLE other.inspecto_pipeline_provenance (pipeline_id VARCHAR, batch_id VARCHAR, "
                    + "node_id VARCHAR, rel VARCHAR, row_count BIGINT, run_ts VARCHAR)");
        }
        for (int open = 1; open <= 2; open++) {
            try (DbProvenanceStore store = DbProvenanceStore.open(url)) {
                assertEquals(List.of("consignment_id"), consignmentColumns(store), "open " + open);
            }
            try (Connection c = JdbcDrivers.connect(url); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_schema = 'other' "
                         + "AND table_name = 'inspecto_pipeline_provenance' AND column_name IN ('batch_id', 'consignment_id')")) {
                assertTrue(rs.next());
                assertEquals("batch_id", rs.getString(1), "open " + open + ": the other schema is not this store's");
                assertFalse(rs.next());
            }
        }
    }

    private static List<String> consignmentColumns(DbProvenanceStore store) throws Exception {
        return store.browseSource().with(c -> {
            List<String> out = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT column_name "
                    + "FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'inspecto_pipeline_provenance' "
                    + "AND column_name IN ('batch_id', 'consignment_id') ORDER BY 1")) {
                while (rs.next()) out.add(rs.getString(1));
            }
            return out;
        });
    }
}
