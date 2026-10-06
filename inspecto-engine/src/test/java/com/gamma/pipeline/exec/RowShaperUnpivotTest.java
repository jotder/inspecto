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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@code transform.matrix.unpivot} (operator 2026-10-06) — {@link RowShaper} over an embedded DuckDB input. */
class RowShaperUnpivotTest {

    private File db;
    private Connection conn;

    @BeforeEach
    void open() throws Exception {
        db = DuckDbUtil.tempDbFile("rsu_");
        conn = DuckDbUtil.openConnection(db);
    }

    @AfterEach
    void close() throws Exception {
        if (conn != null) conn.close();
        DuckDbUtil.deleteTempDb(db);
    }

    /** src(id VARCHAR, h00 INT, h01 DOUBLE, h02 INT, note VARCHAR) — mixed types, one NULL cell. */
    private void seed() throws SQLException {
        exec("CREATE TABLE src AS SELECT * FROM (VALUES ('a', 5, 1.5, NULL::INT, 'x'), ('b', 7, 2.0, 9, 'y'))"
                + " t(id, h00, h01, h02, note)");
    }

    private String unpivot(Map<String, Object> cfg) throws SQLException {
        return RowShaper.shape(conn, PipelineNode.of("u", "transform.matrix.unpivot", cfg), "src", "u").stream()
                .filter(r -> r.rel().equals(PipelineRel.DATA)).findFirst().orElseThrow().table();
    }

    private static Map<String, Object> cfg(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void aListUnpivotsToVarcharByDefaultAndDropsNullCells() throws Exception {
        seed();
        String out = unpivot(cfg("columns", List.of("h00", "H01", "h02")));
        assertEquals(List.of("id", "note", "name", "value"), columns(out), "other columns pass through");
        assertEquals("VARCHAR", type(out, "value"), "the default value type is VARCHAR, whatever the input mix");
        assertEquals(List.of("a|h00|5", "a|h01|1.5", "b|h00|7", "b|h01|2.0", "b|h02|9"),
                rows("SELECT id, name, value FROM \"" + out + "\" ORDER BY id, name"),
                "a's NULL h02 yields no row; H01 matched case-insensitively and is named as declared");
    }

    @Test
    void aPatternPicksColumnsAndValueTypeAndIncludeNullsApply() throws Exception {
        seed();
        String out = unpivot(cfg("columns_pattern", "^h0[0-9]$", "name_column", "hour", "value_column", "usage",
                "value_type", "double", "include_nulls", true));
        assertEquals(List.of("id", "note", "hour", "usage"), columns(out));
        assertEquals("DOUBLE", type(out, "usage"));
        assertEquals(6, rows("SELECT * FROM \"" + out + "\"").size(), "2 rows x 3 hours, the NULL cell kept");
        assertEquals(List.of("a|h02|null"), rows("SELECT id, hour, usage FROM \"" + out + "\" WHERE usage IS NULL"));
    }

    @Test
    void aStrictCastFailsTheRunRatherThanLandingASilentNull() throws Exception {
        seed();
        assertThrows(SQLException.class, () -> unpivot(cfg("columns", List.of("h00", "note"), "value_type", "BIGINT")),
                "'x' is not a BIGINT: the run must fail, not write null");
    }

    // ── negative probes: each would otherwise SUCCEED with a valid config ──

    @Test
    void refusesNoneOrBothSelectorsAnUnknownColumnAndAPatternThatMatchesNothing() throws Exception {
        seed();
        assertTrue(msg(cfg()).contains("exactly one"));
        assertTrue(msg(cfg("columns", List.of("h00"), "columns_pattern", "^h")).contains("exactly one"));
        assertTrue(msg(cfg("columns", List.of("h99"))).contains("no column 'h99'"));
        assertTrue(msg(cfg("columns_pattern", "^zz")).contains("no inbound column matches"));
        assertTrue(msg(cfg("columns_pattern", "([")).contains("not a valid regular expression"));
    }

    @Test
    void refusesBadNewNamesCollisionsAndAValueTypeOffTheList() throws Exception {
        seed();
        for (String bad : List.of("v\" FROM x; --", "9v", "a b"))
            assertTrue(msg(cfg("columns", List.of("h00"), "value_column", bad)).contains("not a valid new column name"), bad);
        assertTrue(msg(cfg("columns", List.of("h00"), "name_column", "ID")).contains("collide"));
        assertTrue(msg(cfg("columns", List.of("h00"), "name_column", "k", "value_column", "K")).contains("must differ"));
        for (String bad : List.of("VARCHAR); DROP TABLE src; --", "BLOB", "DECIMAL(999,1)"))
            assertTrue(msg(cfg("columns", List.of("h00"), "value_type", bad)).contains("value_type"), bad);
        assertEquals(2, rows("SELECT * FROM src").size(), "the input is untouched");
    }

    private String msg(Map<String, Object> cfg) {
        return assertThrows(IllegalArgumentException.class, () -> unpivot(cfg)).getMessage();
    }

    // ── helpers ──

    private void exec(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) { st.execute(sql); }
    }

    private List<String> rows(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> cells = new ArrayList<>();
                for (int i = 1; i <= n; i++) cells.add(String.valueOf(rs.getObject(i)));
                out.add(String.join("|", cells));
            }
        }
        return out;
    }

    private List<String> columns(String table) throws SQLException {
        List<String> out = new ArrayList<>();
        for (String r : rows("SELECT column_name FROM (DESCRIBE \"" + table + "\")")) out.add(r);
        return out;
    }

    private String type(String table, String column) throws SQLException {
        return rows("SELECT column_type FROM (DESCRIBE \"" + table + "\") WHERE column_name = '" + column + "'").get(0);
    }
}
