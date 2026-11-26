package com.gamma.insights;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.job.JobConfig;
import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.gamma.job.PostgresPublishJobType;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.SpaceConfigRoot;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code insight.scan} (BI-INSIGHTS-1 I-8) run by a real JobService over Parquet in DuckDB: registered through
 * ServiceLoader, it stores ranked Insights, registers the system Dataset {@code insights_<profile>}, lowers a repeated
 * Insight's novelty on the next run, stores a classified slice only as its alias, and refuses a Measure over a masked
 * column and a run that tries to point it at another Profile.
 *
 * <p>{@code sales}: 60 days to yesterday (the last complete day), regions N / S / E at about 100 a day, N = 400
 * yesterday; {@code msisdn} (classified MSISDN) alternates two numbers.
 */
class InsightScanJobTest {

    private static final String SPACE = "default", K1 = "441700900101", K2 = "441700900202";

    @TempDir Path tmp;
    private String priorAuditDir, priorDataDir;
    private Path priorConfigRoot, configRoot, data;
    private ComponentStore store;

    @BeforeEach
    void space() throws Exception {
        priorAuditDir = System.getProperty("jobs.audit.dir");
        priorDataDir = System.getProperty("data.dir");
        data = tmp.resolve("data");
        configRoot = tmp.resolve("config-root");
        System.setProperty("jobs.audit.dir", tmp.resolve("jobs_audit").toString());
        System.setProperty("data.dir", data.toString());
        priorConfigRoot = SpaceConfigRoot.forSpace(SPACE);
        SpaceConfigRoot.register(SPACE, configRoot);
        Files.createDirectories(data.resolve("sales"));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT r.region, CASE WHEN d % 2 = 0 THEN '" + K1 + "' ELSE '" + K2 + "' END AS msisdn, "
                    + "CAST(CASE WHEN d % 2 = 0 THEN " + K1 + " ELSE " + K2 + " END AS BIGINT) AS msisdn_n, "
                    + "CAST(current_date - CAST(d AS INTEGER) AS DATE) AS ts, "
                    + "CASE WHEN d = 1 AND r.region = 'N' THEN 400.0 ELSE 100.0 + ((d * 7 + ascii(r.region)) % 5) END AS amount "
                    + "FROM range(1, 61) AS t(d), (VALUES ('N'), ('S'), ('E')) AS r(region)) TO '"
                    + data.resolve("sales/part-0.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        Files.createDirectories(configRoot);
        Files.writeString(configRoot.resolve("link-analysis.toon"), "masking_mode: typed\n");
        store = new ComponentStore(configRoot.resolve("registry"));
        store.write("dataset", "sales", Map.of("physicalRef", "sales", "columns", List.of(
                Map.of("name", "region"), Map.of("name", "msisdn", "classification", "MSISDN"),
                Map.of("name", "msisdn_n", "classification", "MSISDN"),
                Map.of("name", "ts"), Map.of("name", "amount"))));
        profile("daily", Map.of("agg", "sum", "field", "amount"), List.of("region", "msisdn"));
    }

    @AfterEach
    void restore() {
        PostgresPublishJobType.installAuthority(null);
        restore("jobs.audit.dir", priorAuditDir);
        restore("data.dir", priorDataDir);
        if (priorConfigRoot != null) SpaceConfigRoot.register(SPACE, priorConfigRoot);
        else SpaceConfigRoot.forget(SPACE);
    }

    private static void restore(String key, String prior) {
        if (prior != null) System.setProperty(key, prior);
        else System.clearProperty(key);
    }

    private void profile(String id, Map<String, Object> measure, List<String> dims) throws Exception {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("dataset", "sales");
        p.put("measure", measure);
        p.put("field", "ts");
        p.put("grain", "day");
        p.put("dimensions", dims);
        p.put("types", List.of("anomaly"));
        p.put("season", "none");
        store.write(InsightProfile.KIND, id, p);
    }

    private JobRun scan(String profile, Map<String, String> args) throws Exception {
        Path toon = TestConfigs.csv(tmp.resolve("cfg"), PipelineConfigBatchTest.miniSchema()).write();
        try (CollectorService svc = new CollectorService(List.of(toon), 3600, 1)) {
            JobService js = svc.jobServiceOrCreate();
            js.upsertJob(new JobConfig("scan", InsightScanJobType.TYPE, null, null, true, false,
                    Map.of("profile", profile), null, null));
            String runId = js.triggerRun("scan", "t", args).orElseThrow();
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                JobRun r = js.runById(runId).orElse(null);
                if (r != null && r.endTime() != null && !r.endTime().isBlank()) return r;
                Thread.sleep(50);
            }
            return fail("run " + runId + " did not finish");
        }
    }

    private Map<String, Object> dataset(String id) {
        return store.get("dataset", id).map(ComponentRegistry.Component::content).orElse(null);
    }

    @Test
    void storesRankedInsightsAndRegistersASystemDataset() throws Exception {
        JobRun run = scan("daily", Map.of());
        assertEquals("SUCCESS", run.status(), run.message());

        Map<String, Object> ds = dataset("insights_daily");
        assertEquals("insights_daily", ds.get("physicalRef"));
        assertEquals("system", ds.get("origin"));
        Path dir = data.resolve("insights_daily");
        List<Map<String, Object>> rows = InsightStore.read(dir);
        Map<String, Object> north = rows.stream().filter(r -> "anomaly|region|N".equals(r.get("insight_key")))
                .findFirst().orElseThrow(() -> new AssertionError("no spike in N: " + rows + " / " + run.message()));
        assertEquals("new", north.get("status"));
        assertTrue(((Number) north.get("score")).doubleValue() > 0);
        assertTrue(rows.stream().noneMatch(r -> "S".equals(r.get("slice_value")) || "E".equals(r.get("slice_value"))));
        for (Map<String, Object> r : rows)
            assertFalse(K1.equals(r.get("slice_value")) || K2.equals(r.get("slice_value")),
                    "a classified slice is stored as its alias, never raw: " + r.get("slice_value"));
    }

