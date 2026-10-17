package com.gamma.pipeline.exec;

import com.gamma.analytics.Stats;
import com.gamma.analytics.distribution.Binning;
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
 * The distribution Steps (ANALYTICS-CORE-1 S3) — {@code transform.stats.outlier}, {@code transform.data.binning},
 * {@code transform.stats.correlation} — through {@link RowShaper} over an embedded DuckDB input. Every written figure
 * equals the Analytics Kernel's JVM definition; the row-level Steps keep every row; the refusals name the problem.
 */
class RowShaperDistributionTest {

    private File db;
    private Connection conn;
    private int runs;

    /** id, region, amount (DECIMAL), qty (INTEGER), note (VARCHAR); region E has one empty amount. */
    private static final String VALUES = "(1,'N',10.00,1,'a'),(2,'N',12.50,2,'b'),(3,'N',11.00,3,'c'),(4,'N',13.00,4,'d'),"
            + "(5,'N',95.00,5,'e'),(6,'S',100.00,2,'f'),(7,'S',101.00,4,'g'),(8,'S',99.00,6,'h'),(9,'S',102.00,8,'i'),"
            + "(10,'S',10.00,10,'j'),(11,'E',NULL,1,'k'),(12,'E',7.00,2,'l')";

    @BeforeEach
    void open() throws Exception {
        db = DuckDbUtil.tempDbFile("rsdist_");
        conn = DuckDbUtil.openConnection(db);
        exec("CREATE TABLE src AS SELECT id, region, CAST(amount AS DECIMAL(10,2)) AS amount, CAST(qty AS INTEGER) AS qty, "
                + "note FROM (VALUES " + VALUES + ") t(id, region, amount, qty, note)");
    }

    @AfterEach
    void close() throws Exception {
        if (conn != null) conn.close();
        DuckDbUtil.deleteTempDb(db);
    }

