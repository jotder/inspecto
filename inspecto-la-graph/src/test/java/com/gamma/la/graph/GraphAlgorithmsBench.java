package com.gamma.la.graph;

import com.gamma.la.graph.GraphAlgorithms.Direction;
import com.gamma.la.graph.GraphAlgorithms.Edge;
import com.gamma.la.graph.GraphAlgorithms.Graph;
import com.gamma.la.graph.GraphAlgorithms.Node;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * MEASUREMENT harness, not a test: sets the SYNC/JOB thresholds of {@code docs/superpower/la-separation-d4-design.md}
 * (§3 catalogue, §6 "JOB/SYNC split is an estimate"). Never runs in the default suite: it needs
 * {@code -Dinspecto.bench=true}. Example:
 * {@code mvn -o test -Pedition-enterprise -pl inspecto-la-graph -am -Dtest=GraphAlgorithmsBench
 * -Dinspecto.bench=true -Dsurefire.failIfNoSpecifiedTests=false} (optional {@code -Dinspecto.bench.out=<file>} for
 * the markdown table, {@code -Dinspecto.bench.timeoutSec=60}, {@code -Dinspecto.bench.only=kCore,linkPrediction} to
 * run just the algorithms whose name starts with one of the listed prefixes).
 *
 * <p>Corpus (same idea as {@code InvTraversalBench}): {@code edges = 5 · nodes}; source {@code = floor(n · u³)},
 * target {@code = floor(n · u²)} with {@code u} a seeded SplitMix64 hash of the edge number mapped to [0,1) — a
 * heavy-tailed (Zipf-like) degree distribution, mean degree 5 (out) and ~10 undirected, a few hubs at the low ids.
 * Self-loops are shifted to the next node; parallel edges are kept. Fully deterministic (seed constant).
 *
 * <p>Method per (algorithm x size): one warm-up run (discarded), then the median of 3 runs; if the warm-up alone
 * took more than 20 s the warm-up time is reported as the single measurement (marked "1 cold run"). Each run is on
 * a daemon thread under a wall-clock guard (default 60 s); a timeout is recorded and larger sizes of that
 * algorithm are skipped. A timed-out thread cannot be killed on the JDK and keeps burning a core until it ends
 * (it is a daemon, so it never blocks JVM exit) — later timings after a timeout are therefore slightly pessimistic.
 * Allocation is the worker thread's total allocated bytes for the median-closest run
 * ({@code com.sun.management.ThreadMXBean}) — an approximation of memory pressure, not peak heap.
 * 10^6 nodes runs only for the cheap linear algorithms whose 10^5 median was under 1 s.
 */
@Tag("bench")
@EnabledIfSystemProperty(named = "inspecto.bench", matches = "true")
class GraphAlgorithmsBench {

    private static final long SEED = 0x9E3779B97F4A7C15L;
    private static final int[] SIZES = {1_000, 10_000, 100_000, 1_000_000};
    private static final long TIMEOUT_MS = Long.getLong("inspecto.bench.timeoutSec", 60L) * 1000L;
    private static final long COLD_ONLY_MS = 20_000L;

    private record Algo(String name, String cls, boolean million, Function<Graph, Object> run) {}

    private record Result(double ms, double allocMb, String note) {}

    // ---------------------------------------------------------------- corpus

    private static long mix(long z) {
        z += SEED;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double unit(long i) {
        return (mix(i) >>> 11) * 0x1.0p-53;
    }

    static Graph graph(int n) {
        List<Node> nodes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) nodes.add(new Node("n" + i, "n" + i));
        long edges = 5L * n;
        List<Edge> es = new ArrayList<>((int) edges);
        for (long i = 0; i < edges; i++) {
            double u = unit(i), v = unit(i + 7_777_777_777L);
            int s = (int) Math.min(n - 1, Math.floor(n * u * u * u));
            int t = (int) Math.min(n - 1, Math.floor(n * v * v));
            if (s == t) t = (t + 1) % n;
            es.add(new Edge("e" + i, "n" + s, "n" + t));
        }
        return new Graph(nodes, es);
    }

    private static Map<String, Double> weights(Graph g) {
        Map<String, Double> w = new HashMap<>();
        for (Edge e : g.edges()) w.put(e.id(), 1.0 + (e.id().hashCode() & 3));
        return w;
    }

    // ---------------------------------------------------------------- catalogue

