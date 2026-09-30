package com.gamma.alert;

import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.StatusStore;
import com.gamma.job.JobConfig;
import com.gamma.objects.FakeObjectAccess;
import com.gamma.objects.ObjectType;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetMeasureProbe;
import com.gamma.query.DatasetRelation;
import com.gamma.query.QueryExecutor;
import com.gamma.sql.SqlGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `ASSURE-PACK-BUSINESS-ASSURANCE-1` golden test: the shipped {@code spaces/_templates/business-assurance} pack,
 * evaluated through the production {@link DatasetMeasureProbe} and {@link AlertService} over its OWN registry and
 * views, must raise exactly the planted detections and nothing else.
 *
 * <p><b>The corpus</b> (deterministic — a fixed-offset Weyl sequence feeds Box-Muller, no RNG state):
 * <ul>
 *   <li>daily revenue, 182 days from Monday 2026-01-05: {@code 1000 + 2t + season[t mod 7] + N(0, 20)}, with
 *       season {@code [-120, -60, 0, 40, 80, 150, -90]}. Planted anomaly: day 150 (2026-06-04) {@code -400}
 *       (~20 sigma). Planted look-alike: day 120 (2026-05-05) {@code +60} (~3 sigma, inside the 4-sigma band).</li>
 *   <li>sales lines, 56 days x 4 products x 3 channels x 3 partners. Planted erosion: P3/online/PB cost +15 % in
 *       the recent 28 days (~11 points). Look-alikes that must stay silent: P1/online/PA cost +3 % (~1.8 points,
 *       under the 5-point threshold), P2/retail/PC volume halved (margin % unchanged), and the P4 lines, whose
 *       margin is LOW (5-12 %) but stable.</li>
 * </ul>
 */
class BusinessAssurancePackGoldenTest {

    private static final Path PACK = Path.of("..", "spaces", "_templates", "business-assurance").toAbsolutePath().normalize();
    private static final Path CFG = PACK.resolve("config");
    /** The noise sigma the corpus is built with. */
    private static final double SIGMA = 20.0;

    private static ConfigSource noPipelines() {
        return new ConfigSource() {
            @Override public List<PipelineConfig> pipelines() { return List.of(); }
            @Override public List<EnrichmentConfig> enrichments() { return List.of(); }
            @Override public List<SemanticModel> semantics() { return List.of(); }
        };
    }

    private static StatusStore emptyStore() {
        return new StatusStore() {
            @Override public Set<String> committedBatches(PipelineConfig cfg) { return Set.of(); }
            @Override public List<Map<String, String>> batches(PipelineConfig cfg) { return List.of(); }
            @Override public List<Map<String, String>> files(PipelineConfig cfg) { return List.of(); }
            @Override public List<Map<String, String>> lineage(PipelineConfig cfg, String b) { return List.of(); }
            @Override public List<Map<String, String>> quarantine(PipelineConfig cfg) { return List.of(); }
        };
    }

    private static List<Map<String, Object>> rows(String dataset, String sql) throws Exception {
        Map<String, Object> ds = new ComponentStore(CFG.resolve("registry")).get("dataset", dataset)
                .map(ComponentRegistry.Component::content).orElseThrow();
        return QueryExecutor.run(new QueryExecutor.Request(dataset,
                DatasetRelation.relationSql(ds, null, new ViewStore(CFG.resolve("views"))), sql, 10_000, 0,
                List.of(), List.of())).rows();
    }

    private static double num(Object o) { return ((Number) o).doubleValue(); }

    private static final List<String> RULES = List.of("ba_revenue_outside_band", "ba_revenue_regime_change",
            "ba_revenue_drift", "ba_margin_erosion", "ba_margin_data_quality");

    /** Every pack Alert Rule through the production sweep. */
    private record Sweep(List<Alert> fired, List<FakeObjectAccess.Opened> incidents) {
        long count(String rule) { return fired.stream().filter(a -> a.rule().equals(rule)).count(); }
        String of(String rule) { return fired.stream().filter(a -> a.rule().equals(rule)).toList().toString(); }
    }

    private static Sweep sweep(Path cfg) {
        ComponentStore store = new ComponentStore(cfg.resolve("registry"));
        List<AlertRule> rules = RULES.stream().map(n -> AlertRule.fromMap(store.get("alert-rule", n)
                .map(ComponentRegistry.Component::content).orElseThrow())).toList();
        DatasetMeasureProbe probe = new DatasetMeasureProbe(() -> cfg, () -> null);
        FakeObjectAccess objects = new FakeObjectAccess();
        AlertService svc = new AlertService(rules, noPipelines(), emptyStore(), objects);
        svc.groupedMeasureProbe(r -> probe.breaches(r.dataset(), r.measure(), r.by(), r.comparator(),
                r.threshold(), r.stormCap()));
        svc.datasetLabel(probe::label);
        List<Alert> fired = svc.evaluateRules();
        return new Sweep(fired, objects.opened.stream().filter(o -> o.kind() == ObjectType.INCIDENT).toList());
    }

