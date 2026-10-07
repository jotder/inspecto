package com.gamma.la.storage;

import com.gamma.la.core.Algorithm;
import com.gamma.la.core.GraphEngine;
import com.gamma.la.core.GraphInput;
import com.gamma.la.core.GraphResult;
import com.gamma.la.core.IndexCapExceeded;
import com.gamma.la.core.InvalidGraphRequest;
import com.gamma.la.core.LinkIds;
import com.gamma.la.graph.GraphAlgorithms.Direction;
import com.gamma.la.graph.GraphAlgorithms.Edge;
import com.gamma.la.graph.GraphAlgorithms.Graph;
import com.gamma.la.graph.GraphAlgorithms.Node;
import com.gamma.la.graph.GraphAlgorithms.Score;
import com.gamma.la.graph.RunControl;
import com.gamma.la.storage.IndexReader.Folded;
import com.gamma.la.storage.IndexReader.Side;
import com.gamma.sql.SqlSandboxPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The index-backed {@link GraphEngine} (D-3 step 7, design 4.2): {@code neighborhood}, {@code egoNetwork} and a seeds-only
 * {@code degreeCentrality}, answered from ONE pinned index version by one equality statement per key
 * ({@link IndexReader#fold}); nothing is materialised beyond the keys it looked up.
 *
 * <p><b>Fences, never a reroute.</b> At most {@link #MAX_HOPS} hops, and at most {@link #FRONTIER_CAP} keys looked up at any one
 * level - the expansion levels AND the final pass that collects the edges among the kept nodes. A walk that needs more throws
 * {@link IndexCapExceeded} naming the cap and the measured figure, dropping whatever it had gathered; the caller chose the
 * index, so nothing answers instead.
 *
 * <p><b>Same answer as the in-memory engine over the same graph</b> (proved by {@code SqlGraphEngineParityTest}): a link is a
 * distinct {@code (source, target, kind)} with the wire id {@link LinkIds#encode} (a NULL kind is the literal {@code "null"}, as
 * the Working Set keys it); a neighbourhood is the induced sub-graph on the nodes reached; a self-loop counts twice in a degree.
 * <b>Order</b> is canonical-v1: nodes by id and edges by id in UTF-16 code units (the in-memory engine returns the input's
 * order, and a Working Set has no order of its own), scores by score descending, then id, then label. The kind list keeps links
 * by the same {@code String.valueOf(kind)} test the Working Set adapter uses.
 *
 * <p><b>Nodes</b> are the endpoints of indexed rows, so a node with no row at all is not in the index: its neighbourhood is empty
 * and it is absent from a degree ranking, where the in-memory engine would know it as an isolated entity. {@code droppedDangling}
 * is always 0 - an index edge has both endpoints by construction (rows with a NULL endpoint are not indexed).
 */
public final class SqlGraphEngine implements GraphEngine {

    public static final String ENGINE_ID = "index";
    /** The walk's depth ceiling (the same figure as {@link IndexedTraversal#MAX_DEPTH}). */
    public static final int MAX_HOPS = IndexedTraversal.MAX_DEPTH;
    /** Keys looked up per level (the same figure as {@link IndexedTraversal#FRONTIER_CAP}). */
    public static final int FRONTIER_CAP = IndexedTraversal.FRONTIER_CAP;
    /** The cap's name in the refusal. */
    public static final String FRONTIER_CAP_NAME = "frontier keys looked up per level";

    private static final Set<Algorithm> SUPPORTED = Set.of(Algorithm.NEIGHBORHOOD, Algorithm.EGO_NETWORK, Algorithm.DEGREE_CENTRALITY);
    private static final Comparator<Score> BY_SCORE_THEN_ID_THEN_LABEL =
            Comparator.comparingDouble(Score::score).reversed().thenComparing(Score::id).thenComparing(Score::label);

    private final SqlSandboxPolicy policy;

    /** The algorithms an index can answer: the catalogue's `engines` and the route's refusal read this one set. */
    public static Set<Algorithm> nativeAlgorithms() {
        return SUPPORTED;
    }

    public SqlGraphEngine(SqlSandboxPolicy policy) {
        this.policy = policy;
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public Set<Algorithm> supported() {
        return SUPPORTED;
    }

    @Override
    public GraphResult run(Algorithm algorithm, Map<String, Object> params, GraphInput in, RunControl ctl) {
        if (algorithm == null)
            throw new InvalidGraphRequest(InvalidGraphRequest.Reason.UNKNOWN_ALGORITHM, null, "no algorithm named");
        if (!(in instanceof GraphInput.IndexRef ref))
            throw new IllegalArgumentException("the index engine runs only an index reference, not an input of kind '" + in.kind() + "'");
        if (!SUPPORTED.contains(algorithm))
            throw new InvalidGraphRequest(InvalidGraphRequest.Reason.UNKNOWN_ALGORITHM, algorithm.id(),
                    "'" + algorithm.id() + "' cannot run from the index (it can: neighborhood, egoNetwork, degreeCentrality)");
        Map<String, Object> p = algorithm.resolve(params);                        // refuses BEFORE any work
        RunControl c = ctl == null ? RunControl.NONE : ctl;
        int hops = switch (algorithm) {
            case NEIGHBORHOOD -> (Integer) p.get("hops");
            case EGO_NETWORK -> 1;
            default -> 0;
        };
        if (hops > MAX_HOPS)
            throw new InvalidGraphRequest(InvalidGraphRequest.Reason.OUT_OF_RANGE, "hops",
                    "hops " + hops + " is over the index cap of " + MAX_HOPS + " (a deeper neighbourhood runs on the Working Set)");
        long t0 = System.nanoTime();
        try (IndexReader reader = IndexReader.borrow(ref.versionDir(), IndexManifest.read(ref.versionDir()), policy)) {
            GraphResult.Payload payload = algorithm == Algorithm.DEGREE_CENTRALITY
                    ? new GraphResult.Scores(degrees(reader, ref, c))
                    : new GraphResult.SubGraph(subGraph(reader, ref, (String) p.get(Algorithm.NODE), hops,
                            Direction.valueOf(((String) p.get("direction")).toUpperCase(Locale.ROOT)), c));
            return new GraphResult(algorithm, payload, 0, (System.nanoTime() - t0) / 1_000_000L);
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("the index could not be read: " + e.getClass().getSimpleName(), e);
        }
    }

    // ── neighborhood / egoNetwork ────────────────────────────────────────────────────────────────────────────

    private static Graph subGraph(IndexReader reader, GraphInput.IndexRef ref, String root, int hops, Direction dir, RunControl c)
            throws SQLException {
        if (root.indexOf((char) 0) >= 0 || reader.degree(root) == 0) return new Graph(List.of(), List.of());          // not a node of the index
        Set<String> kinds = ref.kinds().isEmpty() ? null : Set.copyOf(ref.kinds());
        Map<String, List<Link>> outOf = new HashMap<>(), inOf = new HashMap<>();       // looked-up keys only
        Set<String> keep = new LinkedHashSet<>(List.of(root));
        List<String> frontier = List.of(root);
        for (int h = 0; h < hops && !frontier.isEmpty(); h++) {
            guard(frontier.size());
            List<String> next = new ArrayList<>();
            for (String key : frontier) {
                c.checkpoint();
                if (dir != Direction.IN)
                    for (Link l : links(reader, outOf, key, Side.OUT, kinds)) if (keep.add(l.target())) next.add(l.target());
                if (dir != Direction.OUT)
                    for (Link l : links(reader, inOf, key, Side.IN, kinds)) if (keep.add(l.source())) next.add(l.source());
            }
            frontier = next;
        }
        guard(keep.size());                                   // the edges among the kept nodes: one OUT lookup per kept node
        TreeMap<String, Edge> edges = new TreeMap<>();
        for (String key : keep) {
            c.checkpoint();
            for (Link l : links(reader, outOf, key, Side.OUT, kinds))
                if (keep.contains(l.target())) edges.put(l.id(), new Edge(l.id(), l.source(), l.target()));
        }
        List<Node> nodes = new ArrayList<>();
        for (String id : new java.util.TreeSet<>(keep)) nodes.add(new Node(id, id));
        return new Graph(nodes, new ArrayList<>(edges.values()));
    }

    private static void guard(int keys) {
        if (keys > FRONTIER_CAP) throw new IndexCapExceeded(FRONTIER_CAP_NAME, keys, FRONTIER_CAP);
    }

    // ── degreeCentrality (seeds only) ────────────────────────────────────────────────────────────────────────

    /**
     * The scores of the SEED nodes only, never of the whole graph: a node's score is its number of distinct links as source plus
     * as target (a self-loop is both, so it counts twice), exactly the in-memory engine's figure for that node. A seed that is not
     * a node of the index is absent from the ranking.
     */
    private static List<Score> degrees(IndexReader reader, GraphInput.IndexRef ref, RunControl c) throws SQLException {
        guard(ref.seeds().size());
        Set<String> kinds = ref.kinds().isEmpty() ? null : Set.copyOf(ref.kinds());
        List<Score> out = new ArrayList<>();
        for (String seed : new LinkedHashSet<>(ref.seeds())) {
            c.checkpoint();
            if (seed.indexOf((char) 0) >= 0 || reader.degree(seed) == 0) continue;                // an absent-node marker (a masked raw id) names no node
            int d = links(reader, new HashMap<>(), seed, Side.OUT, kinds).size() + links(reader, new HashMap<>(), seed, Side.IN, kinds).size();
            out.add(new Score(seed, seed, d));
        }
        out.sort(BY_SCORE_THEN_ID_THEN_LABEL);
        return out;
    }

    // ── one key, one side ────────────────────────────────────────────────────────────────────────────────────

    private record Link(String id, String source, String target) { }

    private static List<Link> links(IndexReader reader, Map<String, List<Link>> memo, String key, Side side, Set<String> kinds)
            throws SQLException {
        List<Link> hit = memo.get(key);
        if (hit != null) return hit;
        List<Link> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Folded f : reader.fold(key, side, List.of("kind"), null, null)) {
            String kind = String.valueOf(f.extras().get(0));                               // a NULL kind is the literal "null", as the Working Set keys it
            if (kinds != null && !kinds.contains(kind)) continue;
            String id = LinkIds.encode(f.source(), f.target(), kind);
            if (seen.add(id)) out.add(new Link(id, f.source(), f.target()));
        }
        memo.put(key, out);
        return out;
    }
}
