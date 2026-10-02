package com.gamma.la.core;

import com.gamma.la.graph.GraphPaths;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The adapter from a Working Set to a {@link GraphInput} (design §3.2). It takes the NEUTRAL shape of the Working Set's
 * two relations — the row maps {@code GET …/working-set?of=entities} and {@code ?of=links} serve — so {@code inspecto-la-core}
 * does not depend on {@code inspecto-la-api}'s {@code Relation}; the route passes {@code relation.tables().get("entities")}
 * and {@code .get("links")}.
 *
 * <ul>
 *   <li><b>Nodes</b> — one per entity row; id and label are the {@code entityId} (the value as read, which is what the SPA
 *       draws and what an op must send).</li>
 *   <li><b>Edge ids</b> — the D-U9 wire id, {@link LinkIds#encode(String, String, String)} of
 *       {@code (source, target, kind)}: exactly the {@code linkId} the links relation serves and the SPA receives, so a
 *       returned edge id names the link the analyst sees. (The SPA's own canvas ids, {@code sid->tid:kind}, depend on its
 *       client-side projection and are not reproducible here; step 7 maps {@code linkId} to the canvas edge.) These are the
 *       RAW ids — the rows are the evaluator's unmasked cache; masking a result (D-U6) is the route's job.</li>
 *   <li><b>Weights</b> — the link {@code count} through {@link GraphPaths#edgeWeight}, the port of {@code edgeWeight()}
 *       in {@code graph-analysis.ts}: a positive count, else 1.</li>
 *   <li><b>Kinds</b> — {@code kinds} (null or empty = all) keeps only links of those kinds; a filtered link is not
 *       "dropped", it was never asked for.</li>
 *   <li><b>Closed graph</b> — a link whose endpoint is not an entity row is dropped and counted
 *       ({@link GraphInput#droppedDangling()}), never silent.</li>
 * </ul>
 * Not decided here (the route chooses the rows it passes): entities a {@code hide} op hid (the browser leaves them off
 * its canvas) and the identity groups of a {@code resolve}.
 */
public final class WorkingSetGraphInput {

    private WorkingSetGraphInput() {}

    public static GraphInput.Materialised from(List<Map<String, Object>> entityRows, List<Map<String, Object>> linkRows,
                                  Collection<String> kinds) {
        Set<String> wanted = kinds == null || kinds.isEmpty() ? null : Set.copyOf(kinds);
        List<GraphInput.Node> nodes = entityRows.stream()
                .map(r -> String.valueOf(r.get("entityId")))
                .map(id -> new GraphInput.Node(id, id))
                .toList();
        List<GraphInput.Edge> edges = new ArrayList<>();
        Map<String, Double> weights = new LinkedHashMap<>();
        for (Map<String, Object> r : linkRows) {
            String source = String.valueOf(r.get("source"));
            String target = String.valueOf(r.get("target"));
            String kind = String.valueOf(r.get("kind"));          // a null kind is the literal "null", as the evaluator keys it
            if (wanted != null && !wanted.contains(kind)) continue;
            String id = LinkIds.encode(source, target, kind);
            edges.add(new GraphInput.Edge(id, source, target));
            Double count = r.get("count") instanceof Number n ? n.doubleValue() : null;
            weights.put(id, GraphPaths.edgeWeight(count, kind));
        }
        return GraphInput.of(nodes, edges, weights);
    }
}
