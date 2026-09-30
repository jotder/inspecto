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

    private static AlertRule rule(String name) {
        return AlertRule.fromMap(new ComponentStore(CFG.resolve("registry")).get("alert-rule", name)
                .map(ComponentRegistry.Component::content).orElseThrow());
    }

    private static List<Map<String, Object>> rows(String dataset, String sql) throws Exception {
        Map<String, Object> ds = new ComponentStore(CFG.resolve("registry")).get("dataset", dataset)
                .map(ComponentRegistry.Component::content).orElseThrow();
        return QueryExecutor.run(new QueryExecutor.Request(dataset,
                DatasetRelation.relationSql(ds, null, new ViewStore(CFG.resolve("views"))), sql, 10_000, 0,
                List.of(), List.of())).rows();
    }

    private static double num(Object o) { return ((Number) o).doubleValue(); }

    @Test
    void thePackRaisesExactlyThePlantedDetectionsAndNoFalsePositive() {
        assertTrue(Files.isDirectory(CFG), "the pack ships at " + CFG);
        AlertRule band = rule("ba_revenue_outside_band");
        AlertRule erosion = rule("ba_margin_erosion");
        DatasetMeasureProbe probe = new DatasetMeasureProbe(() -> CFG, () -> null);

        var forecastBreaches = probe.breaches(band.dataset(), band.measure(), band.by(), band.comparator(),
                band.threshold(), band.stormCap()).orElseThrow();
        assertEquals(1, forecastBreaches.total(), "exactly the planted anomaly: " + forecastBreaches);
        assertEquals("2026-06-04", String.valueOf(forecastBreaches.keys().get(0).key().get("ds")));

        var erosionBreaches = probe.breaches(erosion.dataset(), erosion.measure(), erosion.by(), erosion.comparator(),
                erosion.threshold(), erosion.stormCap()).orElseThrow();
        assertEquals(1, erosionBreaches.total(), "exactly the planted erosion: " + erosionBreaches);
        assertEquals(Map.of("product", "P3", "channel", "online", "partner", "PB"), erosionBreaches.keys().get(0).key());

        // The same two rules through the production sweep: one Alert each, one Incident each (CRITICAL / WARNING).
        FakeObjectAccess objects = new FakeObjectAccess();
        AlertService svc = new AlertService(List.of(band, erosion), noPipelines(), emptyStore(), objects);
        svc.groupedMeasureProbe(r -> probe.breaches(r.dataset(), r.measure(), r.by(), r.comparator(),
                r.threshold(), r.stormCap()));
        var fired = svc.evaluateRules();
        assertEquals(2, fired.size(), "2 detections, 0 false positives: " + fired);
        assertTrue(fired.toString().contains("partner=PB"), fired.toString());
        // Only the CRITICAL forecast breach opens an Incident; the WARNING erosion stays an Alert.
        var incidents = objects.opened.stream().filter(o -> o.kind() == ObjectType.INCIDENT).toList();
        assertEquals(1, incidents.size(), incidents.toString());
        assertEquals("2026-06-04", incidents.get(0).attributes().get("key.ds"));
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
