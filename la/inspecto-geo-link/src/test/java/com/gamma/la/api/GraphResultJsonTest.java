package com.gamma.la.api;

import com.gamma.la.core.Algorithm;
import com.gamma.la.core.GraphInput;
import com.gamma.la.core.GraphResult;
import com.gamma.la.core.InMemoryGraphEngine;
import com.gamma.la.core.LinkIds;
import com.gamma.la.core.InvestigationStores;
import com.gamma.la.graph.GraphAlgorithms.Score;
import com.gamma.la.graph.GraphAlgorithms.Selection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-4 step 6 - {@link GraphResultJson}, the one serializer: ALL 28 algorithms run through the real engine on a graph of
 * raw ids, serialized under {@code maskingMode all}, and the masked tree is checked three ways - no raw id and no raw
 * edge endpoint anywhere in it (edge ids are decoded, they are base64 and a plain text search would miss them); mapping
 * the pseudonyms back gives EXACTLY the unmasked tree (masking changed ids and nothing else - not the order, not a
 * score); and the masked edge ids equal the wire ids minted from the pseudonyms.
 */
class GraphResultJsonTest {

    private static final List<String> RAW = List.of("alice", "bob", "carol", "dave", "erin");
    private static final String KIND = "voice";

    private static GraphInput graph() {
        String[][] links = {{"alice", "bob"}, {"bob", "carol"}, {"carol", "alice"}, {"carol", "dave"}, {"dave", "erin"}};
        List<GraphInput.Node> nodes = new ArrayList<>();
        for (String id : RAW) nodes.add(new GraphInput.Node(id, id));
        List<GraphInput.Edge> edges = new ArrayList<>();
        Map<String, Double> weights = new LinkedHashMap<>();
        for (String[] l : links) {
            String id = LinkIds.encode(l[0], l[1], KIND);
            edges.add(new GraphInput.Edge(id, l[0], l[1]));
            weights.put(id, 2.0);
        }
        return GraphInput.of(nodes, edges, weights);
    }

    private static EntityMasking maskAll(Path root) throws Exception {
        Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: all\n");
        InvestigationRoutes.Inv inv = new InvestigationRoutes.Inv(InvestigationStores.of(root), root, "case-a",
                Map.of("dataset", "calls_ds", "sourceCol", "caller", "targetCol", "callee"));
        inv.store().create(inv.id(), "{}");   // the Investigation must exist before it can mint its mask key
        return EntityMasking.of(inv, List.of(Map.of("op", "seed", "params", Map.of("ids", RAW))), List.of(KIND));
    }

