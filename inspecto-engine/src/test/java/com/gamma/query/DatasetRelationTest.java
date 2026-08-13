package com.gamma.query;

import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.util.JdbcDrivers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link DatasetRelation} (W4): view-backed and physicalRef-backed relation resolution + rejections. */
class DatasetRelationTest {

    @Test
    void physicalRefBecomesReadParquetGlob() {
        String sql = DatasetRelation.relationSql(Map.of("physicalRef", "cdr"), Path.of("/data"), null);
        assertTrue(sql.startsWith("SELECT * FROM read_parquet('"), sql);
        assertTrue(sql.replace('\\', '/').contains("cdr/**/*.parquet"), sql);
    }

    @Test
    void viewRefReturnsDerivedSql(@TempDir Path root) throws Exception {
        ViewStore views = new ViewStore(root);
        views.write(new ViewDefinition("sales_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES (1)) AS t(n)", "2026-07-06T00:00:00Z"));
        String sql = DatasetRelation.relationSql(Map.of("view", "sales_view"), null, views);
        assertEquals("SELECT * FROM (VALUES (1)) AS t(n)", sql);
    }

    @Test
    void unknownViewRejected(@TempDir Path root) {
        assertThrows(IllegalArgumentException.class,
                () -> DatasetRelation.relationSql(Map.of("view", "nope"), null, new ViewStore(root)));
    }

    @Test
    void physicalRefWithDatabaseSubtreeReadsMappedOutputOnly(@TempDir Path root) throws Exception {
        // store-layout contract: a pipeline-shaped store (database/ subtree present) reads its mapped
        // output only, so backup/quarantine/nested trees never leak into the dataset
        Files.createDirectories(root.resolve("orders").resolve("database"));
        Files.createDirectories(root.resolve("orders").resolve("backup"));
        String sql = DatasetRelation.relationSql(Map.of("physicalRef", "orders"), root, null);
        assertTrue(sql.replace('\\', '/').contains("orders/database/**/*.parquet"),
                "reads the mapped output, not the whole store tree: " + sql);
        // an explicit deeper ref is honoured as written
        String explicit = DatasetRelation.relationSql(Map.of("physicalRef", "orders/backup"), root, null);
        assertTrue(explicit.replace('\\', '/').contains("orders/backup/**/*.parquet"), explicit);
    }

