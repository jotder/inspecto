package com.gamma.la.core;

import com.gamma.la.core.InvalidGraphRequest.Reason;
import com.gamma.la.graph.GraphAborted;
import com.gamma.la.graph.RunControl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D-4 step 4: the catalogue, request validation and cancellation of {@link InMemoryGraphEngine}. */
class GraphEngineRequestTest {

    private static final InMemoryGraphEngine ENGINE = new InMemoryGraphEngine();

    /** The 28 ported functions (design §2.2 / feasibility §7.13), by their TypeScript export names. */
    private static final Set<String> THE_28 = Set.of("shortestPath", "neighborhood", "egoNetwork", "degreeCentrality",
            "connectedComponents", "kCore", "triangleCount", "articulationPoints", "bridges", "isForest", "descendants",
            "weightedShortestPath", "maximumSpanningForest", "jaccardSimilarity", "pageRank", "betweennessCentrality",
            "closenessCentrality", "suspicionScore", "louvainCommunities", "detectCommunities", "cliques", "findCycles",
            "allPaths", "maxFlow", "linkPrediction", "eigenvectorCentrality", "katzCentrality", "hits");

    /** A directed ring a→b→c→d→a plus a chord a→c: small, but every algorithm has something to do. */
    private static GraphInput ring() {
        List<GraphInput.Node> nodes = new ArrayList<>();
        for (String id : List.of("a", "b", "c", "d")) nodes.add(new GraphInput.Node(id, id));
        List<GraphInput.Edge> edges = List.of(new GraphInput.Edge("e1", "a", "b"), new GraphInput.Edge("e2", "b", "c"),
                new GraphInput.Edge("e3", "c", "d"), new GraphInput.Edge("e4", "d", "a"), new GraphInput.Edge("e5", "a", "c"));
        return GraphInput.of(nodes, edges, Map.of());
    }

