package com.gamma.la.api;

import com.gamma.la.core.Algorithm;
import com.gamma.la.core.GraphResult;
import com.gamma.la.core.LinkIds;
import com.gamma.la.graph.GraphAlgorithms.Score;
import com.gamma.la.graph.GraphAlgorithms.Selection;
import com.gamma.la.graph.GraphCentrality.PredictedLink;
import com.gamma.la.graph.GraphPropagation.Factor;
import com.gamma.la.graph.GraphPropagation.Risk;
import com.gamma.la.graph.GraphSuspicion.Suspicion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ONE serializer of a {@link GraphResult} (LA separation D-4 step 6, design §3.4, D-U6): turns the sealed
 * {@link GraphResult.Payload} into a JSON-ready tree and masks every id on the way out. The engine and the result cache
 * hold RAW ids (so ranks and tie-breaks are the true ones, one cache entry per Working Set); nothing leaves except
 * through here.
 *
 * <p><b>Exhaustive.</b> {@link #data} is a {@code switch} over the sealed payload with no {@code default}: a payload
 * variant added to {@link GraphResult.Payload} does not compile here until it is given a case - and a case is where its
 * ids get masked. That is the whole point of having one serializer rather than a mapper per call site.
 *
 * <p><b>Edge ids are wire ids</b> ({@link LinkIds#encode}): masked by decoding, masking the endpoint entity ids (and the
 * kind, exactly as the Working Set's links relation does) and encoding again, so a masked edge id equals the {@code linkId}
 * the masked Working Set serves for that link and carries no raw value. A masked ranking keeps the raw order.
 *
 * <p>Wire shape: {@code {algorithm, kind, dropped, elapsedMs, ...the variant's own fields, truncated, lists}}.
 */
final class GraphResultJson {

    /** How ids leave the server: identity when nothing is masked, else the Investigation's pseudonyms. */
    interface Ids {
        String node(String raw);

        String edge(String wireId);

        Ids NONE = new Ids() {
            @Override public String node(String raw) { return raw; }
            @Override public String edge(String wireId) { return wireId; }
        };

        /** Masks node ids by {@code mask}; an edge id by decode, mask both endpoints and the kind, re-encode. */
        static Ids of(EntityMasking mask) {
            if (!mask.masking()) return NONE;
            return new Ids() {
                @Override
                public String node(String raw) {
                    return (String) mask.apply(raw);
                }

                @Override
                public String edge(String wireId) {
                    List<String> p;
                    try {
                        p = LinkIds.decode(wireId);
                    } catch (RuntimeException notAWireId) {
                        // An edge id the adapter minted always decodes; one that does not would leak if passed through.
                        throw new IllegalStateException("a graph run produced an edge id that is not a wire id");
                    }
                    return LinkIds.encode(node(p.get(0)), node(p.get(1)), node(p.get(2)));
                }
            };
        }
    }

    private GraphResultJson() {}

    /** Items per list when {@code graph_run.max_result_items} is not stated. */
    static final int DEFAULT_MAX_RESULT_ITEMS = 10_000;
    /** The hard ceiling of {@code graph_run.max_result_items}: a stated value above it is clamped, and the clamp is echoed. */
    static final int MAX_RESULT_ITEMS = 1_000_000;

    static Map<String, Object> of(GraphResult r, Ids ids) {
        return of(r, ids, DEFAULT_MAX_RESULT_ITEMS);
    }

    /**
     * <b>Never a silent cap.</b> Each list-shaped part of the payload (scores, hubs, authorities, groups, ids, communities,
     * links, suspicions, selections, sub-graph nodes and edges (an edge is returned only when both its nodes are), a selection's node and edge ids) is cut to its first
     * {@code maxItems} entries - the engine's canonical-v1 order, so the top of a ranking survives - and the cut is SAID:
     * {@code truncated} is true and {@code lists.<key> = {total, returned, limit, truncated}} gives the full count. Every
     * top-level list has a {@code lists} entry whether cut or not; a list nested inside another (one group, one path) gets
     * an entry, keyed {@code groups[2]}, only when it was cut. The cache holds the FULL result; the cut is applied here, on
     * the way out (like masking), so raising the setting needs no re-run.
     */
    static Map<String, Object> of(GraphResult r, Ids ids, int maxItems) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("algorithm", r.algorithm().id());
        out.put("kind", r.payload().kind().name());
        out.put("dropped", r.dropped());
        out.put("elapsedMs", r.elapsedMs());
        Cap cap = new Cap(Math.max(1, maxItems));
        out.putAll(data(r.algorithm(), r.payload(), ids, cap));
        out.put("truncated", cap.truncated);
        out.put("lists", cap.lists);
        return out;
    }

    /** The response-size policy of one result: cuts a list to {@code limit} and records what it did, per list. */
    private static final class Cap {
        final int limit;
        final Map<String, Object> lists = new LinkedHashMap<>();
        boolean truncated;

        Cap(int limit) {
            this.limit = limit;
        }

        /** Re-states a recorded list as cut from {@code total} to {@code returned} (edges dropped because their node was cut). */
        @SuppressWarnings("unchecked")
        void restate(String key, int total, int returned) {
            Map<String, Object> o = (Map<String, Object>) lists.get(key);
            o.put("total", total);
            o.put("returned", returned);
            o.put("truncated", true);
            truncated = true;
        }

        /** The first {@code limit} entries of {@code in}. {@code always} = record the list even when not cut (top-level lists). */
        <T> List<T> cut(String key, List<T> in, boolean always) {
            boolean cut = in.size() > limit;
            if (cut || always) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("total", in.size());
                o.put("returned", cut ? limit : in.size());
                o.put("limit", limit);
                o.put("truncated", cut);
                lists.put(key, o);
            }
            if (cut) truncated = true;
            return cut ? in.subList(0, limit) : in;
        }
    }

    /** The algorithms whose {@code Ids} payload names EDGES (the rest name nodes) - pinned by {@code GraphResultJsonTest}. */
    static boolean idsAreEdges(Algorithm a) {
        return a == Algorithm.BRIDGES;
    }

    private static Map<String, Object> data(Algorithm a, GraphResult.Payload payload, Ids ids, Cap cap) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (payload) {
            case GraphResult.Scores p -> m.put("scores", scores(cap.cut("scores", p.scores(), true), ids));
            case GraphResult.Hits p -> {
                m.put("hubs", scores(cap.cut("hubs", p.hubs(), true), ids));
                m.put("authorities", scores(cap.cut("authorities", p.authorities(), true), ids));
            }
            case GraphResult.OneSelection p ->
                    m.put("selection", p.selection() == null ? null : selection(p.selection(), ids, cap, "selection"));
            case GraphResult.Selections p -> {
                List<Object> all = new ArrayList<>();
                List<Selection> kept = cap.cut("selections", p.selections(), true);
                for (int i = 0; i < kept.size(); i++) all.add(selection(kept.get(i), ids, cap, "selections[" + i + "]"));
                m.put("selections", all);
            }
            case GraphResult.Groups p -> {
                List<Object> groups = new ArrayList<>();
                List<List<String>> kept = cap.cut("groups", p.groups(), true);
                for (int i = 0; i < kept.size(); i++) groups.add(nodes(cap.cut("groups[" + i + "]", kept.get(i), false), ids));
                m.put("groups", groups);
            }
            case GraphResult.Communities p -> {
                // An ordered list of pairs, not an object: the algorithm's pair order is part of the answer. A community
                // id is one of the member node ids (label propagation) or a number (Louvain); masking an exact id
                // match covers the first and leaves the second alone.
                List<Object> pairs = new ArrayList<>();
                for (Map.Entry<String, String> e : cap.cut("communities", new ArrayList<>(p.communityOf().entrySet()), true)) {
                    Map<String, Object> pair = new LinkedHashMap<>();
                    pair.put("id", ids.node(e.getKey()));
                    pair.put("community", ids.node(e.getValue()));
                    pairs.add(pair);
                }
                m.put("communities", pairs);
            }
            case GraphResult.Ids p -> {
                List<Object> out = new ArrayList<>();
                for (String id : cap.cut("ids", p.ids(), true)) out.add(idsAreEdges(a) ? ids.edge(id) : ids.node(id));
                m.put("ids", out);
            }
            case GraphResult.Flag p -> m.put("value", p.value());
            case GraphResult.Flow p -> {
                m.put("value", p.flow().value());
                m.put("minCut", p.flow().minCut() == null ? null : selection(p.flow().minCut(), ids, cap, "minCut"));
            }
            case GraphResult.Links p -> {
                List<Object> links = new ArrayList<>();
                for (PredictedLink l : cap.cut("links", p.links(), true)) {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("source", ids.node(l.source()));
                    o.put("target", ids.node(l.target()));
                    o.put("sourceLabel", ids.node(l.sourceLabel()));
                    o.put("targetLabel", ids.node(l.targetLabel()));
                    o.put("score", l.score());
                    links.add(o);
                }
                m.put("links", links);
            }
            case GraphResult.Suspicions p -> {
                List<Object> out = new ArrayList<>();
                for (Suspicion s : cap.cut("scores", p.scores(), true)) {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("id", ids.node(s.id()));
                    o.put("label", ids.node(s.label()));
                    o.put("score", s.score());
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("degree", s.factors().degree());
                    f.put("betweenness", s.factors().betweenness());
                    f.put("pageRank", s.factors().pageRank());
                    f.put("core", s.factors().core());
                    f.put("triangles", s.factors().triangles());
                    o.put("factors", f);
                    out.add(o);
                }
                m.put("scores", out);
            }
            case GraphResult.PropagatedRisks p -> {
                // factors are at most GraphPropagation.FACTOR_LIMIT and are said by 'contributors': not cut here
                List<Object> out = new ArrayList<>();
                for (Risk r : cap.cut("scores", p.risks(), true)) {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("id", ids.node(r.id()));
                    o.put("label", ids.node(r.label()));
                    o.put("score", r.score());
                    o.put("raw", r.raw());
                    o.put("own", r.own());
                    o.put("contributors", r.contributors());
                    List<Object> factors = new ArrayList<>();
                    for (Factor f : r.factors()) {
                        Map<String, Object> fo = new LinkedHashMap<>();
                        fo.put("origin", ids.node(f.origin()));
                        fo.put("distance", f.distance());
                        fo.put("weight", f.weight());
                        fo.put("contribution", f.contribution());
                        factors.add(fo);
                    }
                    o.put("factors", factors);
                    out.add(o);
                }
                m.put("scores", out);
            }
            case GraphResult.SubGraph p -> {
                List<Object> nodes = new ArrayList<>();
                List<com.gamma.la.graph.GraphAlgorithms.Node> keptNodes = cap.cut("nodes", p.graph().nodes(), true);
                keptNodes.forEach(n -> {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("id", ids.node(n.id()));
                    o.put("label", ids.node(n.label()));
                    nodes.add(o);
                });
                // An edge whose endpoint node was cut would dangle: keep only edges with both endpoints returned, and say
                // so in lists.edges (total = every edge of the result, returned = what is here).
                List<com.gamma.la.graph.GraphAlgorithms.Edge> allEdges = p.graph().edges();
                List<com.gamma.la.graph.GraphAlgorithms.Edge> reachable = allEdges;
                if (keptNodes.size() < p.graph().nodes().size()) {
                    java.util.Set<String> keptIds = new java.util.HashSet<>();
                    keptNodes.forEach(n -> keptIds.add(n.id()));
                    reachable = allEdges.stream().filter(e -> keptIds.contains(e.source()) && keptIds.contains(e.target())).toList();
                }
                List<Object> edges = new ArrayList<>();
                cap.cut("edges", reachable, true).forEach(e -> {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("id", ids.edge(e.id()));
                    o.put("source", ids.node(e.source()));
                    o.put("target", ids.node(e.target()));
                    edges.add(o);
                });
                if (reachable.size() < allEdges.size()) cap.restate("edges", allEdges.size(), edges.size());
                m.put("nodes", nodes);
                m.put("edges", edges);
            }
        }
        return m;
    }

    private static List<Object> scores(List<Score> in, Ids ids) {
        List<Object> out = new ArrayList<>(in.size());
        for (Score s : in) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", ids.node(s.id()));
            o.put("label", ids.node(s.label()));
            o.put("score", s.score());
            out.add(o);
        }
        return out;
    }

    /** A selection's id lists are cut too; {@code at} names it in {@code lists} (an entry only when a list was cut, or for the top-level one). */
    private static Map<String, Object> selection(Selection s, Ids ids, Cap cap, String at) {
        boolean top = !at.contains("[");                       // selections[i] are nested in a cut list already
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("nodeIds", nodes(cap.cut(at + ".nodeIds", s.nodeIds(), top), ids));
        List<String> keptEdges = cap.cut(at + ".edgeIds", s.edgeIds(), top);
        List<Object> edges = new ArrayList<>(keptEdges.size());
        for (String e : keptEdges) edges.add(ids.edge(e));
        o.put("edgeIds", edges);
        return o;
    }

    private static List<Object> nodes(List<String> in, Ids ids) {
        List<Object> out = new ArrayList<>(in.size());
        for (String id : in) out.add(ids.node(id));
        return out;
    }
}
