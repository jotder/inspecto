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
 * {@code transform.timeseries.changepoint}, {@code transform.cohort.retention} and {@code transform.cohort.funnel}
 * (ANALYTICS-STEPS-TS-1) — {@link RowShaper} over an embedded DuckDB input, each against figures worked by hand from the
 * kernel's definition, plus the refusals (bad config and bad data) with the words the author sees.
 */
class RowShaperChangeCohortTest {

    private File db;
    private Connection conn;

    @BeforeEach
    void open() throws Exception {
        db = DuckDbUtil.tempDbFile("rscc_");
        conn = DuckDbUtil.openConnection(db);
        exec("SET TimeZone = 'Pacific/Chatham'");
    }

    @AfterEach
    void close() throws Exception {
        if (conn != null) conn.close();
        DuckDbUtil.deleteTempDb(db);
    }

    // ── change point ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * σ 1, the template's constants (z 4, K 3): a lone 10 is a band breach; three 9s in a row are ONE regime change on
     * the third (not three breaches); 0s never feed the CUSUM (0 − k clamps at 0). An empty residual keeps its row with
     * no flags; the other columns ride along.
     */
    @Test
    void aSpikeIsABreachAndAThreePointRunIsOneRegimeChange() throws Exception {
        exec("CREATE TABLE res AS SELECT * FROM (VALUES (1, 0.0, 'a'), (2, 0.0, 'b'), (3, 10.0, 'c'), (4, 0.0, 'd'),"
                + " (5, 0.0, 'e'), (6, 0.0, 'f'), (7, 9.0, 'g'), (8, 9.0, 'h'), (9, 9.0, 'i'), (10, 0.0, 'j'),"
                + " (11, NULL, 'k')) t(t, residual, note)");
        String out = run("transform.timeseries.changepoint", "res", cfg("order_column", "t", "residual_column", "residual",
                "sigma", 1));
        assertEquals(List.of("1|a|0|0|0|0", "2|b|0|0|0|0", "3|c|1|0|0|0", "4|d|0|0|0|0", "5|e|0|0|0|0", "6|f|0|0|0|0",
                        "7|g|0|0|0|0", "8|h|0|0|0|0", "9|i|0|1|0|0", "10|j|0|0|0|0", "11|k|null|null|null|null"),
                rows("SELECT t, note, outside_band, regime_change, drift_change, warmup FROM " + q(out) + " ORDER BY t"));
    }

    /**
     * Per-key σ from a column: residual 5 is outside for σ 1 (5 &gt; 4) and inside for σ 2 (5 &lt; 8). A steady residual
     * of 1 at σ 1 adds 1 − 0.25 = 0.75 to S⁺ per point, past h 8 on the 11th point (8.25): a drift there; the two
     * warm-up points update the sums but carry no flag.
     */
    @Test
    void sigmaIsPerSeriesAndASteadyLeanIsADriftAfterTheWarmUp() throws Exception {
        exec("CREATE TABLE res AS SELECT 'A' AS k, 1 AS t, 5.0 AS r, 1.0 AS sg UNION ALL SELECT 'B', 1, 5.0, 2.0"
                + " UNION ALL SELECT 'D', i, 1.0, 1.0 FROM range(1, 13) s(i)");
        String out = run("transform.timeseries.changepoint", "res", cfg("order_column", "t", "residual_column", "r",
                "sigma_column", "sg", "group_by", List.of("k"), "warmup", 2));
        List<String> got = rows("SELECT k, t, outside_band, drift_change, warmup FROM " + q(out) + " ORDER BY k, t");
        // A and B: one point each, inside the warm-up — the flags are held back, so check the band on a run without it.
        assertEquals("A|1|0|0|1", got.get(0));
        assertEquals("D|1|0|0|1", got.get(2));
        assertEquals("D|2|0|0|1", got.get(3));
        assertEquals("D|10|0|0|0", got.get(11));
        assertEquals("D|11|0|1|0", got.get(12));
        assertEquals("D|12|0|0|0", got.get(13));
        String noWarm = run("transform.timeseries.changepoint", "res", cfg("order_column", "t", "residual_column", "r",
                "sigma_column", "sg", "group_by", List.of("k")));
        assertEquals(List.of("A|1", "B|0"),
                rows("SELECT k, outside_band FROM " + q(noWarm) + " WHERE k IN ('A', 'B') ORDER BY k"));
    }

