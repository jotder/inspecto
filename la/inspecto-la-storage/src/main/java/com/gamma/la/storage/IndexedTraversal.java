package com.gamma.la.storage;

import com.gamma.la.storage.IndexReader.Edge;
import com.gamma.la.storage.IndexReader.Side;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The simple-path walk of {@code POST /inv/traversal/recursive-paths}, answered FROM THE INDEX (D-3 step 5, design 4.4).
 * It reproduces the flat recursive CTE level by level: a path never revisits a node (cycle refusal), a level yields at most
 * {@code maxEdgeYield} paths (reported, never silent), parallel edges are separate paths, weight is the sum of
 * {@code COALESCE(w, 0)}, and the optional temporal constraint (time present, monotonic, total duration) is applied the way the
 * CTE's recursive member applies it. Output is {@code ORDER BY hops, path-json} capped at {@code limit}.
 *
 * <p><b>The Path A cap (design scope decision):</b> at most {@link #MAX_DEPTH} levels and {@link #FRONTIER_CAP} distinct
 * frontier keys per level, measured on the ACTUAL frontier. A wider frontier throws {@link FrontierOverCap}: the caller discards
 * everything walked so far and answers from the flat path, so one response never mixes the two.
 */
public final class IndexedTraversal {

    /** Deepest walk the index serves (design 4.1: 1 + 20 lookups at depth 2 is inside the 1.5 s criterion; depth 3 is not measured). */
    public static final int MAX_DEPTH = 2;
    /** Most distinct frontier keys looked up at one level (about 35 ms per key at 10^8). */
    public static final int FRONTIER_CAP = 20;

    private IndexedTraversal() { }

    /**
     * @param temporal   the request has a {@code temporalConstraint}: edges without a time are not walked
     * @param monotonic  each edge's time must not precede the previous edge's
     * @param maxHours   the whole path must span at most this many hours (null = unbounded)
     * @param maxGapHours each edge must follow the previous one within this many hours (null = unbounded; the caller requires {@code monotonic})
     * @param filterSql  rendered predicate over the index columns, or null
     */
    public record Params(String start, String target, boolean undirected, int maxDepth, int maxEdgeYield, int limit,
                         boolean temporal, boolean monotonic, Double maxHours, Double maxGapHours, String filterSql) { }

    public record PathRow(List<String> nodes, int hops, double weight) { }

    /** {@code limitTruncated}: more paths than {@code limit} existed; {@code yieldCapped}: some level reached its edge yield. */
    public record Result(List<PathRow> paths, boolean limitTruncated, boolean yieldCapped) { }

    /** The frontier at some level had more than {@link #FRONTIER_CAP} distinct keys. */
    public static final class FrontierOverCap extends Exception {
        private final int keys;

        FrontierOverCap(int keys) {
            super("frontier of " + keys + " keys exceeds the index cap of " + FRONTIER_CAP, null, false, false);
            this.keys = keys;
        }

        public int keys() { return keys; }
    }

    private record Walk(List<String> nodes, double weight, Long firstTs, Long lastTs) { }

    public static Result walk(IndexReader reader, Params p) throws SQLException, FrontierOverCap {
        if (p.maxDepth() > MAX_DEPTH) throw new IllegalArgumentException("maxDepth " + p.maxDepth() + " exceeds the index cap " + MAX_DEPTH);
        List<Side> sides = p.undirected() ? List.of(Side.OUT, Side.IN) : List.of(Side.OUT);
        boolean yieldCapped = 1 >= p.maxEdgeYield();                                  // level 0 holds the one start row, as in the CTE
        List<Walk> level = List.of(new Walk(List.of(p.start()), 0.0, null, null));
        List<Walk> all = new ArrayList<>();
        for (int depth = 0; depth < p.maxDepth(); depth++) {
            List<Walk> extendable = new ArrayList<>();
            Set<String> keys = new LinkedHashSet<>();
            for (Walk w : level) {
                String end = w.nodes().get(w.nodes().size() - 1);
                if (p.target() != null && end.equals(p.target())) continue;           // a path stops extending at the target
                extendable.add(w);
                keys.add(end);
            }
            if (keys.isEmpty()) break;
            if (keys.size() > FRONTIER_CAP) throw new FrontierOverCap(keys.size());
            Map<String, List<Edge>> edges = reader.edges(new ArrayList<>(keys), sides, p.filterSql(), p.maxEdgeYield());
            for (List<Edge> perKey : edges.values())
                if (perKey.size() >= p.maxEdgeYield()) yieldCapped = true;            // a key's own edges reached the yield: say so
            List<Walk> next = new ArrayList<>();
            for (Walk w : extendable) {
                String end = w.nodes().get(w.nodes().size() - 1);
                for (Edge e : edges.getOrDefault(end, List.of())) {
                    if (w.nodes().contains(e.neighbour())) continue;                  // cycle refusal: simple paths only
                    if (p.temporal() && e.tsMicros() == null) continue;
                    if (p.monotonic() && w.lastTs() != null && e.tsMicros() < w.lastTs()) continue;
                    if (p.maxGapHours() != null && w.lastTs() != null
                            && (e.tsMicros() - w.lastTs()) / 1_000_000.0 > p.maxGapHours() * 3600) continue;
                    if (p.maxHours() != null && w.firstTs() != null
                            && (e.tsMicros() - w.firstTs()) / 1_000_000.0 > p.maxHours() * 3600) continue;
                    List<String> nodes = new ArrayList<>(w.nodes().size() + 1);
                    nodes.addAll(w.nodes());
                    nodes.add(e.neighbour());
                    next.add(new Walk(nodes, w.weight() + (e.weight() == null ? 0.0 : e.weight()),
                            w.firstTs() != null ? w.firstTs() : e.tsMicros(), e.tsMicros()));
                }
            }
            if (next.size() >= p.maxEdgeYield()) {
                yieldCapped = true;
                if (next.size() > p.maxEdgeYield()) next = new ArrayList<>(next.subList(0, p.maxEdgeYield()));
            }
            all.addAll(next);
            level = next;
        }
        List<PathRow> rows = new ArrayList<>();
        Map<PathRow, byte[]> sortKey = new IdentityHashMap<>();
        for (Walk w : all) {
            if (p.target() != null && !w.nodes().get(w.nodes().size() - 1).equals(p.target())) continue;
            PathRow r = new PathRow(w.nodes(), w.nodes().size() - 1, w.weight());
            rows.add(r);
            sortKey.put(r, toJson(r.nodes()).getBytes(StandardCharsets.UTF_8));
        }
        rows.sort(Comparator.<PathRow>comparingInt(PathRow::hops).thenComparing(r -> sortKey.get(r), Arrays::compareUnsigned));
        boolean over = rows.size() > p.limit();
        return new Result(over ? List.copyOf(rows.subList(0, p.limit())) : List.copyOf(rows), over, yieldCapped);
    }

    /** The text DuckDB's {@code to_json(list)} gives for the ordering key: a JSON array of strings. */
    private static String toJson(List<String> nodes) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"');
            for (char c : nodes.get(i).toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            sb.append('"');
        }
        return sb.append(']').toString();
    }
}
