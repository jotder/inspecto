package com.gamma.job;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.risk.RiskCorpus;
import com.gamma.risk.RiskScoreEvaluator;
import com.gamma.risk.RiskScorer;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-RISK-SCORE-1: the {@code risk.score} Job evaluates a saved model over a real DuckDB/Parquet corpus
 * ({@link RiskCorpus}) and writes the scores Dataset — one row per entity per run, every score recomputable
 * from the factors stored beside it.
 */
class RiskScoreJobTest {

    private static final double EPS = 1e-9;
    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void clear() { System.clearProperty("assist.write.root"); }

    private static JobRun await(Supplier<JobRun> s, int runs) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        JobRun r = null;
        while (System.nanoTime() < deadline) {
            r = s.get();
            if (r != null && !"RUNNING".equals(r.status())) return r;
            Thread.sleep(50);
        }
        fail("expected a finished job run within 20s, last " + r);
        return null;
    }

    private static List<Map<String, Object>> rows(Path dir) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        String glob = dir.toString().replace('\\', '/') + "/*.parquet";
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM read_parquet('" + glob + "') ORDER BY run_id, entity_key")) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++)
                    row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                out.add(row);
            }
        }
        return out;
    }

    private static long parquetFiles(Path dir) throws Exception {
        long n = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.parquet")) { for (Path ignored : ds) n++; }
        return n;
    }

    @Test
    void scoresEveryEntityWithReproducibleFactorsAndKeepsHistory(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config");
        Path data = dir.resolve("data");
        RiskCorpus.plant(cfg, data, false);
        System.setProperty("assist.write.root", cfg.toString());

        JobConfig job = new JobConfig("score-subs", "risk.score", null, null, true, false,
                Map.of("model", RiskCorpus.MODEL), null, null);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString(), null, null, data.toString())) {
            js.start();
            assertTrue(js.triggerRun("score-subs", null).isPresent());
            JobRun run = await(() -> js.lastRunOf("score-subs").orElse(null), 1);
            assertEquals("SUCCESS", run.status(), "run failed: " + run.message());

            List<Map<String, Object>> rows = rows(data.resolve("risk_scores_subs"));
            String version = RiskScoreEvaluator.version(new com.gamma.pipeline.ComponentStore(cfg.resolve("registry"))
                    .get("risk-score", RiskCorpus.MODEL).orElseThrow().content());
            assertEquals(List.of("m1", "m2", "m3", "m4"), rows.stream().map(r -> r.get("entity_key")).toList(),
                    "one row per entity any factor names");
            Map<String, Double> expected = Map.of("m1", 65.6, "m2", 10.05, "m3", 1.0, "m4", 20.0);
            for (Map<String, Object> r : rows) {
                String key = (String) r.get("entity_key");
                double score = ((Number) r.get("score")).doubleValue();
                assertEquals(expected.get(key), score, EPS, "score of " + key);
                List<Map<String, Object>> factors = JSON.readValue((String) r.get("factors"), new TypeReference<>() {});
                assertEquals(score, RiskScorer.recompute(factors), EPS, key + " is reproducible from its factors");
                assertEquals(3, factors.size());
                assertEquals("subs", r.get("model"));
                assertEquals("subscriber", r.get("entity_type"));
                assertEquals(version, r.get("model_version"), "the model version is the stored content's hash");
                assertNotNull(r.get("scored_at"));
                assertEquals(key.equals("m1"), r.get("high"), "only m1 reaches 50");
            }
            Map<String, Object> m1Failed = JSON.<List<Map<String, Object>>>readValue(
                    (String) rows.get(0).get("factors"), new TypeReference<>() {}).get(0);
            assertEquals(true, m1Failed.get("capped"), "3 × 10 = 30 is held to the cap of 25");
            assertEquals(25.0, ((Number) m1Failed.get("contribution")).doubleValue(), EPS);
            assertEquals(3, ((List<?>) m1Failed.get("evidence")).size(), "evidence rows ride with the factor");
            assertEquals("t1", ((Map<?, ?>) ((List<?>) m1Failed.get("evidence")).get(0)).get("topup_id"));
            Map<String, Object> m4Failed = JSON.<List<Map<String, Object>>>readValue(
                    (String) rows.get(3).get("factors"), new TypeReference<>() {}).get(0);
            assertEquals(true, m4Failed.get("missing"), "m4 has no top-ups: 0, flagged");

            // a second run appends history and REPLACES the latest snapshot
            assertTrue(js.triggerRun("score-subs", null).isPresent());
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (parquetFiles(data.resolve("risk_scores_subs")) < 2 && System.nanoTime() < deadline) Thread.sleep(50);
            assertEquals(2, parquetFiles(data.resolve("risk_scores_subs")), "history: one file per run");
            assertEquals(8, rows(data.resolve("risk_scores_subs")).size(), "one row per entity per run");
            assertEquals(1, parquetFiles(data.resolve("risk_scores_subs_latest")), "latest: this run only");
            assertEquals(4, rows(data.resolve("risk_scores_subs_latest")).size());
        }
    }

    @Test
    void anUnknownModelFailsTheRun(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config");
        Files.createDirectories(cfg.resolve("registry"));
        System.setProperty("assist.write.root", cfg.toString());
        JobConfig job = new JobConfig("score-x", "risk.score", null, null, true, false,
                Map.of("model", "nope"), null, null);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString(), null, null, dir.resolve("data").toString())) {
            js.start();
            js.triggerRun("score-x", null);
            JobRun run = await(() -> js.lastRunOf("score-x").orElse(null), 1);
            assertNotEquals("SUCCESS", run.status());
            assertTrue(run.message().contains("unknown risk-score model 'nope'"), run.message());
        }
    }
}