    @Test
    void aRepeatedInsightLosesNoveltyAndFeedbackIsStamped() throws Exception {
        assertEquals("SUCCESS", scan("daily", Map.of()).status());
        Path dir = data.resolve("insights_daily");
        double first = score(dir, 1);
        Object firstRunAt = InsightStore.read(dir).get(0).get("run_at");
        InsightStore.putFeedback(dir, "anomaly|region|N", "useful", null, "tester", java.time.Instant.now());

        assertEquals("SUCCESS", scan("daily", Map.of()).status());
        List<Map<String, Object>> rows = InsightStore.read(dir);
        assertTrue(rows.stream().anyMatch(r -> firstRunAt.equals(r.get("run_at"))),
                "a rewrite keeps every stored run_at exactly (no host time-zone shift)");
        assertEquals(2, rows.stream().map(r -> r.get("run_id")).distinct().count());
        assertEquals(first / 2, score(dir, 2), 1e-6, () -> "reported in the run before → novelty ½: " + rows.stream()
                .filter(r -> "anomaly|region|N".equals(r.get("insight_key"))).toList());
        assertTrue(rows.stream().filter(r -> "anomaly|region|N".equals(r.get("insight_key")))
                .allMatch(r -> "useful".equals(r.get("status"))));
    }

    private static double score(Path dir, int runs) throws Exception {
        return InsightStore.read(dir).stream().filter(r -> "anomaly|region|N".equals(r.get("insight_key")))
                .max(java.util.Comparator.comparing(r -> java.time.Instant.parse(String.valueOf(r.get("run_at")))))
                .map(r -> ((Number) r.get("score")).doubleValue()).orElseThrow();
    }

    @Test
    void aMeasureOverAMaskedColumnIsRefusedAndNothingIsStored() throws Exception {
        // numeric, so sum() compiles: only the masking vet can refuse it
        profile("leak", Map.of("agg", "sum", "field", "msisdn_n"), List.of("region"));
        JobRun run = scan("leak", Map.of());
        assertNotEquals("SUCCESS", run.status(), run.message());
        assertTrue(run.message().contains("msisdn_n") && !run.message().contains("Binder"),
                "refused by the masking vet, not by a type error: " + run.message());
        assertFalse(Files.exists(data.resolve("insights_leak").resolve("insights-x.parquet")));
        try (var files = Files.list(data.resolve("insights_leak"))) {
            assertEquals(0, files.filter(f -> f.toString().endsWith(".parquet")).count(), "nothing stored");
        }
    }

    private void pairsProfile(String id, Map<String, Object> measure) throws Exception {
        Map<String, Object> p = new LinkedHashMap<>(store.get(InsightProfile.KIND, "daily").orElseThrow().content());
        p.put("measure", measure);
        p.put("pairs", true);
        store.write(InsightProfile.KIND, id, p);
    }

    @Test
    void pairsOfDimensionsAreStoredForTheLastBucketWithClassifiedValuesAsAliases() throws Exception {
        pairsProfile("paired", Map.of("agg", "sum", "field", "amount"));
        JobRun run = scan("paired", Map.of());
        assertEquals("SUCCESS", run.status(), run.message());
        assertTrue(run.message().contains("1 pair(s) of dimensions explained"), run.message());

        Path dir = data.resolve("insights_paired");
        String text = Files.readString(dir.resolve(PairExplain.FILE));
        assertFalse(text.contains(K1) || text.contains(K2), "a classified value is stored only as its alias: " + text);
        String yesterday = java.time.LocalDate.now().minusDays(1).toString();
        Map<String, Object> e = PairExplain.read(dir).get(yesterday);
        assertNotNull(e, "an entry for the last complete day: " + text);
        assertEquals(java.time.LocalDate.now().minusDays(2).toString(), e.get("previous"));
        @SuppressWarnings("unchecked") Map<String, Object> pair = ((List<Map<String, Object>>) e.get("drivers")).get(0);
        assertEquals("region × msisdn", pair.get("dimension"));
        @SuppressWarnings("unchecked") List<String> cover = (List<String>) pair.get("cover");
        assertEquals(1, cover.size(), "N on yesterday's msisdn carries the jump: " + pair);
        assertTrue(cover.get(0).startsWith("N × "), String.valueOf(cover));
        assertFalse(InsightStore.read(dir).isEmpty(), "the Insights are stored as before");
    }

    @Test
    void pairsOverAMaskedMeasureAreRefusedAndNothingIsStored() throws Exception {
        // numeric, so sum() compiles: only the masking vet can refuse it
        pairsProfile("leakpairs", Map.of("agg", "sum", "field", "msisdn_n"));
        JobRun run = scan("leakpairs", Map.of());
        assertNotEquals("SUCCESS", run.status(), run.message());
        assertTrue(run.message().contains("msisdn_n") && !run.message().contains("Binder"),
                "refused by the masking vet, not by a type error: " + run.message());
        assertFalse(Files.exists(data.resolve("insights_leakpairs").resolve(PairExplain.FILE)), "no pairs stored");
    }

    @Test
    void aRunMayNotPointTheJobAtAnotherProfile() throws Exception {
        JobRun run = scan("daily", Map.of("profile", "other"));
        assertNotEquals("SUCCESS", run.status());
        assertTrue(run.message().contains("saved Job only"), run.message());
    }
}
