package com.gamma.la.api;

import com.gamma.la.api.IndexedRead.Fitted;
import com.gamma.la.api.IndexedRead.Outcome;
import com.gamma.la.api.IndexedRead.Reason;
import com.gamma.la.api.IndexedRead.Selection;
import com.gamma.la.storage.IndexReader;
import com.gamma.la.storage.IndexReader.Folded;
import com.gamma.la.storage.IndexReader.Side;
import com.gamma.sql.SqlSandboxPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D-3 step 6 - the sealed one-hop read behind an Investigation {@code expand} (D-E3), answered FROM THE INDEX for a SIMPLE rung.
 * Simple means the rung needs nothing but the folded links of its frontier: no {@code window}, no {@code minDistinctDays}, no
 * candidate degree bound (a degree is a property of the whole windowed graph, not of the frontier), no merged traversal, and a
 * frontier of at most {@link #FRONTIER_CAP} distinct entities. Any other rung keeps the flat CTE untouched
 * ({@link Reason#rung_not_indexable}, with the cause in {@code details}).
 *
 * <p>It reproduces {@code InvestigationRoutes.read}'s shape step by step, because the sealed {@code fingerprint} is
 * {@code sha256(canonical(rows))}: links of an allowed kind touching no excluded entity, folded per (source, target, kind) -
 * every such pair touching a frontier entity lies wholly in that entity's {@code out} (as source) or {@code in} (as target)
 * bucket, so the per-frontier folds are exact - then the direction filter (with {@code reciprocal}'s reverse-pair test),
 * {@code minEvents}, the per-anchor {@code maxFanOut} rank (strongest first) with the number it cut, and the output order
 * {@code cnt DESC, source, target, kind NULLS FIRST} cut at the budget.
 */
final class IndexedExpand {

    /** Most distinct frontier entities the index serves (two lookups each; the same cap as the traversal's frontier). */
    static final int FRONTIER_CAP = 20;

    record Request(String datasetId, String sourceCol, String targetCol, String kindCol, List<String> frontier,
                   List<String> excluded, List<String> kinds, String direction, long minEvents, Integer fanOut, int budget,
                   boolean windowed, boolean minDays, boolean degreeBounded, boolean merged) { }

    /** {@code rows} are {@code {source, target, kind, count}}; {@code capped} is how many eligible links the fan-out cap cut. */
    record Result(List<Map<String, Object>> rows, boolean truncated, long capped) { }

    private record Pair(String s, String t, String k) { }

    private IndexedExpand() { }

    /** Why this rung is not for the index, or null when it is. */
    static String notIndexable(Request rq) {
        if (rq.windowed()) return "the rung has a window";
        if (rq.minDays()) return "the rung has minDistinctDays";
        if (rq.degreeBounded()) return "the rung bounds candidate degree";
        if (rq.merged()) return "the rung is a merged traversal";
        if (rq.frontier().size() > FRONTIER_CAP) return "the frontier has " + rq.frontier().size() + " entities (the index serves at most " + FRONTIER_CAP + ")";
        if (new HashSet<>(rq.frontier()).size() != rq.frontier().size()) return "the frontier repeats an entity";
        if (rq.kinds() != null && (rq.kinds().isEmpty() || rq.kindCol() == null)) return "linkKinds cannot be applied";
        if (rq.fanOut() != null && rq.fanOut() < 1) return "maxFanOut is below 1";
        return null;
    }

    static Outcome<Result> attempt(Path writeRoot, Path dataRoot, String relationSql, Request rq, SqlSandboxPolicy policy) {
        return attempt(writeRoot, dataRoot, relationSql, rq, policy, null);
    }

    /** D7-5: as above, reading each index a Draft pinned ({@code mappingHash -> version}) at that version. */
    static Outcome<Result> attempt(Path writeRoot, Path dataRoot, String relationSql, Request rq, SqlSandboxPolicy policy,
                                   java.util.Map<String, Long> pinned) {
        String why = notIndexable(rq);
        Selection sel = IndexedRead.select(writeRoot, dataRoot, relationSql, rq.datasetId(), rq.sourceCol(), rq.targetCol(), (m, utc) -> {
            if (why != null) return Fitted.no(Reason.rung_not_indexable);
            if (rq.kindCol() != null && !rq.kindCol().equalsIgnoreCase(m.kindColumn())) return Fitted.no(Reason.column_not_indexed);
            return Fitted.ok(null);
        }, pinned);
        if (!sel.usable()) {
            Outcome<Result> o = sel.flat();
            return o.reason() == Reason.rung_not_indexable ? Outcome.flat(Reason.rung_not_indexable, why) : o;
        }
        try (IndexReader reader = IndexReader.borrow(sel.dir(), sel.manifest(), policy)) {
            return sel.served(read(reader, rq));
        } catch (SQLException | IOException | RuntimeException unreadable) {
            return Outcome.flat(Reason.index_read_failed);
        }
    }

    private static Result read(IndexReader reader, Request rq) throws SQLException {
        Set<String> fr = new LinkedHashSet<>(rq.frontier());
        Set<String> excluded = new HashSet<>(rq.excluded());
        List<String> extra = rq.kindCol() != null ? List.of("kind") : List.of();
        boolean out = !rq.direction().equals("in"), in = !rq.direction().equals("out");
        Map<Pair, Long> pairs = new LinkedHashMap<>();                                       // the pair is wholly in each end's own bucket
        for (String f : fr) {
            List<Folded> folded = new ArrayList<>();
            if (out) folded.addAll(reader.fold(f, Side.OUT, extra, rq.kinds(), null));
            if (in) folded.addAll(reader.fold(f, Side.IN, extra, rq.kinds(), null));
            for (Folded p : folded) {
                if (excluded.contains(p.source()) || excluded.contains(p.target())) continue;   // prune, then expand
                pairs.putIfAbsent(new Pair(p.source(), p.target(), extra.isEmpty() ? null : p.extras().get(0)), p.count());
            }
        }
        Set<List<String>> have = new HashSet<>();
        for (Pair p : pairs.keySet()) have.add(List.of(p.s(), p.t()));
        Map<String, List<Map.Entry<Pair, Long>>> byAnchor = new LinkedHashMap<>();
        for (Map.Entry<Pair, Long> e : pairs.entrySet()) {
            Pair p = e.getKey();
            boolean s = fr.contains(p.s()), t = fr.contains(p.t());
            boolean cand = switch (rq.direction()) {
                case "out" -> s;
                case "in" -> t;
                case "reciprocal" -> (s || t) && have.contains(List.of(p.t(), p.s()));
                default -> s || t;
            };
            if (cand && e.getValue() >= rq.minEvents()) byAnchor.computeIfAbsent(s ? p.s() : p.t(), x -> new ArrayList<>()).add(e);
        }
        Comparator<Map.Entry<Pair, Long>> strongest = (a, b) -> {
            int c = Long.compare(b.getValue(), a.getValue());
            if (c == 0) c = bytes(a.getKey().s(), b.getKey().s());
            if (c == 0) c = bytes(a.getKey().t(), b.getKey().t());
            return c != 0 ? c : bytes(a.getKey().k(), b.getKey().k());                      // NULL kind first
        };
        List<Map.Entry<Pair, Long>> kept = new ArrayList<>();
        long capped = 0;
        for (List<Map.Entry<Pair, Long>> group : byAnchor.values()) {
            group.sort(strongest);
            int keep = rq.fanOut() == null ? group.size() : Math.min(group.size(), rq.fanOut());
            kept.addAll(group.subList(0, keep));
            capped += group.size() - keep;
        }
        kept.sort(strongest);
        boolean truncated = kept.size() > rq.budget();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Pair, Long> e : truncated ? kept.subList(0, rq.budget()) : kept) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", e.getKey().s());
            m.put("target", e.getKey().t());
            m.put("kind", rq.kindCol() != null ? e.getKey().k() : null);
            m.put("count", e.getValue());
            rows.add(m);
        }
        return new Result(rows, truncated, rows.isEmpty() ? 0 : capped);                     // the flat statement reports `capped` on a row
    }

    /** DuckDB's VARCHAR order: unsigned bytes of the UTF-8; NULL first. */
    private static int bytes(String a, String b) {
        if (a == null || b == null) return a == null ? (b == null ? 0 : -1) : 1;
        return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
