package com.gamma.analytics;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.util.DuckDbUtil;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JVM ↔ DuckDB parity (ANALYTICS-CORE-1 M1): the same inputs through {@link Stats} and through DuckDB's built-in
 * aggregates must agree within 1e-9 (relative to the value's magnitude when it exceeds 1). {@code quantile_disc}
 * and {@code median} on an odd count pick an observed value, so those must agree exactly.
 */
class StatsDuckDbParityTest {

    private static final double[] FRACTIONS = {0, 0.01, 0.05, 0.1, 0.25, 0.26, 1.0 / 3, 0.5, 0.75, 0.9, 0.95, 0.99, 1};

    @TempDir
    static Path spill;
    private static Connection conn;

    @BeforeAll
    static void open() throws Exception {
        conn = DuckDbUtil.openInMemory(spill.resolve(DuckDbUtil.SPILL_DIR_NAME));
    }

    @AfterAll
    static void close() throws Exception {
        if (conn != null) conn.close();
    }

    /** Hand-picked shapes plus seeded random sets of every size from 1 to 41 and one of 1000. */
    private static List<double[]> inputs() {
        List<double[]> out = new ArrayList<>();
        out.add(new double[] {2, 4, 4, 4, 5, 5, 7, 9});
        out.add(new double[] {1, 2, 3, 4});
        out.add(new double[] {42});
        out.add(new double[] {5, 5, 5, 5, 5});
        out.add(new double[] {-3.5, 0, 0, 1e-3, 7.25, -100, 1e6});
        out.add(new double[] {1, 1, 2, 2, 4, 6, 9});
        Random r = new Random(20261017);
        for (int n = 1; n <= 41; n++) out.add(r.doubles(n, -1000, 1000).map(x -> Math.rint(x * 1000) / 1000).toArray());
        out.add(r.ints(1000, 0, 50).asDoubleStream().toArray());     // many ties
        out.add(r.doubles(1000).map(x -> 1e6 + r.nextGaussian() * 250).toArray());
        return out;
    }

    @Test
    void everyStatisticMatchesDuckDb() throws Exception {
        int checked = 0;
        for (double[] v : inputs()) {
            load(v);
            String tag = "n=" + v.length;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT avg(x), var_pop(x), var_samp(x), stddev_pop(x), stddev_samp(x), "
                         + "median(x), mad(x) FROM t")) {
                assertTrue(rs.next());
                close(rs.getDouble(1), Stats.mean(v), tag + " avg");
                close(rs.getDouble(2), Stats.variancePopulation(v), tag + " var_pop");
                close(rs.getDouble(4), Stats.stddevPopulation(v), tag + " stddev_pop");
                if (v.length >= 2) {
                    close(rs.getDouble(3), Stats.varianceSample(v), tag + " var_samp");
                    close(rs.getDouble(5), Stats.stddevSample(v), tag + " stddev_samp");
                } else {
                    rs.getDouble(3);
                    assertTrue(rs.wasNull(), "DuckDB var_samp of one value is NULL");
                    assertThrows(IllegalArgumentException.class, () -> Stats.varianceSample(v));
                }
                if (v.length % 2 == 1) assertEquals(rs.getDouble(6), Stats.median(v), 0.0, tag + " median (odd: exact)");
                else close(rs.getDouble(6), Stats.median(v), tag + " median");
                close(rs.getDouble(7), Stats.mad(v), tag + " mad");
                checked += 7;
            }
            for (double q : FRACTIONS) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT quantile_cont(x, ?::DOUBLE), quantile_disc(x, ?::DOUBLE) FROM t")) {
                    ps.setDouble(1, q);
                    ps.setDouble(2, q);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next());
                        close(rs.getDouble(1), Stats.quantile(v, q, QuantileMode.CONTINUOUS), tag + " quantile_cont " + q);
                        double disc = rs.getDouble(2);
                        assertEquals(disc, Stats.quantile(v, q, QuantileMode.DISCRETE), 0.0, tag + " quantile_disc " + q);
                        assertEquals(disc, Stats.quantile(v, q, QuantileMode.NEAREST_RANK), 0.0,
                                tag + " nearest rank picks the same observed value as quantile_disc, q=" + q);
                        checked += 3;
                    }
                }
            }
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT quantile_cont(x, 0.75) - quantile_cont(x, 0.25) FROM t")) {
                assertTrue(rs.next());
                close(rs.getDouble(1), Stats.iqr(v), tag + " iqr");
                checked++;
            }
        }
        assertTrue(checked > 2000, "parity checks run: " + checked);
    }

    private static void load(double[] v) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE t(x DOUBLE)");
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?)")) {
            for (double x : v) {
                ps.setDouble(1, x);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void close(double duck, double jvm, String what) {
        double tol = 1e-9 * Math.max(1, Math.abs(duck));
        assertEquals(duck, jvm, tol, what + ": DuckDB " + duck + " vs JVM " + jvm);
    }
}