    /** Without rebase a shift that stays raises a regime change every K points; rebase re-levels after the first. */
    @Test
    void rebaseReadsAShiftedLevelAsTheNewZero() throws Exception {
        exec("CREATE TABLE res AS SELECT i AS t, CASE WHEN i <= 3 THEN 0.0 ELSE 10.0 END AS r FROM range(1, 11) s(i)");
        String plain = run("transform.timeseries.changepoint", "res", cfg("order_column", "t", "residual_column", "r",
                "sigma", 1));
        assertEquals(List.of("6", "9"), rows("SELECT t FROM " + q(plain) + " WHERE regime_change = 1 ORDER BY t"));
        String rebased = run("transform.timeseries.changepoint", "res", cfg("order_column", "t", "residual_column", "r",
                "sigma", 1, "rebase", true));
        assertEquals(List.of("6"), rows("SELECT t FROM " + q(rebased) + " WHERE regime_change = 1 ORDER BY t"));
    }

    // ── retention ────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Monthly, as of 2026-03-31. Cohort Jan = {a, b}, Feb = {c}; d's April event is after the as-of and dropped. Exact:
     * Jan p1 has a, p2 has b. Unbounded ("on or after"): Jan p1 has a and b (b returns in p2). A cell after the as-of
     * (Feb p2) is absent, never 0.
     */
    @Test
    void retentionCountsEachCohortPerPeriodAsOfTheStatedDate() throws Exception {
        exec("CREATE TABLE ev AS SELECT * FROM (VALUES ('a', DATE '2026-01-05'), ('a', DATE '2026-02-10'),"
                + " ('b', DATE '2026-01-20'), ('b', DATE '2026-03-01'), ('c', DATE '2026-02-03'), ('c', DATE '2026-03-15'),"
                + " ('d', DATE '2026-04-02')) t(customer, day)");
        String exact = run("transform.cohort.retention", "ev", cfg("entity_column", "customer", "time_column", "day",
                "grain", "month", "through", "2026-03-31"));
        assertEquals(List.of("2026-01-01|0|2|2|1.0", "2026-01-01|1|2|1|0.5", "2026-01-01|2|2|1|0.5",
                        "2026-02-01|0|1|1|1.0", "2026-02-01|1|1|1|1.0"),
                rows("SELECT strftime(cohort, '%Y-%m-%d'), period, size, active, rate FROM " + q(exact) + " ORDER BY ALL"));
        String unbounded = run("transform.cohort.retention", "ev", cfg("entity_column", "customer", "time_column", "day",
                "grain", "month", "through", "2026-03-31", "mode", "unbounded"));
        assertEquals(List.of("2026-01-01|0|2", "2026-01-01|1|2", "2026-01-01|2|1", "2026-02-01|0|1", "2026-02-01|1|1"),
                rows("SELECT strftime(cohort, '%Y-%m-%d'), period, active FROM " + q(unbounded) + " ORDER BY ALL"));
    }

    // ── funnel ───────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * visit → signup → buy. e1 does all three; e2 skips signup; e3 signed up BEFORE visiting (does not count); e4 signs
     * up two days after visiting. Within 1 day, e4's signup falls outside the window.
     */
    @Test
    void funnelCountsEntitiesThatReachEachStepInOrder() throws Exception {
        exec("CREATE TABLE ev AS SELECT * FROM (VALUES"
                + " ('e1', 'visit', TIMESTAMP '2026-01-05 10:00'), ('e1', 'signup', TIMESTAMP '2026-01-05 10:05'),"
                + " ('e1', 'buy', TIMESTAMP '2026-01-05 11:00'), ('e2', 'visit', TIMESTAMP '2026-01-05 10:00'),"
                + " ('e2', 'buy', TIMESTAMP '2026-01-05 10:30'), ('e3', 'signup', TIMESTAMP '2026-01-05 09:00'),"
                + " ('e3', 'visit', TIMESTAMP '2026-01-05 10:00'), ('e4', 'visit', TIMESTAMP '2026-01-01 00:00'),"
                + " ('e4', 'signup', TIMESTAMP '2026-01-03 00:00'), ('e4', 'browse', TIMESTAMP '2026-01-03 01:00'))"
                + " t(who, what, ts)");
        Map<String, Object> funnel = cfg("entity_column", "who", "step_column", "what", "time_column", "ts",
                "steps", List.of("visit", "signup", "buy"));
        String open = run("transform.cohort.funnel", "ev", funnel);
        assertEquals(List.of("1|visit|4|null|1.0", "2|signup|2|0.5|0.5", "3|buy|1|0.5|0.25"),
                rows("SELECT step, name, entities, from_previous, from_first FROM " + q(open) + " ORDER BY step"));
        funnel.put("within", "1d");
        String windowed = run("transform.cohort.funnel", "ev", funnel);
        assertEquals(List.of("1|visit|4|null|1.0", "2|signup|1|0.25|0.25", "3|buy|1|1.0|0.25"),
                rows("SELECT step, name, entities, from_previous, from_first FROM " + q(windowed) + " ORDER BY step"));
    }