    private static List<Algo> catalogue() {
        List<Algo> a = new ArrayList<>();
        // SYNC reference (design doc §3)
        a.add(new Algo("degreeCentrality", "SYNC", true, GraphAlgorithms::degreeCentrality));
        a.add(new Algo("connectedComponents", "SYNC", true, GraphAlgorithms::connectedComponents));
        a.add(new Algo("shortestPath", "SYNC", true, g -> GraphAlgorithms.shortestPath(g, "n0", "n" + g.nodes().size() / 2, Direction.BOTH)));
        a.add(new Algo("neighborhood(3 hops)", "SYNC", true, g -> GraphAlgorithms.neighborhood(g, "n0", 3, Direction.BOTH)));
        a.add(new Algo("kCore", "SYNC", false, GraphAlgorithms::kCore));
        a.add(new Algo("triangleCount", "SYNC", false, GraphAlgorithms::triangleCount));
        a.add(new Algo("articulationPoints", "SYNC", false, GraphStructure::articulationPoints));
        a.add(new Algo("bridges", "SYNC", false, GraphStructure::bridges));
        a.add(new Algo("isForest", "SYNC", true, GraphStructure::isForest));
        a.add(new Algo("descendants", "SYNC", true, g -> GraphStructure.descendants(g, "n0")));
        a.add(new Algo("weightedShortestPath", "SYNC", false, g -> GraphPaths.weightedShortestPath(g, weights(g), "n0", "n" + g.nodes().size() / 2, Direction.BOTH)));
        a.add(new Algo("maximumSpanningForest", "SYNC", false, g -> GraphPaths.maximumSpanningForest(g, weights(g))));
        a.add(new Algo("jaccardSimilarity(n0)", "SYNC", true, g -> GraphCentrality.jaccardSimilarity(g, "n0")));
        a.add(new Algo("pageRank", "SYNC", true, GraphIterative::pageRank));
        // JOB (design doc §3)
        a.add(new Algo("betweennessCentrality", "JOB", false, GraphCentrality::betweennessCentrality));
        a.add(new Algo("closenessCentrality", "JOB", false, GraphCentrality::closenessCentrality));
        a.add(new Algo("suspicionScore", "JOB", false, GraphSuspicion::suspicionScore));
        a.add(new Algo("louvainCommunities", "JOB", false, GraphIterative::louvainCommunities));
        a.add(new Algo("detectCommunities", "JOB", false, GraphIterative::detectCommunities));
        a.add(new Algo("cliques", "JOB", false, GraphStructure::cliques));
        a.add(new Algo("findCycles", "JOB", false, GraphStructure::findCycles));
        a.add(new Algo("allPaths(limit 10, 8 hops)", "JOB", false, g -> GraphPaths.allPaths(g, "n0", "n" + g.nodes().size() / 2, 10, 8, Direction.BOTH)));
        a.add(new Algo("maxFlow", "JOB", false, g -> GraphPaths.maxFlow(g, weights(g), "n0", "n1")));
        a.add(new Algo("linkPrediction(CN, 20)", "JOB", false, g -> GraphCentrality.linkPrediction(g, GraphCentrality.Method.COMMON_NEIGHBORS, 20)));
        a.add(new Algo("eigenvectorCentrality", "JOB", false, GraphIterative::eigenvectorCentrality));
        a.add(new Algo("katzCentrality", "JOB", false, GraphIterative::katzCentrality));
        a.add(new Algo("hits", "JOB", false, GraphIterative::hits));
        return a;
    }

    // ---------------------------------------------------------------- measurement

    private static final com.sun.management.ThreadMXBean TMX =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private static volatile Object sink;

    private record Run(long ms, long allocBytes) {}

