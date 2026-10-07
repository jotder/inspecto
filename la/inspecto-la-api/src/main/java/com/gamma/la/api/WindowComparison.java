package com.gamma.la.api;

import com.gamma.spi.http.ApiContext;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.la.core.DatasetProvider;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.InvestigationEvaluator;
import com.gamma.la.core.InvestigationTime;
import com.gamma.la.core.LinkIds;
import com.gamma.util.SqlIdent;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * <b>Comparison mode</b> (LA-INVESTIGATION-OPS-DEFERRED-1): two time windows of the Investigation's data, diffed over the
 * Working Set. A READ, not an op — see {@link InvestigationComparisonRoutes} for why.
 *
 * <p>Narrowest semantics. The universe is the Working Set at the chosen log position: its entities and its links
 * ({@code source, target, kind}); the diff never reaches an entity or a link the analyst has not already admitted, so it can
 * widen nothing — neither the graph nor the masking, because every id it can name is one the sealed log already carries.
 * For each window the Dataset is read live (the bound time column, the window's timezone contract, R3 gate on the read) and
 * the in-window event count per Working Set link is taken. A link is <i>present</i> in a window with at least one event
 * there; an entity is present when it is an endpoint of a link present there. {@code minEvents}, direction, link-kind and
 * degree rungs of the expands are NOT applied: a count is a count. Results are exact (no sampling): a read over the row
 * cap is refused. ⚠ A live read — not sealed into the log, so it is evidence of what the Dataset says NOW.
 */
final class WindowComparison {

    private WindowComparison() {}

    static final int MAX_ENTITIES = 2_000;
    static final int MAX_ROWS = 50_000;
    static final int LIST_CAP = 500;

    /** The two windows from the query string: {@code aFrom aTo bFrom bTo} and an optional shared {@code timezone}; null when none is present. */
    static Map<String, Map<String, Object>> windows(HttpExchange ex) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        String zone = ApiContext.query(ex, "timezone");
        boolean any = false;
        for (String w : List.of("a", "b")) {
            Map<String, Object> raw = new LinkedHashMap<>();
            String from = ApiContext.query(ex, w + "From"), to = ApiContext.query(ex, w + "To");
            if (from != null) raw.put("from", from);
            if (to != null) raw.put("to", to);
            if (!raw.isEmpty()) any = true;
            if (zone != null) raw.put("timezone", zone);
            out.put(w, raw);
        }
        if (!any) return null;
        for (String w : List.of("a", "b")) {
            if (out.get(w).keySet().stream().noneMatch(k -> k.equals("from") || k.equals("to")))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "comparison needs both windows: '" + w + "From' and/or '" + w + "To'");
            out.put(w, InvestigationTime.window(out.get(w), "window " + w.toUpperCase()));
        }
        return out;
    }

    /** {@code minAbsDelta} as an integer >= 1, or null when absent. */
    static Long minAbsDelta(Object raw) {
        if (raw == null) return null;
        try {
            long v = raw instanceof Number n && n.doubleValue() == n.longValue() ? n.longValue() : Long.parseLong(String.valueOf(raw).trim());
            if (v >= 1) return v;
        } catch (NumberFormatException ignored) {
            // falls through to the refusal
        }
        throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'minAbsDelta' is an integer >= 1 (the smallest event-count change to list), got " + raw);
    }

    /** The diff, with RAW ids (the caller masks). */
    static Map<String, Object> compare(ApiContext api, HttpExchange ex, InvestigationRoutes.Inv inv,
                                       InvestigationEvaluator.State state, Map<String, Map<String, Object>> windows) {
        return compare(api, ex, inv, state, windows, false, null);
    }

    static Map<String, Object> compare(ApiContext api, HttpExchange ex, InvestigationRoutes.Inv inv,
                                       InvestigationEvaluator.State state, Map<String, Map<String, Object>> windows, boolean activity) {
        return compare(api, ex, inv, state, windows, activity, null);
    }

    /**
     * {@code activity}: also report, per Working Set link and entity, the event-COUNT delta between the windows (B minus A).
     * {@code minAbsDelta} (activity only, null = none): a move smaller than this is not listed as changed; it is counted under
     * {@code belowMinDelta} instead.
     */
    static Map<String, Object> compare(ApiContext api, HttpExchange ex, InvestigationRoutes.Inv inv,
                                       InvestigationEvaluator.State state, Map<String, Map<String, Object>> windows, boolean activity,
                                       Long minAbsDelta) {
        Map<String, Object> h = inv.header();
        if (h.get("timeCol") == null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "this Investigation has no time column - create it with 'timeCol' to compare windows");
        if (state.entities.size() > MAX_ENTITIES)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "a comparison covers at most " + MAX_ENTITIES
                    + " entities (the Working Set has " + state.entities.size() + "); exclude or threshold first");
        String dataset = inv.dataset();
        String relationSql = InvRoutes.relationFor(api, ex, inv.writeRoot(), dataset);   // R3 gate on the read
        TreeMap<String, Long> a = counts(inv, dataset, relationSql, state, windows.get("a"));
        TreeMap<String, Long> b = counts(inv, dataset, relationSql, state, windows.get("b"));

        List<Map<String, Object>> onlyA = new ArrayList<>(), onlyB = new ArrayList<>(), both = new ArrayList<>();
        TreeSet<String> entA = new TreeSet<>(), entB = new TreeSet<>();
        long neither = 0, eventsA = 0, eventsB = 0, linkUnchanged = 0;
        List<Map<String, Object>> linkMoves = new ArrayList<>();
        TreeMap<String, long[]> entCounts = new TreeMap<>();   // id -> {events in A, events in B}; a self-loop event counts once
        for (Map.Entry<String, InvestigationEvaluator.Link> e : state.links.entrySet()) {
            InvestigationEvaluator.Link l = e.getValue();
            String key = LinkIds.key(l.source(), l.target(), String.valueOf(l.kind()));
            long ca = a.getOrDefault(key, 0L), cb = b.getOrDefault(key, 0L);
            eventsA += ca;
            eventsB += cb;
            if (ca > 0) { entA.add(l.source()); entA.add(l.target()); }
            if (cb > 0) { entB.add(l.source()); entB.add(l.target()); }
            if (ca == cb) linkUnchanged++;
            if (activity) {
                for (String end : new TreeSet<>(List.of(l.source(), l.target()))) {
                    long[] c = entCounts.computeIfAbsent(end, x -> new long[2]);
                    c[0] += ca;
                    c[1] += cb;
                }
            }
            if (ca == 0 && cb == 0) { neither++; continue; }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("source", l.source());
            row.put("target", l.target());
            row.put("kind", l.kind());
            row.put("countA", ca);
            row.put("countB", cb);
            (cb == 0 ? onlyA : ca == 0 ? onlyB : both).add(row);
            if (activity && ca != cb) {
                Map<String, Object> move = new LinkedHashMap<>(row);
                move.put("delta", cb - ca);
                linkMoves.add(move);
            }
        }
        TreeSet<String> entOnlyA = new TreeSet<>(entA), entOnlyB = new TreeSet<>(entB), entBoth = new TreeSet<>(entA);
        entOnlyA.removeAll(entB);
        entOnlyB.removeAll(entA);
        entBoth.retainAll(entB);
        long entNeither = state.entities.keySet().stream().filter(x -> !entA.contains(x) && !entB.contains(x)).count();

        Map<String, Object> links = new LinkedHashMap<>();
        links.put("onlyA", section(onlyA));
        links.put("onlyB", section(onlyB));
        links.put("both", section(both));
        links.put("neither", neither);
        links.put("eventsA", eventsA);
        links.put("eventsB", eventsB);
        Map<String, Object> entities = new LinkedHashMap<>();
        entities.put("onlyA", section(new ArrayList<>(entOnlyA)));
        entities.put("onlyB", section(new ArrayList<>(entOnlyB)));
        entities.put("both", section(new ArrayList<>(entBoth)));
        entities.put("neither", entNeither);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowA", windows.get("a"));
        out.put("windowB", windows.get("b"));
        out.put("workingSet", Map.of("entities", state.entities.size(), "links", state.links.size()));
        out.put("links", links);
        out.put("entities", entities);
        if (activity) out.put("mode", "activity");
        if (activity) out.put("activity", activity(linkMoves, linkUnchanged, entCounts, minAbsDelta));
        out.put("sealed", false);
        out.put("basis", "Working Set entities and links at the chosen log position; in-window event counts read live from the "
                + "Dataset (not sealed into the log). A link is present in a window with at least one event there; an entity "
                + "when it is an endpoint of a present link.");
        return out;
    }

    /**
     * The event-COUNT diff: links and entities whose in-window event count differs, largest absolute change first (ties by
     * id, so the order is deterministic), each with {@code countA}, {@code countB} and {@code delta} (B minus A); the rest are
     * counted as {@code unchanged}. An entity's count is the events on the Working Set links it ends, a self-loop once.
     */
    private static Map<String, Object> activity(List<Map<String, Object>> linkMoves, long linkUnchanged, TreeMap<String, long[]> entCounts,
                                                Long minAbsDelta) {
        long min = minAbsDelta == null ? 0 : minAbsDelta;
        long linksBelow = linkMoves.stream().filter(m -> Math.abs((Long) m.get("delta")) < min).count();
        linkMoves.removeIf(m -> Math.abs((Long) m.get("delta")) < min);
        linkMoves.sort(Comparator.comparingLong((Map<String, Object> m) -> -Math.abs((Long) m.get("delta")))
                .thenComparing(m -> String.valueOf(m.get("source"))).thenComparing(m -> String.valueOf(m.get("target")))
                .thenComparing(m -> String.valueOf(m.get("kind"))));
        List<Map<String, Object>> entMoves = new ArrayList<>();
        long entUnchanged = 0;
        for (Map.Entry<String, long[]> e : entCounts.entrySet()) {
            long a = e.getValue()[0], b = e.getValue()[1];
            if (a == b) { entUnchanged++; continue; }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.getKey());
            row.put("countA", a);
            row.put("countB", b);
            row.put("delta", b - a);
            entMoves.add(row);
        }
        long entsBelow = entMoves.stream().filter(m -> Math.abs((Long) m.get("delta")) < min).count();
        entMoves.removeIf(m -> Math.abs((Long) m.get("delta")) < min);
        entMoves.sort(Comparator.comparingLong((Map<String, Object> m) -> -Math.abs((Long) m.get("delta")))
                .thenComparing(m -> String.valueOf(m.get("id"))));
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> l = new LinkedHashMap<>(), en = new LinkedHashMap<>();
        l.put("changed", section(linkMoves));
        l.put("unchanged", linkUnchanged);
        en.put("changed", section(entMoves));
        en.put("unchanged", entUnchanged);
        if (minAbsDelta != null) {   // only when asked, so a diff sealed without it is byte-identical to before
            out.put("minAbsDelta", minAbsDelta);
            l.put("belowMinDelta", linksBelow);
            en.put("belowMinDelta", entsBelow);
        }
        out.put("links", l);
        out.put("entities", en);
        return out;
    }

    /**
     * The {@code compare} op's SEALED form of a diff: JSON-normalised (so what is hashed is what the log stores and replay
     * reads back), {@code sealed: true}, and a {@code fingerprint} over the content (windows, Working Set size, links,
     * entities). Nothing time-varying is in it, so a Dossier root over a log carrying it stays deterministic.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> seal(Map<String, Object> raw) {
        try {
            Map<String, Object> out = ApiContext.JSON.readValue(InvestigationEvaluator.canonical(raw), Map.class);
            out.put("sealed", true);
            out.put("basis", String.valueOf(out.get("basis")).replace("not sealed into the log", "sealed into the log when the step "
                    + "was appended: what the Dataset said then"));
            out.put("fingerprint", fingerprint(out));
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("a comparison is plain JSON", e);
        }
    }

    /** SHA-256 over the content of a sealed comparison (not its prose {@code basis}, not the fingerprint itself). */
    static String fingerprint(Map<?, ?> sealed) {
        Map<String, Object> content = new TreeMap<>();
        for (String k : List.of("windowA", "windowB", "workingSet", "links", "entities")) content.put(k, sealed.get(k));
        // activity-mode and inherit extras: in the content only when present, so a presence diff sealed earlier verifies unchanged
        for (String k : List.of("mode", "activity", "inherited")) if (sealed.get(k) != null) content.put(k, sealed.get(k));
        return InvestigationEvaluator.sha256(InvestigationEvaluator.canonical(content));
    }

    /** The counts of a sealed comparison without the item lists - for the log view, where sealed bodies stay out. */
    static Map<String, Object> summary(Map<?, ?> sealed) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("windowA", "windowB", "workingSet", "fingerprint")) out.put(k, sealed.get(k));
        for (String part : List.of("links", "entities")) {
            Map<String, Object> counts = new LinkedHashMap<>();
            if (sealed.get(part) instanceof Map<?, ?> m)
                for (var x : m.entrySet())
                    counts.put(String.valueOf(x.getKey()), x.getValue() instanceof Map<?, ?> s ? s.get("count") : x.getValue());
            out.put(part, counts);
        }
        if (sealed.get("mode") != null) out.put("mode", sealed.get("mode"));
        if (sealed.get("inherited") != null) out.put("inherited", sealed.get("inherited"));
        if (sealed.get("activity") instanceof Map<?, ?> act) {
            Map<String, Object> counts = new LinkedHashMap<>();
            for (String part : List.of("links", "entities")) {
                Map<?, ?> a = (Map<?, ?>) act.get(part);
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("changed", ((Map<?, ?>) a.get("changed")).get("count"));
                c.put("unchanged", a.get("unchanged"));
                if (a.get("belowMinDelta") != null) c.put("belowMinDelta", a.get("belowMinDelta"));
                counts.put(part, c);
            }
            if (act.get("minAbsDelta") != null) counts.put("minAbsDelta", act.get("minAbsDelta"));
            out.put("activity", counts);
        }
        return out;
    }

    private static Map<String, Object> section(List<?> items) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("count", items.size());
        s.put("items", items.size() > LIST_CAP ? new ArrayList<>(items.subList(0, LIST_CAP)) : items);
        s.put("truncated", items.size() > LIST_CAP);
        return s;
    }

    /** In-window event count per Working Set link key, from the Dataset. Exact: a read over the row cap is refused. */
    private static TreeMap<String, Long> counts(InvestigationRoutes.Inv inv, String dataset, String relationSql,
                                                InvestigationEvaluator.State state, Map<String, Object> window) {
        Map<String, Object> h = inv.header();
        String src = SqlIdent.q(String.valueOf(h.get("sourceCol")));
        String tgt = SqlIdent.q(String.valueOf(h.get("targetCol")));
        String kind = h.get("linkKindCol") == null ? null : SqlIdent.q(String.valueOf(h.get("linkKindCol")));
        String time = SqlIdent.q(String.valueOf(h.get("timeCol")));
        List<String> ids = new ArrayList<>(state.entities.keySet());
        TreeMap<String, Long> out = new TreeMap<>();
        if (ids.isEmpty()) return out;
        List<String> binds = new ArrayList<>(ids);
        String in = "(SELECT id FROM wsent)";
        StringBuilder sql = new StringBuilder("WITH wsent(id) AS (VALUES ")
                .append(String.join(",", Collections.nCopies(ids.size(), "(?)"))).append("), ev0 AS (SELECT CAST(").append(src)
                .append(" AS VARCHAR) AS s, CAST(").append(tgt).append(" AS VARCHAR) AS t, ")
                .append(kind == null ? "CAST(NULL AS VARCHAR)" : "CAST(" + kind + " AS VARCHAR)").append(" AS k, ")
                .append(InvestigationTime.instantExpr(time, h.get("timeColZone") == null ? null : String.valueOf(h.get("timeColZone")), binds))
                .append(" AS ts FROM ").append(SqlIdent.q(dataset)).append(" WHERE ").append(src).append(" IS NOT NULL AND ")
                .append(tgt).append(" IS NOT NULL AND ").append(time).append(" IS NOT NULL AND CAST(").append(src).append(" AS VARCHAR) IN ")
                .append(in).append(" AND CAST(").append(tgt).append(" AS VARCHAR) IN ").append(in)
                .append("), ev1 AS (SELECT *, timezone(?, ts) AS lt FROM ev0), ev AS (SELECT * FROM ev1 WHERE TRUE");
        binds.add(InvestigationTime.localZone(window));
        InvestigationTime.predicates(window, sql, binds);
        sql.append(") SELECT s, t, k, COUNT(*) AS cnt FROM ev GROUP BY s, t, k ORDER BY s, t, k NULLS FIRST");
        try {
            DatasetProvider.Result r = DatasetProviders.require().run(new DatasetProvider.Request(
                    dataset, relationSql, sql.toString(), MAX_ROWS, 0, List.of(), List.of(), binds));
            if (r.truncated())
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the comparison reads more than " + MAX_ROWS
                        + " links in one window; narrow the window or the Working Set (no silent sample)");
            for (Map<String, Object> row : r.rows())
                out.put(LinkIds.key(String.valueOf(row.get("s")), String.valueOf(row.get("t")), String.valueOf(row.get("k"))),
                        ((Number) row.get("cnt")).longValue());
        } catch (SQLException | IOException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "comparison over dataset '" + dataset + "' failed: " + e.getMessage());
        }
        return out;
    }
}
