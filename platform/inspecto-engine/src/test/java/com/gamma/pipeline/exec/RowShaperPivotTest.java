package com.gamma.pipeline.exec;

import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineRel;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.gamma.pipeline.exec.RowShaperRankTest.cfg;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code transform.matrix.pivot} (ANALYTICS-CORE-1 S4) — {@link RowShaper} over an embedded DuckDB input: one row per
 * key, one column per declared value, the Measure grammar's aggregate; unlisted and NULL values add to no column; a
 * dynamic or unbounded pivot is refused.
 */
class RowShaperPivotTest {

    private File db;
    private Connection conn;
    private int runs;

    @BeforeEach
    void open() throws Exception {
        db = DuckDbUtil.tempDbFile("rspivot_");
        conn = DuckDbUtil.openConnection(db);
        exec("CREATE TABLE src AS SELECT * FROM (VALUES ('c1','voice',10.0), ('c1','voice',5.0), ('c1','sms',1.0),"
                + " ('c2','data',7.5), ('c2','fax',99.0), ('c2',NULL,3.0), ('c3','fax',1.0)) t(customer, call_type, amount)");
    }

    @AfterEach
    void close() throws Exception {
        if (conn != null) conn.close();
        DuckDbUtil.deleteTempDb(db);
    }

    @Test
    void sumPerDeclaredValueAndAKeyWithNoListedValueStillGetsARow() throws Exception {
        String out = run(cfg("group_by", List.of("customer"), "pivot_column", "call_type",
                "values", List.of("voice", "sms", "data"), "measure", "sum(amount)"));
        assertEquals(List.of("customer", "voice", "sms", "data"), columns(out));
        assertEquals("c1|15.0|1.0|null, c2|null|null|7.5, c3|null|null|null", read(out, "customer, voice, sms, data"));
    }

    @Test
    void countGivesZeroAndAPrefixMakesNumericValuesNames() throws Exception {
        exec("CREATE TABLE years AS SELECT * FROM (VALUES ('a',2024), ('a',2025), ('a',2025), ('b',2024)) t(k, yr)");
        String out = RowShaper.shape(conn, PipelineNode.of("n", "transform.matrix.pivot", cfg("group_by", "k",
                "pivot_column", "yr", "values", List.of(2024, 2025), "measure", "count", "prefix", "y")), "years", "p_" + (++runs))
                .get(0).table();
        assertEquals("a|1|2, b|1|0", read(out, "k, y2024, y2025"));
    }

    @Test
    void noGroupGivesOneRowAndEveryGrammarAggregateWorks() throws Exception {
        for (String m : List.of("avg(amount)", "min(amount)", "max(amount)", "countDistinct(customer)", "median(amount)",
                "stddev(amount)", "p90(amount)")) {
            String out = run(cfg("pivot_column", "call_type", "values", List.of("voice", "fax"), "measure", m));
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM \"" + out + "\"")) {
                rs.next();
                assertEquals(1, rs.getInt(1), m);
            }
        }
        assertEquals("7.5|50.0", read(run(cfg("pivot_column", "call_type", "values", List.of("voice", "fax"),
                "measure", "avg(amount)")), "voice, fax"));
    }

    @Test
    void refusesDynamicUnboundedAndBadPivots() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PivotStep.parse(cfg("pivot_column", "call_type", "measure", "count")));
        assertTrue(e.getMessage().contains("explicit 'values'"), e.getMessage());
        List<Object> tooMany = new ArrayList<>();
        for (int i = 0; i <= PivotStep.MAX_VALUES; i++) tooMany.add("v" + i);
        e = assertThrows(IllegalArgumentException.class,
                () -> PivotStep.parse(cfg("pivot_column", "call_type", "values", tooMany, "measure", "count")));
        assertTrue(e.getMessage().contains("at most 200"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> run(cfg("pivot_column", "call_type",
                "values", List.of("voice"), "measure", "sum(customer)")));
        assertTrue(e.getMessage().contains("needs a number"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> run(cfg("pivot_column", "nope",
                "values", List.of("voice"), "measure", "count")));
        assertTrue(e.getMessage().contains("'nope' is not in the inbound data"), e.getMessage());
        for (Map<String, Object> bad : List.of(
                cfg("pivot_column", "call_type", "values", List.of("voice"), "measure", "mode(amount)"),
                cfg("pivot_column", "call_type", "values", List.of("voice call"), "measure", "count"),
                cfg("pivot_column", "call_type", "values", List.of("2024"), "measure", "count"),
                cfg("pivot_column", "call_type", "values", List.of("voice", "VOICE"), "measure", "count"),
                cfg("pivot_column", "call_type", "values", List.of("customer"), "measure", "count", "group_by", "customer"),
                cfg("pivot_column", "call_type", "values", List.of("voice"), "measure", "count", "group_by", "call_type"),
                cfg("pivot_column", "call_type", "values", List.of("voice")),
                cfg("pivot_column", "call_type", "values", List.of("voice"), "measure", "count", "dynamic", true)))
            assertThrows(IllegalArgumentException.class, () -> PivotStep.parse(bad), bad.toString());
    }

    private String run(Map<String, Object> cfg) throws SQLException {
        return RowShaper.shape(conn, PipelineNode.of("n", "transform.matrix.pivot", cfg), "src", "n_" + (++runs))
                .stream().filter(r -> r.rel().equals(PipelineRel.DATA)).findFirst().orElseThrow().table();
    }

    private String read(String table, String cols) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + cols + " FROM \"" + table + "\" ORDER BY ALL")) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= n; i++) row.add(String.valueOf(rs.getObject(i)));
                out.add(String.join("|", row));
            }
        }
        return String.join(", ", out);
    }

    private List<String> columns(String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM \"" + table + "\" LIMIT 0")) {
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) out.add(rs.getMetaData().getColumnName(i));
        }
        return out;
    }

    private void exec(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
