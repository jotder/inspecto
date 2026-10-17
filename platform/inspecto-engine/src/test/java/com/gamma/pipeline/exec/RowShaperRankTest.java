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

/**
 * {@code transform.analytics.rank} (ANALYTICS-CORE-1 S4) — {@link RowShaper} over an embedded DuckDB input: the three
 * methods, top N per group, NULLs last, and the mandatory tie-break (the same ranks whatever order the rows arrive in).
 */
class RowShaperRankTest {

    private File db;
    private Connection conn;
    private int runs;

    @BeforeEach
    void open() throws Exception {
        db = DuckDbUtil.tempDbFile("rsrank_");
        conn = DuckDbUtil.openConnection(db);
        // region N: 300, 200, 200 (tie), 100, NULL; region S: 50, 50 (tie)
        exec("CREATE TABLE src AS SELECT * FROM (VALUES ('N','a',200), ('N','b',300), ('N','c',200), ('N','d',100),"
                + " ('N','e',NULL), ('S','f',50), ('S','g',50)) t(region, id, amount)");
        exec("CREATE TABLE src_rev AS SELECT * FROM src ORDER BY id DESC");
    }

    @AfterEach
    void close() throws Exception {
        if (conn != null) conn.close();
        DuckDbUtil.deleteTempDb(db);
    }

    @Test
    void rowNumberBreaksTiesByEveryRemainingColumnAndPutsNullsLast() throws Exception {
        Map<String, Object> cfg = cfg("group_by", List.of("region"), "order_by", List.of("amount desc"));
        String expected = "N|a|2, N|b|1, N|c|3, N|d|4, N|e|5, S|f|1, S|g|2";
        assertEquals(expected, read(run("src", cfg), "region, id, rank"));
        assertEquals(expected, read(run("src_rev", cfg), "region, id, rank"), "the same ranks whatever the input order");
    }

    @Test
    void rankAndDenseRankShareTiesAndTopKeepsTiedRows() throws Exception {
        assertEquals("N|a|2, N|b|1, N|c|2, N|d|4, N|e|5, S|f|1, S|g|1", read(run("src", cfg("group_by", List.of("region"),
                "order_by", List.of("amount desc"), "method", "rank")), "region, id, rank"));
        assertEquals("N|a|2, N|b|1, N|c|2, N|d|3, N|e|4, S|f|1, S|g|1", read(run("src", cfg("group_by", "region",
                "order_by", "amount desc", "method", "dense_rank")), "region, id, rank"), "a single string is a list of one");
        // top 2 by rank keeps three rows of N (the tie) and both of S
        assertEquals("N|a, N|b, N|c, S|f, S|g", read(run("src", cfg("group_by", List.of("region"),
                "order_by", List.of("amount desc"), "method", "rank", "top", 2)), "region, id"));
    }

    @Test
    void topWithoutTheRankColumnAndAnExplicitTieBreak() throws Exception {
        String out = run("src", cfg("order_by", List.of("amount"), "tie_break", List.of("id desc"), "top", 3,
                "keep_rank", false));
        assertEquals("d|100, f|50, g|50", read(out, "id, amount"), "amount asc, ties by id desc: g, f, d");
        assertEquals(List.of("region", "id", "amount"), columns(out));
        // tie_break id desc: g before f
        assertEquals("d|3, f|2, g|1", read(run("src", cfg("order_by", List.of("amount"), "tie_break", List.of("id desc"),
                "top", 3, "rank_column", "pos")), "id, pos"));
    }

    @Test
    void refusesWithTheGrammarsWords() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> run("src", cfg("order_by", List.of("nope"))));
        assertTrue(e.getMessage().contains("'nope' is not in the inbound data"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> run("src", cfg("order_by", List.of("amount"), "rank_column", "ID")));
        assertTrue(e.getMessage().contains("collides"), e.getMessage());
        for (Map<String, Object> bad : List.of(cfg(), cfg("order_by", List.of("amount sideways")),
                cfg("order_by", List.of("amount"), "method", "ntile"), cfg("order_by", List.of("amount"), "top", 0),
                cfg("order_by", List.of("amount"), "keep_rank", false), cfg("order_by", List.of("amount", "amount desc")),
                cfg("order_by", List.of("amount"), "where", "x")))
            assertThrows(IllegalArgumentException.class, () -> RankStep.parse(bad), bad.toString());
    }

    private String run(String input, Map<String, Object> cfg) throws SQLException {
        return RowShaper.shape(conn, PipelineNode.of("n", "transform.analytics.rank", cfg), input, "n_" + (++runs))
                .stream().filter(r -> r.rel().equals(PipelineRel.DATA)).findFirst().orElseThrow().table();
    }

    static Map<String, Object> cfg(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private String read(String table, String cols) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + cols + " FROM \"" + table + "\" ORDER BY " + cols)) {
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
