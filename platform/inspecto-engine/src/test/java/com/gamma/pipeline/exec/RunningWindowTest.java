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
 * {@code transform.running} — the {@link RunningWindow} grammar and its real run through {@link RowShaper} over
 * embedded DuckDB. The velocity fixture is built so that each window edge decides a value: an attempt exactly
 * 60 minutes after another (inclusive edge), one 61 minutes after (outside), and a second key interleaved in
 * time (partition isolation).
 */
class RunningWindowTest {

    private File db;
    private Connection conn;

    @BeforeEach
    void open() throws Exception {
        db = DuckDbUtil.tempDbFile("rw_");
        conn = DuckDbUtil.openConnection(db);
    }

    @AfterEach
    void close() throws Exception {
        if (conn != null) conn.close();
        DuckDbUtil.deleteTempDb(db);
    }

    /**
     * att(id, tok, ts, amt): card A at 10:00, 10:30, 11:00 (exactly 60m after the first), 12:01; card B at
     * 10:15 and 10:45, interleaved with A.
     */
    private void seed() throws SQLException {
        exec("CREATE TABLE att AS SELECT id, tok, CAST(ts AS TIMESTAMP) AS ts, CAST(amt AS DOUBLE) AS amt FROM (VALUES "
                + "(1,'A','2026-10-06 10:00:00',10.0),(2,'A','2026-10-06 10:30:00',20.0),"
                + "(3,'A','2026-10-06 11:00:00',30.0),(4,'A','2026-10-06 12:01:00',40.0),"
                + "(5,'B','2026-10-06 10:15:00',5.0),(6,'B','2026-10-06 10:45:00',7.0)) t(id,tok,ts,amt)");
    }

