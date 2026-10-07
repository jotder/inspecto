package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class DataTransformerCustomPartitionTest {

    /**
     * Schema with explicit partitions[]: record_type (VARCHAR, from EVENT_TYPE) + date components.
     * Simulates the custom ingester path where EVENT_TYPE is a pre-computed column
     * the ingester added to the raw table.
     */
    @Test
    void materialisesWithExplicitPartitions(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTest.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Map<String, Object> schema = Map.of(
                "partitions", List.of(
                        Map.of("column", "record_type", "source", "EVENT_TYPE", "type", "VARCHAR"),
                        Map.of("column", "year",  "source", "EVENT_DATE", "type", "DATE_YEAR"),
                        Map.of("column", "month", "source", "EVENT_DATE", "type", "DATE_MONTH"),
                        Map.of("column", "day",   "source", "EVENT_DATE", "type", "DATE_DAY")),
                "raw", Map.of("fields", List.of(
                        Map.of("name", "ID",         "selector", "0", "type", "VARCHAR"),
                        Map.of("name", "EVENT_TYPE", "selector", "1", "type", "VARCHAR"),
                        Map.of("name", "EVENT_DATE", "selector", "2", "type", "DATE"))),
                "mapping", Map.of("rules", List.of(
                        Map.of("targetColumn", "ID",         "sourceExpression", "ID",         "transformType", "DIRECT"),
                        Map.of("targetColumn", "EVENT_TYPE", "sourceExpression", "EVENT_TYPE", "transformType", "DIRECT"),
                        Map.of("targetColumn", "EVENT_DATE", "sourceExpression", "EVENT_DATE", "transformType", "DIRECT"))));

        File db = DuckDbUtil.tempDbFile("test_");
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {

            // Simulate what the plugin ingester would create (pre-typed DATE column)
            st.execute("CREATE TABLE raw_CALL AS SELECT * FROM (VALUES " +
                    "('r1', 'CALL', DATE '2020-04-03', 0)," +
                    "('r2', 'CALL', DATE '2020-04-03', 0)) " +
                    "t(ID, EVENT_TYPE, EVENT_DATE, __src_id)");

            DataTransformer.materialize(conn, schema, cfg, "raw_CALL", "transformed_CALL");

            try (ResultSet rs = st.executeQuery(
                    "SELECT ID, EVENT_TYPE, record_type, year, month, day FROM transformed_CALL ORDER BY ID")) {
                assertTrue(rs.next());
                assertEquals("r1",   rs.getString("ID"));
                assertEquals("CALL", rs.getString("EVENT_TYPE"));
                assertEquals("CALL", rs.getString("record_type"));  // partition col
                assertEquals("2020", rs.getString("year"));
                assertEquals("04",   rs.getString("month"));
                assertEquals("03",   rs.getString("day"));
                assertTrue(rs.next());
                assertFalse(rs.next());
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /**
     * Simulates exactly what {@link com.gamma.inspector.ConsignmentIngestorPluginDeepTest.StubEventIngester}
     * produces: a raw table with {@code EVENT_DATE} stored as VARCHAR, but the schema
     * declaring it as DATE. Rows span TWO different dates → expects 2 distinct
     * {@code day=} partition outputs from PartitionWriter.
     *
     * <p>This test isolates the DataTransformer + PartitionWriter layer, independent
     * of ConsignmentIngestor, to determine whether multi-date partitioning works at the SQL
     * level before the full integration test is blamed.
     */
    @Test
    void multiDateVarcharSourceProducesDistinctDayPartitions(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTest.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        // Schema identical to callSchemaToon() in ConsignmentIngestorPluginDeepTest:
        // EVENT_DATE declared as DATE, but the ingester inserts it as VARCHAR.
        Map<String, Object> schema = Map.of(
                "partitions", List.of(
                        Map.of("column", "record_type", "source", "EVENT_TYPE", "type", "VARCHAR"),
                        Map.of("column", "year",  "source", "EVENT_DATE", "type", "DATE_YEAR"),
                        Map.of("column", "month", "source", "EVENT_DATE", "type", "DATE_MONTH"),
                        Map.of("column", "day",   "source", "EVENT_DATE", "type", "DATE_DAY")),
                "raw", Map.of("fields", List.of(
                        Map.of("name", "ID",         "selector", "0", "type", "VARCHAR"),
                        Map.of("name", "EVENT_TYPE", "selector", "1", "type", "VARCHAR"),
                        Map.of("name", "EVENT_DATE", "selector", "2", "type", "DATE"))),
                "mapping", Map.of("rules", List.of(
                        Map.of("targetColumn", "ID",         "sourceExpression", "ID",         "transformType", "DIRECT"),
                        Map.of("targetColumn", "EVENT_TYPE", "sourceExpression", "EVENT_TYPE", "transformType", "DIRECT"),
                        Map.of("targetColumn", "EVENT_DATE", "sourceExpression", "EVENT_DATE", "transformType", "DIRECT"))));

        Path outDir = Files.createDirectories(dir.resolve("output"));
        String dbDir = outDir.toString().replace("\\", "/");

        File db = DuckDbUtil.tempDbFile("test_multidate_");
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {

            // Exactly what StubEventIngester.populateTable() creates: VARCHAR columns,
            // with __src_id appended by the union step.
            st.execute("CREATE TABLE raw_CALL " +
                    "(ID VARCHAR, EVENT_TYPE VARCHAR, EVENT_DATE VARCHAR, __src_id INTEGER)");
            st.execute("INSERT INTO raw_CALL VALUES ('C1', 'CALL', '2020-04-03', 0)");
            st.execute("INSERT INTO raw_CALL VALUES ('C2', 'CALL', '2020-04-04', 0)");

            DataTransformer.materialize(conn, schema, cfg, "raw_CALL", "transformed_CALL");

            // ── verify day column values ──────────────────────────────────────
            try (ResultSet rs = st.executeQuery(
                    "SELECT ID, day FROM transformed_CALL ORDER BY ID")) {
                assertTrue(rs.next(), "Expected first row");
                assertEquals("C1",  rs.getString("ID"));
                assertEquals("03",  rs.getString("day"),
                        "C1 (2020-04-03) must produce day='03'");

                assertTrue(rs.next(), "Expected second row");
                assertEquals("C2",  rs.getString("ID"));
                assertEquals("04",  rs.getString("day"),
                        "C2 (2020-04-04) must produce day='04'");

                assertFalse(rs.next(), "Expected exactly 2 rows");
            }

            // ── verify PartitionWriter creates 2 separate output files ────────
            List<PartitionOutput> outputs = PartitionWriter.write(
                    conn, "transformed_CALL", dbDir, "CSV", null, "test",
                    List.of("record_type", "year", "month", "day"));

            assertEquals(2, outputs.size(),
                    "Expected 2 partition outputs (one per distinct day). Got: " + outputs);

            // Directory tree must contain both day= directories
            try (Stream<Path> s = Files.walk(outDir)) {
                List<String> dirs = s.filter(Files::isDirectory)
                        .map(p -> p.toString().replace("\\", "/"))
                        .toList();
                assertTrue(dirs.stream().anyMatch(p -> p.contains("day=03")),
                        "day=03 directory not found. Found: " + dirs);
                assertTrue(dirs.stream().anyMatch(p -> p.contains("day=04")),
                        "day=04 directory not found. Found: " + dirs);
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /**
     * {@code PARTITION-KEY-VALIDATION-GAPS-1} (b) — a partition column that collides, ignoring case, with a
     * mapped data column is REFUSED before DuckDB sees it. Measured before the fix: DuckDB renamed the
     * partition column {@code account_class_1}, {@code PARTITION_BY (account_class)} bound to the MAPPED
     * column, and the folders came out {@code ACCOUNT_CLASS=asset} — the mapped value under the mapped name —
     * with {@code account_class_1} left behind as a data column.
     */
    @Test
    void aPartitionColumnCollidingByCaseWithAMappedColumnIsRefused(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTest.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Map<String, Object> schema = Map.of(
                "partitions", List.of(Map.of("column", "account_class", "source", "ACCOUNT_CLASS", "type", "VARCHAR")),
                "raw", Map.of("fields", List.of(
                        Map.of("name", "ID",            "selector", "0", "type", "VARCHAR"),
                        Map.of("name", "ACCOUNT_CLASS", "selector", "1", "type", "VARCHAR"))),
                "mapping", Map.of("fields", List.of(
                        Map.of("name", "ID",            "from", "ID",            "fn", "keep"),
                        Map.of("name", "ACCOUNT_CLASS", "from", "ACCOUNT_CLASS", "fn", "keep"))));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> DataTransformer.selectFor(schema, cfg, "raw_input"));
        assertTrue(refused.getMessage().contains("'account_class'")
                        && refused.getMessage().contains("'ACCOUNT_CLASS'"),
                "the refusal must name both columns. Got: " + refused.getMessage());
    }

    /**
     * {@code PARTITION-KEY-VALIDATION-GAPS-1} (c) — a {@code DATE_*} partition over a value that does not
     * parse as a date yields NULL, and DuckDB writes a NULL partition value as
     * {@code __HIVE_DEFAULT_PARTITION__}. There is no {@code 1900/01/01} sentinel for unparsed values; this
     * pins what actually happens so the owning doc cannot drift from it again.
     */
    @Test
    void anUnparsableDatePartitionSourceLandsUnderTheHiveDefaultPartition(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTest.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Map<String, Object> schema = Map.of(
                "partitionKey", "CATEGORY",
                "raw", Map.of("fields", List.of(
                        Map.of("name", "ID",       "selector", "0", "type", "VARCHAR"),
                        Map.of("name", "CATEGORY", "selector", "1", "type", "VARCHAR"))),
                "mapping", Map.of("fields", List.of(
                        Map.of("name", "ID",       "from", "ID",       "fn", "keep"),
                        Map.of("name", "CATEGORY", "from", "CATEGORY", "fn", "keep"))));
        Path outDir = Files.createDirectories(dir.resolve("output"));
        File db = DuckDbUtil.tempDbFile("test_default_part_");
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE raw_input (ID VARCHAR, CATEGORY VARCHAR, __src_id INTEGER)");
            st.execute("INSERT INTO raw_input VALUES ('a', 'Hardware', 0), ('b', 'Tools', 0)");
            DataTransformer.materialize(conn, schema, cfg);
            List<PartitionOutput> outputs = PartitionWriter.write(conn, "transformed",
                    outDir.toString().replace("\\", "/"), "CSV", null, "t", List.of("year", "month", "day"));
            assertEquals(1, outputs.size(), "every row lands in ONE partition. Got: " + outputs);
            try (Stream<Path> s = Files.walk(outDir)) {
                List<String> files = s.filter(Files::isRegularFile)
                        .map(p -> outDir.relativize(p).toString().replace("\\", "/")).toList();
                assertEquals(1, files.size(), "Got: " + files);
                assertTrue(files.get(0).startsWith("year=__HIVE_DEFAULT_PARTITION__/month=__HIVE_DEFAULT_PARTITION__/"
                        + "day=__HIVE_DEFAULT_PARTITION__/"), "Got: " + files);
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** Legacy partitionKey on a VARCHAR date column — backward compat unchanged. */
    @Test
    void legacyPartitionKeyStillWorks(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTest.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        // Mini schema with partitionKey (existing format, no partitions[])
        Map<String, Object> schema = PipelineConfigBatchTest.miniSchemaMap();

        File db = DuckDbUtil.tempDbFile("test_");
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE raw_input AS SELECT * FROM (VALUES " +
                    "('a', 1.5, '2020-04-03', 0)) t(ID, AMT, EVENT_DATE, __src_id)");

            DataTransformer.materialize(conn, schema, cfg);   // backward-compat overload

            try (ResultSet rs = st.executeQuery("SELECT year, month, day FROM transformed")) {
                assertTrue(rs.next());
                assertEquals("2020", rs.getString("year"));
                assertEquals("04",   rs.getString("month"));
                assertEquals("03",   rs.getString("day"));
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
