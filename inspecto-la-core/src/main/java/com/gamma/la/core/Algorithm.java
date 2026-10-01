package com.gamma.la.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The catalogue of the 28 graph algorithms the server can run (LA separation D-4; the ports in {@code inspecto-la-graph},
 * D-S4). One constant per ported function; {@link #id()} is the TypeScript export name, so the browser, the wire and the
 * Java port speak one name.
 *
 * <p><b>{@link #cost()} is a HINT, not a class.</b> The Java timings (design §6.2, {@code GraphAlgorithmsBench}) showed
 * that no algorithm is cheap at 10⁵ nodes, so SYNC/JOB cannot be a fixed split. {@link #cost()} keeps the design's
 * complexity estimate (SYNC = linear-ish, JOB = super-linear or unbounded); what the run service actually decides on is
 * {@link #inlineNodeCeiling()}: <i>inline up to N nodes, otherwise a job</i> (Decision 7).
 *
 * <p><b>How the ceilings were taken.</b> Rule: the largest benched node count (10³, 10⁴, 10⁵) whose median was at most
 * 1 000 ms (heavy-tailed graph, edges = 5 × nodes, JDK 27, 12 cores, 8 GB heap — design §6.2). Measured ms at 10³ / 10⁴ /
 * 10⁵ nodes are in each constant's comment ({@code t} = timeout above 60 s, {@code -} = not run). Where even 10³ took more
 * than 1 s (betweenness, suspicion) the ceiling is 500, by the measured quadratic growth (1 335 ms × (500/1 000)² ≈ 330 ms).
 * {@code egoNetwork} was not benched; it is {@code neighborhood} at one hop, so it takes {@code neighborhood}'s ceiling.
 * A ceiling is a node-count hint, not a time guarantee: {@code allPaths}, {@code findCycles} and {@code jaccardSimilarity}
 * are input-dependent (a hub, a dense region), so the run's own deadline stays the backstop.
 */
public enum Algorithm {

    // ── SYNC hints (linear-ish) ─────────────────────────────────────────────────────────────────────────────────────
    /** 2 / 11 / 543 ms. */
    SHORTEST_PATH("shortestPath", "Shortest path", Cost.SYNC, 100_000, ResultKind.SELECTION, false, true, true, false,
            Param.DIRECTION),
    /** 4 / 35 / 828 ms (3 hops). */
    NEIGHBORHOOD("neighborhood", "Neighborhood", Cost.SYNC, 100_000, ResultKind.GRAPH, false, false, false, true,
            Param.integer("hops", 1, 0, 100), Param.DIRECTION),
    /** Not benched: {@code neighborhood} at one hop. */
    EGO_NETWORK("egoNetwork", "Ego network", Cost.SYNC, 100_000, ResultKind.GRAPH, false, false, false, true, Param.DIRECTION),
    /** 2 / 8 / 189 ms. */
    DEGREE_CENTRALITY("degreeCentrality", "Degree centrality", Cost.SYNC, 100_000, ResultKind.SCORES, false, false, false, false),
    /** 4 / 30 / 615 ms. */
    CONNECTED_COMPONENTS("connectedComponents", "Connected components", Cost.SYNC, 100_000, ResultKind.GROUPS, false, false,
            false, false),
    /** 21 / 672 / t ms — accidentally quadratic (LA-GRAPH-QUADRATIC-1). */
    K_CORE("kCore", "k-core", Cost.SYNC, 10_000, ResultKind.SCORES, false, false, false, false),
    /** 14 / 143 / 4 418 ms. */
    TRIANGLE_COUNT("triangleCount", "Triangle count", Cost.SYNC, 10_000, ResultKind.SCORES, false, false, false, false),
    /** 10 / 38 / 866 ms. */
    ARTICULATION_POINTS("articulationPoints", "Articulation points", Cost.SYNC, 100_000, ResultKind.IDS, false, false, false,
            false),
    /** 3 / 35 / 878 ms. */
    BRIDGES("bridges", "Bridges", Cost.SYNC, 100_000, ResultKind.IDS, false, false, false, false),
    /** 2 / 16 / 254 ms. */
    IS_FOREST("isForest", "Is forest", Cost.SYNC, 100_000, ResultKind.FLAG, false, false, false, false),
    /** 2 / 12 / 224 ms. */
    DESCENDANTS("descendants", "Descendants", Cost.SYNC, 100_000, ResultKind.IDS, false, false, false, true),
    /** 46 / 1 740 / t ms — accidentally quadratic (LA-GRAPH-QUADRATIC-1). */
    WEIGHTED_SHORTEST_PATH("weightedShortestPath", "Weighted shortest path", Cost.SYNC, 1_000, ResultKind.SELECTION, true,
            true, true, false, Param.DIRECTION),
    /** 8 / 73 / 1 066 ms. */
    MAXIMUM_SPANNING_FOREST("maximumSpanningForest", "Maximum spanning forest", Cost.SYNC, 10_000, ResultKind.SELECTION, true,
            false, false, false),
    /** 13 / 385 / 36 898 ms (the biggest hub at 10⁵: input-dependent). */
    JACCARD_SIMILARITY("jaccardSimilarity", "Jaccard similarity", Cost.SYNC, 10_000, ResultKind.SCORES, false, false, false,
            true),
    /** 61 / 356 / 11 610 ms. */
    PAGE_RANK("pageRank", "PageRank", Cost.SYNC, 10_000, ResultKind.SCORES, false, false, false, false,
            Param.decimal("damping", 0.85, 0, 1), Param.integer("iterations", 60, 0, 10_000)),

    // ── JOB hints (super-linear or unbounded) ──────────────────────────────────────────────────────────────────────
    /** 1 335 / t / - ms — quadratic; ceiling by extrapolation (see the class comment). */
    BETWEENNESS_CENTRALITY("betweennessCentrality", "Betweenness centrality", Cost.JOB, 500, ResultKind.SCORES, false, false,
            false, false),
    /** 393 / t / - ms. */
    CLOSENESS_CENTRALITY("closenessCentrality", "Closeness centrality", Cost.JOB, 1_000, ResultKind.SCORES, false, false,
            false, false),
    /** 1 366 / t / - ms — composes betweenness; ceiling by extrapolation. */
    SUSPICION_SCORE("suspicionScore", "Suspicion score", Cost.JOB, 500, ResultKind.SUSPICION, false, false, false, false,
            Param.decimal("degree", 1, 0, 1_000), Param.decimal("betweenness", 1, 0, 1_000),
            Param.decimal("pageRank", 1, 0, 1_000), Param.decimal("core", 1, 0, 1_000),
            Param.decimal("triangles", 1, 0, 1_000)),
    /** 39 / 616 / 4 642 ms. */
    LOUVAIN_COMMUNITIES("louvainCommunities", "Louvain communities", Cost.JOB, 10_000, ResultKind.COMMUNITIES, false, false,
            false, false),
    /** 14 / 239 / 8 831 ms. */
    DETECT_COMMUNITIES("detectCommunities", "Label-propagation communities", Cost.JOB, 10_000, ResultKind.COMMUNITIES, false,
            false, false, false, Param.integer("maxIterations", 20, 0, 10_000)),
    /** 38 / 2 376 / t ms — worst case exponential in clique count. */
    CLIQUES("cliques", "Cliques", Cost.JOB, 1_000, ResultKind.GROUPS, false, false, false, false,
            Param.integer("minSize", 3, 0, 1_000)),
    /** 0 / 21 / 292 ms (limit 50, 8 hops) — input-dependent. */
    FIND_CYCLES("findCycles", "Find cycles", Cost.JOB, 100_000, ResultKind.SELECTIONS, false, false, false, false,
            Param.integer("limit", 50, 0, 10_000), Param.integer("maxLen", 8, 1, 64)),
    /** 3 / 44 / 16 839 ms (limit 10, 8 hops; 2.9 GB allocated at 10⁵) — input-dependent. */
    ALL_PATHS("allPaths", "All paths", Cost.JOB, 10_000, ResultKind.SELECTIONS, false, true, true, false,
            Param.integer("limit", 10, 0, 10_000), Param.integer("maxHops", 8, 1, 64), Param.DIRECTION),
    /** 76 / 7 308 / t ms. */
    MAX_FLOW("maxFlow", "Maximum flow", Cost.JOB, 1_000, ResultKind.FLOW, true, true, true, false),
    /** 129 / 13 728 / t ms — O(N²) pair loop (LA-GRAPH-QUADRATIC-1). */
    LINK_PREDICTION("linkPrediction", "Link prediction", Cost.JOB, 1_000, ResultKind.LINKS, false, false, false, false,
            Param.choice("method", "adamic-adar", "common-neighbors", "adamic-adar"), Param.integer("limit", 20, 0, 10_000)),
    /** 60 / 1 725 / 52 999 ms. */
    EIGENVECTOR_CENTRALITY("eigenvectorCentrality", "Eigenvector centrality", Cost.JOB, 1_000, ResultKind.SCORES, false,
            false, false, false, Param.integer("iterations", 100, 0, 10_000)),
    /** 78 / 1 623 / 48 699 ms. */
    KATZ_CENTRALITY("katzCentrality", "Katz centrality", Cost.JOB, 1_000, ResultKind.SCORES, false, false, false, false,
            Param.decimal("alpha", 0.1, 0, 1), Param.decimal("beta", 1, 0, 1_000), Param.integer("iterations", 100, 0, 10_000)),
    /** 91 / 1 823 / 49 213 ms. */
    HITS("hits", "HITS hubs and authorities", Cost.JOB, 1_000, ResultKind.HITS, false, false, false, false,
            Param.integer("iterations", 100, 0, 10_000));

    /** The design's complexity estimate — a HINT; see {@link Algorithm#inlineNodeCeiling()} for the decision input. */
    public enum Cost { SYNC, JOB }

    /** The shape of the answer; each is one {@link GraphResult.Payload} variant. */
    public enum ResultKind { SCORES, HITS, SELECTION, SELECTIONS, GROUPS, COMMUNITIES, IDS, FLAG, FLOW, LINKS, SUSPICION, GRAPH }

    /** The request names of the node-id parameters: {@code from}/{@code to} (a pair) and {@code node} (one). */
    public static final String FROM = "from", TO = "to", NODE = "node";

    private final String id;
    private final String label;
    private final Cost cost;
    private final int inlineNodeCeiling;
    private final ResultKind resultKind;
    private final boolean needsWeights;
    private final boolean needsSource;
    private final boolean needsTarget;
    private final boolean needsNode;
    private final List<Param> params;

    Algorithm(String id, String label, Cost cost, int inlineNodeCeiling, ResultKind resultKind, boolean needsWeights,
              boolean needsSource, boolean needsTarget, boolean needsNode, Param... params) {
        this.id = id;
        this.label = label;
        this.cost = cost;
        this.inlineNodeCeiling = inlineNodeCeiling;
        this.resultKind = resultKind;
        this.needsWeights = needsWeights;
        this.needsSource = needsSource;
        this.needsTarget = needsTarget;
        this.needsNode = needsNode;
        this.params = List.of(params);
    }

    /** The stable wire id (the TypeScript export name). */
    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public Cost cost() {
        return cost;
    }

    /** Inline (answer in the request) up to this many nodes; above it, run as a job. See the class comment. */
    public int inlineNodeCeiling() {
        return inlineNodeCeiling;
    }

    public ResultKind resultKind() {
        return resultKind;
    }

    /** True when the answer depends on edge weights ({@link GraphInput#weights()}). */
    public boolean needsWeights() {
        return needsWeights;
    }

    /** True when the request must carry {@link #FROM}. */
    public boolean needsSource() {
        return needsSource;
    }

    /** True when the request must carry {@link #TO}. */
    public boolean needsTarget() {
        return needsTarget;
    }

    /** True when the request must carry {@link #NODE}. */
    public boolean needsNode() {
        return needsNode;
    }

    /** The tunable parameters (not the node ids), each with its type, default and range. */
    public List<Param> params() {
        return params;
    }

    public static Algorithm byId(String id) {
        for (Algorithm a : values()) if (a.id.equals(id)) return a;
        throw new InvalidGraphRequest(InvalidGraphRequest.Reason.UNKNOWN_ALGORITHM, id, "unknown graph algorithm '" + id + "'");
    }

    /**
     * Validates {@code raw} against this algorithm's descriptors and returns the RESOLVED parameters: every declared
     * parameter present (default filled), numbers as {@code Integer}/{@code Double}, choices as their canonical
     * lower-case spelling, and each required node id as a non-blank {@code String}. Nothing is run; the route layer calls
     * this to refuse a bad request before it spends a job slot. Fail-closed: an unknown name is refused, not ignored.
     */
    public Map<String, Object> resolve(Map<String, ?> raw) {
        Map<String, ?> in = raw == null ? Map.of() : raw;
        List<String> known = new ArrayList<>();
        if (needsSource) known.add(FROM);
        if (needsTarget) known.add(TO);
        if (needsNode) known.add(NODE);
        for (Param p : params) known.add(p.name());
        for (String k : in.keySet())
            if (!known.contains(k))
                throw new InvalidGraphRequest(InvalidGraphRequest.Reason.UNKNOWN_PARAM, k,
                        id + " takes no parameter '" + k + "' (it takes: " + (known.isEmpty() ? "none" : String.join(", ", known)) + ")");
        Map<String, Object> out = new LinkedHashMap<>();
        if (needsSource) out.put(FROM, nodeId(in, FROM));
        if (needsTarget) out.put(TO, nodeId(in, TO));
        if (needsNode) out.put(NODE, nodeId(in, NODE));
        for (Param p : params) out.put(p.name(), p.resolve(id, in.get(p.name())));
        return out;
    }

    private String nodeId(Map<String, ?> in, String name) {
        Object v = in.get(name);
        if (v == null || (v instanceof String s && s.isBlank()))
            throw new InvalidGraphRequest(InvalidGraphRequest.Reason.MISSING_PARAM, name, id + " needs '" + name + "', a node id");
        if (!(v instanceof String s))
            throw new InvalidGraphRequest(InvalidGraphRequest.Reason.BAD_TYPE, name, id + ": '" + name + "' must be a node id string");
        return s;
    }

    /** One tunable parameter of an algorithm: its name, type, default and range (or allowed choices). */
    public record Param(String name, Type type, Object defaultValue, Double min, Double max, List<String> allowed) {

        public enum Type { INT, DOUBLE, ENUM }

        /** The shared edge-direction parameter. */
        public static final Param DIRECTION = choice("direction", "both", "out", "in", "both");

        public static Param integer(String name, int dflt, int min, int max) {
            return new Param(name, Type.INT, dflt, (double) min, (double) max, List.of());
        }

        public static Param decimal(String name, double dflt, double min, double max) {
            return new Param(name, Type.DOUBLE, dflt, min, max, List.of());
        }

        public static Param choice(String name, String dflt, String... allowed) {
            return new Param(name, Type.ENUM, dflt, null, null, List.of(allowed));
        }

        Object resolve(String algorithm, Object v) {
            if (v == null) return defaultValue;
            switch (type) {
                case ENUM -> {
                    String s = v instanceof String str ? str.trim().toLowerCase(Locale.ROOT) : null;
                    if (s == null)
                        throw new InvalidGraphRequest(InvalidGraphRequest.Reason.BAD_TYPE, name, algorithm + ": '" + name + "' must be a string");
                    if (!allowed.contains(s))
                        throw new InvalidGraphRequest(InvalidGraphRequest.Reason.OUT_OF_RANGE, name,
                                algorithm + ": '" + name + "' must be one of " + allowed + ", got '" + v + "'");
                    return s;
                }
                case INT, DOUBLE -> {
                    if (!(v instanceof Number n))
                        throw new InvalidGraphRequest(InvalidGraphRequest.Reason.BAD_TYPE, name, algorithm + ": '" + name + "' must be a number");
                    double d = n.doubleValue();
                    if (!Double.isFinite(d))
                        throw new InvalidGraphRequest(InvalidGraphRequest.Reason.BAD_TYPE, name, algorithm + ": '" + name + "' must be finite");
                    if (type == Type.INT && d != Math.rint(d))
                        throw new InvalidGraphRequest(InvalidGraphRequest.Reason.BAD_TYPE, name, algorithm + ": '" + name + "' must be an integer, got " + v);
                    if (d < min || d > max)
                        throw new InvalidGraphRequest(InvalidGraphRequest.Reason.OUT_OF_RANGE, name,
                                algorithm + ": '" + name + "' must be within [" + trim(min) + ", " + trim(max) + "], got " + v);
                    return type == Type.INT ? (Object) (int) d : (Object) d;
                }
            }
            throw new IllegalStateException(type.name());
        }

        private static String trim(double d) {
            return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
    }
}