    /**
     * A copy of the pack whose view {@code store} runs its OWN model SQL over the shipped corpus rewritten by
     * {@code rewrite} (a SELECT over {@code __base}, the shipped corpus relation).
     */
    private static Path variant(Path dir, String store, String src, String rewrite) throws Exception {
        try (var walk = Files.walk(CFG)) {
            for (Path p : walk.toList()) {
                Path t = dir.resolve(CFG.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t); else Files.copy(p, t);
            }
        }
        ViewStore views = new ViewStore(dir.resolve("views"));
        ViewDefinition v = views.get(store).orElseThrow();
        String sep = ") SELECT * FROM (";
        int cut = v.derivedSql().indexOf(sep);
        String corpus = v.derivedSql().substring(("WITH " + src + " AS (").length(), cut);
        String model = v.derivedSql().substring(cut + sep.length());
        views.write(new ViewDefinition(store, v.pipeline(), v.sourceStores(),
                "WITH __base AS (" + corpus + "), " + src + " AS (" + rewrite + ") SELECT * FROM (" + model,
                v.definedAt()));
        return dir;
    }

    private static List<Map<String, Object>> query(Path cfg, String dataset, String sql) throws Exception {
        Map<String, Object> ds = new ComponentStore(cfg.resolve("registry")).get("dataset", dataset)
                .map(ComponentRegistry.Component::content).orElseThrow();
        return QueryExecutor.run(new QueryExecutor.Request(dataset,
                DatasetRelation.relationSql(ds, null, new ViewStore(cfg.resolve("views"))), sql, 1000, 0,
                List.of(), List.of())).rows();
    }

    /** Every flagged day of the forecast, as "t:flag". */
    private static List<String> flags(Path cfg) throws Exception {
        return query(cfg, "ba_revenue_forecast", "SELECT CAST(ds - DATE '2026-01-05' AS INTEGER) || ':'"
                + " || CASE WHEN outside_band = 1 THEN 'band' WHEN regime_change = 1 THEN 'regime' ELSE 'drift' END AS f"
                + " FROM \"ba_revenue_forecast\" WHERE outside_band + regime_change + drift_change > 0 ORDER BY ds")
                .stream().map(r -> (String) r.get("f")).toList();
    }

    private static String revenue(String plus) {
        return "SELECT ds, revenue + (" + plus + ") AS revenue FROM (SELECT *, CAST(ds - DATE '2026-01-05' AS INTEGER)"
                + " AS t FROM __base) q";
    }

    @Test
    void thePackRaisesExactlyThePlantedDetectionsAndNoFalsePositive() {
        assertTrue(Files.isDirectory(CFG), "the pack ships at " + CFG);
        Sweep s = sweep(CFG);
        assertEquals(2, s.fired().size(), "2 detections, 0 false positives: " + s.fired());
        assertEquals(1, s.count("ba_revenue_outside_band"));
        assertTrue(s.of("ba_margin_erosion").contains("partner=PB"), s.of("ba_margin_erosion"));
        // Only the CRITICAL forecast breach opens an Incident; the WARNING erosion stays an Alert.
        assertEquals(1, s.incidents().size(), s.incidents().toString());
        assertEquals("2026-06-04", s.incidents().get(0).attributes().get("key.ds"));
        assertTrue(s.incidents().get(0).title().contains("Daily revenue with its forecast and prediction band"),
                "the forecast Alert names its Dataset by description, as the erosion one does: " + s.incidents());
    }

    /**
     * A level shift is absorbed: K = 3 consecutive out-of-band days are a regime change, raised ONCE on the K-th
     * day, after which the model re-bases level on the actual and the shift raises nothing more. The spike
     * still fires.
     */
    @Test
    void aLevelShiftIsOneRegimeChangeThenSilence(@TempDir Path dir) throws Exception {
        for (String shift : List.of("300", "-150")) {
            Path cfg = variant(dir.resolve("shift" + shift.replace('-', 'm')), "ba_revenue_forecast", "daily_revenue",
                    revenue("CASE WHEN t >= 100 THEN " + shift + " ELSE 0 END"));
            assertEquals(List.of("102:regime", "150:band"), flags(cfg),
                    shift + ": one regime change on day 3 of the shift, then only the planted spike");
            Sweep s = sweep(cfg);
            assertEquals(1, s.count("ba_revenue_regime_change"), shift + ": " + s.fired());
            assertEquals(1, s.count("ba_revenue_outside_band"), shift + ": " + s.fired());
            assertEquals(3, s.fired().size(), shift + ": shift + spike + the unchanged planted erosion: " + s.fired());
            assertEquals(2, s.incidents().size(), "one Incident for the shift, one for the spike: " + s.incidents());
        }
    }

