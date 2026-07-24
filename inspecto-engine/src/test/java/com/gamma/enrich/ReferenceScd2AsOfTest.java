package com.gamma.enrich;

import com.gamma.etl.PipelineConfig;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reference Phase-2 P2 read-side verify: an {@code scd2} reference store keeps its superseded versions,
 * so an enrichment binding may declare {@code as_of} and see the dimension <b>as it was</b> at that
 * instant — the version valid then wins, a key created later is absent, and a key deleted later is still
 * present. Without {@code as_of} the same store resolves to the current view (P1 semantics).
 *
 * <p>Two batches are staged exactly as the write path lays them down (see
 * {@code BatchIngestStrategy.stampReferenceVersions}).
 */
class ReferenceScd2AsOfTest {

    private static String keyHash(String customerId) {
        return "md5(concat_ws(chr(31), COALESCE(CAST('" + customerId + "' AS VARCHAR), '')))";
    }

    private static String rowHash(String customerId, String region) {
        return "md5(concat_ws(chr(31), COALESCE(CAST('" + customerId + "' AS VARCHAR), ''), "
                + "COALESCE(CAST('" + region + "' AS VARCHAR), '')))";
    }

    /** Append one version file to a versioned reference store, stamped with the §2.1 system columns. */
    private static void appendBatch(Connection c, java.nio.file.Path refdb, String fileStem,
                                    String batchId, String validFrom,
                                    List<String[]> rows /* {customer_id, region, __op} */) throws Exception {
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            if (i > 0) values.append(", ");
            values.append("('").append(r[0]).append("', '").append(r[1]).append("', ")
                  .append(keyHash(r[0])).append(", ").append(rowHash(r[0], r[1]))
                  .append(", TIMESTAMP '").append(validFrom).append("', '")
                  .append(r[2]).append("', '").append(batchId).append("')");
        }
        String target = refdb.resolve(fileStem + ".parquet").toString().replace("\\", "/");
        try (Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + values + ") "
                    + "t(customer_id, region, __key_hash, __row_hash, __valid_from, __op, __batch_id)) "
                    + "TO '" + target + "' (FORMAT PARQUET)");
        }
    }

    private static PipelineConfig referenceProducer(java.nio.file.Path dir, java.nio.file.Path refdb,
                                                    String load) throws Exception {
        return PipelineConfig.fromMap(Map.of(
                "name", "CUSTOMER_DIM",
                "produces", "reference",
                "reference", Map.of("load", load, "key", List.of("customer_id")),
                "dirs", Map.of("poll", dir.resolve("ref_in").toString(), "database", refdb.toString()),
                "output", Map.of("format", "PARQUET"),
                "processing", Map.of("threads", 1)));
    }

    /** C1 changes region, C3 is deleted, C4 appears — all between 10:00 and 11:00. */
    private static java.nio.file.Path seedScd2Store(java.nio.file.Path dir) throws Exception {
        java.nio.file.Path refdb = dir.resolve("refdb");
        java.nio.file.Files.createDirectories(refdb);
        File db = DuckDbUtil.tempDbFile("scd2seed_");
        try (Connection c = DuckDbUtil.openConnection(db)) {
            appendBatch(c, refdb, "b1", "b1", "2026-07-24 10:00:00", List.of(
                    new String[]{"C1", "NA", "upsert"},
                    new String[]{"C2", "EU", "upsert"},
                    new String[]{"C3", "NA", "upsert"}));
            appendBatch(c, refdb, "b2", "b2", "2026-07-24 11:00:00", List.of(
                    new String[]{"C1", "APAC", "upsert"},
                    new String[]{"C4", "SA", "upsert"},
                    new String[]{"C3", "", "delete"}));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        return refdb;
    }

    private static EnrichmentConfig enrichment(java.nio.file.Path in, java.nio.file.Path out, String asOf) {
        return new EnrichmentConfig(
                "CUSTOMER_REGIONS",
                new EnrichmentConfig.Input(in.toString().replace("\\", "/"), "PARQUET", List.of("p")),
                List.of(new EnrichmentConfig.Reference("customer_dim", null, null, "customer_dim", asOf)),
                new EnrichmentConfig.Output(out.toString().replace("\\", "/"), "PARQUET", "snappy", List.of("region")),
                "SELECT customer_id, region FROM customer_dim");
    }

    @Test
    void asOfReturnsTheVersionValidAtThatInstant(@TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path refdb = seedScd2Store(dir);
        java.nio.file.Path in = dir.resolve("in"), out = dir.resolve("out");
        seedTrivialInput(in);

        EnrichmentEngine.runResult(enrichment(in, out, "2026-07-24 10:30:00"), null,
                List.of(referenceProducer(dir, refdb, "scd2")));

        assertEquals(Map.of("C1", "NA", "C2", "EU", "C3", "NA"), readRegions(out),
                "as-of 10:30 = batch-1 state: C1 pre-change, C3 not yet deleted, C4 not yet created");
    }

    @Test
    void noAsOfOnAnScd2StoreStillResolvesTheCurrentView(@TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path refdb = seedScd2Store(dir);
        java.nio.file.Path in = dir.resolve("in"), out = dir.resolve("out");
        seedTrivialInput(in);

        EnrichmentEngine.runResult(enrichment(in, out, null), null,
                List.of(referenceProducer(dir, refdb, "scd2")));

        assertEquals(Map.of("C1", "APAC", "C2", "EU", "C4", "SA"), readRegions(out),
                "current view unchanged by scd2: latest wins, tombstoned C3 dropped");
    }

    @Test
    void asOfAgainstAnUpsertStoreIsRejected(@TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path refdb = seedScd2Store(dir);
        java.nio.file.Path in = dir.resolve("in"), out = dir.resolve("out");
        seedTrivialInput(in);

        List<PipelineConfig> upsert = List.of(referenceProducer(dir, refdb, "upsert"));
        EnrichmentConfig cfg = enrichment(in, out, "2026-07-24 10:30:00");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EnrichmentEngine.runResult(cfg, null, upsert));
        assertTrue(e.getMessage().contains("scd2"), "the error names the load mode as-of needs: " + e.getMessage());
    }

    @Test
    void asOfNeedsAByNameRefNotAPlainPath() {
        Map<String, Object> raw = Map.of(
                "name", "E",
                "input", Map.of("database", "/tmp/in", "partitions", List.of("p")),
                "output", Map.of("database", "/tmp/out", "partitions", List.of("p")),
                "references", Map.of("d", Map.of("path", "/tmp/d.parquet", "as_of", "2026-07-24")),
                "transform", "SELECT 1");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EnrichmentConfig.fromMap(raw, null));
        assertTrue(e.getMessage().contains("as_of"), e.getMessage());
    }

    @Test
    void asOfMustBeAnIsoInstant() {
        Map<String, Object> raw = Map.of(
                "name", "E",
                "input", Map.of("database", "/tmp/in", "partitions", List.of("p")),
                "output", Map.of("database", "/tmp/out", "partitions", List.of("p")),
                "references", Map.of("d", Map.of("ref", "customer_dim", "as_of", "last tuesday")),
                "transform", "SELECT 1");
        assertThrows(IllegalArgumentException.class, () -> EnrichmentConfig.fromMap(raw, null));
    }

    @Test
    void asOfAcceptsABareDateAsStartOfDay() {
        Map<String, Object> raw = Map.of(
                "name", "E",
                "input", Map.of("database", "/tmp/in", "partitions", List.of("p")),
                "output", Map.of("database", "/tmp/out", "partitions", List.of("p")),
                "references", Map.of("d", Map.of("ref", "customer_dim", "as_of", "2026-07-24")),
                "transform", "SELECT 1");
        EnrichmentConfig cfg = EnrichmentConfig.fromMap(raw, null);
        assertEquals("2026-07-24 00:00:00", cfg.references().get(0).asOf());
    }

    private static void seedTrivialInput(java.nio.file.Path root) throws Exception {
        File db = DuckDbUtil.tempDbFile("seed_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('x', 1)) t(p, n)) TO '"
                    + root.toString().replace("\\", "/")
                    + "' (FORMAT PARQUET, PARTITION_BY (p), OVERWRITE_OR_IGNORE 1)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    private static Map<String, String> readRegions(java.nio.file.Path outRoot) throws Exception {
        Map<String, String> m = new HashMap<>();
        File db = DuckDbUtil.tempDbFile("verify_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT customer_id, region FROM read_parquet('" + outRoot.toString().replace("\\", "/")
                     + "/**/*.parquet', hive_partitioning=true, hive_types_autocast=0)")) {
            while (rs.next()) m.put(rs.getString("customer_id"), rs.getString("region"));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        return m;
    }
}
