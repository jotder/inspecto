package com.gamma.la.core;

import com.gamma.la.core.GraphRunService.Limits;
import com.gamma.la.core.GraphRunService.Request;
import com.gamma.la.core.GraphRunService.RunView;
import com.gamma.la.core.GraphRunService.Status;
import com.gamma.graph.RunControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code LA-CONFIG-LIMITS-1} P4: a live service takes new install-wide limits ({@link GraphRunService#retune}) - the ceilings
 * clamp the next submit, a longer waiting line admits more, a shrink drains (waiting runs stay, the next submit is refused) and
 * more workers start waiting runs at once.
 */
class GraphRunServiceRetuneTest {

    private static final GraphBudget DEFAULTS = new GraphBudget(1_000, 10_000, 5_000);
    private GraphRunService svc;

    @AfterEach
    void close() {
        if (svc != null) svc.close();
    }

    private static Limits limits(GraphBudget ceilings, int threads, int queue) {
        return new Limits(DEFAULTS, ceilings, threads, queue, 60_000L, 50, 60_000L, 8);
    }

    private static GraphInput ring(int n) {
        List<GraphInput.Node> nodes = new ArrayList<>();
        List<GraphInput.Edge> edges = new ArrayList<>();
        for (int i = 0; i < n; i++) nodes.add(new GraphInput.Node("n" + i, "n" + i));
        for (int i = 0; i < n; i++) edges.add(new GraphInput.Edge("e" + i, "n" + i, "n" + ((i + 1) % n)));
        return GraphInput.of(nodes, edges, Map.of());
    }

    private static Request req(String scope, GraphBudget budget) {
        return new Request("alice", "inv-1", "rel-1", scope, Algorithm.DEGREE_CENTRALITY, Map.of(), "none", ring(4), budget);
    }

    /** Runs until released. */
    private static GraphEngine blocking(CountDownLatch release) {
        return new GraphEngine() {
            @Override public String engineId() { return "probe"; }
            @Override public Set<Algorithm> supported() { return EnumSet.allOf(Algorithm.class); }

            @Override
            public GraphResult run(Algorithm a, Map<String, Object> p, GraphInput in, RunControl ctl) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new GraphResult(a, new GraphResult.Flag(true), 0, 1);
            }
        };
    }

    private void until(String id, Status want) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (svc.get(id).status() != want) {
            if (System.nanoTime() > end) throw new AssertionError(id + " never reached " + want);
            Thread.sleep(2);
        }
    }

    @Test
    void aLoweredCeilingClampsTheNextSubmit() {
        CountDownLatch release = new CountDownLatch(0);
        GraphBudget wide = new GraphBudget(500_000, 5_000_000, 300_000);
        svc = new GraphRunService(blocking(release), limits(wide, 1, 4));
        GraphBudget asked = new GraphBudget(100_000, 0, 0);
        RunView before = svc.submit(req("s1", asked));
        assertEquals(100_000, before.budget().maxNodes());

        svc.retune(limits(new GraphBudget(3, 5_000_000, 300_000), 1, 4));
        RunView after = svc.submit(req("s2", asked));
        assertEquals(3, after.budget().maxNodes(), "the lowered ceiling clamps the next run");
        assertTrue(after.budgetClamped());
        assertEquals(Status.BUDGET_EXCEEDED, after.status(), "four nodes are over a ceiling of three");
    }

    @Test
    void theWaitingLineAndTheWorkersFollowRetuneAndAShrinkDrains() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        GraphBudget wide = new GraphBudget(500_000, 5_000_000, 300_000);
        svc = new GraphRunService(blocking(release), limits(wide, 1, 1));
        String first = svc.submit(req("s1", GraphBudget.UNSTATED)).id();
        until(first, Status.RUNNING);
        String second = svc.submit(req("s2", GraphBudget.UNSTATED)).id();
        GraphRunException full = assertThrows(GraphRunException.class, () -> svc.submit(req("s3", GraphBudget.UNSTATED)));
        assertTrue(full.getMessage().contains("(1 running, 1 waiting)"), full.getMessage());

        svc.retune(limits(wide, 1, 2));
        String third = svc.submit(req("s3", GraphBudget.UNSTATED)).id();
        assertEquals(Status.QUEUED, svc.get(third).status());

        svc.retune(limits(wide, 1, 1));
        assertEquals(Status.QUEUED, svc.get(second).status(), "a shrink never drops a waiting run");
        assertThrows(GraphRunException.class, () -> svc.submit(req("s4", GraphBudget.UNSTATED)));

        svc.retune(limits(wide, 3, 1));
        until(second, Status.RUNNING);
        until(third, Status.RUNNING);
        release.countDown();
        for (String id : List.of(first, second, third)) assertEquals(Status.COMPLETED, svc.await(id, 10_000).status());
    }
}
