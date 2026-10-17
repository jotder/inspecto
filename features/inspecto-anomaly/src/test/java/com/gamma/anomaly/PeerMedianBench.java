package com.gamma.anomaly;

import com.gamma.analytics.baseline.Baseline;
import com.gamma.analytics.baseline.BaselineStatistic;
import com.gamma.analytics.baseline.BaselineStatistics;
import com.gamma.analytics.baseline.PeerBaseline;
import com.gamma.analytics.baseline.RobustStats;
import com.gamma.analytics.baseline.Seasonality;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MEASUREMENT harness, not a test (ANOMALY-DETECTION-RESIDUALS-1 (e), D-AD9 "full recompute until measured slow"):
 * the JVM-side peer medians of {@code anomaly.score}. Never runs in the default suite: it needs
 * {@code -Dinspecto.bench=true}. Example (see the build-verify skill for the module exclusions):
 * {@code mvn -o test -Pedition-advanced -pl features/inspecto-anomaly -am -Dtest=PeerMedianBench
 * -Dinspecto.bench=true -Dsurefire.failIfNoSpecifiedTests=false} (optional {@code -Dinspecto.bench.out=<file>},
 * {@code -Dinspecto.bench.timeoutSec=60}, {@code -Dinspecto.bench.sizes=10000,200000,1000000}).
 *
 * <p>Three parts. (1) {@code RobustStats.median + scaledMad} on n values. (2) A grid over entity count N x cohort
 * shape of one Feature's peer side for a whole run: the real package-private
 * {@code AnomalyScoreEvaluator.PeerSide} (with {@link PeerBaseline}, {@link RobustStats} and
 * {@link BaselineStatistics}), driven as pass 2 drives it — once per entity, cached per cohort — next to the self
 * baseline pass of the same entities for scale. 28-day window, one row a day, deterministic values. (3) The real
 * {@link AnomalyScoreEvaluator#evaluate} end to end (DuckDB + JVM, two Features) with and without {@code peers}, to
 * confirm (2) on the shipped code ({@code -Dinspecto.bench.e2eSizes=20000,100000}). ⚠ At the default sandbox cap
 * ({@code assist.sql.memory_limit} 1GB) the per-Feature bucket statement already fails with a DuckDB out-of-memory
 * at 20 000 entities x 29 days; pass {@code -Dassist.sql.memory_limit=8GB} to measure past it.
 *
 * <p>Method per cell: one warm-up (discarded), then the median of 3; a warm-up over 20 s is reported alone ("1 cold
 * run"). Each run is on a daemon thread under a wall-clock guard; on a timeout the cohorts finished so far give a
 * linear extrapolation (marked "~", "timeout") and larger N of that shape are skipped. Allocation is the worker
 * thread's allocated bytes ({@code com.sun.management.ThreadMXBean}) — memory pressure, not peak heap.
 */
@Tag("bench")
@EnabledIfSystemProperty(named = "inspecto.bench", matches = "true")
class PeerMedianBench {

    private static final long TIMEOUT_MS = Long.getLong("inspecto.bench.timeoutSec", 60L) * 1000L;
    private static final long COLD_ONLY_MS = 20_000L;
    private static final int WINDOW = 28;
    private static final LocalDateTime SCORED = LocalDate.parse("2026-09-30").atStartOfDay();
    private static final StringBuilder OUT = new StringBuilder();

    private record Shape(String name, int cohortSize, int minGroupSize, boolean fallback) {}

    private record Result(double ms, double allocMb, String note) {
        String cell() {
            return (note.startsWith("~") ? note : "%.1f ms".formatted(ms) + (note.isEmpty() ? "" : " (" + note + ")"))
                    + (Double.isNaN(allocMb) ? "" : " / %.0f MB".formatted(allocMb));
        }
    }

    @Test
    void measure(@TempDir Path dir) throws Exception {
        robustStats();
        grid();
        endToEnd(dir);
        String out = System.getProperty("inspecto.bench.out");
        if (out != null) Files.writeString(Path.of(out), OUT);
        System.out.println(OUT);
    }

    // ---- (1) median / MAD --------------------------------------------------------------------------------------

    private static void robustStats() throws Exception {
        line("## RobustStats.median + scaledMad (sort-based), per call\n\n| n | time | alloc |\n|---|---|---|");
        for (int n : new int[] {10, 1_000, 100_000, 1_000_000}) {
            List<Double> v = new ArrayList<>(n);
            for (int i = 0; i < n; i++) v.add(value(i, 0, i % 50));
            int reps = Math.max(1, 2_000_000 / n);
            Result r = time(() -> {
                double s = 0;
                for (int k = 0; k < reps; k++) {
                    double m = RobustStats.median(v);
                    s += RobustStats.scaledMad(v, m);
                }
                return s;
            }, new AtomicLong(), 1);
            line("| %,d | %.4f ms | %.2f MB |".formatted(n, r.ms / reps, r.allocMb / reps));
        }
    }

    // ---- (2) the peer side of a run, mirrored ------------------------------------------------------------------

    private static void grid() throws Exception {
        int[] sizes = Arrays.stream(System.getProperty("inspecto.bench.sizes", "10000,200000,1000000").split(","))
                .mapToInt(s -> Integer.parseInt(s.trim())).toArray();
        List<Shape> shapes = List.of(
                new Shape("cohorts of 10, minGroupSize 30 (all insufficient)", 10, 30, false),
                new Shape("cohorts of 10, minGroupSize 10 (all sufficient)", 10, 10, false),
                new Shape("cohorts of 10, minGroupSize 30, fallback: population", 10, 30, true),
                new Shape("cohorts of 100", 100, 30, false),
                new Shape("cohorts of 1 000", 1_000, 30, false),
                new Shape("cohorts of 100 000", 100_000, 30, false),
                new Shape("one cohort of N (worst single cohort)", Integer.MAX_VALUE, 30, false));
        line("\n## Peer side of one Feature for a whole run (the real AnomalyScoreEvaluator.PeerSide), "
                + WINDOW + "-day window\n");
        StringBuilder head = new StringBuilder("| shape |"), rule = new StringBuilder("|---|");
        for (int n : sizes) { head.append(" N = %,d |".formatted(n)); rule.append("---|"); }
        line(head.toString());
        line(rule.toString());
        StringBuilder self = new StringBuilder("| (reference) self-baseline pass, same entities |");
        Map<Shape, StringBuilder> rows = new LinkedHashMap<>();
        Map<Shape, Boolean> skip = new LinkedHashMap<>();
        for (Shape s : shapes) { rows.put(s, new StringBuilder("| " + s.name + " |")); skip.put(s, false); }
        for (int n : sizes) {
            Corpus c = Corpus.of(n);
            BaselineStatistic statistic = BaselineStatistics.forModel(Seasonality.NONE, 7);
            self.append(' ').append(time(() -> {
                int insufficient = 0;
                for (Map<String, Map<LocalDateTime, Double>> pf : c.histories.values())
                    if (statistic.compute(pf.get("f"), SCORED).insufficient()) insufficient++;
                return insufficient;
            }, new AtomicLong(), 1).cell()).append(" |");
            for (Shape s : shapes) {
                StringBuilder row = rows.get(s);
                if (s.cohortSize != Integer.MAX_VALUE && s.cohortSize >= n || skip.get(s)) { row.append(" - |"); continue; }
                Map<String, String> cohortOf = c.cohorts(s.cohortSize);
                long cohorts = cohortOf.values().stream().distinct().count();
                AtomicLong progress = new AtomicLong();
                Result r = time(() -> {
                    AnomalyScoreEvaluator.PeerSide ps = new AnomalyScoreEvaluator.PeerSide(
                            new PeerBaseline(s.minGroupSize, s.fallback),
                            new AnomalyScoreEvaluator.Cohorts(cohortOf, labelsOf(cohortOf)),
                            c.histories, c.observedByFeature, statistic, SCORED);
                    AnomalyModel.Feature f = new AnomalyModel.Feature("f", "f", "d", "k", "t", "count", List.of(), null,
                            1.0, 1.0);
                    double sum = 0;
                    for (String e : c.histories.keySet()) sum += ps.of(f, e).shift();
                    return sum;
                }, progress, cohorts);
                if (r.note.contains("timeout")) skip.put(s, true);
                row.append(' ').append(r.cell()).append(" |");
            }
        }
        line(self.toString());
        rows.values().forEach(r -> line(r.toString()));
    }

    /** The per-run inputs exactly as the evaluator's pass 1 leaves them (one Feature "f"). */
    private record Corpus(int n, Map<String, Map<String, Map<LocalDateTime, Double>>> histories,
                          Map<String, Map<String, Double>> observedByFeature) {
        static Corpus of(int n) {
            LocalDateTime[] days = new LocalDateTime[WINDOW];
            for (int d = 0; d < WINDOW; d++) days[d] = SCORED.minusDays(WINDOW - d);
            Map<String, Map<String, Map<LocalDateTime, Double>>> histories = new TreeMap<>();
            Map<String, Double> observed = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                String key = key(i);
                Map<LocalDateTime, Double> h = new TreeMap<>();
                for (int d = 0; d < WINDOW; d++) h.put(days[d], value(i, d, i % 50));
                Map<String, Map<LocalDateTime, Double>> pf = new LinkedHashMap<>();
                pf.put("f", h);
                histories.put(key, pf);
                observed.put(key, value(i, WINDOW, i % 50));
            }
            Map<String, Map<String, Double>> obf = new LinkedHashMap<>();
            obf.put("f", observed);
            return new Corpus(n, histories, obf);
        }

        Map<String, String> cohorts(int size) {
            Map<String, String> out = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) out.put(key(i), size == Integer.MAX_VALUE ? "ALL" : "c" + (i / size));
            return out;
        }
    }

    private static Map<String, String> labelsOf(Map<String, String> cohortOf) {
        Map<String, String> out = new LinkedHashMap<>();
        cohortOf.values().forEach(c -> out.put(c, c));
        return out;
    }

    private static String key(int i) {
        String s = Integer.toString(i);
        return "e" + "0000000".substring(Math.min(7, s.length())) + s;
    }

    /** Deterministic: a per-entity level plus Weyl-style noise in [-20, 20]. */
    private static double value(int i, int d, int level) {
        return 100 + 10 * level + Math.floorMod(7919L * i + 104729L * d, 41) - 20;
    }

    // ---- (3) the real evaluator end to end ---------------------------------------------------------------------

    private static void endToEnd(Path dir) throws Exception {
        DuckDbUtil.loadDriver();
        line("\n## Real AnomalyScoreEvaluator.evaluate, two Features, end to end (DuckDB + JVM)\n");
        line("| N | no peers | one cohort of N | cohorts of 1 000 | cohorts of 10, minGroupSize 10 |");
        line("|---|---|---|---|---|");
        for (int n : Arrays.stream(System.getProperty("inspecto.bench.e2eSizes", "20000,100000").split(","))
                .mapToInt(s -> Integer.parseInt(s.trim())).toArray()) {
            Path cfg = dir.resolve("cfg" + n), data = dir.resolve("data" + n);
            ComponentStore store = new ComponentStore(cfg.resolve("registry"));
            store.write("dataset", "usage", Map.of("physicalRef", "usage"));
            store.write("dataset", "subscribers", Map.of("physicalRef", "subscribers"));
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
                st.execute("COPY (SELECT printf('e%07d', i) AS msisdn, TIMESTAMP '2026-09-02 10:00:00' + to_days(CAST(d AS INTEGER))"
                        + " AS event_time, CAST(100 + 10 * (i % 50) + ((7919 * i + 104729 * d) % 41) - 20 AS DOUBLE) AS mb"
                        + " FROM range(0, " + n + ") t(i), range(0, 29) s(d)) TO '" + file(data, "usage") + "' (FORMAT PARQUET)");
                st.execute("COPY (SELECT printf('e%07d', i) AS msisdn, 'ALL' AS plan_all, CAST(i // 1000 AS VARCHAR) AS plan_1k,"
                        + " CAST(i // 10 AS VARCHAR) AS plan_10 FROM range(0, " + n + ") t(i)) TO '"
                        + file(data, "subscribers") + "' (FORMAT PARQUET)");
            }
            ViewStore views = new ViewStore(cfg.resolve("views"));
            StringBuilder row = new StringBuilder("| %,d |".formatted(n));
            for (String[] p : new String[][] {null, {"plan_all", "30"}, {"plan_1k", "30"}, {"plan_10", "10"}}) {
                Map<String, Object> m = new LinkedHashMap<>(AnomalyCorpus.model());
                if (p != null) {
                    Map<String, Object> peers = new LinkedHashMap<>();
                    peers.put("by", List.of(p[0]));
                    peers.put("dataset", "subscribers");
                    peers.put("minGroupSize", Integer.parseInt(p[1]));
                    m.put("peers", peers);
                }
                AnomalyModel model = AnomalyModel.fromMap("bench", m);
                try {
                    Result r = time(() -> AnomalyScoreEvaluator.evaluate(model, LocalDate.parse("2026-10-01"), id ->
                            DatasetRelation.relationSql(store.get("dataset", id).orElseThrow().content(), data, views))
                            .scored().size(), new AtomicLong(), 0);
                    row.append(' ').append(r.cell()).append(" |");
                } catch (java.util.concurrent.ExecutionException e) {
                    row.append(" failed: ").append(e.getCause().getMessage()).append(" |");
                }
            }
            line(row.toString());
        }
    }

    private static String file(Path dataDir, String name) throws Exception {
        Path d = dataDir.resolve(name);
        Files.createDirectories(d);
        return d.resolve("data.parquet").toString().replace('\\', '/');
    }

    // ---- timing ------------------------------------------------------------------------------------------------

    /** Warm-up + median of 3 under the guard; {@code total} > 0 enables the progress extrapolation on a timeout. */
    private static Result time(Callable<Object> task, AtomicLong progress, long total) throws Exception {
        double[] ms = new double[3];
        double[] alloc = new double[3];
        for (int run = -1; run < 3; run++) {
            progress.set(0);
            double[] one = once(task);
            if (one == null) {
                long done = progress.get();
                String est = total > 1 && done > 0
                        ? "~%.0f s extrapolated (%,d of %,d cohorts in %d s, timeout)".formatted(
                                TIMEOUT_MS / 1000.0 * total / done, done, total, TIMEOUT_MS / 1000)
                        : "~> %d s (timeout)".formatted(TIMEOUT_MS / 1000);
                return new Result(Double.NaN, Double.NaN, est);
            }
            if (run == -1) {
                if (one[0] > COLD_ONLY_MS) return new Result(one[0], one[1], "1 cold run");
                continue;
            }
            ms[run] = one[0];
            alloc[run] = one[1];
        }
        int mid = order(ms);
        return new Result(ms[mid], alloc[mid], "");
    }

    private static int order(double[] ms) {
        Integer[] idx = {0, 1, 2};
        Arrays.sort(idx, (a, b) -> Double.compare(ms[a], ms[b]));
        return idx[1];
    }

    /** {ms, allocated MB}, or null on a timeout. */
    private static double[] once(Callable<Object> task) throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "peer-median-bench");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<double[]> f = ex.submit(() -> {
                com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
                long a0 = mx.getCurrentThreadAllocatedBytes();
                long t0 = System.nanoTime();
                Object sink = task.call();
                long t1 = System.nanoTime();
                long a1 = mx.getCurrentThreadAllocatedBytes();
                if (sink == null) throw new IllegalStateException("no result");
                return new double[] {(t1 - t0) / 1e6, (a1 - a0) / 1048576.0};
            });
            try {
                return f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                f.cancel(true);
                return null;
            }
        } finally {
            ex.shutdownNow();
        }
    }

    private static void line(String s) {
        OUT.append(s).append('\n');
        System.out.println(s);
    }
}