    private static Map<String, Object> params(Algorithm a) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (a.needsSource()) p.put(Algorithm.FROM, "alice");
        if (a.needsTarget()) p.put(Algorithm.TO, "erin");
        if (a.needsNode()) p.put(Algorithm.NODE, "carol");
        return p;
    }

    /** Every string in the tree, with edge ids decoded into their three parts. */
    private static void strings(Object node, List<String> out) {
        if (node instanceof Map<?, ?> m) m.forEach((k, v) -> {
            out.add(String.valueOf(k));
            strings(v, out);
        });
        else if (node instanceof List<?> l) l.forEach(v -> strings(v, out));
        else if (node instanceof String s) {
            out.add(s);
            if (s.startsWith(LinkIds.PREFIX)) out.addAll(LinkIds.decode(s));
        }
    }

    /** The masked tree with every pseudonym (node or edge endpoint) replaced by its raw value. */
    private static Object unmask(Object node, Map<String, String> rawOf) {
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), unmask(v, rawOf)));
            return out;
        }
        if (node instanceof List<?> l) return l.stream().map(v -> unmask(v, rawOf)).toList();
        if (node instanceof String s) {
            if (s.startsWith(LinkIds.PREFIX)) {
                List<String> p = LinkIds.decode(s);
                return LinkIds.encode(rawOf.getOrDefault(p.get(0), p.get(0)), rawOf.getOrDefault(p.get(1), p.get(1)),
                        rawOf.getOrDefault(p.get(2), p.get(2)));
            }
            return rawOf.getOrDefault(s, s);
        }
        return node;
    }

    @Test
    void everyAlgorithmsMaskedResultLeaksNoRawIdAndIsTheUnmaskedResultWithOnlyIdsChanged(@TempDir Path root) throws Exception {
        EntityMasking mask = maskAll(root);
        GraphResultJson.Ids masked = GraphResultJson.Ids.of(mask);
        Map<String, String> rawOf = new LinkedHashMap<>();
        for (String id : RAW) rawOf.put((String) mask.apply(id), id);
        rawOf.put((String) mask.apply(KIND), KIND);       // the kind is part of a wire id and is pseudonymised like the Working Set's
        assertEquals(RAW.size() + 1, mask.describe().get("masked"), "five ids and the kind are pseudonymised: " + mask.describe());

        InMemoryGraphEngine engine = new InMemoryGraphEngine();
        Set<Algorithm> seen = EnumSet.noneOf(Algorithm.class);
        for (Algorithm a : Algorithm.values()) {
            GraphResult r = engine.run(a, a.resolve(params(a)), graph(), null);
            Map<String, Object> plain = GraphResultJson.of(r, GraphResultJson.Ids.NONE);
            Map<String, Object> hidden = GraphResultJson.of(r, masked);
            seen.add(a);

            List<String> strings = new ArrayList<>();
            strings(hidden, strings);
            for (String s : strings)
                for (String raw : RAW)
                    assertFalse(s.equals(raw) || s.contains(raw), a.id() + ": raw id '" + raw + "' leaked as '" + s + "' in " + hidden);
            assertEquals(plain, unmask(hidden, rawOf), a.id() + ": masking changed more than ids");
            if (!strings.isEmpty() && plain.toString().contains(RAW.get(0)))
                assertNotEquals(plain, hidden, a.id() + ": a result naming ids must differ once they are masked");
        }
        assertEquals(EnumSet.allOf(Algorithm.class), seen);
    }

    @Test
    void aMaskedEdgeIdIsTheWireIdOfThePseudonymsAndTheKind(@TempDir Path root) throws Exception {
        EntityMasking mask = maskAll(root);
        GraphResultJson.Ids ids = GraphResultJson.Ids.of(mask);
        String raw = LinkIds.encode("alice", "bob", KIND);
        String out = ids.edge(raw);
        assertEquals(LinkIds.encode((String) mask.apply("alice"), (String) mask.apply("bob"), (String) mask.apply(KIND)), out);
        assertNotEquals(raw, out);
        assertTrue(LinkIds.decode(out).stream().allMatch(p -> p.startsWith(EntityMasking.TOKEN_PREFIX)), LinkIds.decode(out).toString());
    }

    /** The edge-vs-node choice of an {@code Ids} payload is per algorithm; a new IDS-kind algorithm must be decided here. */
    @Test
    void theIdsPayloadAlgorithmsAreExactlyThoseWhoseEdgeOrNodeChoiceWasMade() {
        Set<Algorithm> ids = EnumSet.noneOf(Algorithm.class);
        for (Algorithm a : Algorithm.values()) if (a.resultKind() == Algorithm.ResultKind.IDS) ids.add(a);
        assertEquals(EnumSet.of(Algorithm.ARTICULATION_POINTS, Algorithm.BRIDGES, Algorithm.DESCENDANTS), ids);
        assertTrue(GraphResultJson.idsAreEdges(Algorithm.BRIDGES));
        assertFalse(GraphResultJson.idsAreEdges(Algorithm.ARTICULATION_POINTS));
        assertFalse(GraphResultJson.idsAreEdges(Algorithm.DESCENDANTS));
    }

    @Test
    void withNothingMaskedTheSerializerIsTheIdentity(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: none\n");
        InvestigationRoutes.Inv inv = new InvestigationRoutes.Inv(InvestigationStores.of(root), root, "case-a",
                Map.of("dataset", "calls_ds"));
        inv.store().create(inv.id(), "{}");   // the Investigation must exist before it can mint its mask key
        assertSame(GraphResultJson.Ids.NONE, GraphResultJson.Ids.of(EntityMasking.of(inv, List.of(), List.of())));
    }

    // ── response size (LA-GRAPH-RUN-PAYLOAD-SIZE-1): never a silent cap ─────────────────────────────────────────

    /** Every list in the tree is either whole or has a {@code lists} entry that says it was cut, and the entry's numbers add up. */
    @Test
    void everyAlgorithmAtALimitOfOneCutsOnlyWhatItSaysItCut() {
        InMemoryGraphEngine engine = new InMemoryGraphEngine();
        int cutSomething = 0;
        for (Algorithm a : Algorithm.values()) {
            GraphResult r = engine.run(a, a.resolve(params(a)), graph(), null);
            Map<String, Object> full = GraphResultJson.of(r, GraphResultJson.Ids.NONE, 1_000);
            assertFalse((Boolean) full.get("truncated"), a.id() + ": nothing is cut under a generous limit");
            Map<String, Object> out = GraphResultJson.of(r, GraphResultJson.Ids.NONE, 1);
            @SuppressWarnings("unchecked") Map<String, Map<String, Object>> lists = (Map<String, Map<String, Object>>) out.get("lists");
            boolean anyCut = false;
            for (Map.Entry<String, Object> e : out.entrySet()) {
                if (!(e.getValue() instanceof List<?> l) || e.getKey().equals("lists")) continue;
                Map<String, Object> note = lists.get(e.getKey());
                assertTrue(note != null, a.id() + ": list '" + e.getKey() + "' has no lists entry");
                int total = ((List<?>) full.get(e.getKey())).size();
                assertEquals(total, note.get("total"), a.id() + "." + e.getKey());
                assertEquals(1, note.get("limit"));
                if (e.getKey().equals("edges") && out.containsKey("nodes")) {
                    // a sub-graph returns only edges whose nodes were both returned: fewer than the limit is legal, but said
                    assertEquals(l.size(), note.get("returned"));
                    assertEquals(total > l.size(), note.get("truncated"), a.id() + ".edges");
                    anyCut |= total > l.size();
                    continue;
                }
                assertEquals(Math.min(total, 1), l.size(), a.id() + "." + e.getKey());
                assertEquals(total > 1, note.get("truncated"), a.id() + "." + e.getKey());
                assertEquals(l.size(), note.get("returned"));
                // the survivors are the FIRST entries of the full answer (canonical order), not a sample
                if (!Set.of("groups", "selections").contains(e.getKey()))     // those hold lists that are themselves cut
                    assertEquals(((List<?>) full.get(e.getKey())).subList(0, l.size()), l, a.id() + "." + e.getKey());
                anyCut |= total > 1;
            }
            // nested lists (one path's nodes...) are said too: anything truncated at any depth raises the flag
            if (lists.values().stream().anyMatch(n -> Boolean.TRUE.equals(n.get("truncated")))) anyCut = true;
            assertEquals(anyCut, out.get("truncated"), a.id() + ": the flag matches the entries");
            if (anyCut) cutSomething++;
        }
        assertTrue(cutSomething >= 10, "the probe graph must actually exercise the cut: " + cutSomething);
    }

    @Test
    void aSelectionsInnerListsAreCutAndNamedAndAMaskedCutKeepsTheRawOrder(@TempDir Path root) throws Exception {
        GraphResult sel = new GraphResult(Algorithm.SHORTEST_PATH, new GraphResult.OneSelection(
                new Selection(List.of("alice", "bob", "carol", "dave"), List.of("e1", "e2", "e3"))), 0, 1);
        Map<String, Object> out = GraphResultJson.of(sel, GraphResultJson.Ids.NONE, 2);
        @SuppressWarnings("unchecked") Map<String, Object> s = (Map<String, Object>) out.get("selection");
        assertEquals(List.of("alice", "bob"), s.get("nodeIds"));
        assertEquals(2, ((List<?>) s.get("edgeIds")).size());
        @SuppressWarnings("unchecked") Map<String, Map<String, Object>> lists = (Map<String, Map<String, Object>>) out.get("lists");
        assertEquals(4, lists.get("selection.nodeIds").get("total"));
        assertEquals(3, lists.get("selection.edgeIds").get("total"));
        assertEquals(true, out.get("truncated"));

        GraphResult groups = new GraphResult(Algorithm.CONNECTED_COMPONENTS, new GraphResult.Groups(
                List.of(List.of("alice", "bob", "carol"), List.of("dave"), List.of("erin"))), 0, 1);
        EntityMasking mask = maskAll(root);
        Map<String, Object> g = GraphResultJson.of(groups, GraphResultJson.Ids.of(mask), 2);
        @SuppressWarnings("unchecked") List<List<Object>> kept = (List<List<Object>>) g.get("groups");
        assertEquals(2, kept.size(), "two of three groups");
        assertEquals(List.of(mask.apply("alice"), mask.apply("bob")), kept.get(0), "and the first group is cut to two members, in raw order");
        @SuppressWarnings("unchecked") Map<String, Map<String, Object>> gl = (Map<String, Map<String, Object>>) g.get("lists");
        assertEquals(3, gl.get("groups").get("total"));
        assertEquals(3, gl.get("groups[0]").get("total"));
        assertFalse(gl.containsKey("groups[1]"), "an inner list that was not cut has no entry");
        assertFalse(g.toString().contains("alice"), "the cut result is still masked");
    }

    @Test
    void aCutSubGraphReturnsNoEdgeWhoseNodeWasCutAndSaysSo() {
        var g = new com.gamma.la.graph.GraphAlgorithms.Graph(
                List.of(new com.gamma.la.graph.GraphAlgorithms.Node("a", "a"), new com.gamma.la.graph.GraphAlgorithms.Node("b", "b"),
                        new com.gamma.la.graph.GraphAlgorithms.Node("c", "c")),
                List.of(new com.gamma.la.graph.GraphAlgorithms.Edge(LinkIds.encode("a", "b", "k"), "a", "b"),
                        new com.gamma.la.graph.GraphAlgorithms.Edge(LinkIds.encode("b", "c", "k"), "b", "c"),
                        new com.gamma.la.graph.GraphAlgorithms.Edge(LinkIds.encode("c", "a", "k"), "c", "a")));
        GraphResult r = new GraphResult(Algorithm.MAXIMUM_SPANNING_FOREST, new GraphResult.SubGraph(g), 0, 1);
        Map<String, Object> out = GraphResultJson.of(r, GraphResultJson.Ids.NONE, 2);
        @SuppressWarnings("unchecked") List<Map<String, Object>> edges = (List<Map<String, Object>>) out.get("edges");
        assertEquals(1, edges.size(), "only a-b has both endpoints among the two returned nodes");
        assertEquals("a", edges.get(0).get("source"));
        @SuppressWarnings("unchecked") Map<String, Map<String, Object>> lists = (Map<String, Map<String, Object>>) out.get("lists");
        assertEquals(3, lists.get("edges").get("total"));
        assertEquals(1, lists.get("edges").get("returned"));
        assertEquals(true, lists.get("edges").get("truncated"));
        assertEquals(true, out.get("truncated"));
        Map<String, Object> whole = GraphResultJson.of(r, GraphResultJson.Ids.NONE, 10);
        assertEquals(3, ((List<?>) whole.get("edges")).size(), "nothing cut, every edge returned");
        assertEquals(false, whole.get("truncated"));
    }

    @Test
    void aLimitBelowOneIsTreatedAsOneNeverAsZeroItems() {
        GraphResult r = new GraphResult(Algorithm.DEGREE_CENTRALITY, new GraphResult.Scores(
                List.of(new Score("a", "a", 2), new Score("b", "b", 1))), 0, 1);
        assertEquals(1, ((List<?>) GraphResultJson.of(r, GraphResultJson.Ids.NONE, 0).get("scores")).size());
    }

    private static void assertSame(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertSame(expected, actual);
    }
}
