package com.gamma.la.api;

import com.gamma.la.core.Algorithm;
import com.gamma.la.core.GraphResult;
import com.gamma.la.core.LinkIds;
import com.gamma.la.graph.GraphAlgorithms.Score;
import com.gamma.la.graph.GraphAlgorithms.Selection;
import com.gamma.la.graph.GraphCentrality.PredictedLink;
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
 * <p>Wire shape: {@code {algorithm, kind, dropped, elapsedMs, ...the variant's own fields}}.
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

    static Map<String, Object> of(GraphResult r, Ids ids) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("algorithm", r.algorithm().id());
        out.put("kind", r.payload().kind().name());
        out.put("dropped", r.dropped());
        out.put("elapsedMs", r.elapsedMs());
        out.putAll(data(r.algorithm(), r.payload(), ids));
        return out;
    }

    /** The algorithms whose {@code Ids} payload names EDGES (the rest name nodes) - pinned by {@code GraphResultJsonTest}. */
    static boolean idsAreEdges(Algorithm a) {
        return a == Algorithm.BRIDGES;
    }

    private static Map<String, Object> data(Algorithm a, GraphResult.Payload payload, Ids ids) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (payload) {
            case GraphResult.Scores p -> m.put("scores", scores(p.scores(), ids));
            case GraphResult.Hits p -> {
                m.put("hubs", scores(p.hubs(), ids));
                m.put("authorities", scores(p.authorities(), ids));
            }
            case GraphResult.OneSelection p -> m.put("selection", p.selection() == null ? null : selection(p.selection(), ids));
            case GraphResult.Selections p -> {
                List<Object> all = new ArrayList<>();
                for (Selection s : p.selections()) all.add(selection(s, ids));
                m.put("selections", all);
            }
            case GraphResult.Groups p -> {
                List<Object> groups = new ArrayList<>();
                for (List<String> g : p.groups()) groups.add(nodes(g, ids));
                m.put("groups", groups);
            }
            case GraphResult.Communities p -> {
                // An ordered list of pairs, not an object: the algorithm's pair order is part of the answer. A community
                // id is one of the member node ids (label propagation) or a number (Louvain); masking an exact id
                // match covers the first and leaves the second alone.
                List<Object> pairs = new ArrayList<>();
                p.communityOf().forEach((node, community) -> {
                    Map<String, Object> pair = new LinkedHashMap<>();
                    pair.put("id", ids.node(node));
                    pair.put("community", ids.node(community));
                    pairs.add(pair);
                });
                m.put("communities", pairs);
            }
            case GraphResult.Ids p -> {
                List<Object> out = new ArrayList<>();
                for (String id : p.ids()) out.add(idsAreEdges(a) ? ids.edge(id) : ids.node(id));
                m.put("ids", out);
            }
            case GraphResult.Flag p -> m.put("value", p.value());
            case GraphResult.Flow p -> {
                m.put("value", p.flow().value());
                m.put("minCut", p.flow().minCut() == null ? null : selection(p.flow().minCut(), ids));
            }
            case GraphResult.Links p -> {
                List<Object> links = new ArrayList<>();
                for (PredictedLink l : p.links()) {
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
                for (Suspicion s : p.scores()) {
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
            case GraphResult.SubGraph p -> {
                List<Object> nodes = new ArrayList<>();
                p.graph().nodes().forEach(n -> {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("id", ids.node(n.id()));
                    o.put("label", ids.node(n.label()));
                    nodes.add(o);
                });
                List<Object> edges = new ArrayList<>();
                p.graph().edges().forEach(e -> {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("id", ids.edge(e.id()));
                    o.put("source", ids.node(e.source()));
                    o.put("target", ids.node(e.target()));
                    edges.add(o);
                });
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

    private static Map<String, Object> selection(Selection s, Ids ids) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("nodeIds", nodes(s.nodeIds(), ids));
        List<Object> edges = new ArrayList<>(s.edgeIds().size());
        for (String e : s.edgeIds()) edges.add(ids.edge(e));
        o.put("edgeIds", edges);
        return o;
    }

    private static List<Object> nodes(List<String> in, Ids ids) {
        List<Object> out = new ArrayList<>(in.size());
        for (String id : in) out.add(ids.node(id));
        return out;
    }
}
