package com.gamma.predictive;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.event.EventLog;
import com.gamma.job.JobConfig;
import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.gamma.signal.Signal;
import com.gamma.signal.SignalType;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code predict.churn} Job through a real {@link JobService} over the synthetic {@link ChurnCorpus}: the history
 * and {@code _latest} outputs with their ownership markers, the hand-computed scores and bands, the insufficient entity
 * (NULL score, never 0), rows that recompute from their own explanation, the {@code churn.score.produced} Signal, and
 * the fail-closed paths (unknown model, entity cap).
 */
class ChurnJobTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void clear() {
        System.clearProperty("assist.write.root");
        System.clearProperty("data.dir");
        System.clearProperty(ChurnEvaluator.MAX_ENTITIES_PROPERTY);
    }

    private static JobRun runOnce(Path dir, Path data, Map<String, String> params) throws Exception {
        JobConfig job = new JobConfig("score-churn", "predict.churn", null, null, true, false, params, null, null);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString(), null, null, data.toString())) {
            js.start();
            String id = js.triggerRun("score-churn", null, Map.of()).orElseThrow();
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (System.nanoTime() < deadline) {
                JobRun r = js.lastRunOf("score-churn").orElse(null);
                if (r != null && id.equals(r.runId()) && !"RUNNING".equals(r.status())) return r;
                Thread.sleep(50);
            }
        }
        fail("no finished run within 60s");
        return null;
    }

    private static Path plant(Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        ChurnCorpus.plant(cfg, data);
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
        if (!Files.isDirectory(d)) return 0;
        long n = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, "*.parquet")) { for (Path ignored : ds) n++; }
        return n;
    }

    @Test
    void writesHistoryAndLatestWithTheHandComputedScores(@TempDir Path dir) throws Exception {
        Path data = plant(dir);
        Map<String, String> params = Map.of("model", ChurnCorpus.MODEL, "as_of", ChurnCorpus.AS_OF.toString());
        assertEquals("SUCCESS", runOnce(dir, data, params).status());
        List<Signal> signals = new CopyOnWriteArrayList<>();
        java.util.function.Consumer<Event> listen = e -> { if (EventType.SIGNAL.equals(e.type())) signals.add(Signal.fromEvent(e)); };
        EventLog.global().addSubscriber(listen);   // a Run emits to EventLog.current(): the global log outside a Space
        JobRun run;
        try {
            run = runOnce(dir, data, params);
        } finally {
            EventLog.global().removeSubscriber(listen);
        }
        assertEquals("SUCCESS", run.status(), run.message());
        Path history = data.resolve("churn_scores_churn"), latest = data.resolve("churn_scores_churn_latest");
        assertEquals("churn", Files.readString(history.resolve(ChurnModel.OWNER_MARKER)));
        assertEquals("churn", Files.readString(latest.resolve(ChurnModel.OWNER_MARKER)));
        assertEquals(2, files(history), "history: one file per run");
        assertEquals(1, files(latest), "latest: this run only");

        List<Map<String, Object>> rows = rows(latest);
        assertEquals(List.of("model", "entity_type", "entity_key", "as_of", "score", "band", "logit", "history_days",
                "explanation", "model_version", "run_id", "scored_at"), List.copyOf(rows.get(0).keySet()));
        ChurnModel model = ChurnModel.fromMap(ChurnCorpus.MODEL, ChurnCorpus.model());
        String version = ChurnEvaluator.version(new com.gamma.pipeline.ComponentStore(dir.resolve("config/registry"))
                .get(ChurnModel.KIND, ChurnCorpus.MODEL).orElseThrow().content());
        Map<String, String> bands = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String key = (String) r.get("entity_key");
            bands.put(key, (String) r.get("band"));
            assertEquals(version, r.get("model_version"));
            assertTrue(String.valueOf(r.get("as_of")).startsWith("2026-06-30"), String.valueOf(r.get("as_of")));
            Map<String, Object> explanation = JSON.readValue((String) r.get("explanation"), new TypeReference<>() {});
            ChurnScorer.Recomputed again = ChurnScorer.recompute(model, explanation);
            assertEquals(r.get("band"), again.band(), "a stored row recomputes from its own explanation: " + key);
            if ("newbie".equals(key)) {
                assertNull(r.get("score"), "insufficient history: NULL, never 0");
                assertNull(r.get("logit"));
                assertEquals(10, r.get("history_days"));
                assertEquals("history", explanation.get("insufficient"));
                continue;
            }
            assertEquals(ChurnCorpus.LOGIT.get(key), (Double) r.get("logit"), 1e-9, key);
            assertEquals(100.0 / (1.0 + Math.exp(-ChurnCorpus.LOGIT.get(key))), (Double) r.get("score"), 1e-9, key);
            assertEquals((Double) r.get("score"), again.score(), 1e-9, key);
            double sum = model.intercept();
            for (Object p : (List<?>) explanation.get("inputs")) sum += ((Number) ((Map<?, ?>) p).get("contribution")).doubleValue();
            assertEquals((Double) r.get("logit"), sum, 1e-9, "stored contributions sum to the stored logit: " + key);
        }
        assertEquals(ChurnCorpus.BAND, bands, "future (after as_of) and the NULL key have no row");
        Map<String, Object> fading = rows.stream().filter(r -> "fading".equals(r.get("entity_key"))).findFirst().orElseThrow();
        assertEquals(81.5605, (Double) fading.get("score"), 1e-4, "hand-computed");
        assertEquals(486, fading.get("history_days"), "history counts from its first row, before the window");

        Signal produced = signals.stream().filter(s -> SignalType.CHURN_SCORE_PRODUCED.equals(s.type()))
                .findFirst().orElseThrow(() -> new AssertionError("no churn.score.produced in " + signals));
        assertEquals("churn", produced.payload().get("model"));
        assertEquals(2, ((Number) produced.payload().get("high")).intValue());
        assertEquals(1, ((Number) produced.payload().get("insufficient")).intValue());
    }

    @Test
    void anUnknownModelFailsTheRun(@TempDir Path dir) throws Exception {
        Path data = plant(dir);
        JobRun run = runOnce(dir, data, Map.of("model", "ghost", "as_of", "2026-06-30"));
        assertEquals("FAILED", run.status());
        assertTrue(run.message().contains("unknown churn-model 'ghost'"), run.message());
    }

    @Test
    void morePopulationThanTheCapFailsWithoutWritingASubset(@TempDir Path dir) throws Exception {
        Path data = plant(dir);
        System.setProperty(ChurnEvaluator.MAX_ENTITIES_PROPERTY, "4");
        JobRun run = runOnce(dir, data, Map.of("model", ChurnCorpus.MODEL, "as_of", "2026-06-30"));
        assertEquals("FAILED", run.status());
        assertTrue(run.message().contains("names more than 4 entities - refusing to score a subset"), run.message());
        assertEquals(0, files(data.resolve("churn_scores_churn_latest")), "nothing written");
        System.setProperty(ChurnEvaluator.MAX_ENTITIES_PROPERTY, "5");
        assertEquals("SUCCESS", runOnce(dir, data, Map.of("model", ChurnCorpus.MODEL, "as_of", "2026-06-30"))
                .status(), "exactly at the cap is allowed");
    }

    @Test
    void anOutputDirectoryTheModelDoesNotOwnFailsTheRun(@TempDir Path dir) throws Exception {
        Path data = plant(dir);
        Path foreign = Files.createDirectories(data.resolve("churn_scores_churn_latest"));
        Files.writeString(foreign.resolve("data.parquet"), "not ours");
        JobRun run = runOnce(dir, data, Map.of("model", ChurnCorpus.MODEL, "as_of", "2026-06-30"));
        assertEquals("FAILED", run.status(), run.message());
        assertTrue(run.message().contains("not created by this model"), run.message());
        assertEquals("not ours", Files.readString(foreign.resolve("data.parquet")), "the foreign store is untouched");
    }

    @Test
    void asOfDefaultsToYesterdayAndRefusesAMalformedDate() {
        java.time.Instant now = java.time.Instant.parse("2026-07-01T05:00:00Z");
        assertEquals(java.time.LocalDate.parse("2026-06-30"), ChurnJobType.asOf(null, now));
        assertThrows(IllegalArgumentException.class, () -> ChurnJobType.asOf("30/06/2026", now));
    }
}
