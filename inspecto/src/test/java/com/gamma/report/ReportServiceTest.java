package com.gamma.report;

import com.gamma.enrich.EnrichmentConfig;
import com.gamma.enrich.EnrichmentConfig.Input;
import com.gamma.enrich.EnrichmentConfig.Output;
import com.gamma.enrich.EnrichmentConfig.Triggers;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import com.gamma.etl.StatusStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ReportService} — it must roll the raw {@code StatusStore} audit up
 * into a live status snapshot and a historical batch-audit report after a real run.
 */
class ReportServiceTest {

    private static Path seed(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Path inbox = dir.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"),
                "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n2,20,2020-01-01\n3,30,2020-02-05\n");
        return toon;
    }

    @Test
    void statusAndBatchReportsReflectARun(@TempDir Path dir) throws Exception {
        Path toon = seed(dir);
        try (CollectorService svc = new CollectorService(List.of(toon), 3600, 1)) {
            svc.runAllOnce();

            ReportService reports = svc.reports();

            ReportService.StatusReport status = reports.statusReport();
            assertEquals(1, status.pipelineCount());
            assertEquals(0, status.pausedCount());
            assertTrue(status.totalCommittedBatches() >= 1, "a batch committed");
            ReportService.PipelineStatus ps = status.pipelines().get(0);
            assertEquals("test_etl", ps.pipeline());
            assertTrue(ps.committedBatches() >= 1);
            assertEquals("SUCCESS", ps.lastBatchStatus(), "last batch outcome surfaced");

            ReportService.ConsignmentAuditReport br = reports.batchReport("test_etl");
            assertTrue(br.totalBatches() >= 1);
            assertEquals(br.totalBatches(), br.success(), "all batches succeeded");
            assertEquals(0, br.failed());
            assertEquals(0.0, br.errorRate());
            assertTrue(br.totalOutputRows() >= 3, "three input rows materialised");

            ReportService.ServiceReport sr = reports.serviceReport();
            assertEquals(1, sr.pipelines().size());
            assertTrue(sr.totalBatches() >= 1);
            assertEquals(sr.totalBatches(), sr.success());
            assertEquals(0.0, sr.errorRate());
        }
    }

    @Test
    void batchReportForUnknownPipelineThrows(@TempDir Path dir) throws Exception {
        Path toon = seed(dir);
        try (CollectorService svc = new CollectorService(List.of(toon), 3600, 1)) {
            assertThrows(IllegalArgumentException.class, () -> svc.reports().batchReport("ghost"));
        }
    }

    @Test
    void freshServiceReportsZeroedRollup(@TempDir Path dir) throws Exception {
        Path toon = seed(dir);
        try (CollectorService svc = new CollectorService(List.of(toon), 3600, 1)) {
            // before any run: pipeline registered, nothing committed
            ReportService.StatusReport status = svc.reports().statusReport();
            assertEquals(1, status.pipelineCount());
            assertEquals(0, status.totalCommittedBatches());
            ReportService.ConsignmentAuditReport br = svc.reports().batchReport("test_etl");
            assertEquals(0, br.totalBatches());
            assertEquals(0.0, br.errorRate());
        }
    }

    @Test
    void enrichmentReportRollsUpRunAudit(@TempDir Path dir) throws Exception {
        Path toon = seed(dir);
        Path reports = dir.resolve("reports");
        EnrichmentConfig enrich = new EnrichmentConfig("DAILY_KPI",
                new Input(dir.resolve("db").toString().replace("\\", "/"), "CSV", List.of("year", "month", "day")),
                List.of(),
                new Output(reports.toString().replace("\\", "/"), "CSV", null, List.of("year", "month", "day")),
                "SELECT year, month, day, COUNT(*) AS n FROM input GROUP BY year, month, day",
                new Triggers("test_etl", 0));
        try (CollectorService svc = new CollectorService(List.of(toon), List.of(enrich), 3600, 1)) {
            svc.start();   // immediate poll → Stage-1 commit → enrichment recompute
            // recompute runs asynchronously off the poll cycle — poll the rollup until it lands
            long deadline = System.nanoTime() + 15_000_000_000L;
            ReportService.EnrichmentRunReport r = svc.reports().enrichmentReport("DAILY_KPI");
            while (r.totalRuns() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(150);
                r = svc.reports().enrichmentReport("DAILY_KPI");
            }
            assertTrue(r.totalRuns() >= 1, "an enrichment run rolled up");
            assertEquals(r.totalRuns(), r.success(), "all runs succeeded");
            assertEquals(0, r.failed());
            assertEquals(0.0, r.errorRate());
            assertTrue(r.totalOutputRows() >= 1, "rows materialised");
            assertTrue(r.totalOutputFiles() >= 1, "output files written");
        }
    }

    @Test
    void enrichmentReportThrowsWhenNoEnrichmentOrUnknownJob(@TempDir Path dir) throws Exception {
        Path toon = seed(dir);
        try (CollectorService svc = new CollectorService(List.of(toon), 3600, 1)) {
            // no enrichment registered at all
            assertThrows(IllegalArgumentException.class, () -> svc.reports().enrichmentReport("DAILY_KPI"));
        }
    }

    // ── windowing + percentiles (v2.10.0) ─────────────────────────────────────────

    private static Map<String, String> batch(String start, long dur) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("batch_id", start);
        m.put("status", "SUCCESS");
        m.put("start_time", start);
        m.put("end_time", start);
        m.put("duration_ms", Long.toString(dur));
        m.put("total_input_rows", "10");
        m.put("total_output_rows", "10");
        return m;
    }

    /** A StatusStore that returns a fixed set of crafted batch rows for any pipeline. */
    private record FakeStore(List<Map<String, String>> batches) implements StatusStore {
        public Set<String> committedBatches(PipelineConfig cfg) { return Set.of(); }
        public List<Map<String, String>> batches(PipelineConfig cfg) { return batches; }
        public List<Map<String, String>> files(PipelineConfig cfg) { return List.of(); }
        public List<Map<String, String>> lineage(PipelineConfig cfg, String batchId) { return List.of(); }
        public List<Map<String, String>> quarantine(PipelineConfig cfg) { return List.of(); }
    }

    @Test
    void dateRangeScopesTheRollupAndPercentilesAreNearestRank(@TempDir Path dir) throws Exception {
        Path toon = seed(dir);
        // 5 May batches (100..500 ms) + 2 June batches (1000, 2000 ms)
        List<Map<String, String>> rows = List.of(
                batch("2026-05-10 09:00:00", 100), batch("2026-05-11 09:00:00", 200),
                batch("2026-05-12 09:00:00", 300), batch("2026-05-13 09:00:00", 400),
                batch("2026-05-14 09:00:00", 500),
                batch("2026-06-01 09:00:00", 1000), batch("2026-06-02 09:00:00", 2000));
        StatusStore store = new FakeStore(rows);
        try (CollectorService svc = new CollectorService(List.of(toon), List.of(), 3600, 1, store)) {
            // unbounded: all 7, nearest-rank percentiles over [100..500,1000,2000]
            ReportService.ConsignmentAuditReport all = svc.reports().batchReport("test_etl");
            assertEquals(7, all.totalBatches());
            assertEquals(400, all.p50DurationMs());
            assertEquals(2000, all.p95DurationMs());
            assertEquals(2000, all.p99DurationMs());
            assertEquals(2000, all.maxDurationMs());
            assertEquals("", all.windowFrom(), "unbounded echoes blank bounds");
            assertEquals("", all.windowTo());

            // May only: 5 batches; a date-only `to` covers the whole day
            ReportService.ConsignmentAuditReport may = svc.reports().batchReport(
                    "test_etl", ReportService.Window.of("2026-05-01", "2026-05-31"));
            assertEquals(5, may.totalBatches());
            assertEquals(500, may.maxDurationMs());
            assertEquals(300, may.p50DurationMs());   // ceil(.5*5)=3 → 300
            assertEquals(500, may.p95DurationMs());   // ceil(.95*5)=5 → 500
            assertEquals("2026-05-01", may.windowFrom());
            assertEquals("2026-05-31 23:59:59", may.windowTo(), "date-only upper bound widened to end-of-day");

            // open-ended lower bound: June onward
            ReportService.ConsignmentAuditReport june = svc.reports().batchReport(
                    "test_etl", ReportService.Window.of("2026-06-01", null));
            assertEquals(2, june.totalBatches());

            // window with no rows → zeroed, never throws
            ReportService.ConsignmentAuditReport none = svc.reports().batchReport(
                    "test_etl", ReportService.Window.of("2000-01-01", "2000-12-31"));
            assertEquals(0, none.totalBatches());
            assertEquals(0, none.p95DurationMs());

            // service-wide rollup carries the window echo + service-wide percentiles
            ReportService.ServiceReport svcMay = svc.reports().serviceReport(
                    ReportService.Window.of("2026-05-01", "2026-05-31"));
            assertEquals(5, svcMay.totalBatches());
            assertEquals(300, svcMay.p50DurationMs());
            assertEquals("2026-05-01", svcMay.windowFrom());
        }
    }

    // ── golden percentiles (ANALYTICS-CORE-1 M2): pinned BEFORE percentile moved to the Analytics Kernel ──────

    /** A StatusStore whose batch rows a test swaps between reports. */
    private static final class SwapStore implements StatusStore {
        List<Map<String, String>> rows = List.of();
        void durations(long... ms) {
            List<Map<String, String>> out = new java.util.ArrayList<>();
            for (int i = 0; i < ms.length; i++) out.add(batch(String.format("2026-05-10 %02d:%02d:%02d", i / 3600, i / 60 % 60, i % 60), ms[i]));
            rows = out;
        }
        public Set<String> committedBatches(PipelineConfig cfg) { return Set.of(); }
        public List<Map<String, String>> batches(PipelineConfig cfg) { return rows; }
        public List<Map<String, String>> files(PipelineConfig cfg) { return List.of(); }
        public List<Map<String, String>> lineage(PipelineConfig cfg, String batchId) { return List.of(); }
        public List<Map<String, String>> quarantine(PipelineConfig cfg) { return List.of(); }
    }

    private static long[] range(int n, long step) {
        long[] out = new long[n];
        for (int i = 0; i < n; i++) out[i] = (i + 1) * step;
        return out;
    }

    private static void assertPercentiles(CollectorService svc, SwapStore store, long p50, long p95, long p99, long... ms) {
        store.durations(ms);
        ReportService.ConsignmentAuditReport r = svc.reports().batchReport("test_etl");
        String tag = java.util.Arrays.toString(ms);
        assertEquals(p50, r.p50DurationMs(), "p50 of " + tag);
        assertEquals(p95, r.p95DurationMs(), "p95 of " + tag);
        assertEquals(p99, r.p99DurationMs(), "p99 of " + tag);
    }

    @Test
    void goldenNearestRankPercentilesOverSmallEvenTiedAndLongSeries(@TempDir Path dir) throws Exception {
        SwapStore store = new SwapStore();
        try (CollectorService svc = new CollectorService(List.of(seed(dir)), List.of(), 3600, 1, store)) {
            assertPercentiles(svc, store, 0, 0, 0);                                  // empty: 0, never throws
            assertPercentiles(svc, store, 42, 42, 42, 42);                           // n = 1
            assertPercentiles(svc, store, 10, 20, 20, 10, 20);                       // n = 2: no interpolation
            assertPercentiles(svc, store, 20, 40, 40, 40, 10, 30, 20);               // even, unsorted input
            assertPercentiles(svc, store, 5, 9, 9, 5, 5, 9, 5, 5);                   // ties
            assertPercentiles(svc, store, 2, 3, 3, 3, 1, 2);                         // odd
            assertPercentiles(svc, store, 50, 100, 100, range(10, 10));
            assertPercentiles(svc, store, 100, 190, 200, range(20, 10));             // 0.95 * 20 rounds to rank 19
            assertPercentiles(svc, store, 50, 95, 99, range(100, 1));
            assertPercentiles(svc, store, 51, 96, 100, range(101, 1));

            // 300 seeded sets against the pre-kernel rule, frozen here
            java.util.Random r = new java.util.Random(20261017);
            for (int k = 0; k < 300; k++) {
                long[] ms = new long[1 + r.nextInt(60)];
                for (int i = 0; i < ms.length; i++) ms[i] = r.nextInt(3) == 0 ? r.nextInt(4) * 100 : r.nextInt(100_000);
                assertPercentiles(svc, store, frozenPercentile(ms, 50), frozenPercentile(ms, 95), frozenPercentile(ms, 99), ms);
            }
        }
    }

    /** {@code ReportService.percentile} as shipped before ANALYTICS-CORE-1 M2. */
    private static long frozenPercentile(long[] values, double p) {
        if (values.length == 0) return 0L;
        List<Long> s = new java.util.ArrayList<>();
        for (long v : values) s.add(v);
        java.util.Collections.sort(s);
        int rank = (int) Math.ceil(p / 100.0 * s.size());
        int idx = Math.min(Math.max(rank, 1), s.size()) - 1;
        return s.get(idx);
    }

    @Test
    void windowContainsBoundariesAreInclusive() {
        ReportService.Window w = ReportService.Window.of("2026-05-01", "2026-05-31");
        assertTrue(w.bounded());
        assertTrue(w.contains("2026-05-01 00:00:00"), "lower bound inclusive");
        assertTrue(w.contains("2026-05-31 23:59:59"), "upper bound widened to end-of-day");
        assertFalse(w.contains("2026-04-30 23:59:59"), "before range");
        assertFalse(w.contains("2026-06-01 00:00:00"), "after range");
        assertFalse(w.contains(""), "undated row excluded when bounded");

        assertTrue(ReportService.Window.ALL.contains(""), "undated row included when unbounded");
        assertFalse(ReportService.Window.ALL.bounded());
    }
}
