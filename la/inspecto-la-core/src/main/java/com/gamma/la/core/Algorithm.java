package com.gamma.la.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The catalogue of the 29 graph algorithms the server can run (LA separation D-4; the ports in {@code inspecto-la-graph},
 * D-S4). One constant per ported function; {@link #id()} is the TypeScript export name, so the browser, the wire and the
 * Java port speak one name. The 29th, {@link #PROPAGATED_RISK}, is server-side only (no TypeScript twin).
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
    /** 6 / 46 / 629 ms after LA-GRAPH-QUADRATIC-1 (bucket queue; was 21 / 672 / t). 10⁵ is 0.6-2.1 s with the machine busy: kept at 10⁴. */
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
    /** 10 / 100 / 931 ms after LA-GRAPH-QUADRATIC-1 (binary heap; was 46 / 1 740 / t). 10⁵ is 0.9-2.8 s with the machine busy: 10⁴. */
    WEIGHTED_SHORTEST_PATH("weightedShortestPath", "Weighted shortest path", Cost.SYNC, 10_000, ResultKind.SELECTION, true,
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
    /** 140 / 2 327 / t ms after LA-GRAPH-QUADRATIC-1 (2-hop candidates; was 129 / 13 728 / t). Output-bound: pairs sharing a hub neighbour. */
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
            Param.integer("iterations", 100, 0, 10_000)),
    /**
     * Not benched; server-side only (no TypeScript twin). One BFS per origin, at most {@code weights.size()} (≤ 6) hops deep:
     * never more than {@code closenessCentrality}'s unbounded BFS per node, so it takes closeness's ceiling.
     */
    PROPAGATED_RISK("propagatedRisk", "Propagated risk", Cost.JOB, 1_000, ResultKind.PROPAGATED_RISK, false, false, false,
            false, Param.scoreMap("nodeScores", 0, 100, Param.MAX_ENTRIES), Param.idList("seeds", Param.MAX_ENTRIES),
            Param.decimalList("weights", List.of(1.0, 0.6, 0.35, 0.15), 0, 1, 6), Param.DIRECTION);

    /** The design's complexity estimate — a HINT; see {@link Algorithm#inlineNodeCeiling()} for the decision input. */
    public enum Cost { SYNC, JOB }

    /** The shape of the answer; each is one {@link GraphResult.Payload} variant. */
    public enum ResultKind { SCORES, HITS, SELECTION, SELECTIONS, GROUPS, COMMUNITIES, IDS, FLAG, FLOW, LINKS, SUSPICION, GRAPH,
        PROPAGATED_RISK }

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

    /**
     * One tunable parameter of an algorithm: its name, type, default and range (or allowed choices). For the collection types
     * {@code min}/{@code max} bound each NUMBER in it and {@code maxSize} the entry count (null for the scalar types).
     * <ul>
     *   <li>{@code DOUBLE_LIST} - a non-empty JSON array of numbers; resolved to a {@code List<Double>} in the given order.</li>
     *   <li>{@code ID_LIST} - a JSON array of non-blank node-id strings; resolved sorted and de-duplicated (one cache key).</li>
     *   <li>{@code SCORE_MAP} - a JSON object node id → number; resolved to a {@code Map<String, Double>} sorted by id.</li>
     * </ul>
     */
    public record Param(String name, Type type, Object defaultValue, Double min, Double max, List<String> allowed, Integer maxSize) {

        public enum Type { INT, DOUBLE, ENUM, DOUBLE_LIST, ID_LIST, SCORE_MAP }

        /** The most entries a list or map parameter may take: the server's node ceiling ({@link GraphRunService.Limits#standard()}). */
        public static final int MAX_ENTRIES = 500_000;

        /** The shared edge-direction parameter. */
        public static final Param DIRECTION = choice("direction", "both", "out", "in", "both");

        public static Param integer(String name, int dflt, int min, int max) {
            return new Param(name, Type.INT, dflt, (double) min, (double) max, List.of(), null);
        }

        public static Param decimal(String name, double dflt, double min, double max) {
            return new Param(name, Type.DOUBLE, dflt, min, max, List.of(), null);
        }

        public static Param choice(String name, String dflt, String... allowed) {
            return new Param(name, Type.ENUM, dflt, null, null, List.of(allowed), null);
        }

        /** A non-empty list of numbers, each within {@code [min, max]}, at most {@code maxSize} long. */
        public static Param decimalList(String name, List<Double> dflt, double min, double max, int maxSize) {
            return new Param(name, Type.DOUBLE_LIST, List.copyOf(dflt), min, max, List.of(), maxSize);
        }

        /** A list of node ids, at most {@code maxSize}; the default is empty. */
        public static Param idList(String name, int maxSize) {
            return new Param(name, Type.ID_LIST, List.of(), null, null, List.of(), maxSize);
        }

        /** A node id → number map, each number within {@code [min, max]}, at most {@code maxSize} entries; the default is empty. */
        public static Param scoreMap(String name, double min, double max, int maxSize) {
            return new Param(name, Type.SCORE_MAP, Map.of(), min, max, List.of(), maxSize);
        }

        Object resolve(String algorithm, Object v) {
            if (v == null) return defaultValue;
            switch (type) {
                case DOUBLE_LIST -> {
                    if (!(v instanceof List<?> l))
                        throw new InvalidGraphRequest(InvalidGraphRequest.Reason.BAD_TYPE, name, algorithm + ": '" + name + "' must be a list of numbers");
                    size(algorithm, l.size(), 1);
                    List<Double> out = new ArrayList<>(l.size());
                    for (Object o : l) out.add(number(algorithm, o));
                    return List.copyOf(out);
                }
                case ID_LIST -> {
                    if (!(v instanceof List<?> l))
                        throw new InvalidGraphRequest(InvalidGraphRequest.Reason.BAD_TYPE, name, algorithm + ": '" + name + "' must be a list of node ids");
                    size(algorithm, l.size(), 0);
                    TreeSet<String> out = new TreeSet<>();
                    for (Object o : l) out.add(id(algorithm, o));
                    return List.copyOf(out);
                }
                case SCORE_MAP -> {
                    if (!(v instanceof Map<?, ?> m))
                        throw new InvalidGraphRequest(InvalidGraphRequest.Reason.BAD_TYPE, name, algorithm + ": '" + name + "' must be an object of node id to number");
                    size(algorithm, m.size(), 0);
                    TreeMap<String, Double> out = new TreeMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) out.put(id(algorithm, e.getKey()), number(algorithm, e.getValue()));
                    return Collections.unmodifiableMap(out);
                }
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

        private void size(String algorithm, int n, int least) {
            if (n < least || n > maxSize)
                throw new InvalidGraphRequest(InvalidGraphRequest.Reason.OUT_OF_RANGE, name,
                        algorithm + ": '" + name + "' must have " + least + " to " + maxSize + " entries, got " + n);
        }

        private double number(String algorithm, Object o) {
            if (!(o instanceof Number n) || !Double.isFinite(n.doubleValue()))
                throw new InvalidGraphRequest(InvalidGraphRequest.Reason.BAD_TYPE, name, algorithm + ": '" + name + "' holds a non-number: " + o);
            double d = n.doubleValue();
            if (d < min || d > max)
                throw new InvalidGraphRequest(InvalidGraphRequest.Reason.OUT_OF_RANGE, name,
                        algorithm + ": each number in '" + name + "' must be within [" + trim(min) + ", " + trim(max) + "], got " + o);
            return d;
        }

        private String id(String algorithm, Object o) {
            if (!(o instanceof String s) || s.isBlank())
                throw new InvalidGraphRequest(InvalidGraphRequest.Reason.BAD_TYPE, name, algorithm + ": '" + name + "' holds a blank or non-string node id");
            return s;
        }

        private static String trim(double d) {
            return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
    }
}
