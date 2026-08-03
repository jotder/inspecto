package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code processing.csv_settings.where} — the <b>post-parse</b> row predicate applied by
 * {@link DataTransformer#materialize}.
 *
 * <p>This is deliberately NOT the same feature as the pre-parse {@code include_*}/{@code exclude_*}
 * regex lists ({@link DuckDbCsvIngester#filterWhere}), which match one raw physical column inside the
 * {@code read_csv} SELECT before any field is named or typed. A predicate over parsed values
 * ({@code AMT > 1.0}) is inexpressible as one of those regexes — which is why the two coexist rather
 * than one replacing the other.
 */
class DataTransformerRowPredicateTest {

    /**
     * Appends a {@code where:} to the pipeline written by
     * {@link PipelineConfigBatchTest#writePipeline}, whose last block is {@code csv_settings:} — so a
     * 4-space-indented line continues it. Every test below asserts {@code cfg.csv().where()} parsed,
     * so a template change that breaks this lands as a failure here rather than a silent pass.
     */
    private static PipelineConfig configWithPredicate(Path dir, String predicate) throws Exception {
        Path toon = PipelineConfigBatchTest.writePipeline(dir, "");
        Files.writeString(toon, "    where: \"" + predicate + "\"\n", StandardOpenOption.APPEND);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        assertEquals(predicate, cfg.csv().where(),
                "csv_settings.where did not parse — the appended line missed the csv_settings block");
        assertTrue(cfg.csv().hasRowPredicate());
        assertFalse(cfg.csv().hasRowFilters(),
                "the pre-parse lists must stay empty — this test is about the post-parse moment only");
        return cfg;
    }

    private static List<String> idsIn(Statement st, String table) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT ID FROM " + table + " ORDER BY ID")) {
            while (rs.next()) ids.add(rs.getString("ID"));
        }
        return ids;
    }

    /** Rows failing the predicate are dropped; rows passing it survive untouched. */
    @Test
    void predicateFiltersRowsAfterParsing(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = configWithPredicate(dir, "AMT > 1.0");

        File db = DuckDbUtil.tempDbFile("test_pred_");
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {

            st.execute("CREATE TABLE raw_input AS SELECT * FROM (VALUES " +
                    "('a', 0.5, '2020-04-03', 0)," +
                    "('b', 1.5, '2020-04-03', 0)," +
                    "('c', 2.5, '2020-04-03', 0)) t(ID, AMT, EVENT_DATE, __src_id)");

            DataTransformer.materialize(conn, PipelineConfigBatchTest.miniSchemaMap(), cfg);

            assertEquals(List.of("b", "c"), idsIn(st, "transformed"),
                    "only rows with AMT > 1.0 should survive");

            // the surviving rows must still carry their mapped + partition columns
            try (ResultSet rs = st.executeQuery(
                    "SELECT AMT, year, month, day FROM transformed ORDER BY ID")) {
                assertTrue(rs.next());
                assertEquals(1.5, rs.getDouble("AMT"), 1e-9);
                assertEquals("2020", rs.getString("year"));
                assertEquals("04", rs.getString("month"));
                assertEquals("03", rs.getString("day"));
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /**
     * The predicate is written against <b>target</b> column names, which a SELECT cannot reference
     * from its own WHERE — this is why {@code materialize} wraps the select as a derived table.
     * Flattening that wrapping would fail this test with a binder error on {@code AMOUNT}.
     */
    @Test
    void predicateMayReferenceRenamedTargetColumns(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = configWithPredicate(dir, "AMOUNT > 1.0");

        // AMT (source) is mapped to AMOUNT (target) — the name only exists post-projection.
        Map<String, Object> schema = Map.of(
                "partitionKey", "EVENT_DATE",
                "raw", Map.of("fields", List.of(
                        Map.of("name", "ID", "selector", "0", "type", "VARCHAR"),
                        Map.of("name", "AMT", "selector", "1", "type", "DOUBLE"),
                        Map.of("name", "EVENT_DATE", "selector", "2", "type", "DATE"))),
                "mapping", Map.of("rules", List.of(
                        Map.of("targetColumn", "ID", "sourceExpression", "ID", "transformType", "DIRECT"),
                        Map.of("targetColumn", "AMOUNT", "sourceExpression", "AMT", "transformType", "DIRECT"),
                        Map.of("targetColumn", "EVENT_DATE", "sourceExpression", "EVENT_DATE", "transformType", "DIRECT"))));

        File db = DuckDbUtil.tempDbFile("test_pred_alias_");
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {

            st.execute("CREATE TABLE raw_input AS SELECT * FROM (VALUES " +
                    "('a', 0.5, '2020-04-03', 0)," +
                    "('b', 2.5, '2020-04-03', 0)) t(ID, AMT, EVENT_DATE, __src_id)");

            DataTransformer.materialize(conn, schema, cfg);

            assertEquals(List.of("b"), idsIn(st, "transformed"));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /**
     * A NULL predicate result drops the row — {@code COALESCE(pred, FALSE)}, matching
     * {@code RowShaper.predicateSplit}'s keep-side semantics on the authored-graph path.
     */
    @Test
    void nullPredicateResultDropsTheRow(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = configWithPredicate(dir, "AMT > 1.0");

        File db = DuckDbUtil.tempDbFile("test_pred_null_");
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {

            st.execute("CREATE TABLE raw_input (ID VARCHAR, AMT DOUBLE, EVENT_DATE VARCHAR, __src_id INTEGER)");
            st.execute("INSERT INTO raw_input VALUES ('a', NULL, '2020-04-03', 0)");
            st.execute("INSERT INTO raw_input VALUES ('b', 2.5,  '2020-04-03', 0)");

            DataTransformer.materialize(conn, PipelineConfigBatchTest.miniSchemaMap(), cfg);

            assertEquals(List.of("b"), idsIn(st, "transformed"),
                    "NULL AMT must not survive a > predicate");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** Absent {@code where:} ⇒ byte-for-byte prior behaviour: no filtering, no wrapping. */
    @Test
    void absentPredicateKeepsEveryRow(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(
                PipelineConfigBatchTest.writePipeline(dir, "").toString());
        assertNull(cfg.csv().where());
        assertFalse(cfg.csv().hasRowPredicate());

        File db = DuckDbUtil.tempDbFile("test_pred_none_");
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {

            st.execute("CREATE TABLE raw_input AS SELECT * FROM (VALUES " +
                    "('a', 0.5, '2020-04-03', 0)," +
                    "('b', 2.5, '2020-04-03', 0)) t(ID, AMT, EVENT_DATE, __src_id)");

            DataTransformer.materialize(conn, PipelineConfigBatchTest.miniSchemaMap(), cfg);

            assertEquals(List.of("a", "b"), idsIn(st, "transformed"));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
