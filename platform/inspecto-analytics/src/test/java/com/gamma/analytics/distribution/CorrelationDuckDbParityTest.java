package com.gamma.analytics.distribution;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.analytics.Stats;
import com.gamma.util.DuckDbUtil;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link CorrelationSql} (DuckDB {@code corr}, {@code regr_*}) against {@link Stats#correlation} and
 * {@link Stats#linearRegression}: seeded groups of every size 1–30, constant x, constant y, both constant, NULL and NaN
 * on either side (a pair counts only when both are present), huge offsets, a NULL key, and a group with no pair.
 * Agreement within 1e-9 relative; 1e-8 for the group on a 1e9 offset, where DuckDB's streaming covariance loses digits.
 */
class CorrelationDuckDbParityTest {

    @TempDir
    static Path spill;
    private static Connection conn;
    /** {group, x, y}; x / y null = NULL. */
    private static final List<Object[]> ROWS = new ArrayList<>();

    @BeforeAll
    static void open() throws Exception {
        conn = DuckDbUtil.openInMemory(spill.resolve(DuckDbUtil.SPILL_DIR_NAME));
        Random r = new Random(4242);
        for (int n = 1; n <= 30; n++)
            for (int i = 0; i < n; i++) {
                double x = Math.rint(r.nextGaussian() * 1000) / 100;
                double y = Math.rint((2.5 * x - 3 + r.nextGaussian() * 4) * 100) / 100;
                Double xv = i % 7 == 6 ? null : (Double) x;
                Double yv = i % 5 == 4 ? (i % 2 == 0 ? (Double) Double.NaN : null) : (Double) y;
                ROWS.add(new Object[] {"n" + n, xv, yv});
            }
        for (int i = 0; i < 6; i++) {
            ROWS.add(new Object[] {"flat_x", 0.1, (double) i});
            ROWS.add(new Object[] {"flat_y", (double) i, 0.7});
            ROWS.add(new Object[] {"flat_both", 3.0, 4.0});
            ROWS.add(new Object[] {"huge", 1e9 + i, 1e9 + 2.0 * i + (i % 2) * 0.5});
            ROWS.add(new Object[] {null, (double) i, (double) (i * i)});
        }
        ROWS.add(new Object[] {"no_pair", 1.0, null});
        ROWS.add(new Object[] {"no_pair", null, 2.0});
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t(g VARCHAR, x DOUBLE, y DOUBLE)");
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?, ?, ?)")) {
            for (Object[] row : ROWS) {
                ps.setString(1, (String) row[0]);
                for (int c = 1; c <= 2; c++)
                    if (row[c] == null) ps.setNull(c + 1, java.sql.Types.DOUBLE);
                    else ps.setDouble(c + 1, (Double) row[c]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @AfterAll
    static void close() throws Exception {
        if (conn != null) conn.close();
    }

    @Test
    void everyGroupMatchesTheJvmDefinitions() throws Exception {
        int groups = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(CorrelationSql.select("t", List.of(new CorrelationSql.Pair("x", "y")), List.of("g")))) {
            String previous = "";
            while (rs.next()) {
                groups++;
                String g = rs.getString("g");
                assertEquals("x", rs.getString("x_column"));
                assertEquals("y", rs.getString("y_column"));
                List<double[]> pairs = pairsOf(g);
                double[] x = pairs.stream().mapToDouble(p -> p[0]).toArray();
                double[] y = pairs.stream().mapToDouble(p -> p[1]).toArray();
                assertEquals(x.length, rs.getLong("n"), g + " n counts the complete pairs");
                if (x.length == 0) {
                    for (String c : List.of("correlation", "slope", "intercept", "r2")) {
                        rs.getObject(c);
                        assertTrue(rs.wasNull(), g + " no pair: " + c + " NULL");
                    }
                    continue;
                }
                Stats.Regression reg = Stats.linearRegression(x, y);
                same(Stats.correlation(x, y), rs, "correlation", g);
                same(reg.slope(), rs, "slope", g);
                same(reg.intercept(), rs, "intercept", g);
                same(reg.r2(), rs, "r2", g);
                if (g != null) assertTrue(previous != null, "the NULL key sorts last");
                previous = g;
            }
        }
        assertEquals(30 + 6, groups, "one row per group: n1..n30, flat_x, flat_y, flat_both, huge, no_pair, NULL");
    }

    @Test
    void severalPairsGiveOneRowEachInTheOrderGivenAndUnGroupedIsOneGroup() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE w AS SELECT x AS a, y AS b, x * 2 + 1 AS c FROM t WHERE g = 'n30'");
        }
        List<CorrelationSql.Pair> pairs = List.of(new CorrelationSql.Pair("a", "b"), new CorrelationSql.Pair("a", "c"),
                new CorrelationSql.Pair("b", "c"));
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(CorrelationSql.select("w", pairs, List.of()))) {
            List<String> seen = new ArrayList<>();
            while (rs.next()) {
                seen.add(rs.getString("x_column") + "~" + rs.getString("y_column"));
                if ("c".equals(rs.getString("y_column")) && "a".equals(rs.getString("x_column"))) {
                    assertEquals(1.0, rs.getDouble("correlation"), 1e-12, "c = 2a + 1");
                    assertEquals(2.0, rs.getDouble("slope"), 1e-12);
                    assertEquals(1.0, rs.getDouble("intercept"), 1e-9);
                }
                assertEquals(List.of("x_column", "y_column", "n", "correlation", "slope", "intercept", "r2"),
                        columns(rs), "no group column, no scratch column");
            }
            assertEquals(List.of("a~b", "a~c", "b~c"), seen);
        }
    }

    private static void same(OptionalDouble jvm, ResultSet rs, String column, String group) throws Exception {
        double duck = rs.getDouble(column);
        if (jvm.isEmpty()) {
            assertTrue(rs.wasNull(), group + " " + column + ": no number is NULL, never NaN (got " + duck + ")");
            return;
        }
        assertFalse(rs.wasNull(), group + " " + column + " has a number");
        double v = jvm.getAsDouble();
        // DuckDB's streaming covariance loses digits on a large common offset (values near 1e9: slope off by ~1.7e-9
        // relative, measured 2026-10-17); everything else agrees within 1e-9
        double rel = "huge".equals(group) ? 1e-8 : 1e-9;
        assertEquals(v, duck, rel * Math.max(1, Math.abs(v)), group + " " + column);
    }

    private static List<double[]> pairsOf(String g) {
        List<double[]> out = new ArrayList<>();
        for (Object[] row : ROWS)
            if (java.util.Objects.equals(row[0], g) && row[1] != null && row[2] != null
                    && !Double.isNaN((Double) row[1]) && !Double.isNaN((Double) row[2]))
                out.add(new double[] {(Double) row[1], (Double) row[2]});
        return out;
    }

    private static List<String> columns(ResultSet rs) throws Exception {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) out.add(rs.getMetaData().getColumnName(i));
        return out;
    }
}
