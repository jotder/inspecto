package com.gamma.la.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.la.graph.GraphAlgorithms.Edge;
import com.gamma.la.graph.GraphAlgorithms.Graph;
import com.gamma.la.graph.GraphAlgorithms.Node;
import com.gamma.la.graph.GraphAlgorithms.Selection;
import com.gamma.la.graph.GraphSuspicion.Suspicion;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DR-S10 - the {@code la-showcase} Space Template's demo promises, over the SHIPPED corpus
 * ({@code spaces/_templates/la-showcase/data}, read in place so the test cannot drift from what a Space receives):
 * the planted hub {@code MULE-HUB-01} ranks in the top 3 of the Suspicion score and the planted ring
 * {@code SHELL-A->B->C->D->A} is the only (hence the shortest) directed cycle. The graph is built the way the
 * {@code mule_layering_ring} view folds it: one link per distinct (payer, payee, channel). Expectations:
 * {@code la-showcase-expected.json}.
 */
class LaShowcaseGoldenTest {

    private static final Path TEMPLATE = Path.of("..", "..", "spaces", "_templates", "la-showcase", "data");
    private static final JsonNode EXPECTED = expected();

    private static JsonNode expected() {
        try (var in = LaShowcaseGoldenTest.class.getResourceAsStream("/la-showcase-expected.json")) {
            return new ObjectMapper().readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The CSV rows of the named files (header dropped), looked up in the inbox then in staged. */
    private static List<String[]> rows(List<String> files) throws Exception {
        List<String[]> out = new ArrayList<>();
        for (String f : files) {
            Path p = TEMPLATE.resolve("inbox").resolve("mule_transfers").resolve(f);
            if (!Files.exists(p)) p = TEMPLATE.resolve("staged").resolve("mule_transfers").resolve(f);
            assertTrue(Files.isRegularFile(p), "the template ships no " + f + " (looked in " + p.toAbsolutePath().normalize() + ")");
            List<String> lines = Files.readAllLines(p);
            for (String l : lines.subList(1, lines.size())) out.add(l.split(",", -1));
        }
        return out;
    }

    private static Graph fold(List<String[]> rows) {
        Set<String> nodes = new LinkedHashSet<>();
        Set<String> links = new LinkedHashSet<>();
        List<Edge> edges = new ArrayList<>();
        for (String[] c : rows) {
            nodes.add(c[1]);
            nodes.add(c[2]);
            if (links.add(c[1] + "|" + c[2] + "|" + c[3])) edges.add(new Edge("e" + edges.size(), c[1], c[2]));
        }
        return new Graph(nodes.stream().map(n -> new Node(n, n)).toList(), edges);
    }

    private static int rankOf(Graph g, String id) {
        List<Suspicion> ranked = GraphSuspicion.suspicionScore(g);
        for (int i = 0; i < ranked.size(); i++) if (ranked.get(i).id().equals(id)) return i + 1;
        throw new AssertionError(id + " is not in the graph");
    }

    private static List<String> files(JsonNode landed, String... extra) {
        List<String> out = new ArrayList<>();
        landed.get("files").forEach(f -> out.add(f.asText()));
        out.addAll(List.of(extra));
        return out;
    }

    @Test
    void theLandedDaysRankTheHubInTheTopThreeAndTheRingIsTheOnlyCycle() throws Exception {
        JsonNode e = EXPECTED.get("landed");
        Graph g = fold(rows(files(e)));
        assertEquals(e.get("nodes").asInt(), g.nodes().size(), "nodes");
        assertEquals(e.get("links").asInt(), g.edges().size(), "links");

        int rank = rankOf(g, e.get("hub").asText());
        assertTrue(rank <= e.get("hubMaxRank").asInt(), e.get("hub").asText() + " ranks " + rank + " in the Suspicion score");

        List<Selection> cycles = GraphStructure.findCycles(g, 50, 8);
        assertEquals(1, cycles.size(), "the ring is the ONLY directed cycle: " + cycles);
        List<String> ring = new ArrayList<>();
        e.get("ringOnlyCycle").forEach(n -> ring.add(n.asText()));
        assertEquals(ring, cycles.get(0).nodeIds());
    }

    @Test
    void theStagedDaysKeepTheHubInTheTopThreeAndAddNoCycle() throws Exception {
        for (String key : List.of("afterNextDay", "afterGapDay")) {
            JsonNode e = EXPECTED.get(key);
            Graph g = fold(rows(files(EXPECTED.get("landed"), e.get("extraFile").asText())));
            int rank = rankOf(g, EXPECTED.get("landed").get("hub").asText());
            assertTrue(rank <= e.get("hubMaxRank").asInt(), key + ": hub ranks " + rank);
            assertEquals(1, GraphStructure.findCycles(g, 50, 8).size(), key + ": still only the ring");
        }
    }

    @Test
    void theNarrativeFiguresHoldOverTheLandedRows() throws Exception {
        JsonNode e = EXPECTED.get("landed");
        List<String[]> rows = rows(files(e));
        long inBand = rows.stream().filter(c -> c[2].equals("MULE-HUB-01") && c[1].startsWith("SMURF-"))
                .filter(c -> Double.parseDouble(c[4]) >= 900 && Double.parseDouble(c[4]) < 1000).count();
        assertEquals(e.get("inBandSmurfLegs").asInt(), inBand, "in-band structuring legs into the hub");
        double till6 = 0, all = 0;
        for (String[] c : rows) {
            if (!c[3].equals("cash_out")) continue;
            double amt = Double.parseDouble(c[4]);
            all += amt;
            if (c[2].equals("TILL-06")) till6 += amt;
        }
        assertTrue(till6 / all >= e.get("tillShareMin").asDouble(), "TILL-06 share of cash-out " + till6 / all);
    }
}
