package com.gamma.pipeline.exec;

import com.gamma.analytics.sample.Sampler;
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
 * {@code transform.sample} (ANALYTICS-CORE-1 S4, catalog {@code quality.sample.reservoir}) — {@link RowShaper} over an
 * embedded DuckDB input: the rows written are exactly the Analytics Kernel's JVM {@link Sampler#select}, per group, and
 * the same on every run and whatever the input order.
 */
class RowShaperSampleTest {

    private File db;
    private Connection conn;
    private int runs;

    @BeforeEach
    void open() throws Exception {
        db = DuckDbUtil.tempDbFile("rssample_");
        conn = DuckDbUtil.openConnection(db);
        exec("CREATE TABLE src AS SELECT 'call' || i AS call_id, CASE WHEN i % 10 = 0 THEN 'small' WHEN i % 2 = 0 THEN 'east'"
                + " ELSE 'west' END AS region, i * 3 AS amount FROM range(1, 401) r(i)");
        exec("CREATE TABLE src_shuffled AS SELECT * FROM src ORDER BY md5(call_id)");
    }

    @AfterEach
    void close() throws Exception {
        if (conn != null) conn.close();
        DuckDbUtil.deleteTempDb(db);
    }

    @Test
    void fixedSizePerGroupIsTheKernelsJvmSelection() throws Exception {
        Map<String, Object> c = cfg("size", 7, "seed", 42, "group_by", List.of("region"), "key", List.of("call_id"));
        List<String> got = ids(run("src", c));
        assertEquals(21, got.size(), "seven per region");
        assertEquals(jvm(Sampler.Spec.ofSize(42, 7), true), got);
        assertEquals(got, ids(run("src", c)), "a second run");
        assertEquals(got, ids(run("src_shuffled", c)), "another input order");
        assertNotEquals(got, ids(run("src", cfg("size", 7, "seed", 43, "group_by", List.of("region"), "key", List.of("call_id")))));
    }

    @Test
    void fractionPerGroupRoundsHalfUpAndColumnsAreUnchanged() throws Exception {
        String out = run("src", cfg("fraction", 0.05, "group_by", "region", "key", "call_id"));
        // east 160 -> 8, west 200 -> 10, small 40 -> 2
        assertEquals(20, ids(out).size());
        assertEquals(jvm(Sampler.Spec.ofFraction(0, 0.05), true), ids(out), "seed defaults to 0");
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM \"" + out + "\" LIMIT 0")) {
            assertEquals(3, rs.getMetaData().getColumnCount());
        }
    }

    @Test
    void theDefaultKeyIsEveryColumn() throws Exception {
        String a = run("src", cfg("size", 5, "seed", 9));
        String b = run("src", cfg("size", 5, "seed", 9, "key", List.of("call_id", "region", "amount")));
        assertEquals(ids(b), ids(a));
    }

    @Test
    void refusesWithTheGrammarsWords() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> run("src", cfg("size", 3, "group_by", List.of("nope"))));
        assertTrue(e.getMessage().contains("'nope' is not in the inbound data"), e.getMessage());
        exec("CREATE TABLE clash AS SELECT 1 AS __sample_k");
        e = assertThrows(IllegalArgumentException.class, () -> run("clash", cfg("size", 1)));
        assertTrue(e.getMessage().contains("reserved prefix"), e.getMessage());
        for (Map<String, Object> bad : List.of(cfg(), cfg("size", 3, "fraction", 0.5), cfg("size", 0), cfg("fraction", 0),
                cfg("fraction", 1.5), cfg("fraction", "half"), cfg("size", 3, "seed", "abc"), cfg("size", 3, "method", "bernoulli")))
            assertThrows(IllegalArgumentException.class, () -> SampleStep.parse(bad), bad.toString());
    }

    private List<String> jvm(Sampler.Spec spec, boolean byRegion) throws SQLException {
        List<List<String>> groups = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT call_id, region FROM src")) {
            while (rs.next()) {
                ids.add(rs.getString(1));
                groups.add(byRegion ? List.of(rs.getString(2)) : List.of());
                keys.add(Sampler.keyText(List.of(rs.getString(1))));
            }
        }
        return Sampler.select(spec, groups, keys).stream().map(ids::get).sorted().toList();
    }

    private List<String> ids(String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT call_id FROM \"" + table + "\" ORDER BY call_id")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private String run(String input, Map<String, Object> cfg) throws SQLException {
        return RowShaper.shape(conn, PipelineNode.of("n", "transform.sample", cfg), input, "n_" + (++runs))
                .stream().filter(r -> r.rel().equals(PipelineRel.DATA)).findFirst().orElseThrow().table();
    }

    private void exec(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