    /**
     * A trend change is caught by a two-sided CUSUM over the in-band residuals (k = 0.25 sigma, h = 8 sigma) ONCE,
     * after which the trend is re-based. Bound: within 21 days of the ramp starting at +/-8 a day (measured 19 and
     * 13). Out-of-band days do not feed the CUSUM, so a spike or a level shift never trips it.
     */
    @Test
    void aTrendRampIsOneDriftDetectionWithinTheBound(@TempDir Path dir) throws Exception {
        for (String slope : List.of("8", "-8")) {
            Path cfg = variant(dir.resolve("ramp" + slope.replace('-', 'm')), "ba_revenue_forecast", "daily_revenue",
                    revenue("CASE WHEN t >= 100 THEN " + slope + " * (t - 100) ELSE 0 END"));
            List<String> f = flags(cfg);
            assertEquals(2, f.size(), slope + ": the drift and the planted spike, nothing else: " + f);
            assertTrue(f.contains("150:band"), slope + ": " + f);
            String drift = f.stream().filter(x -> x.endsWith(":drift")).findFirst().orElseThrow();
            int t = Integer.parseInt(drift.substring(0, drift.indexOf(':')));
            assertTrue(t > 100 && t <= 121, slope + ": detected on day " + t);
            assertEquals(1, sweep(cfg).count("ba_revenue_drift"));
        }
    }

    /**
     * Bad input is never read as a margin: a negative revenue line and a null cost line raise a data-quality Alert
     * and suppress erosion; a group with no baseline is "new"; a 2-line group is "insufficient" (min 10 lines and
     * 1000 revenue per window). None of them fires erosion; the planted erosion still fires, once.
     */
    @Test
    void badMarginInputIsFlaggedNotMisRead(@TempDir Path dir) throws Exception {
        Path cfg = variant(dir, "ba_margin_erosion", "margin_lines", ("SELECT ds, product, channel, partner, units,"
                + " CASE WHEN product = 'P1' AND channel = 'retail' AND partner = 'PA' AND ds = DATE '2026-03-01'"
                + " THEN -revenue ELSE revenue END AS revenue,"
                + " CASE WHEN product = 'P2' AND channel = 'online' AND partner = 'PB' AND ds = DATE '2026-03-01'"
                + " THEN NULL ELSE cost END AS cost, margin FROM __base"
                + " UNION ALL SELECT ds, 'P5', channel, partner, units, revenue, cost, margin FROM __base"
                + " WHERE product = 'P1' AND channel = 'online' AND partner = 'PA' AND ds >= DATE '2026-02-02'"
                + " UNION ALL SELECT * FROM (VALUES (DATE '2026-01-20', 'P6', 'retail', 'PB', 10, 1000.0, 500.0, 500.0),"
                + " (DATE '2026-02-20', 'P6', 'retail', 'PB', 10, 1000.0, 900.0, 100.0)) v"));
        Map<String, String> status = new java.util.HashMap<>();
        for (Map<String, Object> r : query(cfg, "ba_margin_erosion",
                "SELECT product || '/' || channel || '/' || partner AS k, status FROM \"ba_margin_erosion\""))
            status.put((String) r.get("k"), (String) r.get("status"));
        assertEquals("data_quality", status.get("P1/retail/PA"), "negative revenue");
        assertEquals("data_quality", status.get("P2/online/PB"), "null cost");
        assertEquals("new", status.get("P5/online/PA"), "no baseline");
        assertEquals("insufficient", status.get("P6/retail/PB"), "2 lines");
        assertEquals("assessed", status.get("P3/online/PB"));

        Sweep s = sweep(cfg);
        assertEquals(1, s.count("ba_margin_erosion"), s.fired().toString());
        assertTrue(s.of("ba_margin_erosion").contains("partner=PB"), s.of("ba_margin_erosion"));
        assertEquals(2, s.count("ba_margin_data_quality"), s.fired().toString());
    }

