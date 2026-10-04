package com.gamma.la.api;

import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.ErrorCodes;
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

    /** The diff, with RAW ids (the caller masks). */
    static Map<String, Object> compare(ApiContext api, HttpExchange ex, InvestigationRoutes.Inv inv,
                                       InvestigationEvaluator.State state, Map<String, Map<String, Object>> windows) {
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
        long neither = 0, eventsA = 0, eventsB = 0;
        for (Map.Entry<String, InvestigationEvaluator.Link> e : state.links.entrySet()) {
            InvestigationEvaluator.Link l = e.getValue();
            String key = LinkIds.key(l.source(), l.target(), String.valueOf(l.kind()));
            long ca = a.getOrDefault(key, 0L), cb = b.getOrDefault(key, 0L);
            eventsA += ca;
            eventsB += cb;
            if (ca > 0) { entA.add(l.source()); entA.add(l.target()); }
            if (cb > 0) { entB.add(l.source()); entB.add(l.target()); }
            if (ca == 0 && cb == 0) { neither++; continue; }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("source", l.source());
            row.put("target", l.target());
            row.put("kind", l.kind());
            row.put("countA", ca);
            row.put("countB", cb);
            (cb == 0 ? onlyA : ca == 0 ? onlyB : both).add(row);
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
        out.put("sealed", false);
        out.put("basis", "Working Set entities and links at the chosen log position; in-window event counts read live from the "
                + "Dataset (not sealed into the log). A link is present in a window with at least one event there; an entity "
                + "when it is an endpoint of a present link.");
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
