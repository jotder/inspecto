package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DataTransformer#countCastFailures} — the audit hole a failed coercion used to fall through.
 *
 * <p>A {@code TRY_CAST}/{@code TRY_STRPTIME} that fails does <b>not</b> reject its row: the value
 * becomes NULL and the row is stored anyway. Parse rejects have {@code error_rows} and a companion
 * {@code _errors.csv}; this stage had nothing, so a whole column of mis-formatted timestamps could land
 * as NULLs with no trace. These tests pin that the loss is now counted — and, just as importantly, that
 * a clean batch and an unmeasurable one are distinguishable.
 */
class DataTransformerCastFailureTest {

    /** ID VARCHAR · AMT DOUBLE · EVENT_DATE DATE — the mini schema's own shape, mapped DIRECT. */
    private static Map<String, Object> schema() {
        return PipelineConfigBatchTest.miniSchemaMap();
    }

    private static PipelineConfig config(Path dir) throws Exception {
        return PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
    }

    /** The core claim: a non-blank value that coerces to NULL is counted, and the row still lands. */
    @Test
    void countsValuesTheCoercionNulledWhileKeepingTheRow(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        File db = DuckDbUtil.tempDbFile("cast_fail_");
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {

            // 'abc' is not a DOUBLE and '31/31/2020' is not a date — two silent losses, one per row.
            st.execute("CREATE TABLE raw_input AS SELECT * FROM (VALUES " +
                    "('a', '1.5',  '2020-04-03', 0)," +
                    "('b', 'abc',  '2020-04-03', 0)," +
                    "('c', '2.5',  '31/31/2020', 0)) t(ID, AMT, EVENT_DATE, __src_id)");

            assertEquals(2, DataTransformer.countCastFailures(conn, schema(), cfg, "raw_input"));

            // …and the rows were KEPT with NULLs — which is exactly why the count has to exist.
            DataTransformer.materialize(conn, schema(), cfg);
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM transformed")) {
                rs.next();
                assertEquals(3, rs.getLong(1), "a failed coercion must not drop its row");
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM transformed WHERE AMT IS NULL")) {
                rs.next();
                assertEquals(1, rs.getLong(1), "the bad AMT landed as NULL");
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** A clean batch reports 0 — distinguishable from "not measured" (-1), which is the whole point. */
    @Test
    void aCleanBatchCountsZeroNotUnknown(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        File db = DuckDbUtil.tempDbFile("cast_clean_");
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE raw_input AS SELECT * FROM (VALUES " +
                    "('a', '1.5', '2020-04-03', 0)) t(ID, AMT, EVENT_DATE, __src_id)");
            assertEquals(0, DataTransformer.countCastFailures(conn, schema(), cfg, "raw_input"));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** Blanks and NULLs abstain: an empty source was never a value, so nulling it is not a failure. */
    @Test
    void blanksAndNullsAreNotFailures(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        File db = DuckDbUtil.tempDbFile("cast_blank_");
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE raw_input (ID VARCHAR, AMT VARCHAR, EVENT_DATE VARCHAR, __src_id INTEGER)");
            st.execute("INSERT INTO raw_input VALUES ('a', '',    '2020-04-03', 0)");
            st.execute("INSERT INTO raw_input VALUES ('b', '   ', '2020-04-03', 0)");
            st.execute("INSERT INTO raw_input VALUES ('c', NULL,  '2020-04-03', 0)");
            assertEquals(0, DataTransformer.countCastFailures(conn, schema(), cfg, "raw_input"));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /**
     * An {@code EXPR} rule is EXCLUDED by design: its {@code sourceExpression} is author-owned SQL,
     * not a column, so "the source was non-blank" has no defined meaning and counting it would invent
     * a denominator. Here the EXPR yields NULL for every row and must still contribute nothing.
     */
    @Test
    void anAuthoredExprRuleIsNotCounted(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        Map<String, Object> schema = Map.of(
                "partitionKey", "EVENT_DATE",
                "raw", Map.of("fields", List.of(
                        Map.of("name", "ID", "selector", "0", "type", "VARCHAR"),
                        Map.of("name", "EVENT_DATE", "selector", "1", "type", "DATE"))),
                "mapping", Map.of("rules", List.of(
                        Map.of("targetColumn", "ID", "sourceExpression", "ID", "transformType", "DIRECT"),
                        Map.of("targetColumn", "JUNK", "sourceExpression", "TRY_CAST(ID AS DOUBLE)",
                                "transformType", "EXPR"),
                        Map.of("targetColumn", "EVENT_DATE", "sourceExpression", "EVENT_DATE",
                                "transformType", "DIRECT"))));

        File db = DuckDbUtil.tempDbFile("cast_expr_");
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE raw_input AS SELECT * FROM (VALUES " +
                    "('notanumber', '2020-04-03', 0)) t(ID, EVENT_DATE, __src_id)");
            assertEquals(0, DataTransformer.countCastFailures(conn, schema, cfg, "raw_input"),
                    "an EXPR's own NULL is the author's business, not a measured coercion failure");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** Never fails a batch: an unmeasurable relation reports -1 ("unknown"), not 0 ("clean"). */
    @Test
    void anUnmeasurableRelationReportsUnknownRatherThanClean(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        File db = DuckDbUtil.tempDbFile("cast_missing_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            assertEquals(-1, DataTransformer.countCastFailures(conn, schema(), cfg, "no_such_table"));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