    /**
     * The bug this pins: a physicalRef read used to hand-build a bare {@code read_parquet(<glob>)} with no
     * options, so a store that gained a column mid-life failed to read as a Dataset while a {@code view}-backed
     * read of the SAME store unioned by name and succeeded. The relation SQL is executed, not just shaped —
     * the option only matters if DuckDB actually honours it.
     */
    @Test
    void physicalRefReadsAStoreWhoseSchemaDriftedAcrossPartitions(@TempDir Path root) throws Exception {
        Path store = root.resolve("cdr");
        // partition 1 predates the column; partition 2 has it — the additive drift union_by_name absorbs
        writeParquet(store.resolve("dt=2026-01-01"), "a.parquet", "SELECT 1 AS id");
        writeParquet(store.resolve("dt=2026-01-02"), "b.parquet", "SELECT 2 AS id, 'x' AS added_later");

        String sql = DatasetRelation.relationSql(Map.of("physicalRef", "cdr"), root, null);
        assertTrue(sql.contains("union_by_name=true"), "reads through SqlViews' shared options: " + sql);

        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, added_later FROM (" + sql + ") ORDER BY id")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("id"));
            assertNull(rs.getString("added_later"), "the older partition reads as NULL, not as a failure");
            assertTrue(rs.next());
            assertEquals(2, rs.getInt("id"));
            assertEquals("x", rs.getString("added_later"));
            assertFalse(rs.next());
        }
    }

    private static void writeParquet(Path dir, String file, String selectSql) throws Exception {
        Files.createDirectories(dir);
        String path = dir.resolve(file).toString().replace("\\", "/");
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             Statement st = conn.createStatement()) {
            st.execute("COPY (" + selectSql + ") TO '" + path + "' (FORMAT PARQUET)");
        }
    }

    @Test
    void unsafePhysicalRefRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DatasetRelation.relationSql(Map.of("physicalRef", "../etc"), Path.of("/data"), null));
    }

    @Test
    void missingBothRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DatasetRelation.relationSql(Map.of("name", "x"), Path.of("/data"), null));
    }

    // ── calculated columns (DAT-5) ────────────────────────────────────────────────

    @Test
    void calculatedColumnsWrapTheBaseRelation() {
        String sql = DatasetRelation.relationSql(Map.of(
                "physicalRef", "cdr",
                "calculated", List.of(Map.of("name", "amount_taxed", "expr", "round(amount * 1.2, 2)"))),
                Path.of("/data"), null);
        assertTrue(sql.startsWith("SELECT *, (round(amount * 1.2, 2)) AS \"amount_taxed\" FROM ("), sql);
        assertTrue(sql.endsWith(") AS __base"), sql);
    }

    @Test
    void calculatedColumnFailsClosed() {
        // an unsafe expression makes the whole dataset unusable (422 at the route), never silently degraded
        assertThrows(IllegalArgumentException.class, () -> DatasetRelation.relationSql(Map.of(
                "physicalRef", "cdr",
                "calculated", List.of(Map.of("name", "x", "expr", "(SELECT 1)"))),
                Path.of("/data"), null), "subquery smuggle rejected");
        assertThrows(IllegalArgumentException.class, () -> DatasetRelation.relationSql(Map.of(
                "physicalRef", "cdr",
                "calculated", List.of(Map.of("name", "bad name", "expr", "1"))),
                Path.of("/data"), null), "non-identifier column name rejected");
        assertThrows(IllegalArgumentException.class, () -> DatasetRelation.relationSql(Map.of(
                "physicalRef", "cdr",
                "calculated", List.of(Map.of("name", "x"))),
                Path.of("/data"), null), "missing expr rejected");
    }

    // ── declared temporal column (consignment addressing step 2) ──────────────────

    /** The shape Studio persists and JToon decodes: columns[n]{name,type,role} → List<Map<String,Object>>. */
    @SafeVarargs
    private static Map<String, Object> columns(Map<String, Object>... cols) {
        return Map.of("physicalRef", "cdr", "columns", List.of(cols));
    }

    private static Map<String, Object> col(String name, String role) {
        return Map.of("name", name, "type", "string", "role", role);
    }

    @Test
    void declaredTemporalColumnResolves() {
        assertEquals(Optional.of("event_time"), DatasetRelation.temporalColumn(columns(
                col("id", "dimension"), col("bytes_used", "measure"), col("event_time", "temporal"))));
        // role matching is case-insensitive: the block is hand-editable TOON, not only Studio output
        assertEquals(Optional.of("event_time"), DatasetRelation.temporalColumn(columns(
                col("event_time", "Temporal"))));
    }

    @Test
    void noTemporalColumnDegradesRatherThanBreaking() {
        // decision D3: a dataset that declares no temporal column stays usable, bounds just stay null
        assertEquals(Optional.empty(), DatasetRelation.temporalColumn(columns(
                col("id", "dimension"), col("cost_usd", "measure"))));
        assertEquals(Optional.empty(), DatasetRelation.temporalColumn(Map.of("physicalRef", "cdr")),
                "no columns block at all");
        assertEquals(Optional.empty(), DatasetRelation.temporalColumn(null));
    }

    @Test
    void twoTemporalColumnsRejected() {
        // no honest way to pick one, and taking the first would bind the catalog's bounds to declaration order
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DatasetRelation.temporalColumn(columns(
                        col("event_time", "temporal"), col("ingested_at", "temporal"))));
        assertTrue(e.getMessage().contains("event_time") && e.getMessage().contains("ingested_at"),
                "names both offenders: " + e.getMessage());
    }

    @Test
    void temporalColumnNameFailsClosed() {
        // the caller embeds this name in min()/max() SQL, so it is identifier-checked like a calculated column
        assertThrows(IllegalArgumentException.class, () -> DatasetRelation.temporalColumn(columns(
                col("event_time\") AS x, (SELECT 1", "temporal"))), "smuggled SQL rejected");
        assertThrows(IllegalArgumentException.class, () -> DatasetRelation.temporalColumn(columns(
                Map.of("type", "date", "role", "temporal"))), "role without a name rejected");
    }

    @Test
    void temporalColumnIgnoresNonObjectEntries() {
        // a scalar entry declares no role, so it cannot be the temporal one — it must not mask a real declaration
        assertEquals(Optional.of("event_time"), DatasetRelation.temporalColumn(Map.of(
                "physicalRef", "cdr",
                "columns", List.of("id", Map.of("name", "event_time", "role", "temporal")))));
    }
}