    private static Map<String, Object> velocity(Object window) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("partition_by", List.of("tok"));
        cfg.put("order_by", "ts");
        cfg.put("window", window);
        cfg.put("measures", List.of(
                Map.of("fn", "count", "as", "n"),
                Map.of("fn", "sum", "column", "amt", "as", "total"),
                Map.of("fn", "max", "column", "amt", "as", "biggest")));
        return cfg;
    }

    private int runs;

    private String run(Map<String, Object> cfg) throws SQLException {
        var rels = RowShaper.shape(conn, PipelineNode.of("rw", "transform.running", cfg), "att", "rw" + (++runs));
        assertEquals(1, rels.size());
        assertEquals(PipelineRel.DATA, rels.get(0).rel());
        return rels.get(0).table();
    }

    /** id → the named column, in id order. */
    private List<Object> col(String table, String column) throws SQLException {
        List<Object> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT \"" + column + "\" FROM \"" + table + "\" ORDER BY id")) {
            while (rs.next()) out.add(rs.getObject(1));
        }
        return out;
    }

    // ── the real run ────────────────────────────────────────────────────────────

    @Test
    void aDurationWindowCountsPerKeyWithAnInclusiveEdge() throws Exception {
        seed();
        String t = run(velocity("60m"));
        // A@11:00 sees 10:00 (exactly 60m back — inclusive), 10:30 and itself; A@12:01 sees only itself
        // (11:00 is 61m back). B never sees A's interleaved rows.
        assertEquals(List.of(1L, 2L, 3L, 1L, 1L, 2L), col(t, "n"));
        assertEquals(List.of(10.0, 30.0, 60.0, 40.0, 5.0, 12.0), col(t, "total"));
        assertEquals(List.of(10.0, 20.0, 30.0, 40.0, 5.0, 7.0), col(t, "biggest"));
        // every inbound row and column is kept
        assertEquals(List.of(1, 2, 3, 4, 5, 6), col(t, "id"));
        assertEquals(List.of("A", "A", "A", "A", "B", "B"), col(t, "tok"));
    }

    @Test
    void aRowWindowCountsTheCurrentRowAndThePrecedingOnes() throws Exception {
        seed();
        String t = run(velocity("2 rows"));
        assertEquals(List.of(1L, 2L, 2L, 2L, 1L, 2L), col(t, "n"));
        assertEquals(List.of(10.0, 30.0, 50.0, 70.0, 5.0, 12.0), col(t, "total"));
        // a bare integer is the same row window
        assertEquals(List.of(1L, 2L, 2L, 2L, 1L, 2L), col(run(velocity(2)), "n"));
    }

    @Test
    void noPartitionTreatsTheBatchAsOneKey() throws Exception {
        seed();
        Map<String, Object> cfg = velocity("30m");
        cfg.remove("partition_by");
        cfg.put("measures", List.of(Map.of("fn", "avg", "column", "amt", "as", "mean"),
                Map.of("fn", "min", "column", "amt", "as", "least")));
        String t = run(cfg);
        // B@10:15 now sees A@10:00 too: avg(10,5) = 7.5, min 5
        assertEquals(7.5, col(t, "mean").get(4));
        assertEquals(5.0, col(t, "least").get(4));
    }

    @Test
    void theRunRefusesAColumnTheInboundDataLacks() throws Exception {
        seed();
        Map<String, Object> cfg = velocity("60m");
        cfg.put("partition_by", List.of("card"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> run(cfg));
        assertTrue(e.getMessage().contains("rw") && e.getMessage().contains("'card'"), e.getMessage());
    }

    @Test
    void theRunRefusesAMeasureNameThatCollidesWithAnInboundColumn() throws Exception {
        seed();
        Map<String, Object> cfg = velocity("60m");
        cfg.put("measures", List.of(Map.of("fn", "sum", "column", "amt", "as", "AMT")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> run(cfg));
        assertTrue(e.getMessage().contains("collides"), e.getMessage());
    }

    @Test
    void aDurationWindowRefusesANonTimeOrderColumn() throws Exception {
        seed();
        Map<String, Object> cfg = velocity("60m");
        cfg.put("order_by", "id");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> run(cfg));
        assertTrue(e.getMessage().contains("TIMESTAMP or DATE"), e.getMessage());
        // …while a row window over the same column is fine
        cfg.put("window", "3 rows");
        assertEquals(List.of(1L, 2L, 3L, 3L, 1L, 2L), col(run(cfg), "n"));
    }

    /** ⛔ The injection probe: a would-be identifier carrying SQL is refused before any SQL is built. */
    @Test
    void anIdentifierCarryingSqlIsRefusedAndTheTableSurvives() throws Exception {
        seed();
        Map<String, Object> cfg = velocity("60m");
        cfg.put("order_by", "ts\") OVER (); DROP TABLE att; --");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> run(cfg));
        assertTrue(e.getMessage().contains("not a plain column name"), e.getMessage());
        assertEquals(6, col("att", "id").size(), "the input table is untouched");
    }

    // ── the grammar ──────────────────────────────────────────────────────────────

    @Test
    void theSqlQuotesEveryIdentifierAndPrintsOnlyParsedNumbers() {
        String sql = RunningWindow.parse(velocity("24h")).select("att");
        assertEquals("SELECT *, COUNT(*) OVER w AS \"n\", SUM(\"amt\") OVER w AS \"total\", MAX(\"amt\") OVER w "
                + "AS \"biggest\" FROM \"att\" WINDOW w AS (PARTITION BY \"tok\" ORDER BY \"ts\" "
                + "RANGE BETWEEN INTERVAL 24 HOUR PRECEDING AND CURRENT ROW)", sql);
        assertTrue(RunningWindow.parse(velocity("5 rows")).select("att")
                .endsWith("ROWS BETWEEN 4 PRECEDING AND CURRENT ROW)"));
        assertTrue(RunningWindow.parse(velocity("30s")).select("att").contains("INTERVAL 30 SECOND"));
        assertTrue(RunningWindow.parse(velocity("7d")).select("att").contains("INTERVAL 7 DAY"));
    }

    @Test
    void theGrammarRefusesEachMalformedShapeByName() {
        assertRefused(velocity("60x"), "neither a duration");
        assertRefused(velocity("0m"), "greater than zero");
        assertRefused(velocity("-5 rows"), "neither a duration");
        assertRefused(velocity("60m; DROP"), "neither a duration");
        assertRefused(velocity(null), "needs a 'window'");
        Map<String, Object> noOrder = velocity("60m");
        noOrder.remove("order_by");
        assertRefused(noOrder, "order_by");
        assertRefused(with("measures", List.of()), "at least one 'measures'");
        assertRefused(with("measures", List.of("sum(amt)")), "must be a map");
        assertRefused(with("measures", List.of(Map.of("fn", "median", "column", "amt", "as", "m"))), "not one of");
        assertRefused(with("measures", List.of(Map.of("fn", "sum", "as", "m"))), "only count may omit");
        assertRefused(with("measures", List.of(Map.of("fn", "sum", "column", "amt"))), "needs an 'as'");
        assertRefused(with("measures", List.of(Map.of("fn", "sum", "column", "amt", "as", "x", "where", "1=1"))),
                "unknown key 'where'");
        assertRefused(with("measures", List.of(Map.of("fn", "count", "as", "n"), Map.of("fn", "sum", "column", "amt",
                "as", "N"))), "same column as an earlier measure");
        assertRefused(with("measures", List.of(Map.of("fn", "sum", "column", "amt", "as", "bad name"))),
                "not a plain column name");
        assertRefused(with("partition_by", List.of("tok", "a.b")), "not a plain column name");
    }

    private static Map<String, Object> with(String key, Object value) {
        Map<String, Object> cfg = velocity("60m");
        cfg.put(key, value);
        return cfg;
    }

    private static void assertRefused(Map<String, Object> cfg, String expected) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> RunningWindow.parse(cfg));
        assertTrue(e.getMessage().startsWith("transform.running") && e.getMessage().contains(expected),
                e.getMessage());
    }

    private void exec(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
