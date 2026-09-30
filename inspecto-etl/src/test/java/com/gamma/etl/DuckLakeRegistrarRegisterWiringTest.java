package com.gamma.etl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the wiring the {@code registerOnce}-level tests cannot: {@link DuckLakeRegistrar#register} (per-sink
 * key) and {@link DuckLakeRegistrar#registerInto} (pinned job-lane id), against a REAL DuckLake catalog.
 * Skips, quietly, when the {@code ducklake} extension is not already cached (LOAD only, never INSTALL), in
 * which case the per-sink key in {@code register()} and the pinned-id skip stay UNVERIFIED on that machine.
 */
class DuckLakeRegistrarRegisterWiringTest {

    @BeforeEach
    void professionalBuild() {
        EditionFeatures.overrideForTest(java.util.Set.of(EditionFeatures.SINK_DUCKLAKE));
    }

    @AfterEach
    void reset() {
        EditionFeatures.overrideForTest(null);
    }

    private static Connection attached(Path dir, String cat) throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement s = c.createStatement()) {
            s.execute("LOAD ducklake");
        } catch (SQLException e) {
            c.close();
            assumeTrue(false, "ducklake extension not cached (" + e.getMessage() + "): UNVERIFIED here -- the "
                    + "per-sink key in register() and the pinned-id skip in registerInto()");
        }
        return c;
    }

    private static void attach(Connection c, String catalog, String data) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("ATTACH 'ducklake:" + catalog + "' AS lake (DATA_PATH '" + data + "')");
        }
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); var rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String parquet(Connection c, Path file, int rows, int offset) throws Exception {
        Files.createDirectories(file.getParent());
        String f = file.toString().replace("\\", "/");
        try (Statement s = c.createStatement()) {
            s.execute("COPY (SELECT range + " + offset + " AS id FROM range(" + rows + ")) TO '" + f
                    + "' (FORMAT parquet)");
        }
        return f;
    }

    /** Two sinks, neither with its own lake, so both inherit {@code output.ducklake}. */
    private static PipelineConfig twoSinksOneLake(Path dir, String catalog, String data) throws Exception {
        Map<String, Object> lake = new LinkedHashMap<>();
        lake.put("enabled", true);
        lake.put("catalog_url", catalog);
        lake.put("data_path", data);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "WIRING_ETL");
        m.put("dirs", Map.of("poll", dir.resolve("in").toString(), "database", dir.resolve("out").toString()));
        m.put("processing", Map.of("threads", 1));
        m.put("output", Map.of("format", "parquet", "ducklake", lake));
        m.put("sinks", List.of(Map.of("database", dir.resolve("out_a").toString()),
                Map.of("database", dir.resolve("out_b").toString())));
        return PipelineConfig.fromMap(m);
    }

    @Test
    void register_keepsBothSinksRowsWhenTheyShareOneLakeAndTable_andARetryAddsNothing(@TempDir Path dir)
            throws Exception {
        String catalog = dir.resolve("cat.ducklake").toString().replace("\\", "/");
        String data = dir.resolve("data").toString().replace("\\", "/");
        try (Connection c = attached(dir, "x")) {
            String a = parquet(c, dir.resolve("out_a/orders/a.parquet"), 5, 0);
            String b = parquet(c, dir.resolve("out_b/orders/b.parquet"), 3, 100);
            PipelineConfig cfg = twoSinksOneLake(dir, catalog, data);

            DuckLakeRegistrar.register(List.of(a, b), "t", cfg, "batch-1");
            DuckLakeRegistrar.register(List.of(a, b), "t", cfg, "batch-1");

            attach(c, catalog, data);
            assertEquals(8, count(c, "SELECT count(*) FROM lake.main.t"),
                    "A's 5 rows plus B's 3, once: B must not be skipped as 'already registered' by A's key");
        }
    }

    /** The documented pinned-{@code batch_id} outcome: a re-run with the same id is skipped, stale rows stay. */
    @Test
    void registerInto_skipsARerunWithTheSamePinnedIdEvenWhenTheDataChanged(@TempDir Path dir) throws Exception {
        String catalog = dir.resolve("cat.ducklake").toString().replace("\\", "/");
        String data = dir.resolve("data").toString().replace("\\", "/");
        try (Connection c = attached(dir, "x")) {
            Path file = dir.resolve("store/a.parquet");
            String f = parquet(c, file, 5, 0);
            DuckLakeRegistrar.registerInto(List.of(f), "t", catalog, data, null, "pinned");
            parquet(c, file, 7, 100);   // the overwriting Pipeline's second run: same path, new rows
            DuckLakeRegistrar.registerInto(List.of(f), "t", catalog, data, null, "pinned");

            attach(c, catalog, data);
            assertEquals(5, count(c, "SELECT count(*) FROM lake.main.t"));
            assertEquals(0, count(c, "SELECT count(*) FROM lake.main.t WHERE id >= 100"), "stale rows kept");
        }
    }
}
