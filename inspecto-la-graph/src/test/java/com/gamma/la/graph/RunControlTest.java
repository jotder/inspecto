package com.gamma.la.graph;

import com.gamma.la.graph.GraphAlgorithms.Edge;
import com.gamma.la.graph.GraphAlgorithms.Graph;
import com.gamma.la.graph.GraphAlgorithms.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-4 step 2 — every job-class algorithm honours {@link RunControl}: cancel mid-run, an expired deadline, a work
 * budget, visible monotone progress, and an unchanged result. Each long-running case is bounded by {@code @Timeout}
 * so a broken (deleted) checkpoint FAILS the build instead of hanging it.
 */
class RunControlTest {

    /** One algorithm under test: a run that takes seconds ({@code big}) and one that finishes at once ({@code small}). */
    record Case(String name, Function<RunControl, Object> big, Function<RunControl, Object> small) {
        @Override public String toString() { return name; }
    }

    // ---- deterministic graph generators --------------------------------------------------------------

    /** Preferential attachment (heavy-tailed degrees), seed 42; each new node links to {@code k} earlier ones. */
    static Graph heavyTailed(int n, int k) {
        Random r = new Random(42);
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            nodes.add(new Node("n" + i, "N" + i));
            if (i > 0) {
                for (int j = 0; j < k; j++) {
                    int t = ends.isEmpty() || r.nextInt(4) == 0 ? r.nextInt(i) : ends.get(r.nextInt(ends.size()));
                    edges.add(new Edge("e" + edges.size(), "n" + i, "n" + t));
                    ends.add(t);
                    ends.add(i);
                }
            }
        }
        return new Graph(nodes, edges);
    }

    /** {@link #heavyTailed} plus a hub n0 linked to every node: one degree-n vertex (O(d^2) work, O(n) per comparison). */
    static Graph star(int n) {
        Graph g = heavyTailed(n, 2);
        List<Edge> edges = new ArrayList<>(g.edges());
        for (int i = 1; i < n; i++) edges.add(new Edge("h" + i, "n0", "n" + i));
        return new Graph(g.nodes(), edges);
    }

    static Graph completeDigraph(int n) {
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        for (int i = 0; i < n; i++) nodes.add(new Node("n" + i, "N" + i));
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) if (i != j) edges.add(new Edge("e" + edges.size(), "n" + i, "n" + j));
        return new Graph(nodes, edges);
    }

    /** Moon-Moser: K_n minus a perfect matching has 3^(n/2) maximal cliques. */
    static Graph moonMoser(int n) {
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        for (int i = 0; i < n; i++) nodes.add(new Node("n" + i, "N" + i));
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) if (!(i % 2 == 0 && j == i + 1)) edges.add(new Edge("e" + edges.size(), "n" + i, "n" + j));
        return new Graph(nodes, edges);
    }

    /** source -> layer 0 ... layer L-1 -> sink, complete between consecutive layers of width W: unit-capacity flow W. */
    static Graph layered(int layers, int width) {
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        nodes.add(new Node("s", "s"));
        nodes.add(new Node("t", "t"));
        for (int l = 0; l < layers; l++) for (int w = 0; w < width; w++) nodes.add(new Node("v" + l + "_" + w, "v"));
        for (int w = 0; w < width; w++) {
            edges.add(new Edge("es" + w, "s", "v0_" + w));
            edges.add(new Edge("et" + w, "v" + (layers - 1) + "_" + w, "t"));
        }
        for (int l = 0; l + 1 < layers; l++)
            for (int a = 0; a < width; a++)
                for (int b = 0; b < width; b++) edges.add(new Edge("e" + l + "_" + a + "_" + b, "v" + l + "_" + a, "v" + (l + 1) + "_" + b));
        return new Graph(nodes, edges);
    }

    static final int FOREVER = 1_000_000;

    static Stream<Case> cases() {
        Graph ht300 = heavyTailed(300, 3);
        Graph k7 = completeDigraph(7);
        Graph layeredSmall = layered(6, 3);
        Graph ht6000 = heavyTailed(6000, 4);
        Graph ht2000 = heavyTailed(2000, 4);
        Graph ht300000 = heavyTailed(300_000, 3); // kCore is O(V + E) now: ~1 s needs 10^5-10^6 nodes
        Graph ht30000 = heavyTailed(30000, 3);
        Graph star20000 = star(20000);
        Graph star30000 = star(30000);
        Graph k12 = completeDigraph(12);
        Graph mm36 = moonMoser(36);
        Graph layeredBig = layered(500, 20);
        Graph ht3000 = heavyTailed(3000, 3);
        Graph ht40000 = heavyTailed(40_000, 3); // linkPrediction walks 2-hop pairs now: ~1.7 s
        return Stream.of(
                new Case("betweennessCentrality", c -> GraphCentrality.betweennessCentrality(ht6000, c), c -> GraphCentrality.betweennessCentrality(ht300, c)),
                new Case("closenessCentrality", c -> GraphCentrality.closenessCentrality(ht6000, c), c -> GraphCentrality.closenessCentrality(ht300, c)),
                new Case("jaccardSimilarity", c -> GraphCentrality.jaccardSimilarity(star30000, "n0", c), c -> GraphCentrality.jaccardSimilarity(ht300, "n0", c)),
                new Case("linkPrediction", c -> GraphCentrality.linkPrediction(ht40000, GraphCentrality.Method.ADAMIC_ADAR, 10, c), c -> GraphCentrality.linkPrediction(ht300, GraphCentrality.Method.COMMON_NEIGHBORS, 10, c)),
                new Case("suspicionScore", c -> GraphSuspicion.suspicionScore(ht3000, GraphSuspicion.Weights.DEFAULT, c), c -> GraphSuspicion.suspicionScore(ht300, GraphSuspicion.Weights.DEFAULT, c)),
                new Case("pageRank", c -> GraphIterative.pageRank(ht2000, 0.85, FOREVER, c), c -> GraphIterative.pageRank(ht300, 0.85, 60, c)),
                new Case("eigenvectorCentrality", c -> GraphIterative.eigenvectorCentrality(ht2000, FOREVER, c), c -> GraphIterative.eigenvectorCentrality(ht300, 100, c)),
                new Case("katzCentrality", c -> GraphIterative.katzCentrality(ht2000, 0.1, 1, FOREVER, c), c -> GraphIterative.katzCentrality(ht300, 0.1, 1, 100, c)),
                new Case("hits", c -> GraphIterative.hits(ht2000, FOREVER, c), c -> GraphIterative.hits(ht300, 100, c)),
                new Case("detectCommunities", c -> GraphIterative.detectCommunities(ht30000, FOREVER, c), c -> GraphIterative.detectCommunities(ht300, 20, c)),
                new Case("louvainCommunities", c -> GraphIterative.louvainCommunities(ht30000, c), c -> GraphIterative.louvainCommunities(ht300, c)),
                new Case("cliques", c -> GraphStructure.cliques(mm36, 3, c), c -> GraphStructure.cliques(moonMoser(10), 3, c)),
                new Case("findCycles", c -> GraphStructure.findCycles(k12, 300_000, 12, c), c -> GraphStructure.findCycles(k7, 50, 8, c)),
                new Case("allPaths", c -> GraphPaths.allPaths(k12, "n0", "n11", 300_000, 11, GraphAlgorithms.Direction.OUT, c),
                        c -> GraphPaths.allPaths(k7, "n0", "n6", 50, 6, GraphAlgorithms.Direction.OUT, c)),
                new Case("maxFlow", c -> GraphPaths.maxFlow(layeredBig, Map.of(), "s", "t", c), c -> GraphPaths.maxFlow(layeredSmall, Map.of(), "s", "t", c)),
                new Case("kCore", c -> GraphAlgorithms.kCore(ht300000, c), c -> GraphAlgorithms.kCore(ht300, c)),
                new Case("triangleCount", c -> GraphAlgorithms.triangleCount(star20000, c), c -> GraphAlgorithms.triangleCount(ht300, c)));
    }

    // ---- (1) cancel mid-run, (4) progress visible and monotone -------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void cancelMidRunAbortsPromptlyWithNoResult(Case c) throws Exception {
        RunControl ctl = RunControl.create();
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "runcontrol-test-" + c.name());
            t.setDaemon(true);
            return t;
        });
        try {
            Future<Object> run = ex.submit(() -> c.big().apply(ctl));
            long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            List<Long> samples = new ArrayList<>();
            long seen;
            while ((seen = ctl.work()) == 0) { // the first progress tick, observed from this thread
                assertFalse(run.isDone(), c.name() + " finished before its first checkpoint");
                assertTrue(System.nanoTime() < giveUp, c.name() + " never reached a checkpoint");
                Thread.onSpinWait();
            }
            samples.add(seen);
            assertFalse(run.isDone(), c.name() + " is too small: it finished before the cancel (test graph invalid)");
            long cancelAt = System.nanoTime();
            ctl.cancel();
            ExecutionException e = assertThrows(ExecutionException.class, () -> run.get(20, TimeUnit.SECONDS),
                    c.name() + " returned a result instead of aborting");
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelAt);
            GraphAborted a = assertInstanceOf(GraphAborted.class, e.getCause(), c.name() + " failed with " + e.getCause());
            assertEquals(GraphAborted.Reason.CANCELLED, a.reason());
            assertTrue(latencyMs < 2000, c.name() + " took " + latencyMs + " ms to honour the cancel");
            assertTrue(a.reached() >= seen, "reached " + a.reached() + " < first tick " + seen);
            samples.add(ctl.work());
            assertTrue(samples.get(1) >= samples.get(0), "work() went backwards: " + samples);
        } finally {
            ctl.cancel();
            ex.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void progressIsMonotoneWhileRunningAndTheResultIsUnchanged(Case c) throws Exception {
        RunControl ctl = RunControl.create();
        java.util.concurrent.ConcurrentLinkedQueue<Long> samples = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Thread sampler = new Thread(() -> {
            long prev = -1;
            while (!Thread.currentThread().isInterrupted()) {
                long w = ctl.work();
                if (w != prev) samples.add(w);
                prev = w;
                Thread.onSpinWait();
            }
        }, "runcontrol-sampler-" + c.name());
        sampler.setDaemon(true);
        sampler.start();
        Object live = c.small().apply(ctl);
        sampler.interrupt();
        sampler.join(5000);
        List<Long> seen = new ArrayList<>(samples);
        for (int i = 1; i < seen.size(); i++) assertTrue(seen.get(i) > seen.get(i - 1), c.name() + " progress not monotone: " + seen);
        assertTrue(ctl.work() > 0, c.name() + " never ticked the work counter");
        assertEquals(c.small().apply(RunControl.NONE), live, c.name() + ": a live RunControl changed the result");
    }

    // ---- (2) deadline, (3) budget ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aDeadlineInThePastAbortsAtTheFirstCheckpoint(Case c) {
        RunControl ctl = RunControl.withTimeout(0);
        GraphAborted a = assertThrows(GraphAborted.class, () -> c.small().apply(ctl), c.name());
        assertEquals(GraphAborted.Reason.DEADLINE, a.reason());
        assertEquals(1, a.reached(), c.name());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aTinyWorkCeilingAbortsWithBudgetAndTheNumbers(Case c) {
        RunControl ctl = RunControl.withBudget(1);
        GraphAborted a = assertThrows(GraphAborted.class, () -> c.small().apply(ctl), c.name());
        assertEquals(GraphAborted.Reason.BUDGET, a.reason());
        assertEquals(1, a.ceiling(), c.name());
        assertEquals(2, a.reached(), c.name());
        assertTrue(a.getMessage().contains("ceiling 1"), a.getMessage());
    }

    // ---- RunControl itself -------------------------------------------------------------------------

    @Test
    void noneIsAnInertNoOp() {
        for (int i = 0; i < 100_000; i++) RunControl.NONE.checkpoint();
        RunControl.NONE.cancel(); // ignored: the shared singleton must never become cancelled
        RunControl.NONE.setFraction(0.5);
        RunControl.NONE.checkpoint();
        assertEquals(0, RunControl.NONE.work());
        assertEquals(0.0, RunControl.NONE.fraction());
    }

    @Test
    void cancelBeforeStartAbortsAtTheFirstCheckpoint() {
        RunControl ctl = RunControl.create();
        ctl.cancel();
        GraphAborted a = assertThrows(GraphAborted.class, ctl::checkpoint);
        assertEquals(GraphAborted.Reason.CANCELLED, a.reason());
    }

    @Test
    void aDeadlineFarAwayDoesNotFireAndFractionIsReadable() {
        RunControl ctl = RunControl.withTimeout(60_000);
        for (int i = 0; i < 1000; i++) ctl.checkpoint();
        ctl.setFraction(0.25);
        assertEquals(1000, ctl.work());
        assertEquals(0.25, ctl.fraction());
    }
}
