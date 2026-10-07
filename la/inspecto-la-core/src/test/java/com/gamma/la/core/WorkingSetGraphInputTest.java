package com.gamma.la.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-4 step 4: the Working Set adapter. Rows are built in the exact shape {@code WorkingSetRoutes.tables()} caches
 * ({@code entityId} | {@code source,target,kind,count}); the id proof against a REAL served response is in
 * inspecto-geo-link's {@code ControlApiWorkingSetGraphInputTest}.
 */
class WorkingSetGraphInputTest {

    private static Map<String, Object> entity(String id) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("entityId", id);
        r.put("type", "msisdn");
        r.put("hop", 0);
        return r;
    }

    private static Map<String, Object> link(String s, String t, String kind, Object count) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("source", s);
        r.put("target", t);
        r.put("kind", kind);
        r.put("count", count);
        r.put("opSeq", 1);
        return r;
    }

    private static final List<Map<String, Object>> ENTITIES = List.of(entity("A"), entity("B"), entity("C"));

    @Test
    void nodesAreTheEntitiesAndLabelsTheirId() {
        GraphInput.Materialised in = WorkingSetGraphInput.from(ENTITIES, List.of(), null);
        assertEquals(List.of("A", "B", "C"), in.nodes().stream().map(GraphInput.Node::id).toList());
        assertEquals(List.of("A", "B", "C"), in.nodes().stream().map(GraphInput.Node::label).toList());
        assertEquals(0, in.droppedDangling());
    }

    @Test
    void edgeIdsAreTheD_U9WireIdsAndWeightsTheLinkCount() {
        GraphInput.Materialised in = WorkingSetGraphInput.from(ENTITIES,
                List.of(link("A", "B", "voice", 5L), link("A", "B", "sms", 2), link("B", "C", "voice", 0L), link("C", "A", null, null)), null);
        List<String> ids = in.edges().stream().map(GraphInput.Edge::id).toList();
        assertEquals(List.of(LinkIds.encode("A", "B", "voice"), LinkIds.encode("A", "B", "sms"),
                LinkIds.encode("B", "C", "voice"), LinkIds.encode("C", "A", "null")), ids, "the id IS the wire id");
        assertEquals(List.of("A", "A", "B", "C"), in.edges().stream().map(GraphInput.Edge::source).toList());
        assertEquals(List.of("B", "B", "C", "A"), in.edges().stream().map(GraphInput.Edge::target).toList());
        assertEquals(5.0, in.weights().get(ids.get(0)));
        assertEquals(2.0, in.weights().get(ids.get(1)));
        assertEquals(1.0, in.weights().get(ids.get(2)), "a non-positive count weighs 1 (edgeWeight)");
        assertEquals(1.0, in.weights().get(ids.get(3)), "an absent count weighs 1");
        assertEquals(List.of("A", "B", "voice"), LinkIds.decode(ids.get(0)), "and it decodes back to the link");
    }

    @Test
    void theKindsFilterKeepsOnlyThoseLinksWithoutCountingThemDropped() {
        List<Map<String, Object>> links = List.of(link("A", "B", "voice", 1L), link("A", "C", "sms", 1L), link("B", "C", "voice", 1L));
        GraphInput.Materialised voice = WorkingSetGraphInput.from(ENTITIES, links, List.of("voice"));
        assertEquals(2, voice.edges().size());
        assertTrue(voice.edges().stream().allMatch(e -> !e.target().equals("C") || e.source().equals("B")));
        assertEquals(0, voice.droppedDangling(), "a filtered link was never asked for - it is not dropped");
        assertEquals(3, WorkingSetGraphInput.from(ENTITIES, links, List.of()).edges().size(), "empty = all kinds");
        assertEquals(3, WorkingSetGraphInput.from(ENTITIES, links, null).edges().size());
        assertEquals(0, WorkingSetGraphInput.from(ENTITIES, links, List.of("nothing")).edges().size());
    }

    @Test
    void aLinkWhoseEndpointIsNotAnEntityIsDroppedAndCounted() {
        GraphInput.Materialised in = WorkingSetGraphInput.from(ENTITIES,
                List.of(link("A", "B", "voice", 1L), link("A", "GHOST", "voice", 1L), link("GHOST", "B", "voice", 1L), link("X", "Y", "sms", 1L)),
                List.of("voice"));
        assertEquals(1, in.edges().size());
        assertEquals(2, in.droppedDangling(), "the sms link is filtered, the two ghosts are dropped");
        assertEquals(2, WorkingSetGraphInput.from(ENTITIES, List.of(link("A", "GHOST", "voice", 1L), link("GHOST", "B", "voice", 1L)), null).droppedDangling());
    }

    @Test
    void theAdapterFeedsTheEngineEndToEnd() {
        GraphInput.Materialised in = WorkingSetGraphInput.from(ENTITIES,
                List.of(link("A", "B", "voice", 4L), link("B", "C", "voice", 1L), link("A", "C", "voice", 1L), link("A", "GHOST", "voice", 1L)), null);
        GraphResult r = new InMemoryGraphEngine().run(Algorithm.WEIGHTED_SHORTEST_PATH, Map.of("from", "A", "to", "C", "direction", "out"), in, null);
        var sel = ((GraphResult.OneSelection) r.payload()).selection();
        // cost = 1/weight: A>B>C costs 1/4 + 1 = 1.25, the direct A>C costs 1 - the weights decide, and the ids name the links
        assertEquals(List.of("A", "C"), sel.nodeIds());
        assertEquals(List.of(LinkIds.encode("A", "C", "voice")), sel.edgeIds());
        assertEquals(1, r.dropped());
    }
}
