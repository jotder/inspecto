package com.gamma.analytics.time;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.util.DuckDbUtil;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JVM ↔ DuckDB parity for the ACF: {@link SeasonalityDetector#acfSql} against {@link SeasonalityDetector#acf} per key,
 * and {@link SeasonalityDetector#decide} over the SQL rows picks the same period as {@link SeasonalityDetector#detect}.
 */
class SeasonalityDuckDbParityTest {

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

    @Test
    void acfAndDetectionMatchPerKey() throws Exception {
        Map<String, double[]> series = new HashMap<>();
        series.put("weekly", SeasonalityDetectorTest.seasonal(91, 7, 2, 11));
        series.put("monthly", SeasonalityDetectorTest.seasonal(60, 12, 1, 12));
        series.put("noise", new Random(13).doubles(70).map(v -> Math.rint(v * 1000)).toArray());
        series.put("constant", new double[40]);
        series.put("short", new double[] {1, 5, 2});
        double[] trend = new double[50];
        for (int t = 0; t < 50; t++) trend[t] = t * 1.5 + (t % 3);
        series.put("trend", trend);

        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE s (k VARCHAR, t INTEGER, v DOUBLE)");
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO s VALUES (?, ?, ?)")) {
            for (var e : series.entrySet()) {
                for (int t = 0; t < e.getValue().length; t++) {
                    ps.setString(1, e.getKey());
                    ps.setInt(2, 1000 - t * 3);   // order column not the insertion order: descending stamps reversed below
                    ps.setDouble(3, e.getValue()[t]);
                    ps.addBatch();
                }
            }
            ps.setString(1, "weekly");          // a NULL value is left out of the series
            ps.setInt(2, 5000);
            ps.setNull(3, java.sql.Types.DOUBLE);
            ps.addBatch();
            ps.executeBatch();
        }
        // order column = -t so the series runs oldest first
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE VIEW sv AS SELECT k, -t AS ord, v FROM s");
        }
        int[] candidates = {7, 12, 24};
        int[] lags = SeasonalityDetector.lagsFor(candidates);
        String sql = SeasonalityDetector.acfSql("sv", "ord", "v", List.of("k"), lags);

        Map<String, Map<Integer, OptionalDouble>> rows = new HashMap<>();
        Map<String, Integer> lengths = new HashMap<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                double acf = rs.getDouble("acf");
                OptionalDouble v = rs.wasNull() ? OptionalDouble.empty() : OptionalDouble.of(acf);
                rows.computeIfAbsent(rs.getString("k"), x -> new HashMap<>()).put(rs.getInt("lag"), v);
                lengths.put(rs.getString("k"), rs.getInt("n"));
            }
        }
        assertEquals(series.keySet(), rows.keySet());
        int checked = 0;
        for (var e : series.entrySet()) {
            double[] x = e.getValue();
            assertEquals(x.length, lengths.get(e.getKey()), e.getKey() + " n");
            for (int lag : lags) {
                OptionalDouble jvm = SeasonalityDetector.acf(x, lag), db = rows.get(e.getKey()).get(lag);
                assertEquals(jvm.isPresent(), db.isPresent(), e.getKey() + " lag " + lag + " presence");
                if (jvm.isPresent()) assertEquals(jvm.getAsDouble(), db.getAsDouble(), 1e-9, e.getKey() + " lag " + lag);
                checked++;
            }
            var fromSql = SeasonalityDetector.decide(lengths.get(e.getKey()), candidates, SeasonalityDetector.Params.DEFAULT,
                    lag -> rows.get(e.getKey()).get(lag));
            var fromJvm = SeasonalityDetector.detect(x, candidates);
            assertEquals(fromJvm.period(), fromSql.period(), e.getKey());
            List<SeasonalityDetector.Verdict> vj = new ArrayList<>(), vs = new ArrayList<>();
            fromJvm.evidence().forEach(ev -> vj.add(ev.verdict()));
            fromSql.evidence().forEach(ev -> vs.add(ev.verdict()));
            assertEquals(vj, vs, e.getKey() + " verdicts");
        }
        assertEquals(series.size() * lags.length, checked);
        assertEquals(7, SeasonalityDetector.detect(series.get("weekly"), candidates).period().getAsInt());
        assertEquals(12, SeasonalityDetector.detect(series.get("monthly"), candidates).period().getAsInt());
    }

    @Test
    void withoutKeysOneSeries() throws Exception {
        double[] x = SeasonalityDetectorTest.seasonal(30, 7, 0.5, 21);
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE one (t INTEGER, v DOUBLE)");
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO one VALUES (?, ?)")) {
            for (int t = 0; t < x.length; t++) {
                ps.setInt(1, t);
                ps.setDouble(2, x[t]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        int[] lags = {0, 1, 7, 29, 30, 31};
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(SeasonalityDetector.acfSql("one", "t", "v", List.of(), lags))) {
            for (int lag : lags) {
                assertTrue(rs.next());
                assertEquals(lag, rs.getInt("lag"));
                assertEquals(30, rs.getInt("n"));
                assertEquals(SeasonalityDetector.acf(x, lag).getAsDouble(), rs.getDouble("acf"), 1e-9, "lag " + lag);
            }
            assertFalse(rs.next());
        }
    }
}
