package com.gamma.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.job.JobConfig;
import com.gamma.risk.RiskScorer;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code TELCO-BYPASS-SIMBOX-1} slice B2 golden test. The shipped {@code telco-bypass} Space Template, copied verbatim,
 * ingests the synthetic {@link BypassCorpus} (day 1, and day 2 with one allowed line moved) through its own Pipelines, runs
 * the four nightly Jobs as authored ({@code tb_features}, {@code tb_factors}, the {@code risk.score} Job {@code tb_risk_score},
 * {@code tb_findings}) and sweeps the shipped Alert Rules with the production {@link com.gamma.alert.AlertService}. Held to:
 * <ul>
 *   <li>an independent plain-Java oracle for every factor, primary-signal count and score of every scored SIM;</li>
 *   <li>the Alerts equal the planted set, each SIM exactly once, with the planted top factors;</li>
 *   <li>every look-alike silent for its planted reason (the named factor is zero or protective, asserted);</li>
 *   <li>an allow entry suppresses and its expiry un-suppresses; a changed allowed line raises the lower Alert;</li>
 *   <li>the score recomputes from its stored factors; shadow mode writes scores and raises nothing.</li>
 * </ul>
 */
class TelcoBypassGoldenTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DAY1 = "2026-07-01", DAY2 = "2026-07-02";
    private static final double HIGH = 60.0;
    private static final String TOP3 = "b_diversity,no_inbound,short_calls";

    private static boolean regenerate() { return "true".equals(System.getenv("TELCO_BYPASS_REGENERATE")); }

    // ------------------------------------------------------------------------------------------- the oracle

    /** The shipped weights, pinned here independently of the model file: factor id, weight, tb_factors column. */
    private static final String[] IDS = {"b_diversity", "short_calls", "no_inbound", "voice_only", "static_cell", "seq_dial", "night",
            "onnet", "inbound_excess", "service_mix", "mobility"};
    private static final double[] WEIGHT = {14, 12, 14, 10, 8, 8, 8, 6, -20, -15, -12};
    private static final String[] COLUMN = {"b_diversity_excess", "short_uniform_index", "no_inbound_flag", "voice_only_flag",
            "static_cell_flag", "seq_dial_share", "night_share", "onnet_target_share", "inbound_excess", "normal_service_mix", "mobility_flag"};

    private record Expect(Double[] values, int primary, double score) {}

    private static Double num(Map<String, Object> row, String col) {
        Object v = row.get(col);
        return v == null ? null : ((Number) v).doubleValue();
    }

    private static double p(Map<String, String> params, String name) { return Double.parseDouble(params.get(name)); }

    private static double clamp01(double x) { return Math.min(1.0, Math.max(0.0, x)); }

    /** Factor values, primary-signal count and score of one tb_features row: plain Java, no SQL, no Job. */
    private static Expect expect(Map<String, Object> f, Map<String, String> params) {
        Double[] v = new Double[IDS.length];
        v[0] = num(f, "b_diversity") > p(params, "cand_min_b_div") ? 1.0 : 0.0;
        Double scr = num(f, "short_call_ratio");
        v[1] = scr != null && scr > p(params, "cand_min_short") ? 1.0 : 0.0;
        v[2] = num(f, "inbound_ratio") < p(params, "cand_max_inbound") ? 1.0 : 0.0;
        v[3] = num(f, "voice_only_share") > p(params, "cand_min_voice_only") ? 1.0 : 0.0;
        v[4] = num(f, "top_cell_share") > p(params, "cand_min_top_cell") ? 1.0 : 0.0;
        v[5] = num(f, "seq_dial_share");
        v[6] = num(f, "night_share");
        v[7] = num(f, "onnet_target_share");
        v[8] = clamp01((num(f, "inbound_ratio") - p(params, "cand_max_inbound")) / (p(params, "inbound_full") - p(params, "cand_max_inbound")));
        v[9] = clamp01((1.0 - num(f, "voice_only_share")) / p(params, "mix_full"));
        Double kmh = num(f, "max_implied_kmh");
        v[10] = num(f, "top_cell_share") < p(params, "mobility_top_cell") && (kmh == null ? 0.0 : kmh) <= p(params, "mobility_max_kmh") ? 1.0 : 0.0;
        int primary = (int) (v[0] + v[1] + v[2] + v[3] + v[4]) + (num(f, "active_hours") >= p(params, "rtc_hours") ? 1 : 0);
        double sum = 0;
        for (int i = 0; i < IDS.length; i++) {
            double raw = WEIGHT[i] * (v[i] == null ? 0.0 : v[i]);
            double cap = Math.abs(WEIGHT[i]);
            sum += Math.abs(raw) > cap ? Math.signum(raw) * cap : raw;
        }
        return new Expect(v, primary, Math.min(100.0, Math.max(0.0, sum)));
    }

    // ------------------------------------------------------------------------------------------- the shipped parts

    @Test
    void theShippedPartsDeclareTheNightlyChainTheAlertRulesAndTheSeeds() throws Exception {
        Path cfg = BypassPack.TEMPLATE.resolve("config");
        Map<String, String> cron = new LinkedHashMap<>();
        Map<String, JobConfig> jobs = new LinkedHashMap<>();
        for (String j : List.of("tb_features", "tb_factors", "tb_risk_score", "tb_findings")) {
            JobConfig jc = JobConfig.load(cfg.resolve("jobs").resolve(j + "_job.toon").toString());
            jobs.put(j, jc);
            cron.put(j, jc.cron());
        }
        assertEquals(Map.of("tb_features", "0 3 * * *", "tb_factors", "10 3 * * *", "tb_risk_score", "20 3 * * *",
                "tb_findings", "30 3 * * *"), cron, "each stage runs after the one it reads");
        assertEquals("risk.score", jobs.get("tb_risk_score").type());
        assertEquals("tb_sim", jobs.get("tb_risk_score").params().get("model"));
        assertEquals("tb_features", jobs.get("tb_factors").params().get("sources"));
        assertEquals(Set.of("tb_factors", "risk_scores_tb_sim_latest", "tb_findings", "entity_list_tb_allow", "entity_list_tb_watch"),
                new TreeSet<>(List.of(jobs.get("tb_findings").params().get("sources").split(","))));
        assertEquals("window_date", jobs.get("tb_findings").params().get("incremental.column"));
        assertEquals("31", jobs.get("tb_findings").params().get("incremental.retain"));
        // every clause threshold of the factor Job is the SAME value as box_like's in the feature Job: one set of numbers
        for (String k : List.of("cand_max_inbound", "cand_min_b_div", "cand_min_short", "cand_min_voice_only", "cand_min_top_cell"))
            assertEquals(jobs.get("tb_features").params().get(k), jobs.get("tb_factors").params().get(k), k + " must not drift between the two Jobs");
        for (String k : List.of("inbound_full", "mix_full", "mobility_top_cell", "mobility_max_kmh", "rtc_hours"))
            assertNotNull(jobs.get("tb_factors").params().get(k), k);
        assertEquals("false", jobs.get("tb_findings").params().get("shadow"), "the shadow switch ships OFF");
        assertEquals("5", jobs.get("tb_findings").params().get("min_signals"));
        assertEquals("2", jobs.get("tb_findings").params().get("persist_days"));
        assertEquals("3", jobs.get("tb_findings").params().get("persist_window"));

        Map<String, Object> high = ToonHelper.load(cfg.resolve("registry/alert-rules/tb_sim_high.toon").toString());
        assertEquals("tb_findings", high.get("dataset"));
        assertEquals("max(alert_score)", high.get("measure"));
        assertEquals(List.of("entity_key"), high.get("by"));
        assertEquals("CRITICAL", high.get("severity"));
        assertEquals(1000, ((Number) high.get("stormCap")).intValue(), "above retain x the daily offenders, at the platform ceiling (B0 answer 2)");
        Map<String, Object> lower = ToonHelper.load(cfg.resolve("registry/alert-rules/tb_allowed_line_changed.toon").toString());
        assertEquals("max(shape_changed)", lower.get("measure"));
        assertEquals("INFO", lower.get("severity"));
        assertFalse(Files.exists(cfg.resolve("pending")), "no pending Alert Rule: both rules read a Dataset the template seeds");

        Path data = BypassPack.TEMPLATE.resolve("data");
        assertTrue(Files.isRegularFile(data.resolve("tb_factors/seed.parquet")));
        assertTrue(Files.isRegularFile(data.resolve("tb_findings/window_date=1970-01-01/seed.parquet")));
        for (String list : List.of("tb_allow", "tb_watch")) {
            assertTrue(Files.isRegularFile(data.resolve("entity_list_" + list + "/entries.parquet")), list + " sidecar seed");
            assertEquals(list, Files.readString(data.resolve("entity_list_" + list + "/.entity-list-output")).trim(),
                    "the sidecar marker names the list, so the list's first write claims the seeded folder instead of refusing it");
        }
        try (Stream<Path> s = Files.list(cfg.resolve("registry/datasets"))) {
            assertEquals(Set.of("voice_cdr.toon", "cell_site.toon", "number_plan.toon", "subscriber_profile.toon", "tb_features.toon",
                    "tb_factors.toon", "tb_findings.toon", "tb_cluster_members.toon", "tb_clusters.toon"), new TreeSet<>(s.map(f -> f.getFileName().toString()).toList()));
        }
    }

    /**
     * The seeds are what the Jobs' SQL really produces (zero rows, the same columns and types), so the Datasets have a
     * Schema before the first run and the Alert Rules, KPIs and the Risk Score pass their save gates on a fresh Space.
     * Regenerate with env TELCO_BYPASS_REGENERATE=true.
     */
    @Test
    void everyNewSeedMatchesTheSqlOfTheJobThatFillsIt() throws Exception {
        Path data = BypassPack.TEMPLATE.resolve("data");
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            String featuresSeed = data.resolve("tb_features/window_date=1970-01-01/seed.parquet").toString().replace('\\', '/');
            st.execute("CREATE TABLE tb_features AS SELECT * FROM read_parquet('" + featuresSeed + "', hive_partitioning = true)");
            JobConfig factorsJob = JobConfig.load(BypassPack.TEMPLATE.resolve("config/jobs/tb_factors_job.toon").toString());
            String factorsSql = substitute(factorsJob.params().get("sql"), factorsJob.params(), DAY1);
            Path factorsSeed = data.resolve("tb_factors/seed.parquet");
            if (regenerate()) {
                Files.createDirectories(factorsSeed.getParent());
                st.execute("COPY (SELECT * FROM (" + factorsSql + ") LIMIT 0) TO '" + factorsSeed.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
            }
            String factorsRel = "read_parquet('" + factorsSeed.toString().replace('\\', '/') + "')";
            assertEquals(describe(st, "(SELECT * FROM (" + factorsSql + ") LIMIT 0)"), describe(st, factorsRel),
                    "the tb_factors seed drifted from the Job's SQL - regenerate with env TELCO_BYPASS_REGENERATE=true");
            assertEquals(0, scalar(st, "SELECT count(*) FROM " + factorsRel));
            st.execute("CREATE TABLE tb_factors AS SELECT * FROM " + factorsRel);

            // the stand-ins for what the other sources look like at run time
            st.execute("CREATE TABLE risk_scores_tb_sim_latest (model VARCHAR, entity_type VARCHAR, entity_key VARCHAR, score DOUBLE, "
                    + "high BOOLEAN, factors VARCHAR, model_version VARCHAR, run_id VARCHAR, scored_at TIMESTAMP)");
            for (String list : List.of("tb_allow", "tb_watch")) {
                Path seed = data.resolve("entity_list_" + list + "/entries.parquet");
                st.execute("CREATE TABLE entity_list_" + list + " (list_id VARCHAR, purpose VARCHAR, entity_type VARCHAR, match VARCHAR, "
                        + "entry VARCHAR, lo VARCHAR, hi VARCHAR, expires_at TIMESTAMP, added_at TIMESTAMP, added_by VARCHAR, reason VARCHAR)");
                if (regenerate()) {
                    Files.createDirectories(seed.getParent());
                    Files.writeString(seed.getParent().resolve(".entity-list-output"), list);
                    st.execute("COPY entity_list_" + list + " TO '" + seed.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
                }
                assertEquals(describe(st, "entity_list_" + list), describe(st, "read_parquet('" + seed.toString().replace('\\', '/') + "')"),
                        list + ": the sidecar seed is not the shape EntityListSidecar writes");
                assertEquals(0, scalar(st, "SELECT count(*) FROM read_parquet('" + seed.toString().replace('\\', '/') + "')"));
            }

            JobConfig findingsJob = JobConfig.load(BypassPack.TEMPLATE.resolve("config/jobs/tb_findings_job.toon").toString());
            Path findingsSeed = data.resolve("tb_findings/window_date=1970-01-01/seed.parquet");
            st.execute("CREATE TABLE tb_findings (entity_key VARCHAR, kind VARCHAR, score DOUBLE, high BOOLEAN, primary_signals BIGINT, "
                    + "status VARCHAR, alert_score DOUBLE, top_factors VARCHAR, est_bypass_minutes DOUBLE, allowed BOOLEAN, watched BOOLEAN, "
                    + "persist_hits BIGINT, shape_sig VARCHAR, shape_changed INTEGER, evidence_ids VARCHAR, factors VARCHAR, window_date DATE)");
            String findingsSql = substitute(findingsJob.params().get("sql"), findingsJob.params(), DAY1);
            if (regenerate()) {
                Files.createDirectories(findingsSeed.getParent());
                st.execute("COPY (SELECT * EXCLUDE (window_date) FROM (" + findingsSql + ") LIMIT 0) TO '" + findingsSeed.toString().replace('\\', '/')
                        + "' (FORMAT PARQUET)");
            }
            String seedRel = "read_parquet('" + findingsSeed.toString().replace('\\', '/') + "')";
            assertEquals(describe(st, "(SELECT * EXCLUDE (window_date), window_date FROM (" + findingsSql + ") LIMIT 0)"), describe(st, seedRel),
                    "the tb_findings seed drifted from the Job's SQL - regenerate with env TELCO_BYPASS_REGENERATE=true");
            assertEquals(0, scalar(st, "SELECT count(*) FROM " + seedRel));
        }
    }

    /**
     * The save gate every seeded Risk Score meets (the Schema columns it names must exist in the Dataset's seeded Schema, the
     * output names must not collide), run with the Scoring module installed: the module that owns the gate is not in the
     * core's own template test, which only proves the template applies where the kind is opaque config.
     */
    @Test
    void theShippedRiskScoreMeetsItsSaveGateAgainstTheSeededSchema(@TempDir Path tmp) throws Exception {
        Path space = BypassPack.copyTemplate(tmp);
        com.gamma.pipeline.ComponentStore store = new com.gamma.pipeline.ComponentStore(space.resolve("config").resolve("registry"));
        Map<String, Object> content = store.get("risk-score", "tb_sim").orElseThrow().content();
        assertDoesNotThrow(() -> ComponentRoutes.validateKind(space.resolve("config"), () -> space.resolve("data"), "risk-score", "tb_sim", content));
        // the gate is live here: a factor naming a column the seeded Schema lacks is refused
        Map<String, Object> bad = new java.util.LinkedHashMap<>(content);
        List<Map<String, Object>> factors = new ArrayList<>((List<Map<String, Object>>) content.get("factors"));
        Map<String, Object> first = new java.util.LinkedHashMap<>(factors.get(0));
        first.put("measure", "max(no_such_column)");
        factors.set(0, first);
        bad.put("factors", factors);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ComponentRoutes.validateKind(space.resolve("config"), () -> space.resolve("data"), "risk-score", "tb_sim", bad));
        assertTrue(refused.getMessage().contains("no_such_column"), refused.getMessage());
    }

    // ------------------------------------------------------------------------------------------- the golden run

    @Test
    void theAlertsEqualThePlantedSetOnceAndEveryLookAlikeIsSilentForItsPlantedReason(@TempDir Path tmp) throws Exception {
        BypassCorpus corpus = new BypassCorpus().generateB2();
        Planted pl = new Planted(corpus);
        Path space = BypassPack.copyTemplate(tmp);
        Path data = space.resolve("data");
        BypassPack.ingest(space, corpus.files(true));
        // the Fraud Lead's verified line (permanent) and a second one whose verification lapses before night 2; one SIM on watch
        BypassPack.writeSidecar(data, "tb_allow", "allow", List.of(BypassPack.Entry.key(pl.l2a, null),
                BypassPack.Entry.key(pl.l2b, "2026-07-02 12:00:00")));
        BypassPack.writeSidecar(data, "tb_watch", "watch", List.of(BypassPack.Entry.key(pl.watched, "2026-07-02 12:00:00")));
        Map<String, String> factorParams = BypassPack.job(space, "tb_factors").params();

        // ---- night 1
        BypassPack.runNight(space, DAY1, Map.of());
        Map<String, Map<String, Object>> feats1 = BypassPack.rows(data, "tb_features", "msisdn", "window_date = DATE '" + DAY1 + "'");
        Map<String, Map<String, Object>> fac1 = BypassPack.rows(data, "tb_factors", "msisdn", null);
        Map<String, Map<String, Object>> risk1 = BypassPack.rows(data, "risk_scores_tb_sim_latest", "entity_key", null);
        assertFactorsAndScoresEqualTheOracle(feats1, fac1, risk1, factorParams, DAY1);
        Set<String> high1 = highSet(risk1);
        assertEquals(pl.high(false), high1, "night 1: exactly the planted SIM-box-like SIMs (and the pinned PBX pair, and X1) score high");

        Map<String, Map<String, Object>> find1 = BypassPack.rows(data, "tb_findings", "entity_key", "window_date = DATE '" + DAY1 + "'");
        Set<String> want1 = new TreeSet<>(high1);
        assertEquals(want1, find1.keySet(), "a finding for every high SIM and every allowed scored SIM, and nothing else");
        assertStatuses(find1, pl.allowed1(), pl.x1, Set.of(pl.watched), "night 1");
        BypassPack.Sweep sweep = new BypassPack.Sweep(space, BypassPack.SIM_RULES);
        Map<String, Set<String>> alerts1 = sweep.evaluate();
        assertEquals(Map.of("tb_sim_high", Set.of(pl.watched)), alerts1, "night 1: persistence holds everything back except the watched SIM");

        // ---- night 2: L2a (allowed) starts to move, L2b's allow entry has lapsed
        BypassPack.ingest(space, corpus.filesOnDay(1, Set.of(pl.l2a)));
        BypassPack.runNight(space, DAY2, Map.of());
        Map<String, Map<String, Object>> feats2 = BypassPack.rows(data, "tb_features", "msisdn", "window_date = DATE '" + DAY2 + "'");
        Map<String, Map<String, Object>> fac2 = BypassPack.rows(data, "tb_factors", "msisdn", null);
        Map<String, Map<String, Object>> risk2 = BypassPack.rows(data, "risk_scores_tb_sim_latest", "entity_key", null);
        assertFactorsAndScoresEqualTheOracle(feats2, fac2, risk2, factorParams, DAY2);
        assertEquals(pl.high(true), highSet(risk2), "night 2: the relocated allowed line fell below high (protective mobility, no longer static)");
        Map<String, Map<String, Object>> find2 = BypassPack.rows(data, "tb_findings", "entity_key", "window_date = DATE '" + DAY2 + "'");
        Set<String> want2 = new TreeSet<>(highSet(risk2));
        want2.add(pl.l2a);   // allowed, below high: still followed
        assertEquals(want2, find2.keySet());
        Set<String> alertSet2 = new TreeSet<>(pl.alerting());
        assertStatuses(find2, Set.of(pl.l2a), pl.x1, alertSet2, "night 2");
        for (String m : alertSet2) {
            assertEquals(TOP3, find2.get(m).get("top_factors"), m + ": the planted top three factors");
            assertEquals(((Number) risk2.get(m).get("score")).doubleValue(), ((Number) find2.get(m).get("alert_score")).doubleValue(), 1e-9, m);
            assertEquals(6L, ((Number) find2.get(m).get("primary_signals")).longValue(), m + ": all six primary signals");
        }
        assertEquals(1L, ((Number) find2.get(pl.l2a).get("shape_changed")).longValue(), "the allowed line moved: mobility flipped");
        for (String m : find2.keySet()) if (!m.equals(pl.l2a)) assertEquals(0L, ((Number) find2.get(m).get("shape_changed")).longValue(), m);
        assertEquals(Boolean.FALSE, find2.get(pl.l2b).get("allowed"), "its entry lapsed");
        assertEquals(2L, ((Number) find2.get(pl.l2b).get("persist_hits")).longValue(), "its first night counted although it was allowed that night");

        Map<String, Set<String>> alerts2 = sweep.evaluate();
        assertAlerts(Map.of("tb_sim_high", alertSet2, "tb_allowed_line_changed", Set.of(pl.l2a)), alerts2,
                "night 2: every planted SIM, the lapsed allow entry's SIM and the pinned dialler pair; the changed allowed line raises the lower Alert");
        assertEquals(alertSet2.size(), sweep.incidents(), "one Incident per alerting SIM: the watched SIM did not open a second, and the lower-severity rule opens an Alert, not an Incident");
        assertEquals(alerts2, sweep.evaluate(), "a further sweep over unchanged data raises nothing new");
        assertEquals(alertSet2.size(), sweep.incidents());
        assertEquals(60 + 20 + 6 + 1 + 2 + 1, alertSet2.size(), "P1 + P3 + one per clause and the volume floor + X2 + L3 + the SIM whose allow entry lapsed");
        assertFalse(alerts2.get("tb_sim_high").contains(pl.l2a), "the permanently allowed line is silent");
        assertTrue(Collections.disjoint(alerts2.get("tb_sim_high"), pl.x1), "the agreement rule held back the two SIMs with only four primary signals");

        // ---- every look-alike is silent for its planted reason (night 1 factors, night 1 scores)
        assertSilentForThePlantedReason(corpus, risk1, find1);

        // ---- the score recomputes from its stored factors, and the finding carries the same factors
        for (Map.Entry<String, Map<String, Object>> e : risk2.entrySet()) {
            List<Map<String, Object>> factors = JSON.readValue((String) e.getValue().get("factors"), new TypeReference<>() {});
            assertEquals(11, factors.size());
            assertEquals(((Number) e.getValue().get("score")).doubleValue(), RiskScorer.recompute(factors), 1e-9, e.getKey());
            if (find2.containsKey(e.getKey())) assertEquals(e.getValue().get("factors"), find2.get(e.getKey()).get("factors"), e.getKey());
        }

        // ---- re-running night 2 changes nothing: same findings, no duplicate row, no new Incident
        BypassPack.runNight(space, DAY2, Map.of());
        Map<String, Map<String, Object>> find2b = BypassPack.rows(data, "tb_findings", "entity_key", "window_date = DATE '" + DAY2 + "'");
        assertEquals(find2.keySet(), find2b.keySet());
        for (String m : find2.keySet()) {
            assertEquals(find2.get(m).get("status"), find2b.get(m).get("status"), m);
            assertEquals(find2.get(m).get("persist_hits"), find2b.get(m).get("persist_hits"), m + ": a re-run does not count its own night twice");
        }
        assertEquals(alerts2, sweep.evaluate());
        assertEquals(alertSet2.size(), sweep.incidents());
        System.out.println("BYPASS-B2: " + risk1.size() + " scored SIMs night 1, " + high1.size() + " high, " + alertSet2.size()
                + " alerting night 2, " + sweep.incidents() + " Incidents");
    }

    @Test
    void shadowModeWritesTheScoresAndTheFindingsAndRaisesNothing(@TempDir Path tmp) throws Exception {
        BypassCorpus corpus = new BypassCorpus().generateB2();
        Planted pl = new Planted(corpus);
        Path space = BypassPack.copyTemplate(tmp);
        Path data = space.resolve("data");
        BypassPack.ingest(space, corpus.files(true));
        BypassPack.writeSidecar(data, "tb_allow", "allow", List.of(BypassPack.Entry.key(pl.l2a, null)));
        BypassPack.writeSidecar(data, "tb_watch", "watch", List.of(BypassPack.Entry.key(pl.watched, "2026-07-02 12:00:00")));
        Map<String, String> shadow = Map.of("shadow", "true");
        BypassPack.runNight(space, DAY1, shadow);
        Map<String, Map<String, Object>> night1 = BypassPack.rows(data, "tb_findings", "entity_key", "window_date = DATE '" + DAY1 + "'");
        for (Map.Entry<String, Map<String, Object>> e : night1.entrySet()) {
            String want = pl.l2a.equals(e.getKey()) ? "allowed" : pl.x1.contains(e.getKey()) ? "agreement"
                    : pl.watched.equals(e.getKey()) ? "shadow" : "persistence";
            assertEquals(want, e.getValue().get("status"), "shadow night 1 " + e.getKey() + ": shadow only replaces 'alert'; persistence and agreement still hold");
        }
        BypassPack.ingest(space, corpus.filesOnDay(1, Set.of(pl.l2a)));
        BypassPack.runNight(space, DAY2, shadow);

        Map<String, Map<String, Object>> risk = BypassPack.rows(data, "risk_scores_tb_sim_latest", "entity_key", null);
        assertEquals(pl.high(true), highSet(risk), "the scores are written exactly as in live mode");
        Map<String, Map<String, Object>> find = BypassPack.rows(data, "tb_findings", "entity_key", "window_date = DATE '" + DAY2 + "'");
        long shadowRows = find.values().stream().filter(r -> "shadow".equals(r.get("status"))).count();
        assertEquals(pl.alerting().size(), shadowRows, "everything that would have alerted is recorded as shadow");
        assertTrue(find.values().stream().noneMatch(r -> "alert".equals(r.get("status"))), "no finding is an alert");
        for (Map<String, Object> r : find.values()) {
            assertEquals(0.0, ((Number) r.get("alert_score")).doubleValue(), "no alert score anywhere: " + r.get("entity_key"));
            assertEquals(0L, ((Number) r.get("shape_changed")).longValue(), "the allowed-line signal is a page too: silent in shadow");
        }
        assertEquals(Map.of(), new BypassPack.Sweep(space, BypassPack.SIM_RULES).evaluate(), "no Alert Rule has anything to raise");
    }

    // ------------------------------------------------------------------------------------------- the planted sets

    /** What the corpus plants, by role, in one place. */
    private static final class Planted {
        final BypassCorpus c;
        final Set<String> p1, p3, inside, l3, x1, x2;
        final String l2a, l2b, watched;

        Planted(BypassCorpus c) {
            this.c = c;
            p1 = c.archetypes.get("P1");
            p3 = c.archetypes.get("P3");
            inside = new TreeSet<>();
            for (String clause : BypassCorpus.CLAUSES) inside.addAll(c.archetypes.get(BypassCorpus.insideClause(clause)));
            inside.addAll(c.archetypes.get("INSIDE_min_events"));
            l3 = c.archetypes.get("L3");
            x1 = c.agreementTrap;
            x2 = c.roundClockEdge;
            List<String> l2 = new ArrayList<>(c.archetypes.get("L2"));
            l2a = l2.get(0);
            l2b = l2.get(1);
            watched = c.archetypes.get(BypassCorpus.insideClause("inbound")).iterator().next();
        }

        /** Every SIM whose score is at or above high: the planted boxes, the clause fixtures, both PBX lines, the dialler pair and X1. */
        Set<String> high(boolean night2) {
            Set<String> s = new TreeSet<>();
            s.addAll(p1);
            s.addAll(p3);
            s.addAll(inside);
            s.addAll(l3);
            s.addAll(x1);
            s.addAll(x2);
            s.add(l2b);
            if (!night2) s.add(l2a);
            return s;
        }

        Set<String> allowed1() { return Set.of(l2a, l2b); }

        /** The SIMs that alert on night 2: planted boxes, clause fixtures, X2, the dialler pair and the SIM whose allow entry lapsed. */
        Set<String> alerting() {
            Set<String> s = new TreeSet<>(p1);
            s.addAll(p3);
            s.addAll(inside);
            s.addAll(x2);
            s.addAll(l3);
            s.add(l2b);
            return s;
        }
    }

    /** Equal alert sets per rule; a difference is reported as the SIMs missing and the SIMs extra, not as two walls of numbers. */
    private static void assertAlerts(Map<String, Set<String>> want, Map<String, Set<String>> got, String message) {
        assertEquals(want.keySet(), got.keySet(), message + " (rules)");
        for (String rule : want.keySet()) {
            Set<String> missing = new TreeSet<>(want.get(rule)), extra = new TreeSet<>(got.get(rule));
            missing.removeAll(got.get(rule));
            extra.removeAll(want.get(rule));
            assertTrue(missing.isEmpty() && extra.isEmpty(), message + " - " + rule + ": missing " + missing + ", unexpected " + extra);
        }
    }

    private static Set<String> highSet(Map<String, Map<String, Object>> risk) {
        Set<String> out = new TreeSet<>();
        for (Map.Entry<String, Map<String, Object>> e : risk.entrySet())
            if (((Number) e.getValue().get("score")).doubleValue() >= HIGH) out.add(e.getKey());
        for (Map.Entry<String, Map<String, Object>> e : risk.entrySet())
            assertEquals(((Number) e.getValue().get("score")).doubleValue() >= HIGH, e.getValue().get("high"), e.getKey() + ": the high flag is score >= 60");
        return out;
    }

    private static void assertStatuses(Map<String, Map<String, Object>> find, Set<String> allowed, Set<String> agreement,
                                       Set<String> alert, String night) {
        for (Map.Entry<String, Map<String, Object>> e : find.entrySet()) {
            String m = e.getKey();
            String want = allowed.contains(m) ? "allowed" : agreement.contains(m) ? "agreement" : alert.contains(m) ? "alert" : "persistence";
            assertEquals(want, e.getValue().get("status"), night + " " + m);
            assertEquals("alert".equals(want) ? ((Number) e.getValue().get("score")).doubleValue() : 0.0,
                    ((Number) e.getValue().get("alert_score")).doubleValue(), 1e-9, night + " " + m + " alert_score");
        }
    }

    // ------------------------------------------------------------------------------------------- oracle comparison

    private static void assertFactorsAndScoresEqualTheOracle(Map<String, Map<String, Object>> feats, Map<String, Map<String, Object>> fac,
                                                             Map<String, Map<String, Object>> risk, Map<String, String> params, String day) throws Exception {
        assertFalse(feats.isEmpty());
        assertEquals(feats.keySet(), fac.keySet(), day + ": one factor row per scored SIM");
        assertEquals(feats.keySet(), risk.keySet(), day + ": one score per scored SIM");
        for (String m : feats.keySet()) {
            Expect want = expect(feats.get(m), params);
            Map<String, Object> got = fac.get(m);
            for (int i = 0; i < IDS.length; i++) {
                Double w = want.values()[i], g = num(got, COLUMN[i]);
                if (w == null) assertNull(g, day + " " + m + " " + COLUMN[i]);
                else assertEquals(w, g, 1e-9, day + " " + m + " " + COLUMN[i]);
            }
            assertEquals(want.primary(), ((Number) got.get("primary_signals")).intValue(), day + " " + m + " primary_signals");
            assertEquals(want.score(), ((Number) risk.get(m).get("score")).doubleValue(), 1e-9, day + " " + m + " score");
            Map<String, Object> f = feats.get(m);
            double minutes = ((Number) f.get("calls_mo")).doubleValue() * (f.get("asr") == null ? 1.0 : ((Number) f.get("asr")).doubleValue())
                    * (f.get("acd_s") == null ? 0.0 : ((Number) f.get("acd_s")).doubleValue()) / 60.0;
            assertEquals(minutes, ((Number) got.get("est_bypass_minutes")).doubleValue(), 0.051, day + " " + m + " est_bypass_minutes");
            List<Map<String, Object>> factors = JSON.readValue((String) risk.get(m).get("factors"), new TypeReference<>() {});
            assertEquals(IDS.length, factors.size());
            for (int i = 0; i < IDS.length; i++) {
                assertEquals(IDS[i], factors.get(i).get("indicator"));
                assertEquals(WEIGHT[i], ((Number) factors.get(i).get("weight")).doubleValue(), 0.0, m + " " + IDS[i] + " weight");
                Object value = factors.get(i).get("value");
                if (want.values()[i] == null) assertEquals(Boolean.TRUE, factors.get(i).get("missing"), m + " " + IDS[i] + " missing");
                else assertEquals(want.values()[i], ((Number) value).doubleValue(), 1e-9, m + " " + IDS[i]);
            }
        }
    }

    // ------------------------------------------------------------------------------------------- silent, for the planted reason

    private static double contribution(Map<String, Map<String, Object>> risk, String msisdn, String factor) throws Exception {
        List<Map<String, Object>> factors = JSON.readValue((String) risk.get(msisdn).get("factors"), new TypeReference<>() {});
        for (Map<String, Object> f : factors) if (factor.equals(f.get("indicator"))) return ((Number) f.get("contribution")).doubleValue();
        throw new AssertionError("no factor " + factor);
    }

    private static double score(Map<String, Map<String, Object>> risk, String msisdn) {
        return ((Number) risk.get(msisdn).get("score")).doubleValue();
    }

    /**
     * Each look-alike is not high, and the factor that keeps it down is asserted by value: a boundary SIM (exactly AT a
     * clause) would be high if only that one factor counted; a protective factor is negative; a missing positive factor is zero.
     */
    private static void assertSilentForThePlantedReason(BypassCorpus corpus, Map<String, Map<String, Object>> risk,
                                                        Map<String, Map<String, Object>> findings) throws Exception {
        // a SIM exactly AT a clause: the one clause factor is zero, and with its weight added the SIM would be high
        Map<String, String> clauseFactor = Map.of("inbound", "no_inbound", "b_diversity", "b_diversity", "short_calls", "short_calls",
                "voice_only", "voice_only", "static_cell", "static_cell");
        Map<String, Double> weight = new HashMap<>();
        for (int i = 0; i < IDS.length; i++) weight.put(IDS[i], WEIGHT[i]);
        for (Map.Entry<String, String> e : clauseFactor.entrySet()) {
            String at = corpus.archetypes.get(BypassCorpus.atClause(e.getKey())).iterator().next();
            assertEquals(0.0, contribution(risk, at, e.getValue()), 0.0, "AT " + e.getKey() + ": the " + e.getValue() + " factor is zero (the clause is strict)");
            assertTrue(score(risk, at) < HIGH, "AT " + e.getKey() + " is not high: " + score(risk, at));
            assertTrue(score(risk, at) + weight.get(e.getValue()) >= HIGH,
                    "AT " + e.getKey() + ": that one factor is what keeps it below high (" + score(risk, at) + " + " + weight.get(e.getValue()) + ")");
            assertFalse(findings.containsKey(at), "AT " + e.getKey() + " has no finding");
        }
        // look-alikes: the named positive factors are zero, the named protective factors are negative
        Map<String, List<String>> zero = new LinkedHashMap<>();
        zero.put("L1", List.of("no_inbound", "short_calls"));
        zero.put("L4", List.of("b_diversity"));
        zero.put("L5", List.of("static_cell"));
        zero.put("L6", List.of("no_inbound", "voice_only"));
        zero.put("L7", List.of("b_diversity"));
        zero.put("P4", List.of("short_calls", "no_inbound"));
        zero.put("P4b", List.of("b_diversity", "short_calls"));
        zero.put("P5", List.of("static_cell", "mobility"));
        zero.put("P6", List.of("b_diversity"));
        Map<String, List<String>> protective = Map.of("L1", List.of("inbound_excess"), "L5", List.of("mobility"),
                "L6", List.of("service_mix", "inbound_excess"), "L7", List.of("service_mix"));
        for (Map.Entry<String, List<String>> e : zero.entrySet()) {
            assertFalse(corpus.archetypes.get(e.getKey()).isEmpty(), e.getKey());
            for (String m : corpus.archetypes.get(e.getKey())) {
                assertTrue(risk.containsKey(m), e.getKey() + " " + m + " is scored");
                assertTrue(score(risk, m) < HIGH, e.getKey() + " " + m + " is not high: " + score(risk, m));
                assertFalse(findings.containsKey(m), e.getKey() + " " + m + " has no finding");
                for (String f : e.getValue())
                    assertEquals(0.0, contribution(risk, m, f), 0.0, e.getKey() + " " + m + ": " + f + " is zero");
                for (String f : protective.getOrDefault(e.getKey(), List.of()))
                    assertTrue(contribution(risk, m, f) < 0.0, e.getKey() + " " + m + ": the protective factor " + f + " subtracts");
            }
        }
        for (String m : corpus.archetypes.get("BUSY")) assertTrue(!risk.containsKey(m) || score(risk, m) < HIGH, "BUSY " + m);
        // X1 is the one SIM whose score is high and which does NOT alert: the agreement rule, not the score, decides it
        for (String m : corpus.agreementTrap) {
            assertTrue(score(risk, m) >= HIGH, "X1 " + m + " scores high: " + score(risk, m));
            assertEquals(4L, ((Number) findings.get(m).get("primary_signals")).longValue(), "X1 " + m + " has four primary signals");
            assertEquals("agreement", findings.get(m).get("status"));
            assertEquals(0.0, contribution(risk, m, "static_cell"), 0.0, "X1 is spread over two cells");
        }
    }

    // ------------------------------------------------------------------------------------------- plumbing

    /** {@code $name} to quoted literal, longest name first, the rolling window tokens pinned to {@code day}. */
    private static String substitute(String sql, Map<String, String> params, String day) {
        Map<String, String> window = BypassPack.window(day);
        List<String> names = new ArrayList<>(params.keySet());
        names.sort((a, b) -> b.length() - a.length());
        for (String n : names) {
            String v = params.get(n);
            if (v.startsWith("$")) v = window.getOrDefault(n, v);
            sql = sql.replace("$" + n, "'" + v.replace("'", "''") + "'");
        }
        return sql;
    }

    private static List<String> describe(Statement st, String relation) throws Exception {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("DESCRIBE SELECT * FROM " + relation)) {
            while (rs.next()) out.add(rs.getString("column_name") + " " + rs.getString("column_type"));
        }
        return out;
    }

    private static long scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
