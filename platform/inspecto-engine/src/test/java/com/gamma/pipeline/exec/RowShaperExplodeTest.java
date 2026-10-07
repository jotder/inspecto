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

/** {@code transform.explode} (operator 2026-10-06) — {@link RowShaper} over an embedded DuckDB input. */
class RowShaperExplodeTest {

    private File db;
    private Connection conn;

    @BeforeEach
    void open() throws Exception {
        db = DuckDbUtil.tempDbFile("rsx_");
        conn = DuckDbUtil.openConnection(db);
    }

    @AfterEach
    void close() throws Exception {
        if (conn != null) conn.close();
        DuckDbUtil.deleteTempDb(db);
    }

    /** src(id, items INT[]): 1 → [10,20,30], 2 → [], 3 → NULL. */
    private void seedList() throws SQLException {
        exec("CREATE TABLE src AS SELECT * FROM (VALUES (1, [30,10,20]), (2, []::INT[]), (3, NULL::INT[])) t(id, items)");
    }

    /** src(id, charges VARCHAR) — what parser.json lands: a JSON array as text, plus an object and junk. */
    private void seedJsonText() throws SQLException {
        exec("""
                CREATE TABLE src AS SELECT * FROM (VALUES
                  (1, '[{"code":"VOICE","amount":1.5},{"code":"SMS","amount":0.2}]'),
                  (2, '[]'), (3, '{"code":"NOT_AN_ARRAY"}'), (4, 'not json'), (5, NULL)) t(id, charges)""");
    }

    private String explode(Map<String, Object> cfg) throws SQLException {
        return new RelationByRel(RowShaper.shape(conn, PipelineNode.of("x", "transform.explode", cfg), "src", "x"))
                .table(PipelineRel.DATA);
    }

    private static Map<String, Object> cfg(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void aListExplodesInArrayOrderWithA1BasedIndexAndKeepsEmptyAndNullRows() throws Exception {
        seedList();
        String out = explode(cfg("column", "items", "as", "item", "index_column", "pos"));
        assertEquals(List.of("id", "item", "pos"), columns(out), "the source column is dropped by default");
        assertEquals(List.of("1|30|1", "1|10|2", "1|20|3", "2|null|null", "3|null|null"),
                rows("SELECT id, item, pos FROM \"" + out + "\" ORDER BY id, pos NULLS LAST"),
                "array order kept (30 before 10), index 1-based, empty and null rows kept once with null");
    }

    @Test
    void onEmptyDropDropsEmptyAndNullRows() throws Exception {
        seedList();
        String out = explode(cfg("column", "items", "on_empty", "drop"));
        assertEquals(List.of("1|30", "1|10", "1|20"), rows("SELECT id, items FROM \"" + out + "\""),
                "as defaults to the source name; empty and null arrays drop");
    }

    @Test
    void keepSourceKeepsTheArrayBesideTheElement() throws Exception {
        seedList();
        String out = explode(cfg("column", "ITEMS", "as", "item", "keep_source", true, "on_empty", "drop"));
        assertEquals(List.of("id", "items", "item"), columns(out), "column matches case-insensitively");
        assertEquals(3, rows("SELECT * FROM \"" + out + "\"").size());
    }

    @Test
    void aJsonArrayHeldAsTextExplodesToJsonElementsAndANonArrayCountsAsEmpty() throws Exception {
        seedJsonText();
        String out = explode(cfg("column", "charges", "as", "charge", "index_column", "n"));
        assertEquals(List.of("1|VOICE|1", "1|SMS|2", "2|null|null", "3|null|null", "4|null|null", "5|null|null"),
                rows("SELECT id, charge->>'code', n FROM \"" + out + "\" ORDER BY id, n"),
                "elements are JSON a following sql step can pick apart; an object, junk and NULL read as empty");
    }

    // ── negative probes: each of these would otherwise SUCCEED with a valid config ──

    @Test
    void refusesAMissingOrUnknownColumn() throws Exception {
        seedList();
        assertTrue(assertThrows(IllegalArgumentException.class, () -> explode(cfg("as", "x")))
                .getMessage().contains("needs a 'column'"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> explode(cfg("column", "nope")))
                .getMessage().contains("no column 'nope'"));
    }

    @Test
    void refusesANonArrayColumnNamingItsType() throws Exception {
        seedList();
        var e = assertThrows(IllegalArgumentException.class, () -> explode(cfg("column", "id")));
        assertTrue(e.getMessage().contains("INTEGER") && e.getMessage().contains("nothing to explode"), e.getMessage());
        exec("CREATE TABLE s2 AS SELECT {'a': 1} AS st");
        var e2 = assertThrows(IllegalArgumentException.class, () -> RowShaper.shape(conn,
                PipelineNode.of("x", "transform.explode", Map.of("column", "st")), "s2", "x2"));
        assertTrue(e2.getMessage().contains("STRUCT"), "a bare struct is one value: " + e2.getMessage());
    }

    @Test
    void refusesANewNameThatIsNotAPlainIdentifierSoNothingReachesSqlUnchecked() throws Exception {
        seedList();
        for (String bad : List.of("a\"; DROP TABLE src; --", "1st", "has space", "x-y")) {
            var e = assertThrows(IllegalArgumentException.class, () -> explode(cfg("column", "items", "as", bad)));
            assertTrue(e.getMessage().contains("not a valid new column name"), bad + " → " + e.getMessage());
            assertThrows(IllegalArgumentException.class, () -> explode(cfg("column", "items", "index_column", bad)));
        }
        assertEquals(3, rows("SELECT * FROM src").size(), "the input is untouched");
    }

    @Test
    void refusesCollidingNamesAndABadOnEmpty() throws Exception {
        seedList();
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> explode(cfg("column", "items", "as", "ID"))).getMessage().contains("collide"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> explode(cfg("column", "items", "keep_source", true))).getMessage().contains("its own name"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> explode(cfg("column", "items", "as", "e", "index_column", "E"))).getMessage().contains("must differ"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> explode(cfg("column", "items", "on_empty", "skip"))).getMessage().contains("on_empty"));
    }

    // ── helpers ──

    private record RelationByRel(List<RowShaper.Relation> rels) {
        String table(String rel) {
            return rels.stream().filter(r -> r.rel().equals(rel)).map(RowShaper.Relation::table).findFirst()
                    .orElseThrow(() -> new AssertionError("no relation '" + rel + "' in " + rels));
        }
    }

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
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM \"" + table + "\" LIMIT 0")) {
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) out.add(rs.getMetaData().getColumnName(i));
        }
        return out;
    }
}
