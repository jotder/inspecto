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

    /**
     * The fixture size: 30 days x 4,000 keys unless {@code RECON_PERF_DAYS} / {@code RECON_PERF_KEYS} (environment) or
     * {@code recon.perf.days} / {@code recon.perf.keys} (system property) say otherwise (RECON-UPLIFT-1 B0).
     * ⚠ Every test in this class reads them, so set them only when running {@link #scaleProbe} alone.
     */
    static final int DAYS = intSetting("RECON_PERF_DAYS", "recon.perf.days", 30);
    static final int KEYS = intSetting("RECON_PERF_KEYS", "recon.perf.keys", 4_000);

    private static int intSetting(String env, String prop, int dflt) {
        String v = System.getenv(env);
        if (v == null || v.isBlank()) v = System.getProperty(prop);
        return v == null || v.isBlank() ? dflt : Integer.parseInt(v.trim().replace("_", ""));
    }

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
    /**
     * RECON-UPLIFT-1 B0: the scale probe. One spec over {@code DAYS} x {@code KEYS} x 3 sides (set both, with
     * {@code RECON_PERF_DAYS=1}, to measure N key groups per day); prints one {@code RECON-SCALE} line per measure: the
     * whole-relation run, the day run at the production row cap, the record compute (capped as production, then with
     * the cap lifted to measure the cost the 422 protects), one status change on a 50k-Break state file, a
     * deep-offset Break page against offset 0, and the peak JVM heap and process working set.
     */
    @Test
    void scaleProbe(@TempDir Path dir) throws Exception {
        long g0 = System.nanoTime();
        for (String[] s : new String[][]{{"hlr", "1"}, {"crm", "2"}, {"cbs", "3"}}) generate(dir, s[0], Integer.parseInt(s[1]));
        System.out.println("RECON-SCALE fixture " + DAYS + " day(s) x " + KEYS + " keys x 3 sides generated in "
                + ms(g0) + " ms, maxHeap " + Runtime.getRuntime().maxMemory() / (1 << 20) + " MB");
        ReconService.Spec spec = spec(dir);
        for (java.lang.management.MemoryPoolMXBean p : java.lang.management.ManagementFactory.getMemoryPoolMXBeans())
            if (p.getType() == java.lang.management.MemoryType.HEAP) p.resetPeakUsage();

        long t0 = System.nanoTime();
        ReconService.RunResult run = ReconService.run(spec, 5_000);
        System.out.println("RECON-SCALE whole-relation run (limit 5000), cold: " + ms(t0) + " ms, groups "
                + run.summary().get("groups") + ", truncated " + run.truncated());
        long heapBefore = usedHeapMb();
        t0 = System.nanoTime();
        ReconService.DayPage dp = ReconService.dayRun(spec, 0, 0, 0, 50, 200_000);
        long dayMs = ms(t0);
        System.out.println("RECON-SCALE day run at the production row cap 200000: " + dayMs + " ms, materialised rows = "
                + (dp.day().rows() != null) + (dp.day().rows() == null ? ""
                : ", retained heap of the cached day = " + (usedHeapMb() - heapBefore) + " MB for " + dp.day().rows().size() + " rows"));

        t0 = System.nanoTime();
        String capped;
        try {
            capped = ReconBreaks.compute(spec, ReconStateStore.MAX_BREAKS).size() + " Breaks";
        } catch (IllegalArgumentException refused) {
            capped = "refused (422): " + refused.getMessage().substring(0, Math.min(70, refused.getMessage().length()));
        }
        System.out.println("RECON-SCALE record compute at the production cap " + ReconStateStore.MAX_BREAKS + ": " + ms(t0) + " ms, " + capped);
        t0 = System.nanoTime();
        List<ReconBreaks.Break> all = ReconBreaks.compute(spec, 20_000_000);
        System.out.println("RECON-SCALE record compute, cap lifted: " + ms(t0) + " ms, " + all.size() + " Breaks");
        all = null;
        // The same compute the way it ran before B1 (one ReconService.breaks scan set per pair), in this same process, so the
        // pair of numbers shares one machine load: the A/B that B1's gain is read from.
        t0 = System.nanoTime();
        long perType = 0;
        for (int other = 1; other < spec.sides().size(); other++)
            for (ReconService.BreakSet set : ReconService.breaks(spec, null, null, other, 20_000_000, 0).values()) perType += set.rowCount();
        System.out.println("RECON-SCALE record compute, per-type scans (the pre-B1 path), cap lifted: " + ms(t0) + " ms, " + perType + " Break rows");

        // One status change on a 50k-Break state file (the cap), after the cost of recording that file once.
        Path root = dir.resolve("state-root");
        Files.createDirectories(root);
        ReconStateStore store = new ReconStateStore(root);
        List<ReconBreaks.Break> fresh = new java.util.ArrayList<>(ReconStateStore.MAX_BREAKS);
        for (int i = 0; i < ReconStateStore.MAX_BREAKS; i++)
            fresh.add(ReconBreaks.Break.fresh("m" + i, null, "missing_right", null, null, null, null));
        t0 = System.nanoTime();
        store.record("perf", fresh, ReconStateStore.now(), "2026-08-28");
        long recordMs = ms(t0);
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            t0 = System.nanoTime();
            store.setStatus("perf", "2026-08-28", ReconBreaks.PAIR_AB, "missing_right", "m" + (25_000 + i), null, "resolved", "n", null);
            best = Math.min(best, ms(t0));
        }
        System.out.println("RECON-SCALE state file of " + ReconStateStore.MAX_BREAKS + " Breaks ("
                + Files.size(root.resolve("recon-state/perf.json")) / 1024 + " KB): record " + recordMs
                + " ms, one status change best of 3 = " + best + " ms");

        // A deep offset against offset 0, on the pair's Break sets.
        java.util.Map<?, ?> byType = (java.util.Map<?, ?>) run.summary().get("byType");
        for (String type : new String[]{"value_break", "missing_right"}) {
            long n = ((Number) byType.get(type)).longValue();
            int deep = (int) Math.max(0, n - 200);
            t0 = System.nanoTime();
            ReconService.breaks(spec, null, type, 1, 200, 0);
            long first = ms(t0);
            t0 = System.nanoTime();
            ReconService.breaks(spec, null, type, 1, 200, deep);
            System.out.println("RECON-SCALE " + type + " page (" + n + " in the set): offset 0 = " + first + " ms, offset " + deep
                    + " = " + ms(t0) + " ms");
        }

        long heapPeak = 0;
        for (java.lang.management.MemoryPoolMXBean p : java.lang.management.ManagementFactory.getMemoryPoolMXBeans())
            if (p.getType() == java.lang.management.MemoryType.HEAP) heapPeak += p.getPeakUsage().getUsed();
        System.out.println("RECON-SCALE peak JVM heap " + heapPeak / (1 << 20) + " MB, peak process working set "
                + peakWorkingSetMb() + " MB (heap + DuckDB native)");
    }

    /** Used heap in MB after a best-effort collection. */
    private static long usedHeapMb() {
        System.gc();
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) / (1 << 20);
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    /** Windows only, best effort: the JVM process's peak working set in MB, or -1. */
    private static long peakWorkingSetMb() {
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "(Get-Process -Id " + ProcessHandle.current().pid() + ").PeakWorkingSet64 / 1MB")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return Math.round(Double.parseDouble(out));
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * RECON-CACHE-1: the {@code /recon/run} request path over real {@code physicalRef} Datasets with a data root -
     * resolve the day, look the day up, slice a page - for the first page, the next page and page 1 again.
     */
    @Test
    void cachedPageRequestPath(@TempDir Path dir) throws Exception {
        for (String[] s : new String[][]{{"hlr", "1"}, {"crm", "2"}, {"cbs", "3"}}) generate(dir, s[0], Integer.parseInt(s[1]));
        java.util.Map<String, Object> cfg = java.util.Map.of("datasets", List.of("hlr", "crm", "cbs"), "keyColumns", List.of("msisdn"),
                "compareColumns", List.of(java.util.Map.of("column", "active_flag")));
        java.util.function.Function<String, java.util.Map<String, Object>> ds =
                id -> java.util.Map.of("physicalRef", id, "dateField", "event_date");
        java.util.function.Function<String, String> relOf = id -> com.gamma.query.DatasetRelation.relationSql(ds.apply(id), dir, null);
        ReconDay.Cache.clear();
        StringBuilder out = new StringBuilder("RECON-PERF request path:");
        for (int[] p : new int[][]{{0}, {50}, {0}}) {
            long t0 = System.nanoTime();
            ReconDay.Scoped scoped = ReconDay.resolve(cfg, ds, relOf, dir, null);
            String key = "grain|" + scoped.fingerprint() + "|" + scoped.day() + "|0";
            ReconService.DayResult day = ReconDay.Cache.get(key);
            if (day == null) {
                day = ReconService.dayRun(scoped.spec(), 0, 0, p[0], 50, 200_000).day();
                ReconDay.Cache.put(key, day);
            }
            day.page(0, p[0], 50);
            out.append(" offset ").append(p[0]).append(" = ").append((System.nanoTime() - t0) / 1_000).append(" us;");
        }
        System.out.println(out);
    }
}
