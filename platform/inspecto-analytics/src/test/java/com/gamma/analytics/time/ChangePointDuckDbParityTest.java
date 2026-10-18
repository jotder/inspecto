package com.gamma.analytics.time;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.util.DuckDbUtil;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JVM ↔ DuckDB parity for {@link ChangePoint}, and the golden test against the origin of the rule: the
 * {@code business-assurance} template's forecast view, read as shipped, whose own flags the kernel reproduces from the
 * template's (actual − forecast) residuals.
 */
class ChangePointDuckDbParityTest {

    private static final Path VIEW = Path.of("..", "..", "spaces", "_templates", "business-assurance", "config", "views",
            "ba_revenue_forecast_view.toon").toAbsolutePath().normalize();

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

    /** A residual series with planted spikes, a level shift, a ramp and noise. */
    private static double[] series(long seed, int n) {
        Random r = new Random(seed);
        double[] e = new double[n];
        for (int i = 0; i < n; i++) e[i] = r.nextGaussian() * 0.9;
        e[n / 5] += 7;
        for (int i = n / 3; i < n / 3 + 2; i++) e[i] += 6;                 // two days: below K
        for (int i = n / 2; i < n / 2 + 25; i++) e[i] += 6;                // a level shift
        for (int i = 3 * n / 4; i < n; i++) e[i] += 0.1 * (i - 3 * n / 4); // a ramp
        return e;
    }

    @Test
    void sqlEqualsJvmPerKeyForEveryOption() throws Exception {
        int n = 160;
        double[][] ser = {series(1, n), series(2, n), series(3, n)};
        String[] keys = {"a", "b", null};
        double[] sigma = {1.0, 0.8, 1.3};
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE cp (k VARCHAR, ord INTEGER, e DOUBLE, sg DOUBLE)");
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO cp VALUES (?, ?, ?, ?)")) {
            for (int s = 0; s < ser.length; s++)
                for (int i = 0; i < n; i++) {
                    ps.setString(1, keys[s]);
                    ps.setInt(2, 1000 - i * 2);                // stored newest first: order column, not insertion order
                    ps.setDouble(3, ser[s][i]);
                    ps.setDouble(4, sigma[s]);
                    ps.addBatch();
                }
            ps.setString(1, "a");                               // a NULL residual is left out
            ps.setInt(2, 5000);
            ps.setNull(3, java.sql.Types.DOUBLE);
            ps.setDouble(4, 1.0);
            ps.addBatch();
            ps.executeBatch();
        }
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE VIEW cpv AS SELECT k, -ord AS o, e, sg FROM cp");
        }
        for (boolean rebase : new boolean[] {false, true})
            for (int warmup : new int[] {0, 12}) {
                ChangePoint.Params base = ChangePoint.Params.of(1.0).withRebase(rebase).withWarmup(warmup);
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(ChangePoint.sql("cpv", "o", "e", "sg", List.of("k"), base))) {
                    int[] seen = new int[3];
                    ChangePoint.Result[] expected = new ChangePoint.Result[3];
                    for (int s = 0; s < 3; s++) {
                        ChangePoint.Params p = new ChangePoint.Params(sigma[s], base.z(), base.regimeRun(), base.cusumK(),
                                base.cusumH(), warmup, rebase);
                        expected[s] = ChangePoint.detect(ser[s], p);
                    }
                    int total = 0, regimes = 0, drifts = 0;
                    while (rs.next()) {
                        String k = rs.getString("k");
                        int s = k == null ? 2 : k.equals("a") ? 0 : 1;
                        int i = seen[s]++;
                        String where = "key " + k + " point " + i + " rebase " + rebase + " warmup " + warmup;
                        assertEquals(expected[s].outsideBand()[i] ? 1 : 0, rs.getInt("outside_band"), where);
                        assertEquals(expected[s].regimeChange()[i] ? 1 : 0, rs.getInt("regime_change"), where);
                        assertEquals(expected[s].driftChange()[i] ? 1 : 0, rs.getInt("drift_change"), where);
                        assertEquals(expected[s].warmup()[i] ? 1 : 0, rs.getInt("warmup"), where);
                        regimes += rs.getInt("regime_change");
                        drifts += rs.getInt("drift_change");
                        total++;
                    }
                    assertEquals(3 * n, total);
                    assertTrue(regimes >= 3, "the corpus has regime changes (" + regimes + ")");
                    assertTrue(drifts + regimes > 3, "and something else to detect");
                }
            }
    }

    /** The template's corpus as shipped, then with a level shift up, a level shift down and a ramp up and down added to revenue. */
    @Test
    void theKernelReproducesTheTemplatesOwnFlagsFromItsResiduals() throws Exception {
        Matcher m = Pattern.compile("(?m)^derived_sql: \"(.*)\"\s*$").matcher(Files.readString(VIEW, StandardCharsets.UTF_8));
        assertTrue(m.find(), "derived_sql in " + VIEW);
        String shipped = m.group(1);
        String anchor = "ROUND(1000.0 + 2.0 * t";
        assertTrue(shipped.contains(anchor));
        int[] totals = new int[3];                     // band breaches, regime changes, drifts over all variants
        for (String extra : new String[] {"0.0", "CASE WHEN t >= 100 THEN 300.0 ELSE 0.0 END",
                "CASE WHEN t >= 100 THEN -150.0 ELSE 0.0 END", "CASE WHEN t >= 100 THEN 8.0 * (t - 100) ELSE 0.0 END", "CASE WHEN t >= 100 THEN -8.0 * (t - 100) ELSE 0.0 END"}) {
            int[] c = compareWithTemplate(shipped.replace(anchor, anchor + " + " + extra));
            for (int i = 0; i < 3; i++) totals[i] += c[i];
        }
        assertTrue(totals[0] >= 1 && totals[1] >= 2 && totals[2] >= 1,
                "the variants exercise all three flags: band " + totals[0] + ", regime " + totals[1] + ", drift " + totals[2]);
    }

    private static int[] compareWithTemplate(String viewSql) throws Exception {
        String corpus = viewSql.substring(0, viewSql.indexOf(") SELECT * FROM (") + 1)
                + " SELECT revenue FROM daily_revenue ORDER BY ds";
        List<Double> ys = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(corpus)) {
            while (rs.next()) ys.add(rs.getDouble(1));
        }
        double[] y = ys.stream().mapToDouble(Double::doubleValue).toArray();
        double sigma = ChangePoint.scaleOfDifferences(y, 7);

        List<double[]> rows = new ArrayList<>();      // residual, band, regime, drift
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(viewSql)) {
            while (rs.next())
                rows.add(new double[] {rs.getDouble("actual") - rs.getDouble("forecast"), rs.getInt("outside_band"),
                        rs.getInt("regime_change"), rs.getInt("drift_change")});
        }
        assertEquals(175, rows.size());               // days 8..182; the template's warm-up is days <= 28
        double[] e = rows.stream().mapToDouble(r -> r[0]).toArray();
        ChangePoint.Result r = ChangePoint.detect(e, new ChangePoint.Params(sigma, 4.0, 3, 0.25, 8.0, 21, false));
        int[] counts = new int[3];
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(rows.get(i)[1] == 1, r.outsideBand()[i], "outside_band on day " + (i + 8));
            assertEquals(rows.get(i)[2] == 1, r.regimeChange()[i], "regime_change on day " + (i + 8));
            assertEquals(rows.get(i)[3] == 1, r.driftChange()[i], "drift_change on day " + (i + 8));
            for (int f = 0; f < 3; f++) counts[f] += (int) rows.get(i)[f + 1];
        }
        return counts;
    }
}
