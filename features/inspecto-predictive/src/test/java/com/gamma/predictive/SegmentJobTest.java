package com.gamma.predictive;

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
 * The {@code predict.segment} Job through a real {@link JobService} over the synthetic {@link RfmCorpus}: the history
 * and {@code _latest} outputs with their ownership markers, the hand-computed segments, rows that recompute from their
 * own explanation, and every fail-closed path (unknown model, entity cap, a directory the model does not own) failing
 * the run without writing a {@code _latest}.
 */
class SegmentJobTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void clear() {
        System.clearProperty("assist.write.root");
        System.clearProperty("data.dir");
        System.clearProperty(SegmentEvaluator.MAX_ENTITIES_PROPERTY);
    }

    private static JobRun runOnce(Path dir, Path data, Map<String, String> params) throws Exception {
        JobConfig job = new JobConfig("segment-customers", "predict.segment", null, null, true, false, params, null, null);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString(), null, null, data.toString())) {
            js.start();
            String id = js.triggerRun("segment-customers", null, Map.of()).orElseThrow();
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (System.nanoTime() < deadline) {
                JobRun r = js.lastRunOf("segment-customers").orElse(null);
                if (r != null && id.equals(r.runId()) && !"RUNNING".equals(r.status())) return r;
                Thread.sleep(50);
            }
        }
        fail("no finished run within 60s");
        return null;
    }

    private static Path plant(Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        RfmCorpus.plant(cfg, data);
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
    void writesHistoryAndLatestWithTheHandComputedSegments(@TempDir Path dir) throws Exception {
        Path data = plant(dir);
        Map<String, String> params = Map.of("model", RfmCorpus.MODEL, "as_of", RfmCorpus.AS_OF.toString());
        assertEquals("SUCCESS", runOnce(dir, data, params).status());
        JobRun run = runOnce(dir, data, params);
        assertEquals("SUCCESS", run.status(), run.message());
        Path history = data.resolve("customer_segments_rfm"), latest = data.resolve("customer_segments_rfm_latest");
        assertEquals("rfm", Files.readString(history.resolve(SegmentationModel.OWNER_MARKER)));
        assertEquals("rfm", Files.readString(latest.resolve(SegmentationModel.OWNER_MARKER)));
        assertEquals(2, files(history), "history: one file per run");
        assertEquals(1, files(latest), "latest: this run only");

        List<Map<String, Object>> rows = rows(latest);
        assertEquals(List.of("model", "entity_type", "entity_key", "as_of", "segment", "recency_score",
                "frequency_score", "monetary_score", "recency_days", "frequency", "monetary", "explanation",
                "model_version", "run_id", "scored_at"), List.copyOf(rows.get(0).keySet()));
        Map<String, String> got = new LinkedHashMap<>();
        SegmentationModel model = SegmentationModel.fromMap(RfmCorpus.MODEL, RfmCorpus.model());
        String version = SegmentEvaluator.version(RfmCorpus.model());
        for (Map<String, Object> r : rows) {
            got.put((String) r.get("entity_key"), (String) r.get("segment"));
            assertEquals(version, r.get("model_version"));
            assertEquals("customer", r.get("entity_type"));
            assertTrue(String.valueOf(r.get("as_of")).startsWith("2026-06-30"), String.valueOf(r.get("as_of")));
            Map<String, Object> explanation = JSON.readValue((String) r.get("explanation"), new TypeReference<>() {});
            assertEquals(List.of(r.get("segment"), r.get("recency_score"), r.get("frequency_score"), r.get("monetary_score")),
                    RfmSegmenter.recompute(model, explanation), "a stored row recomputes from its own explanation");
        }
        assertEquals(RfmCorpus.EXPECTED, got, "outside (before the window) has no row");
        Map<String, Object> future = rows.stream().filter(r -> "future".equals(r.get("entity_key"))).findFirst().orElseThrow();
        assertEquals(71, future.get("recency_days"), "the order after as_of is not its last activity");
        assertEquals(20.0, future.get("monetary"), "nor in its monetary value");
        Map<String, Object> nullamt = rows.stream().filter(r -> "nullamt".equals(r.get("entity_key"))).findFirst().orElseThrow();
        assertEquals(0.0, nullamt.get("monetary"), "an all-NULL sum is 0");
    }

    @Test
    void anUnknownModelFailsTheRun(@TempDir Path dir) throws Exception {
        Path data = plant(dir);
        JobRun run = runOnce(dir, data, Map.of("model", "ghost", "as_of", "2026-06-30"));
        assertEquals("FAILED", run.status());
        assertTrue(run.message().contains("unknown segmentation-model 'ghost'"), run.message());
    }

    @Test
    void morePopulationThanTheCapFailsWithoutWritingASubset(@TempDir Path dir) throws Exception {
        Path data = plant(dir);
        System.setProperty(SegmentEvaluator.MAX_ENTITIES_PROPERTY, "5");
        JobRun run = runOnce(dir, data, Map.of("model", RfmCorpus.MODEL, "as_of", "2026-06-30"));
        assertEquals("FAILED", run.status());
        assertTrue(run.message().contains("names more than 5 entities - refusing to segment a subset"), run.message());
        assertEquals(0, files(data.resolve("customer_segments_rfm_latest")), "nothing written");
        System.setProperty(SegmentEvaluator.MAX_ENTITIES_PROPERTY, "6");
        assertEquals("SUCCESS", runOnce(dir, data, Map.of("model", RfmCorpus.MODEL, "as_of", "2026-06-30")).status(),
                "exactly at the cap is allowed");
    }

    @Test
    void anOutputDirectoryTheModelDoesNotOwnFailsTheRun(@TempDir Path dir) throws Exception {
        Path data = plant(dir);
        Path foreign = Files.createDirectories(data.resolve("customer_segments_rfm_latest"));
        Files.writeString(foreign.resolve("data.parquet"), "not ours");
        JobRun run = runOnce(dir, data, Map.of("model", RfmCorpus.MODEL, "as_of", "2026-06-30"));
        assertEquals("FAILED", run.status(), run.message());
        assertEquals("not ours", Files.readString(foreign.resolve("data.parquet")), "the foreign store is untouched");
    }

    @Test
    void asOfDefaultsToYesterdayAndRefusesAMalformedDate() {
        java.time.Instant now = java.time.Instant.parse("2026-07-01T05:00:00Z");
        assertEquals(java.time.LocalDate.parse("2026-06-30"), SegmentJobType.asOf(null, now));
        assertEquals(java.time.LocalDate.parse("2026-01-02"), SegmentJobType.asOf(" 2026-01-02 ", now));
        assertThrows(IllegalArgumentException.class, () -> SegmentJobType.asOf("30/06/2026", now));
    }
}