    private static Map<String, Object> minimal(Algorithm a) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (a.needsSource()) p.put(Algorithm.FROM, "a");
        if (a.needsTarget()) p.put(Algorithm.TO, "c");
        if (a.needsNode()) p.put(Algorithm.NODE, "a");
        return p;
    }

    private static InvalidGraphRequest refused(Algorithm a, Map<String, Object> params) {
        RunControl ctl = RunControl.create();
        InvalidGraphRequest e = assertThrows(InvalidGraphRequest.class, () -> ENGINE.run(a, params, ring(), ctl));
        assertEquals(0, ctl.work(), "a refused request does no work");
        return e;
    }

    // ── the catalogue ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void theCatalogueIsExactlyThe28PortedAlgorithms() {
        Set<String> ids = new java.util.HashSet<>();
        for (Algorithm a : Algorithm.values()) assertTrue(ids.add(a.id()), "duplicate id " + a.id());
        assertEquals(THE_28, ids);
        assertEquals(28, Algorithm.values().length);
        assertEquals(15, java.util.Arrays.stream(Algorithm.values()).filter(a -> a.cost() == Algorithm.Cost.SYNC).count());
        assertEquals(13, java.util.Arrays.stream(Algorithm.values()).filter(a -> a.cost() == Algorithm.Cost.JOB).count());
        for (Algorithm a : Algorithm.values()) {
            assertEquals(a, Algorithm.byId(a.id()));
            assertTrue(a.inlineNodeCeiling() > 0, a.id());
            assertNotNull(a.label());
            for (Algorithm.Param p : a.params()) {
                assertNotNull(p.defaultValue(), a.id() + "." + p.name() + " default");
                assertEquals(p.defaultValue(), p.resolve(a.id(), null));
            }
        }
        assertEquals(Set.of(Algorithm.WEIGHTED_SHORTEST_PATH, Algorithm.MAXIMUM_SPANNING_FOREST, Algorithm.MAX_FLOW),
                EnumSet.copyOf(java.util.Arrays.stream(Algorithm.values()).filter(Algorithm::needsWeights).toList()));
    }

    /** No silent gap: every catalogued algorithm resolves to a dispatch and answers with the shape it declares. */
    @Test
    void everyCatalogueEntryDispatchesAndAnswersItsDeclaredKind() {
        assertEquals(EnumSet.allOf(Algorithm.class), ENGINE.supported());
        assertEquals("memory", ENGINE.engineId());
        for (Algorithm a : Algorithm.values()) {
            GraphResult r = ENGINE.run(a, minimal(a), ring(), null);
            assertEquals(a, r.algorithm());
            assertEquals(a.resultKind(), r.payload().kind(), a.id());
            assertEquals(0, r.dropped());
            assertTrue(r.elapsedMs() >= 0);
        }
    }

    @Test
    void theDroppedCountTravelsIntoTheResult() {
        GraphInput.Materialised in = GraphInput.of(List.of(new GraphInput.Node("a", "a"), new GraphInput.Node("b", "b")),
                List.of(new GraphInput.Edge("e1", "a", "b"), new GraphInput.Edge("e2", "a", "ghost"), new GraphInput.Edge("e3", "ghost", "b")),
                Map.of());
        assertEquals(2, in.droppedDangling());
        assertEquals(1, in.edges().size());
        assertEquals(2, ENGINE.run(Algorithm.DEGREE_CENTRALITY, Map.of(), in, null).dropped());
    }

    // ── validation ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void unknownAlgorithmIsTyped() {
        InvalidGraphRequest e = assertThrows(InvalidGraphRequest.class, () -> Algorithm.byId("pagerank2"));
        assertEquals(Reason.UNKNOWN_ALGORITHM, e.reason());
        assertEquals("pagerank2", e.param());
        assertEquals(Reason.UNKNOWN_ALGORITHM, assertThrows(InvalidGraphRequest.class, () -> ENGINE.run(null, Map.of(), ring(), null)).reason());
        assertTrue(new InvalidGraphRequest(Reason.BAD_TYPE, "x", "m") instanceof IllegalArgumentException, "the route layer maps IAE to 422");
    }

    @Test
    void aMissingOrBlankNodeIdIsRefusedBeforeAnyWork() {
        InvalidGraphRequest e = refused(Algorithm.SHORTEST_PATH, Map.of("from", "a"));
        assertEquals(Reason.MISSING_PARAM, e.reason());
        assertEquals("to", e.param());
        assertEquals("from", refused(Algorithm.MAX_FLOW, Map.of("to", "c")).param());
        assertEquals(Reason.MISSING_PARAM, refused(Algorithm.JACCARD_SIMILARITY, Map.of()).reason());
        assertEquals(Reason.MISSING_PARAM, refused(Algorithm.DESCENDANTS, Map.of("node", "  ")).reason());
        assertEquals(Reason.BAD_TYPE, refused(Algorithm.NEIGHBORHOOD, Map.of("node", 7)).reason());
    }

    @Test
    void outOfRangeAndMistypedParametersAreRefusedBeforeAnyWork() {
        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("damping", 1.5);
        InvalidGraphRequest e = refused(Algorithm.PAGE_RANK, pr);
        assertEquals(Reason.OUT_OF_RANGE, e.reason());
        assertEquals("damping", e.param());
        assertEquals(Reason.OUT_OF_RANGE, refused(Algorithm.PAGE_RANK, Map.of("iterations", -1)).reason());
        assertEquals(Reason.OUT_OF_RANGE, refused(Algorithm.PAGE_RANK, Map.of("iterations", 10_001)).reason());
        assertEquals(Reason.OUT_OF_RANGE, refused(Algorithm.ALL_PATHS, Map.of("from", "a", "to", "c", "limit", -1)).reason());
        assertEquals(Reason.OUT_OF_RANGE, refused(Algorithm.SHORTEST_PATH, Map.of("from", "a", "to", "c", "direction", "sideways")).reason());
        assertEquals(Reason.OUT_OF_RANGE, refused(Algorithm.SUSPICION_SCORE, Map.of("core", -1)).reason());
        assertEquals(Reason.OUT_OF_RANGE, refused(Algorithm.LINK_PREDICTION, Map.of("method", "jaccard")).reason());
        assertEquals(Reason.BAD_TYPE, refused(Algorithm.PAGE_RANK, Map.of("iterations", 2.5)).reason());
        assertEquals(Reason.BAD_TYPE, refused(Algorithm.PAGE_RANK, Map.of("iterations", "60")).reason());
        assertEquals(Reason.BAD_TYPE, refused(Algorithm.PAGE_RANK, Map.of("damping", Double.NaN)).reason());
        assertEquals(Reason.BAD_TYPE, refused(Algorithm.SHORTEST_PATH, Map.of("from", "a", "to", "c", "direction", 3)).reason());
    }

    @Test
    void anUnknownParameterIsRefusedNotIgnored() {
        InvalidGraphRequest e = refused(Algorithm.DEGREE_CENTRALITY, Map.of("limit", 5));
        assertEquals(Reason.UNKNOWN_PARAM, e.reason());
        assertEquals("limit", e.param());
        assertEquals(Reason.UNKNOWN_PARAM, refused(Algorithm.SHORTEST_PATH, Map.of("from", "a", "to", "c", "node", "a")).reason());
    }

    @Test
    void resolveFillsDefaultsAndNormalises() {
        Map<String, Object> r = Algorithm.PAGE_RANK.resolve(null);
        assertEquals(0.85, r.get("damping"));
        assertEquals(60, r.get("iterations"));
        Map<String, Object> custom = Algorithm.ALL_PATHS.resolve(Map.of("from", "a", "to", "c", "limit", 3.0, "direction", " OUT "));
        assertEquals(3, custom.get("limit"));
        assertEquals(8, custom.get("maxHops"));
        assertEquals("out", custom.get("direction"));
    }

    /** A well-formed id that names no node is NOT a refusal: the answer is the algorithm's empty one, as in the browser. */
    @Test
    void anAbsentNodeIsAnEmptyAnswerNotAnError() {
        assertNull(((GraphResult.OneSelection) ENGINE.run(Algorithm.SHORTEST_PATH, Map.of("from", "a", "to", "zz"), ring(), null).payload()).selection());
        assertTrue(((GraphResult.Scores) ENGINE.run(Algorithm.JACCARD_SIMILARITY, Map.of("node", "zz"), ring(), null).payload()).scores().isEmpty());
        assertTrue(((GraphResult.Ids) ENGINE.run(Algorithm.DESCENDANTS, Map.of("node", "zz"), ring(), null).payload()).ids().isEmpty());
    }

    // ── cancellation, deadline, budget — through the engine ─────────────────────────────────────────────────────────

    @Test
    void aCancelledRunAbortsWithoutAResult() {
        RunControl ctl = RunControl.create();
        ctl.cancel();
        GraphAborted e = assertThrows(GraphAborted.class, () -> ENGINE.run(Algorithm.BETWEENNESS_CENTRALITY, Map.of(), ring(), ctl));
        assertEquals(GraphAborted.Reason.CANCELLED, e.reason());
    }

    @Test
    void aDeadlineAndABudgetAbortThroughTheEngineToo() {
        GraphAborted d = assertThrows(GraphAborted.class,
                () -> ENGINE.run(Algorithm.PAGE_RANK, Map.of(), ring(), RunControl.withTimeout(0)));
        assertEquals(GraphAborted.Reason.DEADLINE, d.reason());
        GraphAborted b = assertThrows(GraphAborted.class,
                () -> ENGINE.run(Algorithm.BETWEENNESS_CENTRALITY, Map.of(), ring(), RunControl.withBudget(2)));
        assertEquals(GraphAborted.Reason.BUDGET, b.reason());
    }

    @Test
    void aLiveRunCountsItsWorkAndANullControlIsNone() {
        RunControl ctl = RunControl.create();
        ENGINE.run(Algorithm.BETWEENNESS_CENTRALITY, Map.of(), ring(), ctl);
        assertTrue(ctl.work() > 0, "betweenness checkpoints once per source");
        assertEquals(4, ((GraphResult.Scores) ENGINE.run(Algorithm.BETWEENNESS_CENTRALITY, Map.of(), ring(), null).payload()).scores().size());
    }
}