    @Test
    void theLookAlikesStaySilentForTheRightReason() throws Exception {
        var lookalike = rows("ba_revenue_forecast", "SELECT actual, forecast, upper_band FROM \"ba_revenue_forecast\""
                + " WHERE ds = DATE '2026-05-05'").get(0);
        double gap = num(lookalike.get("actual")) - num(lookalike.get("forecast"));
        assertTrue(gap > 1.5 * SIGMA, "the +60 look-alike is really high (" + gap + ")");
        assertTrue(num(lookalike.get("actual")) < num(lookalike.get("upper_band")), "and still inside the band");

        Map<String, Double> byKey = new java.util.HashMap<>();
        for (Map<String, Object> r : rows("ba_margin_erosion", "SELECT product || '/' || channel || '/' || partner AS k,"
                + " erosion_pp, margin_pct FROM \"ba_margin_erosion\""))
            byKey.put((String) r.get("k"), num(r.get("erosion_pp")));
        assertEquals(36, byKey.size(), "4 products x 3 channels x 3 partners");
        assertTrue(byKey.get("P3/online/PB") > 10, "the planted erosion is ~11 points: " + byKey.get("P3/online/PB"));
        assertTrue(byKey.get("P1/online/PA") > 1 && byKey.get("P1/online/PA") < 5, "a real but small erosion");
        assertTrue(Math.abs(byKey.get("P2/retail/PC")) < 1, "halved volume is not margin erosion");
        double p4 = num(rows("ba_margin_erosion", "SELECT max(margin_pct) AS m FROM \"ba_margin_erosion\""
                + " WHERE product = 'P4'").get(0).get("m"));
        assertTrue(p4 < 15, "P4 is low-margin (" + p4 + " %) yet raises nothing: erosion, not level, is the signal");
    }

    /**
     * Accuracy against the KNOWN signal. Tolerances: the realized noise has sigma ~20, so no one-step forecast can
     * beat an RMSE of ~20 against the actuals; a seasonal-naive forecast (last week's value) scores ~sigma*sqrt(2)
     * = 28 against the actuals and ~20 against the truth. Holt-Winters must do clearly better than that against
     * the noise-free truth ({@code <= 0.75 sigma = 15}, measured 10.3) and stay within {@code 1.5 sigma = 30}
     * of the actuals (measured 24.9), a MAPE under 2.5 % (measured 1.7 %). Warm-up (first 4 weeks) and the planted
     * anomaly day are excluded.
     */
    @Test
    void theForecastTracksTheKnownSeasonalSignal() throws Exception {
        var r = rows("ba_revenue_forecast", "SELECT count(*) AS n,"
                + " sqrt(avg(power(forecast - (1000 + 2 * t + list_extract([-120.0, -60.0, 0.0, 40.0, 80.0, 150.0, -90.0],"
                + " t % 7 + 1)), 2))) AS rmse_truth, sqrt(avg(power(actual - forecast, 2))) AS rmse_actual,"
                + " 100 * avg(abs(actual - forecast) / actual) AS mape,"
                + " avg(CASE WHEN actual BETWEEN lower_band AND upper_band THEN 1.0 ELSE 0.0 END) AS coverage"
                + " FROM (SELECT *, CAST(ds - DATE '2026-01-05' AS INTEGER) AS t FROM \"ba_revenue_forecast\")"
                + " WHERE warmup = 0 AND t <> 150").get(0);
        assertEquals(153, num(r.get("n")), 1e-9, "182 days, first forecast on day 8, 21 more warm-up days, 1 anomaly day excluded: " + r);
        assertTrue(num(r.get("rmse_truth")) <= 0.75 * SIGMA, "forecast vs truth: " + r);
        assertTrue(num(r.get("rmse_actual")) <= 1.5 * SIGMA, "forecast vs actual: " + r);
        assertTrue(num(r.get("mape")) < 2.5, "MAPE: " + r);
        assertEquals(1.0, num(r.get("coverage")), 1e-9, "every clean day sits inside the band: " + r);
    }

    /** The Job form runs the SAME SQL as the pack's view over the user's own store, and passes the Job's guard. */
    @Test
    void eachJobRunsTheViewsSqlAndPassesTheSqlGuard() throws Exception {
        ViewStore views = new ViewStore(CFG.resolve("views"));
        for (String[] p : List.of(new String[]{"ba_revenue_forecast", "ba_revenue_forecast"},
                new String[]{"ba_margin_erosion", "ba_margin_erosion"})) {
            JobConfig job = JobConfig.load(CFG.resolve("jobs").resolve(p[0] + "_job.toon").toString());
            String sql = job.params().get("sql");
            ViewDefinition view = views.get(p[1]).orElseThrow();
            assertTrue(view.derivedSql().contains("SELECT * FROM (" + sql + ") AS f"),
                    p[0] + ": the Job and the view drifted apart");
            assertEquals(List.of(), SqlGuard.check(sql), p[0] + " passes the sql.template guard");
            assertFalse(job.enabled(), p[0] + " ships disabled until the user's store exists");
        }
    }
}
