package com.gamma.predictive;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.job.JobConfig;
import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.gamma.pipeline.ComponentStore;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code predict.forecast} Job through a real {@link JobService} over the synthetic {@link ForecastCorpus}: the
 * history and {@code _latest} outputs with their ownership markers, the golden forecasts of both methods at day and
 * week grain, the backtest error persisted on every row, empty buckets read as 0, the insufficient series (NULL
 * forecast, never 0), rows that recompute from their own explanation, and the fail-closed paths (unknown model,
 * series cap, a stored horizon past 90).
 */
class ForecastJobTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void clear() {
        System.clearProperty("assist.write.root");
        System.clearProperty("data.dir");
        System.clearProperty(ForecastEvaluator.MAX_SERIES_PROPERTY);
    }

    private static JobRun runOnce(Path dir, Path data, Map<String, String> params) throws Exception {
        JobConfig job = new JobConfig("forecast", "predict.forecast", null, null, true, false, params, null, null);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString(), null, null, data.toString())) {
            js.start();
            String id = js.triggerRun("forecast", null, Map.of()).orElseThrow();
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (System.nanoTime() < deadline) {
                JobRun r = js.lastRunOf("forecast").orElse(null);
                if (r != null && id.equals(r.runId()) && !"RUNNING".equals(r.status())) return r;
                Thread.sleep(50);
            }
        }
        fail("no finished run within 60s");
        return null;
    }

    private static Path plant(Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        ForecastCorpus.plant(cfg, data);
        System.setProperty("assist.write.root", cfg.toString());
        System.setProperty("data.dir", data.toString());
        return data;
    }

    private static Map<String, String> params(String model) {
        return Map.of("model", model, "as_of", ForecastCorpus.AS_OF.toString());
    }

    private static List<Map<String, Object>> rows(Path d) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        String glob = d.toString().replace('\\', '/') + "/*.parquet";
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM read_parquet('" + glob + "') ORDER BY run_id, entity_key, step")) {
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

    private static double num(Object o) { return ((Number) o).doubleValue(); }

    private static Map<String, Object> explanation(Map<String, Object> row) throws Exception {
        return JSON.readValue((String) row.get("explanation"), new TypeReference<>() {});
    }

    private static void assertRecomputes(Map<String, Object> r) throws Exception {
        Forecaster.Recomputed again = Forecaster.recompute((Integer) r.get("step"), explanation(r));
        assertEquals(num(r.get("forecast")), again.forecast(), 1e-9);
        assertEquals(num(r.get("lower")), again.lower(), 1e-9);
        assertEquals(num(r.get("upper")), again.upper(), 1e-9);
    }

    @Test
    void holtWintersWritesHistoryAndLatestWithTheGoldenForecasts(@TempDir Path dir) throws Exception {
        Path data = plant(dir);
        assertEquals("SUCCESS", runOnce(dir, data, params("demand")).status());
        JobRun run = runOnce(dir, data, params("demand"));
        assertEquals("SUCCESS", run.status(), run.message());
        assertTrue(run.message().contains("3 series forecast 10 day(s) ahead as of 2026-06-30, 1 insufficient"),
                run.message());
        Path history = data.resolve("forecasts_demand"), latest = data.resolve("forecasts_demand_latest");
        assertEquals("demand", Files.readString(history.resolve(ForecastModel.OWNER_MARKER)));
        assertEquals("demand", Files.readString(latest.resolve(ForecastModel.OWNER_MARKER)));
        assertEquals(2, files(history), "history: one file per run");
        assertEquals(1, files(latest), "latest: this run only");

        List<Map<String, Object>> rows = rows(latest);
        assertEquals(List.of("model", "entity_type", "entity_key", "as_of", "status", "step", "bucket", "forecast",
                "lower", "upper", "backtest_mae", "backtest_smape", "backtest_rel_mae", "history_buckets",
                "explanation", "model_version", "run_id", "scored_at"), List.copyOf(rows.get(0).keySet()));
        assertEquals(21, rows.size(), "gappy 10 + short 1 + trend 10; future (after as_of) and the NULL key have none");
        String version = ForecastEvaluator.version(new ComponentStore(dir.resolve("config/registry"))
                .get(ForecastModel.KIND, "demand").orElseThrow().content());
        for (Map<String, Object> r : rows) {
            assertEquals(version, r.get("model_version"));
            assertTrue(String.valueOf(r.get("as_of")).startsWith("2026-06-30"), String.valueOf(r.get("as_of")));
            String key = (String) r.get("entity_key");
            if ("short".equals(key)) {
                assertEquals(ForecastModel.INSUFFICIENT, r.get("status"));
                assertNull(r.get("forecast"), "insufficient history: NULL, never 0");
                assertNull(r.get("step"));
                assertNull(r.get("backtest_mae"));
                assertEquals(10, r.get("history_buckets"));
                assertEquals("history", explanation(r).get("insufficient"));
                continue;
            }
            int h = (Integer) r.get("step");
            assertEquals("ok", r.get("status"));
            assertEquals(28, r.get("history_buckets"), key + ": the 2026-05-01 and 2026-06-02 rows are outside the 28-day history");
            assertTrue(String.valueOf(r.get("bucket")).startsWith(ForecastCorpus.AS_OF.plusDays(h).toString()),
                    String.valueOf(r.get("bucket")));
            double expected = "trend".equals(key) ? ForecastCorpus.trend(27 + h) : ((27 + h) % 7 == 0 || (27 + h) % 7 == 3 ? 5 : 0);
            assertEquals(expected, num(r.get("forecast")), 1e-9, key + " step " + h);
            assertEquals(0.0, num(r.get("backtest_mae")), 1e-9, key + ": exact series, zero backtest error");
            assertEquals(num(r.get("forecast")), num(r.get("lower")), 1e-9);
            assertRecomputes(r);
            if ("trend".equals(key))
                assertEquals(0.0, num(r.get("backtest_rel_mae")), 1e-9, "seasonal-naive missed by 14, this by 0");
            else
                assertNull(r.get("backtest_rel_mae"), "empty buckets are 0, so gappy is an exact season for naive too");
        }
    }

    @Test
    void seasonalNaivePersistsTheHandComputedBacktestError(@TempDir Path dir) throws Exception {
        Path data = plant(dir);
        assertEquals("SUCCESS", runOnce(dir, data, params("naive")).status());
        List<Map<String, Object>> trend = rows(data.resolve("forecasts_naive_latest")).stream()
                .filter(r -> "trend".equals(r.get("entity_key"))).toList();
        assertEquals(7, trend.size());
        for (Map<String, Object> r : trend) {
            int h = (Integer) r.get("step");
            double f = ForecastCorpus.trend(20 + h);
            assertEquals(f, num(r.get("forecast")), 1e-9, "the last season repeated");
            assertEquals(14.0, num(r.get("backtest_mae")), 1e-12, "the trend over one season: 2 x 7");
            assertEquals(1.0, num(r.get("backtest_rel_mae")), 1e-12);
            double smape = 0;
            for (int j = 0; j < 7; j++)
                smape += 2 * 14 / (ForecastCorpus.trend(21 + j) + ForecastCorpus.trend(14 + j));
            assertEquals(100 * smape / 7, num(r.get("backtest_smape")), 1e-9);
            assertEquals(f - 1.2815515655446004 * 14, num(r.get("lower")), 1e-9, "80 % interval, RMSE 14");
            assertEquals(f + 1.2815515655446004 * 14, num(r.get("upper")), 1e-9);
            assertRecomputes(r);
        }
    }

    @Test
    void weekGrainForecastsCompleteIsoWeeksOnly(@TempDir Path dir) throws Exception {
        Path data = plant(dir);
        JobRun run = runOnce(dir, data, params("weekly"));
        assertEquals("SUCCESS", run.status(), run.message());
        List<Map<String, Object>> rows = rows(data.resolve("forecasts_weekly_latest"));
        assertEquals(4, rows.size());
        for (Map<String, Object> r : rows) {
            int h = (Integer) r.get("step");
            assertEquals(104, r.get("history_buckets"), "the incomplete week of as_of is not history");
            assertEquals(9.0 + h, num(r.get("forecast")), 1e-12, "slot h of the last season");
            LocalDate monday = LocalDate.parse("2026-06-22").plusWeeks(h);
            assertTrue(String.valueOf(r.get("bucket")).startsWith(monday.toString()), String.valueOf(r.get("bucket")));
            assertEquals(0.0, num(r.get("backtest_mae")), 0.0);
        }
    }

    @Test
    void theLastCompleteBucketIsTheDayOrTheLastFullIsoWeek() {
        ForecastModel week = ForecastModel.fromMap("weekly", ForecastCorpus.weekly());
        ForecastModel day = ForecastModel.fromMap("naive", ForecastCorpus.naive());
        assertEquals(LocalDate.parse("2026-06-30"), ForecastEvaluator.lastBucket(day, LocalDate.parse("2026-06-30")));
        assertEquals(LocalDate.parse("2026-06-22"), ForecastEvaluator.lastBucket(week, LocalDate.parse("2026-06-30")));
        assertEquals(LocalDate.parse("2026-06-22"), ForecastEvaluator.lastBucket(week, LocalDate.parse("2026-06-28")),
                "a Sunday closes its own week");
        assertEquals(LocalDate.parse("2026-06-22"), ForecastEvaluator.lastBucket(week, LocalDate.parse("2026-06-29")));
    }

    @Test
    void anUnknownModelFailsTheRun(@TempDir Path dir) throws Exception {
        Path data = plant(dir);
        JobRun run = runOnce(dir, data, Map.of("model", "ghost", "as_of", "2026-06-30"));
        assertEquals("FAILED", run.status());
        assertTrue(run.message().contains("unknown forecast-model 'ghost'"), run.message());
    }

    @Test
    void moreSeriesThanTheCapFailsWithoutWritingASubset(@TempDir Path dir) throws Exception {
        Path data = plant(dir);
        System.setProperty(ForecastEvaluator.MAX_SERIES_PROPERTY, "2");
        JobRun run = runOnce(dir, data, params("demand"));
        assertEquals("FAILED", run.status());
        assertTrue(run.message().contains("names more than 2 series - refusing to forecast a subset"), run.message());
        assertEquals(0, files(data.resolve("forecasts_demand_latest")), "nothing written");
        System.setProperty(ForecastEvaluator.MAX_SERIES_PROPERTY, "3");
        assertEquals("SUCCESS", runOnce(dir, data, params("demand")).status(), "exactly at the cap is allowed");
    }

    /** Dataset {@code full}: series a, b, c with qty 1 on every day of the 28-day history, {@code nulls} with qty NULL. */
    private static void plantFull(Path dir) throws Exception {
        Path file = Files.createDirectories(dir.resolve("data/full")).resolve("data.parquet");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT k AS sku, CAST(DATE '2026-06-03' + CAST(t AS INTEGER) + INTERVAL 12 HOUR AS TIMESTAMP) "
                    + "AS sold_at, CASE WHEN k = 'nulls' THEN CAST(NULL AS DOUBLE) ELSE 1.0 END AS qty "
                    + "FROM range(28) r(t), (VALUES ('a'), ('b'), ('c'), ('nulls')) v(k)) TO '"
                    + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        ComponentStore store = new ComponentStore(dir.resolve("config/registry"));
        store.write("dataset", "full", Map.of("physicalRef", "full"));
        Map<String, Object> m = new LinkedHashMap<>(ForecastCorpus.naive());
        m.put("dataset", "full");
        store.write(ForecastModel.KIND, "full", m);
    }

    @Test
    void aSumThatIsNullOnEveryRowIsASeriesOfZeros(@TempDir Path dir) throws Exception {
        Path data = plant(dir);
        plantFull(dir);
        JobRun run = runOnce(dir, data, params("full"));
        assertEquals("SUCCESS", run.status(), run.message());
        List<Map<String, Object>> nulls = rows(data.resolve("forecasts_full_latest")).stream()
                .filter(r -> "nulls".equals(r.get("entity_key"))).toList();
        assertEquals(7, nulls.size());
        for (Map<String, Object> r : nulls) {
            assertEquals(28, r.get("history_buckets"));
            assertEquals(0.0, num(r.get("forecast")), 0.0);
        }
    }

    @Test
    void theSeriesCapHoldsWhenEverySeriesHasItsFullHistory(@TempDir Path dir) throws Exception {
        Path data = plant(dir);
        plantFull(dir);
        System.setProperty(ForecastEvaluator.MAX_SERIES_PROPERTY, "3");
        JobRun run = runOnce(dir, data, params("full"));
        assertEquals("FAILED", run.status(), "4 series of 28 buckets each, cap 3");
        assertTrue(run.message().contains("names more than 3 series"), run.message());
        assertEquals(0, files(data.resolve("forecasts_full_latest")), "nothing written");
        System.setProperty(ForecastEvaluator.MAX_SERIES_PROPERTY, "4");
        assertEquals("SUCCESS", runOnce(dir, data, params("full")).status());
    }

    @Test
    void aStoredModelPastTheHorizonLimitFailsTheRun(@TempDir Path dir) throws Exception {
        Path data = plant(dir);
        Map<String, Object> tooFar = new LinkedHashMap<>(ForecastCorpus.naive());
        tooFar.put("horizon", 91);
        new ComponentStore(dir.resolve("config/registry")).write(ForecastModel.KIND, "far", tooFar);
        JobRun run = runOnce(dir, data, params("far"));
        assertEquals("FAILED", run.status());
        assertTrue(run.message().contains("horizon must be a whole number in 1..90"), run.message());
        assertEquals(0, files(data.resolve("forecasts_far_latest")), "nothing written");
    }

    @Test
    void anOutputDirectoryTheModelDoesNotOwnFailsTheRun(@TempDir Path dir) throws Exception {
        Path data = plant(dir);
        Path foreign = Files.createDirectories(data.resolve("forecasts_demand_latest"));
        Files.writeString(foreign.resolve("data.parquet"), "not ours");
        JobRun run = runOnce(dir, data, params("demand"));
        assertEquals("FAILED", run.status(), run.message());
        assertTrue(run.message().contains("not created by this model"), run.message());
        assertEquals("not ours", Files.readString(foreign.resolve("data.parquet")), "the foreign store is untouched");
    }
}
