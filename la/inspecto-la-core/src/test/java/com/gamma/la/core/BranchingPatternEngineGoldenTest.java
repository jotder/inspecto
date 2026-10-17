package com.gamma.la.core;

import com.gamma.la.core.BranchingPatternEngine.Edge;
import com.gamma.la.core.BranchingPatternEngine.Match;
import com.gamma.la.core.BranchingPatternEngine.Result;
import com.gamma.la.core.PatternQueryCompiler.Stage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden tests of the motif search alone (no SQL): exact node order, edge order, layers and the truncated flag,
 * pinned BEFORE the analytics core plan's S6 move so the move provably changes no output. The end-to-end parity
 * with the browser matcher stays with {@code ControlApiInvPatternTest} over {@code branching-parity.fixture.json}.
 */
class BranchingPatternEngineGoldenTest {

    private static final long H = 3_600_000L;

    private static Stage fanIn(int min, Double window, boolean after, Double gap) {
        return new Stage(true, min, null, null, null, window, after, gap, false);
    }

    private static Stage fanOut(int min, Double window, boolean after, Double gap) {
        return new Stage(false, min, null, null, null, window, after, gap, false);
    }

    private static Stage closingFanIn(int min) {
        return new Stage(true, min, null, null, null, null, true, null, true);
    }

    private static Stage withNodeKind(Stage s, String kind) {
        return new Stage(s.fanIn(), s.minBranches(), s.edgeKind(), kind, s.threshold(), s.windowHours(),
                s.afterPrevious(), s.maxGapHours(), s.closesToStart());
    }

    private static Result match(List<Stage> stages, List<List<Edge>> legs, int limit) {
        return BranchingPatternEngine.match(stages, legs, limit);
    }

    private static Edge e(String id, String s, String t, Long hours) {
        return new Edge(id, s, t, hours == null ? null : hours * H);
    }

    /** Fan-in of smurfs (window 24 h) → fan-out to relays (≤ 48 h after) → fan-in to one exit. */
    private static List<Stage> structuring() {
        return List.of(fanIn(3, 24.0, false, null), fanOut(2, null, true, 48.0), fanIn(2, null, true, null));
    }

    private static List<List<Edge>> structuringLegs(long c2Hours) {
        return List.of(
                List.of(e("i1", "s1", "H", 1L), e("i2", "s2", "H", 2L), e("i3", "s3", "H", 3L), e("i4", "s4", "H", 30L)),
                List.of(e("o1", "H", "r1", 10L), e("o2", "H", "r2", 11L), e("o3", "H", "r3", 100L)),
                List.of(e("c1", "r1", "X", 20L), e("c2", "r2", "X", c2Hours)));
    }

    @Test
    void theStructuringRingMatchesWithExactNodesEdgesAndLayers() {
        Result r = match(structuring(), structuringLegs(21L), 10);
        assertFalse(r.truncated());
        assertEquals(1, r.matches().size());
        Match m = r.matches().get(0);
        assertEquals(List.of("s1", "s2", "s3", "H", "r1", "r2", "X"), m.nodeIds());
        assertEquals(List.of("i1", "i2", "i3", "o1", "o2", "c1", "c2"), m.edgeIds());
        assertEquals(List.of(List.of("s1", "s2", "s3"), List.of("H"), List.of("r1", "r2"), List.of("X")), m.layers());
    }

    @Test
    void aClosingLegBeforeItsRelayWasReachedBreaksTheRing() {
        Result r = match(structuring(), structuringLegs(5L), 10);
        assertEquals(0, r.matches().size());
        assertFalse(r.truncated());
    }

    @Test
    void anUnknownTimeFailsAnOrderedStage() {
        List<List<Edge>> legs = structuringLegs(21L);
        List<List<Edge>> withNull = List.of(legs.get(0), legs.get(1),
                List.of(e("c1", "r1", "X", 20L), e("c2", "r2", "X", null)));
        assertEquals(0, match(structuring(), withNull, 10).matches().size());
    }

    @Test
    void sendersSpreadWiderThanTheWindowDoNotMatch() {
        List<List<Edge>> legs = List.of(
                List.of(e("i1", "s1", "H", 0L), e("i2", "s2", "H", 25L), e("i3", "s3", "H", 50L)),
                List.of(e("o1", "H", "r1", 60L), e("o2", "H", "r2", 61L)),
                List.of(e("c1", "r1", "X", 70L), e("c2", "r2", "X", 71L)));
        assertEquals(0, match(structuring(), legs, 10).matches().size());
    }