    // ── refusals ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void badConfigIsRefusedWithTheProblemNamed() throws Exception {
        exec("CREATE TABLE res AS SELECT 1 AS t, 0.0 AS r, 1.0 AS sg");
        String cp = "transform.timeseries.changepoint";
        assertRefused(cp, cfg("order_column", "t", "residual_column", "r"), "exactly one of 'sigma'", "res");
        assertRefused(cp, cfg("order_column", "t", "residual_column", "r", "sigma", 1, "sigma_column", "sg"),
                "exactly one of 'sigma'", "res");
        assertRefused(cp, cfg("order_column", "t", "residual_column", "r", "sigma", 0), "'sigma' must be more than 0", "res");
        assertRefused(cp, cfg("order_column", "t", "residual_column", "r", "sigma", 1, "regime_run", 1),
                "'regime_run' must be between 2", "res");
        assertRefused(cp, cfg("order_column", "t", "residual_column", "r", "sigma", 1, "rebase", "yes"),
                "'rebase' 'yes' is not true or false", "res");
        assertRefused(cp, cfg("order_column", "t", "residual_column", "r; DROP TABLE res", "sigma", 1),
                "not a plain column name", "res");
        assertRefused(cp, cfg("order_column", "t", "residual_column", "r", "sigma", 1, "threshold", 3),
                "does not take 'threshold'", "res");

        exec("CREATE TABLE ev AS SELECT 'a' AS who, TIMESTAMP '2026-01-01 00:00' AS ts, 'visit' AS what");
        String ret = "transform.cohort.retention";
        assertRefused(ret, cfg("entity_column", "who", "time_column", "ts", "grain", "month"), "needs a 'through'", "ev");
        assertRefused(ret, cfg("entity_column", "who", "time_column", "ts", "grain", "minute", "through", "2026-01-31"),
                "must be hour or coarser", "ev");
        assertRefused(ret, cfg("entity_column", "who", "time_column", "ts", "grain", "month", "through", "2026-01-31",
                "mode", "rolling"), "'mode' 'rolling' is not exact", "ev");
        assertRefused(ret, cfg("entity_column", "who", "time_column", "ts", "grain", "month", "through", "2026-01-31",
                "from", "2026-03-01"), "'from' 2026-03-01 is after 'through'", "ev");