    /** One guarded run on a daemon thread; {@code null} on timeout. */
    private static Run guarded(Algo algo, Graph g) throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "bench-" + algo.name());
            t.setDaemon(true);
            return t;
        });
        try {
            Future<Run> f = ex.submit(() -> {
                long tid = Thread.currentThread().threadId();
                long a0 = TMX.getThreadAllocatedBytes(tid);
                long t0 = System.nanoTime();
                Object r = algo.run().apply(g);
                long ns = System.nanoTime() - t0;
                sink = r;
                return new Run(ns / 1_000_000L, TMX.getThreadAllocatedBytes(tid) - a0);
            });
            try {
                return f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                f.cancel(true);
                return null;
            } catch (ExecutionException ee) {
                if (ee.getCause() instanceof OutOfMemoryError) throw new OutOfMemoryError("oom");
                throw ee;
            }
        } finally {
            ex.shutdownNow();
        }
    }

    private static Result measure(Algo algo, Graph g) {
        try {
            Run warm = guarded(algo, g);
            if (warm == null) return new Result(-1, 0, "timeout >" + TIMEOUT_MS / 1000 + "s (warm-up)");
            if (warm.ms() > COLD_ONLY_MS) return new Result(warm.ms(), warm.allocBytes() / 1e6, "1 cold run");
            List<Run> runs = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Run r = guarded(algo, g);
                if (r == null) return new Result(-1, 0, "timeout >" + TIMEOUT_MS / 1000 + "s (run " + (i + 1) + ")");
                runs.add(r);
            }
            runs.sort((x, y) -> Long.compare(x.ms(), y.ms()));
            Run med = runs.get(1);
            return new Result(med.ms(), med.allocBytes() / 1e6, "");
        } catch (OutOfMemoryError oom) {
            return new Result(-2, 0, "OutOfMemoryError");
        } catch (Exception e) {
            return new Result(-3, 0, "error: " + e);
        }
    }

    private static String cell(Result r) {
        if (r == null) return "-";
        if (r.ms() == -1) return "timeout";
        if (r.ms() == -2) return "OOM";
        if (r.ms() == -3) return "error";
        return Long.toString(Math.round(r.ms()));
    }

    // ---------------------------------------------------------------- machine

    private static String machine() {
        String cpu = System.getenv("PROCESSOR_IDENTIFIER");
        if (cpu == null) cpu = "unknown";
        long ram = -1;
        try {
            ram = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
        } catch (Throwable ignored) { /* unknown */ }
        return "CPU " + cpu + " | cores " + Runtime.getRuntime().availableProcessors() + " | RAM "
                + (ram > 0 ? Math.round(ram / 1073741824.0) + " GB" : "unknown")
                + " | max heap " + Runtime.getRuntime().maxMemory() / 1048576 + " MB"
                + " | JDK " + System.getProperty("java.version") + " | OS " + System.getProperty("os.name") + " "
                + System.getProperty("os.version");
    }

    // ---------------------------------------------------------------- run

    @Test
    void bench() throws Exception {
        List<Algo> algos = catalogue();
        String only = System.getProperty("inspecto.bench.only"); // comma list of name prefixes; default = all
        if (only != null && !only.isBlank()) {
            List<String> want = List.of(only.split(","));
            algos = algos.stream().filter(a -> want.stream().anyMatch(w -> a.name().startsWith(w.trim()))).toList();
        }
        Map<String, Result[]> res = new java.util.LinkedHashMap<>();
        for (Algo a : algos) res.put(a.name(), new Result[SIZES.length]);

        for (int si = 0; si < SIZES.length; si++) {
            int n = SIZES[si];
            Graph g = graph(n);
            System.out.println("[bench] graph " + n + " nodes / " + g.edges().size() + " edges built");
            for (Algo a : algos) {
                Result[] row = res.get(a.name());
                if (si > 0) {
                    Result prev = row[si - 1];
                    if (prev == null || prev.ms() < 0) { row[si] = null; continue; }
                    if (si == 3 && (!a.million() || prev.ms() >= 1000)) { row[si] = null; continue; }
                }
                row[si] = measure(a, g);
                System.out.println("[bench] " + a.name() + " @" + n + " -> " + cell(row[si]) + " ms " + row[si].note());
            }
        }

        StringBuilder md = new StringBuilder();
        md.append("Machine: ").append(machine()).append("\n\n");
        md.append("Generator: heavy-tailed, edges = 5 x nodes, src = floor(n u^3), dst = floor(n u^2), seeded SplitMix64; ")
                .append("median of 3 after 1 warm-up; guard ").append(TIMEOUT_MS / 1000).append(" s; alloc = worker-thread allocated MB at 10^5 (or the largest size run).\n\n");
        md.append("| algorithm | class | 10^3 ms | 10^4 ms | 10^5 ms | 10^6 ms | notes |\n|---|---|---:|---:|---:|---:|---|\n");
        for (Algo a : algos) {
            Result[] r = res.get(a.name());
            List<String> notes = new ArrayList<>();
            Result last = null;
            for (int i = 0; i < SIZES.length; i++) {
                if (r[i] != null && r[i].ms() >= 0) last = r[i];
                if (r[i] != null && !r[i].note().isEmpty()) notes.add("10^" + (3 + i) + ": " + r[i].note());
                if (r[i] == null && i > 0 && i < 3 && r[i - 1] != null) notes.add("10^" + (3 + i) + ": skipped (previous size failed)");
            }
            if (last != null) notes.add(String.format("alloc %.0f MB at largest ok size", last.allocMb()));
            if (r[1] != null && r[0] != null && r[0].ms() > 5 && r[1].ms() > 0 && r[1].ms() / (double) r[0].ms() > 30)
                notes.add("super-linear 10^3->10^4 (x" + Math.round(r[1].ms() / (double) r[0].ms()) + ")");
            md.append("| ").append(a.name()).append(" | ").append(a.cls());
            for (Result x : r) md.append(" | ").append(cell(x));
            md.append(" | ").append(String.join("; ", notes)).append(" |\n");
        }
        System.out.println("\n" + md);
        String out = System.getProperty("inspecto.bench.out");
        if (out != null && !out.isBlank()) Files.writeString(Path.of(out), md.toString());
    }
}