    @Test
    void aClosingStageCollectsOnlyOnTheOriginLayer() {
        List<Stage> stages = List.of(fanOut(2, null, false, null), closingFanIn(2));
        List<List<Edge>> closed = List.of(
                List.of(e("a1", "A", "B", 1L), e("a2", "A", "C", 2L)),
                List.of(e("b1", "B", "A", 3L), e("b2", "C", "A", 4L)));
        Result r = match(stages, closed, 10);
        assertEquals(1, r.matches().size());
        Match m = r.matches().get(0);
        assertEquals(List.of("A", "B", "C"), m.nodeIds());
        assertEquals(List.of("a1", "a2", "b1", "b2"), m.edgeIds());
        assertEquals(List.of(List.of("A"), List.of("B", "C"), List.of("A")), m.layers());

        List<List<Edge>> open = List.of(closed.get(0), List.of(e("b1", "B", "Z", 3L), e("b2", "C", "Z", 4L)));
        assertEquals(0, match(stages, open, 10).matches().size(), "a new exit is not a round trip");
    }

    @Test
    void aStageNamingANonEntityNodeKindMatchesNothing() {
        List<Stage> base = structuring();
        for (int i = 0; i < base.size(); i++) {
            List<Stage> st = new ArrayList<>(base);
            st.set(i, withNodeKind(base.get(i), "account"));
            assertEquals(0, match(st, structuringLegs(21L), 10).matches().size(), "stage " + i);
        }
        List<Stage> entity = new ArrayList<>(base);
        entity.set(1, withNodeKind(base.get(1), PatternQueryCompiler.ENTITY_KIND));
        assertEquals(1, match(entity, structuringLegs(21L), 10).matches().size());
    }

    @Test
    void theMatchLimitCutsAndSetsTruncated() {
        List<Stage> stages = List.of(fanIn(2, null, false, null));
        List<List<Edge>> legs = List.of(List.of(
                e("p1", "a", "P", 1L), e("p2", "b", "P", 2L), e("q1", "c", "Q", 1L), e("q2", "d", "Q", 2L)));
        Result one = match(stages, legs, 1);
        assertTrue(one.truncated());
        assertEquals(1, one.matches().size());
        assertEquals(List.of("a", "b", "P"), one.matches().get(0).nodeIds(), "anchors are visited in key order");
        Result all = match(stages, legs, 2);
        assertTrue(all.truncated(), "reaching the limit exactly also reports truncated");
        Result room = match(stages, legs, 3);
        assertFalse(room.truncated());
        assertEquals(2, room.matches().size());
        assertEquals(List.of("c", "d", "Q"), room.matches().get(1).nodeIds());
    }

    @Test
    void theWorkBudgetStopsTheSearchAndSaysSo() {
        int n = BranchingPatternEngine.WORK_BUDGET + 1;
        List<Edge> legs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) legs.add(new Edge("e" + i, "A", "t" + i, (long) i));
        Result r = match(List.of(fanOut(n + 1, null, false, null)), List.of(legs), 10);
        assertTrue(r.truncated());
        assertEquals(0, r.matches().size());
        assertEquals(500_000, BranchingPatternEngine.WORK_BUDGET);
    }

    @Test
    void aSelfLoopIsNeverALeg() {
        List<Stage> stages = List.of(fanIn(2, null, false, null));
        List<List<Edge>> legs = List.of(List.of(e("x1", "P", "P", 1L), e("p1", "a", "P", 2L)));
        assertEquals(0, match(stages, legs, 10).matches().size());
    }

    @Test
    void followsInTimeIsStrictAndBoundedByTheGap() {
        Stage st = fanOut(1, null, true, 2.0);
        assertTrue(BranchingPatternEngine.followsInTime(0L, 2 * H, st));
        assertFalse(BranchingPatternEngine.followsInTime(0L, 2 * H + 1, st));
        assertFalse(BranchingPatternEngine.followsInTime(5L, 5L, st));
        assertFalse(BranchingPatternEngine.followsInTime(null, 5L, st));
        assertTrue(BranchingPatternEngine.followsInTime(null, null, fanOut(1, null, false, null)));
    }
}
