package com.gamma.recon;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

/**
 * Timing harness, not a gate (RECON-PERF-1, operator 2026-10-09): 30 days x 4,000 msisdns x 3 sides (HLR / CRM /
 * CBS), the ra_c01 subscriber-status shape. Runs only with {@code RECON_PERF=1} in the environment and prints the
 * timings; it asserts nothing about them.
 */
@EnabledIfEnvironmentVariable(named = "RECON_PERF", matches = "1")
class ReconPerfTest {

    static final int DAYS = 30, KEYS = 4_000;

    /** Writes side {@code name} as one parquet file per day under {@code <dir>/<name>/day=<d>/}; drops/flips keys by side. */
    static void generate(Path dir, String name, int salt) throws Exception {
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("recon_perf_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            for (int d = 0; d < DAYS; d++) {
                Path part = dir.resolve(name).resolve("day=" + d);
                Files.createDirectories(part);
                String f = part.resolve("data.parquet").toString().replace(File.separatorChar, '/');
                st.execute("COPY (SELECT 'm' || i AS msisdn, DATE '2026-08-28' + INTERVAL " + d + " DAY AS event_date,"
                        + " CAST(CASE WHEN (i * 7 + " + salt + " + " + d + ") % 97 = 0 THEN 0 ELSE 1 END AS INTEGER) AS active_flag,"
                        + " CAST(i % 300 AS DOUBLE) AS fee"
                        + " FROM range(" + KEYS + ") t(i) WHERE (i + " + salt + " * 13 + " + d + ") % 151 <> 0) TO '" + f
                        + "' (FORMAT PARQUET)");
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    static String rel(Path dir, String name) {
        return "SELECT * FROM read_parquet('" + dir.resolve(name).toString().replace(File.separatorChar, '/') + "/**/*.parquet')";
    }

    static ReconService.Spec spec(Path dir) {
        List<ReconService.Side> sides = List.of(
                new ReconService.Side("hlr", rel(dir, "hlr"), null, null),
                new ReconService.Side("crm", rel(dir, "crm"), null, null),
                new ReconService.Side("cbs", rel(dir, "cbs"), null, null));
        return ReconService.Spec.of(sides, List.of("msisdn"),
                List.of(new ReconService.Measure("active_flag", "sum", "exact", 0)), true);
    }

    @Test
    void wholeRelationRun(@TempDir Path dir) throws Exception {
        for (String[] s : new String[][]{{"hlr", "1"}, {"crm", "2"}, {"cbs", "3"}}) generate(dir, s[0], Integer.parseInt(s[1]));
        ReconService.Spec spec = spec(dir);
        ReconService.run(spec, 5_000);   // warm-up
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            ReconService.run(spec, 5_000);
            best = Math.min(best, (System.nanoTime() - t0) / 1_000_000);
        }
        System.out.println("RECON-PERF whole-relation run (30d x 4000 x 3, limit 5000): best of 3 = " + best + " ms");
        long t0 = System.nanoTime();
        int n = ReconBreaks.compute(spec, ReconStateStore.MAX_BREAKS).size();
        System.out.println("RECON-PERF whole-relation record compute: " + (System.nanoTime() - t0) / 1_000_000 + " ms, " + n + " breaks");

        // RECON-PERF-1: ONE day (the latest), first page of 50, then a further page off the computed day.
        java.util.Map<String, Object> cfg = java.util.Map.of("datasets", List.of("hlr", "crm", "cbs"), "keyColumns", List.of("msisdn"),
                "compareColumns", List.of(java.util.Map.of("column", "active_flag")));
        long t1 = System.nanoTime();
        ReconDay.Scoped scoped = ReconDay.resolve(cfg, id -> java.util.Map.of("dateField", "event_date"), id -> rel(dir, id), null, null);
        long resolveMs = (System.nanoTime() - t1) / 1_000_000;
        long bestDay = Long.MAX_VALUE;
        ReconService.DayResult day = null;
        for (int i = 0; i < 3; i++) {
            long t2 = System.nanoTime();
            day = ReconService.dayRun(scoped.spec(), 0, 0, 0, 50, 200_000).day();
            bestDay = Math.min(bestDay, (System.nanoTime() - t2) / 1_000_000);
        }
        long t3 = System.nanoTime();
        day.page(ReconService.filterMask("breaks", 3), 50, 50);
        long pageUs = (System.nanoTime() - t3) / 1_000;
        long t4 = System.nanoTime();
        int dayBreaks = ReconBreaks.compute(scoped.spec(), ReconStateStore.MAX_BREAKS).size();
        long recDay = (System.nanoTime() - t4) / 1_000_000;
        long t5 = System.nanoTime();
        ReconService.dayRun(scoped.spec(), 1_000, 0, 0, 50, 200_000);
        long sampled = (System.nanoTime() - t5) / 1_000_000;
        System.out.println("RECON-PERF day " + scoped.day() + " (" + scoped.availableDays().size() + " days): resolve " + resolveMs
                + " ms, first page best of 3 = " + bestDay + " ms, cached page = " + pageUs + " us, sample 1000 = " + sampled
                + " ms, record compute = " + recDay + " ms (" + dayBreaks + " breaks)");
    }
    /**
     * RECON-PERF-RESIDUALS-1 (1) + (4): the same fixture read through real {@code physicalRef} Datasets. Unpruned =
     * no data root (what every day read did before: all 30 files opened); pruned = the footer-statistics file pruning.
     * Breaks: the uncached compute every request paid before, a sampled compute, and the cached read-back.
     */
    @Test
    void dayPruningAndBreaks(@TempDir Path dir) throws Exception {
        for (String[] s : new String[][]{{"hlr", "1"}, {"crm", "2"}, {"cbs", "3"}}) generate(dir, s[0], Integer.parseInt(s[1]));
        java.util.Map<String, Object> cfg = java.util.Map.of("datasets", List.of("hlr", "crm", "cbs"), "keyColumns", List.of("msisdn"),
                "compareColumns", List.of(java.util.Map.of("column", "active_flag")));
        java.util.function.Function<String, java.util.Map<String, Object>> ds =
                id -> java.util.Map.of("physicalRef", id, "dateField", "event_date");
        java.util.function.Function<String, String> relOf = id -> com.gamma.query.DatasetRelation.relationSql(ds.apply(id), dir, null);
        for (boolean prune : new boolean[]{false, true}) {
            ReconDay.Cache.clear();
            ReconDay.Scoped scoped = ReconDay.resolve(cfg, ds, relOf, prune ? dir : null, null);
            ReconService.dayRun(scoped.spec(), 0, 0, 0, 50, 200_000);   // warm-up
            long best = Long.MAX_VALUE;
            for (int i = 0; i < 3; i++) {
                long t0 = System.nanoTime();
                ReconService.dayRun(scoped.spec(), 0, 0, 0, 50, 200_000);
                best = Math.min(best, (System.nanoTime() - t0) / 1_000_000);
            }
            long t1 = System.nanoTime();
            ReconDay.resolve(cfg, ds, relOf, prune ? dir : null, null);
            long resolveMs = (System.nanoTime() - t1) / 1_000_000;
            long bBest = Long.MAX_VALUE;
            for (int i = 0; i < 3; i++) {
                long t2 = System.nanoTime();
                ReconService.breaks(scoped.spec(), null, null, 1, 200, 0);
                bBest = Math.min(bBest, (System.nanoTime() - t2) / 1_000_000);
            }
            long t3 = System.nanoTime();
            ReconService.breaks(scoped.spec(), null, null, 1, 200, 0, 1_000, new java.util.HashMap<>());
            long sampled = (System.nanoTime() - t3) / 1_000_000;
            System.out.println("RECON-PERF " + (prune ? "PRUNED  " : "UNPRUNED") + " day " + scoped.day() + ": first page best of 3 = "
                    + best + " ms, re-resolve (days cached) = " + resolveMs + " ms, breaks compute best of 3 = " + bBest
                    + " ms, breaks sample 1000 = " + sampled + " ms");
        }
        String key = "breaks|probe";
        ReconDay.Cache.put(key, java.util.Map.of());
        long t4 = System.nanoTime();
        ReconDay.Cache.get(key);
        System.out.println("RECON-PERF cached breaks/rows read-back = " + (System.nanoTime() - t4) / 1_000 + " us");
    }
}
