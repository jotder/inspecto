package com.gamma.anomaly;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.job.JobConfig;
import com.gamma.job.JobRun;
import com.gamma.job.JobService;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code anomaly.score} Job through a real {@link JobService} over the golden corpus: the history and
 * {@code _latest} outputs with their ownership markers, reproducible explanations, and every fail-closed path
 * (unknown model, entity cap, a directory the model does not own) failing the run without a partial {@code _latest}.
 */
class AnomalyScoreJobTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void clear() {
        System.clearProperty("assist.write.root");
        System.clearProperty("data.dir");
        System.clearProperty(AnomalyScoreEvaluator.MAX_ENTITIES_PROPERTY);
    }

    private static JobRun runOnce(Path dir, Path data, Map<String, String> params, int times) throws Exception {
        JobRun r = null;
        for (int i = 0; i < times; i++) r = runOnce(dir, data, params);
        return r;
    }

    private static JobRun runOnce(Path dir, Path data, Map<String, String> params) throws Exception {
        JobConfig job = new JobConfig("score-usage", "anomaly.score", null, null, true, false, params, null, null);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString(), null, null, data.toString())) {
            js.start();
            String id = js.triggerRun("score-usage", null).orElseThrow();
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (System.nanoTime() < deadline) {
                JobRun r = js.lastRunOf("score-usage").orElse(null);
                if (r != null && id.equals(r.runId()) && !"RUNNING".equals(r.status())) return r;
                Thread.sleep(50);
            }
        }
        fail("no finished run within 60s");
        return null;
    }

    private static Path plant(Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        AnomalyCorpus.plant(cfg, data);
        System.setProperty("assist.write.root", cfg.toString());
        System.setProperty("data.dir", data.toString());
        return data;
    }

    private static List<Map<String, Object>> rows(Path d) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        String glob = d.toString().replace('\\', '/') + "/*.parquet";
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

    private static long files(Path d) throws Exception {
        long n = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, "*.parquet")) { for (Path ignored : ds) n++; }
        return n;
    }

    @Test
    void writesHistoryAndLatestWithMarkersAndReproducibleExplanations(@TempDir Path dir) throws Exception {
        Path data = plant(dir);
        JobRun run = runOnce(dir, data, Map.of("model", AnomalyCorpus.MODEL, "as_of", "2026-10-01"), 2);
        assertEquals("SUCCESS", run.status(), run.message());
        Path history = data.resolve("anomaly_scores_usage"), latest = data.resolve("anomaly_scores_usage_latest");
        assertEquals("usage", Files.readString(history.resolve(AnomalyModel.OWNER_MARKER)));
        assertEquals("usage", Files.readString(latest.resolve(AnomalyModel.OWNER_MARKER)));
        assertEquals(2, files(history), "history: one file per run");
        assertEquals(1, files(latest), "latest: this run only");
        List<Map<String, Object>> rows = rows(latest);
        assertEquals(306, rows.size());
        assertEquals(List.of("model", "entity_type", "entity_key", "period_start", "score", "band", "raw", "features",
                "insufficient_count", "model_version", "run_id", "scored_at"), List.copyOf(rows.get(0).keySet()));
        String version = AnomalyScoreEvaluator.version(AnomalyCorpus.model());
        for (Map<String, Object> r : rows) {
            assertEquals(version, r.get("model_version"));
            assertEquals("2026-09-30T00:00", String.valueOf(r.get("period_start")).replace(' ', 'T').substring(0, 16));
            List<Map<String, Object>> f = JSON.readValue((String) r.get("features"), new TypeReference<>() {});
            double[] re = AnomalyScorer.recompute(f, 10, 3);
            assertEquals(((Number) r.get("score")).doubleValue(), re[1], 1e-9, r.get("entity_key") + " reproducible");
        }
        assertEquals(List.of("masked", "spike"), rows.stream().filter(r -> "high".equals(r.get("band")))
                .map(r -> (String) r.get("entity_key")).sorted().toList());
    }

    @Test
    void anUnknownModelFailsTheRun(@TempDir Path dir) throws Exception {
        Path data = plant(dir);
        JobRun run = runOnce(dir, data, Map.of("model", "nope"), 1);
        assertEquals("FAILED", run.status());
        assertTrue(run.message().contains("unknown anomaly-model 'nope'"), run.message());
    }

    @Test
    void theEntityCapFailsTheRunAndWritesNothing(@TempDir Path dir) throws Exception {
        Path data = plant(dir);
        System.setProperty(AnomalyScoreEvaluator.MAX_ENTITIES_PROPERTY, "305");   // 306 entities: one past the cap
        JobRun run = runOnce(dir, data, Map.of("model", AnomalyCorpus.MODEL, "as_of", "2026-10-01"), 1);
        assertEquals("FAILED", run.status());
        assertTrue(run.message().contains("names more than 305 entities - refusing to score a subset"), run.message());
        assertFalse(Files.exists(data.resolve("anomaly_scores_usage_latest")), "no partial _latest");

        System.setProperty(AnomalyScoreEvaluator.MAX_ENTITIES_PROPERTY, "306");   // probe: exactly at the cap succeeds
        assertEquals("SUCCESS", runOnce(dir, data, Map.of("model", AnomalyCorpus.MODEL, "as_of", "2026-10-01"), 1).status());
    }

    @Test
    void aDirectoryTheModelDoesNotOwnIsRefusedUntouched(@TempDir Path dir) throws Exception {
        Path data = plant(dir);
        Path foreign = data.resolve("anomaly_scores_usage_latest");
        Files.createDirectories(foreign);
        Files.writeString(foreign.resolve("keep.parquet"), "not ours");
        JobRun run = runOnce(dir, data, Map.of("model", AnomalyCorpus.MODEL, "as_of", "2026-10-01"), 1);
        assertEquals("FAILED", run.status());
        assertTrue(run.message().contains("was not created by this model"), run.message());
        assertEquals("not ours", Files.readString(foreign.resolve("keep.parquet")));
    }

    @Test
    void aBadAsOfFailsTheRun(@TempDir Path dir) throws Exception {
        Path data = plant(dir);
        JobRun run = runOnce(dir, data, Map.of("model", AnomalyCorpus.MODEL, "as_of", "yesterday"), 1);
        assertEquals("FAILED", run.status());
        assertTrue(run.message().contains("as_of must be YYYY-MM-DD"), run.message());
    }
}