    // ── outlier ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void outlierIqrWritesEachGroupsTukeyFencesAndFlagsStrictlyOutside() throws Exception {
        String out = run("transform.stats.outlier", cfg("column", "amount", "group_by", List.of("region")));
        List<Object[]> got = rows("SELECT id, outlier_lower, outlier_upper, outlier_flag FROM \"" + out + "\" ORDER BY id");
        assertEquals(12, got.size(), "every row kept");
        Stats.Fences n = Stats.tukeyFences(new double[] {10, 12.5, 11, 13, 95});
        Stats.Fences s = Stats.tukeyFences(new double[] {100, 101, 99, 102, 10});
        assertEquals(n.lower(), (Double) got.get(0)[1], 1e-12);
        assertEquals(n.upper(), (Double) got.get(0)[2], 1e-12);
        assertEquals(s.upper(), (Double) got.get(5)[2], 1e-12);
        List<Object> flags = got.stream().map(r -> r[3]).toList();
        assertEquals(java.util.Arrays.asList(false, false, false, false, true, false, false, false, false, true, null, false),
                flags, "95 in N and 10 in S are outside their own group's fences; the empty amount has no flag");
    }

    @Test
    void outlierRobustZWritesTheKernelsScoreWithItsFloor() throws Exception {
        String out = run("transform.stats.outlier", cfg("column", "amount", "method", "robust_z", "group_by", "region",
                "threshold", 3.5, "unit", 0.01, "prefix", "amt_"));
        List<Object[]> got = rows("SELECT id, amt_score, amt_flag FROM \"" + out + "\" ORDER BY id");
        double[] n = {10, 12.5, 11, 13, 95};
        double m = Stats.median(n);
        double z = Stats.robustZ(95, m, Stats.scaledMad(n, m), Stats.robustZFloor(m, 0.01));
        assertEquals(z, (Double) got.get(4)[1], 1e-12);
        assertEquals(true, got.get(4)[2]);
        assertNull(got.get(10)[1], "an empty value has no score");
        assertEquals(List.of("id", "region", "amount", "qty", "note", "amt_score", "amt_flag"), columns(out));
    }

    // ── binning ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void equalWidthBinsUseTheGroupRangeOrAStatedOneAndLabelEachBin() throws Exception {
        String out = run("transform.data.binning", cfg("column", "qty", "bins", 2));
        List<Object[]> got = rows("SELECT id, bin, bin_label FROM \"" + out + "\" ORDER BY id");
        assertEquals(12, got.size());
        // range 1..10, width 4.5: [1, 5.5) and [5.5, 10]
        assertEquals(List.of(0L, "[1.0, 5.5)"), List.of(got.get(0)[1], got.get(0)[2]));
        assertEquals(List.of(1L, "[5.5, 10.0]"), List.of(got.get(9)[1], got.get(9)[2]), "the maximum is in the last bin");

        String stated = run("transform.data.binning", cfg("column", "qty", "bins", 4, "min", 0, "max", 8,
                "bin_column", "qty_bin", "label_column", "qty_range"));
        List<Object[]> s = rows("SELECT id, qty_bin, qty_range FROM \"" + stated + "\" ORDER BY id");
        assertEquals(List.of(0L, "[0.0, 2.0)"), List.of(s.get(0)[1], s.get(0)[2]));
        assertEquals(List.of(3L, "[6.0, 8.0]"), List.of(s.get(8)[1], s.get(8)[2]), "8 = the stated max: last bin");
        assertNull(s.get(9)[1], "10 is above the stated range: no bin");
        assertNull(s.get(9)[2]);
    }

    @Test
    void quantileBinsFollowTheKernelsEdgesPerGroup() throws Exception {
        String out = run("transform.data.binning", cfg("column", "amount", "method", "quantile", "bins", 2,
                "group_by", List.of("region")));
        List<Object[]> got = rows("SELECT id, bin FROM \"" + out + "\" ORDER BY id");
        double[] e = Binning.quantileEdges(new double[] {10, 12.5, 11, 13, 95}, 2, com.gamma.analytics.QuantileMode.CONTINUOUS);
        for (int i = 0; i < 5; i++) {
            double v = new double[] {10, 12.5, 11, 13, 95}[i];
            assertEquals((long) Binning.quantileIndex(v, e), got.get(i)[1], "N id " + (i + 1));
        }
        assertNull(got.get(10)[1], "an empty value gets no bin");
    }

    // ── correlation ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void correlationWritesOneRowPerGroupPerPairWithTheKernelsFigures() throws Exception {
        String out = run("transform.stats.correlation", cfg("columns", List.of("qty", "amount"), "group_by", List.of("region")));
        List<Object[]> got = rows("SELECT region, x_column, y_column, n, correlation, slope, intercept, r2 FROM \"" + out
                + "\"");
        assertEquals(3, got.size(), "E, N, S - one pair each");
        assertEquals(List.of("region", "x_column", "y_column", "n", "correlation", "slope", "intercept", "r2"), columns(out));
        Object[] n = got.stream().filter(r -> "N".equals(r[0])).findFirst().orElseThrow();
        double[] x = {1, 2, 3, 4, 5}, y = {10, 12.5, 11, 13, 95};
        Stats.Regression reg = Stats.linearRegression(x, y);
        assertEquals(List.of("qty", "amount", 5L), List.of(n[1], n[2], n[3]));
        assertEquals(Stats.correlation(x, y).orElseThrow(), (Double) n[4], 1e-12);
        assertEquals(reg.slope().orElseThrow(), (Double) n[5], 1e-12);
        assertEquals(reg.intercept().orElseThrow(), (Double) n[6], 1e-9);
        assertEquals(reg.r2().orElseThrow(), (Double) n[7], 1e-12);
        Object[] e = got.stream().filter(r -> "E".equals(r[0])).findFirst().orElseThrow();
        assertEquals(1L, e[3], "E has one complete pair");
        assertNull(e[4], "one pair: no correlation (NULL, never NaN)");
        assertNull(e[5]);
    }

    // ── refusals ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aTextColumnAMissingColumnACollisionAndABadConfigAreRefusedByName() {
        assertRefused("transform.stats.outlier", cfg("column", "note"), "must be a number column");
        assertRefused("transform.data.binning", cfg("column", "nope", "bins", 3), "'nope' is not in the inbound data");
        assertRefused("transform.data.binning", cfg("column", "qty", "bins", 3, "bin_column", "note"), "collides");
        assertRefused("transform.stats.outlier", cfg("column", "qty", "method", "zscore"), "not one of [iqr, robust_z]");
        assertRefused("transform.stats.correlation", cfg("columns", List.of("qty")), "at least two");
        assertRefused("transform.stats.correlation", cfg("columns", List.of("qty", "note")), "'note' must be a number");
        assertRefused("transform.data.binning", cfg("column", "qty", "bins", 0), "between 1 and");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    private void assertRefused(String type, Map<String, Object> cfg, String fragment) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> run(type, cfg));
        assertTrue(e.getMessage().contains(fragment) && e.getMessage().contains("node 'n'"), e.getMessage());
    }

    private String run(String type, Map<String, Object> cfg) throws SQLException {
        return RowShaper.shape(conn, PipelineNode.of("n", type, cfg), "src", "n_" + (++runs))
                .stream().filter(r -> r.rel().equals(PipelineRel.DATA)).findFirst().orElseThrow().table();
    }

    private static Map<String, Object> cfg(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private List<String> columns(String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM \"" + table + "\" LIMIT 0")) {
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) out.add(rs.getMetaData().getColumnName(i));
        }
        return out;
    }

    private List<Object[]> rows(String sql) throws SQLException {
        List<Object[]> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Object[] row = new Object[n];
                for (int i = 0; i < n; i++) row[i] = rs.getObject(i + 1);
                out.add(row);
            }
        }
        return out;
    }

    private void exec(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