        String fun = "transform.cohort.funnel";
        assertRefused(fun, cfg("entity_column", "who", "step_column", "what", "time_column", "ts", "steps", List.of("visit")),
                "a funnel needs 2..12 steps", "ev");
        assertRefused(fun, cfg("entity_column", "who", "step_column", "what", "time_column", "ts",
                "steps", List.of("visit", "visit")), "funnel steps must be distinct", "ev");
        assertRefused(fun, cfg("entity_column", "who", "step_column", "what", "time_column", "ts",
                "steps", List.of("visit", "buy"), "within", "2w"), "'within' '2w' is not a duration", "ev");
        assertRefused(fun, cfg("entity_column", "who", "step_column", "what", "time_column", "ts",
                "steps", List.of("visit", "buy"), "within", "999999999d"), "is longer than 36500d", "ev");
    }

    @Test
    void dataTheKernelCannotReadIsRefusedBeforeItRuns() throws Exception {
        String cp = "transform.timeseries.changepoint";
        exec("CREATE TABLE dup AS SELECT * FROM (VALUES ('A', 1, 0.0), ('A', 1, 1.0), ('B', 1, 0.0)) t(k, t, r)");
        assertRefused(cp, cfg("order_column", "t", "residual_column", "r", "sigma", 1, "group_by", List.of("k")),
                "1 't' value(s) repeat within a series", "dup");
        exec("CREATE TABLE badsg AS SELECT * FROM (VALUES (1, 0.0, 1.0), (2, 0.0, 0.0), (3, NULL, -1.0)) t(t, r, sg)");
        assertRefused(cp, cfg("order_column", "t", "residual_column", "r", "sigma_column", "sg"),
                "1 row(s) have a residual but no sigma above 0", "badsg");
        exec("CREATE TABLE txt AS SELECT 1 AS t, 'x' AS r");
        assertRefused(cp, cfg("order_column", "t", "residual_column", "r", "sigma", 1), "'r' must be a number", "txt");
        exec("CREATE TABLE clash AS SELECT 1 AS t, 0.0 AS r, 0 AS regime_change");
        assertRefused(cp, cfg("order_column", "t", "residual_column", "r", "sigma", 1),
                "output column 'regime_change' collides", "clash");
        assertRefused(cp, cfg("order_column", "t", "residual_column", "missing", "sigma", 1),
                "column 'missing' is not in the inbound data", "clash");

        // a text order sorts '10' before '9': the series would be read out of order with no error
        exec("CREATE TABLE txtord AS SELECT CAST(i AS VARCHAR) AS t, 0.0 AS r FROM range(1, 13) s(i)");
        assertRefused(cp, cfg("order_column", "t", "residual_column", "r", "sigma", 1),
                "'t' must be a number, DATE or TIMESTAMP", "txtord");
        // with sigma a number the Step adds __cp_sigma; an inbound one would be read instead
        exec("CREATE TABLE ownsg AS SELECT 1 AS t, 5.0 AS r, 99.0 AS __cp_sigma");
        assertRefused(cp, cfg("order_column", "t", "residual_column", "r", "sigma", 1),
                "output column '__cp_sigma' collides", "ownsg");
        // a repeated order value on rows with no residual is ignored by the kernel, so it is not refused
        exec("CREATE TABLE emptydup AS SELECT * FROM (VALUES (1, 0.0), (2, NULL), (2, NULL)) t(t, r)");
        String ok = run(cp, "emptydup", cfg("order_column", "t", "residual_column", "r", "sigma", 1));
        assertEquals(List.of("1|0", "2|null", "2|null"), rows("SELECT t, outside_band FROM " + q(ok) + " ORDER BY t"));

        exec("CREATE TABLE zoned AS SELECT 'a' AS who, TIMESTAMPTZ '2026-01-01 00:00:00+00' AS ts, 'visit' AS what");
        assertRefused("transform.cohort.retention", cfg("entity_column", "who", "time_column", "ts", "grain", "month",
                "through", "2026-01-31"), "set 'timezone'", "zoned");
        exec("CREATE TABLE daily AS SELECT 'a' AS who, DATE '2026-01-01' AS ts, 'visit' AS what");
        assertRefused("transform.cohort.funnel", cfg("entity_column", "who", "step_column", "what", "time_column", "ts",
                "steps", List.of("visit", "buy")), "'ts' must be a TIMESTAMP column", "daily");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    private String run(String type, String input, Map<String, Object> cfg) throws SQLException {
        return RowShaper.shape(conn, PipelineNode.of("n", type, cfg), input, "n_" + Math.abs(cfg.hashCode())).stream()
                .filter(r -> r.rel().equals(PipelineRel.DATA)).findFirst().orElseThrow().table();
    }

    private static Map<String, Object> cfg(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private void assertRefused(String type, Map<String, Object> cfg, String fragment, String input) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> run(type, input, cfg));
        assertTrue(e.getMessage().contains(fragment), "expected '" + fragment + "' in: " + e.getMessage());
    }

    private static String q(String t) {
        return "\"" + t.replace("\"", "\"\"") + "\"";
    }

    private void exec(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private List<String> rows(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder b = new StringBuilder();
                for (int i = 1; i <= n; i++) b.append(i == 1 ? "" : "|").append(rs.getString(i));
                out.add(b.toString());
            }
        }
        return out;
    }
}
