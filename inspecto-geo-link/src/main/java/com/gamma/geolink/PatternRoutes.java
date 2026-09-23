package com.gamma.geolink;

import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.RouteModule;
import com.gamma.control.WriteGates;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.geolink.BranchingPatternEngine.Edge;
import com.gamma.geolink.PatternQueryCompiler.Compiled;
import com.gamma.geolink.PatternQueryCompiler.Stage;
import com.gamma.query.QueryExecutor;
import com.gamma.sql.SqlSandboxPolicy;
import com.gamma.util.DuckDbUtil;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code POST /inv/pattern/branching} (LA-14b, server half) — run a branching motif (structuring & co.) over a
 * WHOLE Dataset instead of over the capped projection the browser holds.
 *
 * <p>Body {@code {dataset, sourceCol, targetCol, linkKindCol?, timeCol?, stages, filter?, limit?}} — the same
 * column names as {@code /inv/projection}, {@code stages} the TS {@code BranchStage[]} verbatim. Answer
 * {@code {matches:[{nodeIds, edgeIds, layers}], edges:[{id, source, target, kind, attrs}], truncated, legCapped,
 * refusal?, fences}} — the browser matcher's {@code BranchingResult} shape, with node ids as RAW values (the SPA
 * mints its {@code entity:} ids from them, exactly as for LA-11's paths) and the matched legs spelled out, since
 * they may lie outside the loaded graph.
 *
 * <p>Gate order: write root 503 → body 422 → Dataset 404 (R3: unviewable = absent, via
 * {@link InvRoutes#relationFor}) → unknown column 422 → filter 422. Refusals the browser gives (no time column,
 * a threshold nothing carries or passes) are a 200 with {@code refusal} and no matches, in the SAME words.
 *
 * <p>Fences: every value bound; identifiers checked against the relation's real columns; at most
 * {@value #MAX_LEGS} legs leave DuckDB (more ⇒ {@code legCapped}, {@code truncated}); a
 * {@value #TIMEOUT_SECONDS} s statement timeout; the matcher's work budget; a match limit.
 * Read-shaped: persists nothing, audited as {@code LINK_PATTERN_MATCHED}.
 */
public final class PatternRoutes implements RouteModule {

    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    static final int MAX_LEGS = 100_000;
    private static final int DEFAULT_MATCHES = 200;
    private static final int MAX_MATCHES = 1_000;
    static final int TIMEOUT_SECONDS = 5;

    @Override
    public void register(ApiContext api) {
        api.post("/inv/pattern/branching", (e, m) -> branching(api, e, api.body(e)));
    }

    private Object branching(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "branching pattern");
        String datasetId = ApiContext.str(body, "dataset");
        if (datasetId == null) throw new ApiException(422, "body must include 'dataset'");
        String sourceCol = ident(body, "sourceCol", true);
        String targetCol = ident(body, "targetCol", true);
        String kindCol = ident(body, "linkKindCol", false);
        String timeCol = ident(body, "timeCol", false);
        List<Stage> stages = PatternQueryCompiler.parseStages(body.get("stages"));
        int limit = body.get("limit") instanceof Number n ? Math.max(1, Math.min(MAX_MATCHES, n.intValue())) : DEFAULT_MATCHES;

        String relationSql = InvRoutes.relationFor(api, ex, writeRoot, datasetId);
        List<String> columns = InvRoutes.relationColumns(datasetId, relationSql);
        List<String> named = new ArrayList<>(java.util.Arrays.asList(sourceCol, targetCol, kindCol, timeCol));
        named.addAll(PatternQueryCompiler.thresholdAttrs(stages));
        for (String col : named) {
            if (col != null && !InvRoutes.containsIgnoreCase(columns, col))
                throw new ApiException(422, "unknown column '" + col + "' — not a column of dataset '" + datasetId + "'");
        }
        String filterSql = body.get("filter") == null ? "TRUE" : InvRoutes.checkedFilterSql(body.get("filter"), columns, datasetId);

        if (PatternQueryCompiler.needsTime(stages) && timeCol == null)
            return refusal(ex, datasetId, "This pattern has a time window or ordering — choose a time column in the Query panel first.");

        SqlSandboxPolicy policy = SqlSandboxPolicy.withCaps(null, 0, TIMEOUT_SECONDS);
        try {
            String refused = probe(datasetId, relationSql, sourceCol, targetCol, kindCol, timeCol, stages, filterSql, policy);
            if (refused != null) return refusal(ex, datasetId, refused);

            Compiled c = PatternQueryCompiler.legs(datasetId, sourceCol, targetCol, kindCol, timeCol, stages, filterSql);
            QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request(
                    datasetId, relationSql, c.sql(), MAX_LEGS, 0, List.of(), List.of(), c.binds()), policy);

            List<String> attrs = PatternQueryCompiler.thresholdAttrs(stages);
            List<List<Edge>> byStage = new ArrayList<>();
            for (int i = 0; i < stages.size(); i++) byStage.add(new ArrayList<>());
            Map<String, String> label = new LinkedHashMap<>();
            Map<String, Map<String, Object>> legOut = new LinkedHashMap<>();
            for (Map<String, Object> row : r.rows()) {
                int stage = ((Number) row.get("stage")).intValue();
                String s = String.valueOf(row.get("s")), t = String.valueOf(row.get("t"));
                label.putIfAbsent(s, String.valueOf(row.get("sl")));
                label.putIfAbsent(t, String.valueOf(row.get("tl")));
                Map<String, Object> legAttrs = new LinkedHashMap<>();
                if (timeCol != null) legAttrs.put(timeCol, row.get("tr"));
                for (int i = 0; i < attrs.size(); i++) legAttrs.put(attrs.get(i), row.get("r_" + i));
                // A leg is identified by what the browser folds on: endpoints, kind and attribute values.
                String id = s + "->" + t + ":" + row.get("k") + ":" + legAttrs;
                legOut.putIfAbsent(id, leg(id, s, t, String.valueOf(row.get("k")), legAttrs));
                Object ts = row.get("ts");
                byStage.get(stage).add(new Edge(id, s, t, ts instanceof Number n ? n.longValue() : null));
            }
            BranchingPatternEngine.Result res = BranchingPatternEngine.match(stages, byStage, limit);

            List<Map<String, Object>> matches = new ArrayList<>();
            Set<String> usedLegs = new LinkedHashSet<>();
            for (BranchingPatternEngine.Match mt : res.matches()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("nodeIds", mt.nodeIds().stream().map(label::get).toList());
                m.put("edgeIds", mt.edgeIds());
                m.put("layers", mt.layers().stream().map(l -> l.stream().map(label::get).toList()).toList());
                matches.add(m);
                usedLegs.addAll(mt.edgeIds());
            }
            List<Map<String, Object>> edges = new ArrayList<>();
            for (String id : usedLegs) {
                Map<String, Object> e = new LinkedHashMap<>(legOut.get(id));
                e.put("source", label.get(String.valueOf(e.get("source"))));
                e.put("target", label.get(String.valueOf(e.get("target"))));
                edges.add(e);
            }
            boolean truncated = res.truncated() || r.truncated();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("matches", matches);
            out.put("edges", edges);
            out.put("truncated", truncated);
            out.put("legCapped", r.truncated());
            out.put("fences", fences());
            audit(ex, datasetId, matches.size(), truncated, null);
            return out;
        } catch (SQLException e) {
            throw new ApiException(422, "pattern query failed: " + DuckDbUtil.withoutPendingQueryPreamble(e.getMessage()));
        }
    }

    /** The TS matcher's two threshold refusals, in its words, or null when every thresholded stage has legs. */
    private static String probe(String datasetId, String relationSql, String sourceCol, String targetCol, String kindCol,
                                String timeCol, List<Stage> stages, String filterSql, SqlSandboxPolicy policy)
            throws SQLException, IOException {
        if (PatternQueryCompiler.thresholdAttrs(stages).isEmpty()) return null;
        Compiled p = PatternQueryCompiler.refusalProbe(datasetId, sourceCol, targetCol, kindCol, timeCol, stages, filterSql);
        Map<String, Object> row = QueryExecutor.run(new QueryExecutor.Request(
                datasetId, relationSql, p.sql(), 1, 0, List.of(), List.of(), p.binds()), policy).rows().get(0);
        for (int i = 0; i < stages.size(); i++) {
            PatternQueryCompiler.Threshold th = stages.get(i).threshold();
            if (th == null) continue;
            if (((Number) row.get("valued_" + i)).longValue() == 0)
                return "No link carries a numeric " + th.attr() + ", so the threshold " + label(th)
                        + " cannot be evaluated — add " + th.attr() + " as a link attribute in the Query panel.";
            if (((Number) row.get("passing_" + i)).longValue() == 0)
                return "No link in this graph passes " + label(th) + ". If the view filters " + th.attr()
                        + " (for example to ≥ 5 000), the legs this pattern looks for were removed before it ran — clear that filter rather than read this as \"none found\".";
        }
        return null;
    }

    /** TS {@code thresholdLabel}: {@code 900 ≤ AMOUNT < 1000}. Numbers print as JS does (no trailing {@code .0}). */
    static String label(PatternQueryCompiler.Threshold t) {
        List<String> parts = new ArrayList<>();
        if (t.min() != null) parts.add(js(t.min()) + " ≤");
        parts.add(t.attr());
        if (t.max() != null) parts.add("< " + js(t.max()));
        return String.join(" ", parts);
    }

    private static String js(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }

    private Map<String, Object> refusal(HttpExchange ex, String datasetId, String why) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("matches", List.of());
        out.put("edges", List.of());
        out.put("truncated", false);
        out.put("legCapped", false);
        out.put("refusal", why);
        out.put("fences", fences());
        audit(ex, datasetId, 0, false, why);
        return out;
    }

    private static Map<String, Object> fences() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("maxLegs", MAX_LEGS);
        f.put("workBudget", BranchingPatternEngine.WORK_BUDGET);
        f.put("timeoutMs", TIMEOUT_SECONDS * 1000);
        return f;
    }

    private static Map<String, Object> leg(String id, String s, String t, String kind, Map<String, Object> attrs) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", id);
        e.put("source", s);
        e.put("target", t);
        e.put("kind", kind);
        e.put("attrs", attrs);
        return e;
    }

    /** Best-effort audit (LA-04 pattern): a whole-Dataset pattern search is its own analytic act. */
    private static void audit(HttpExchange ex, String datasetId, int matches, boolean truncated, String refusal) {
        try {
            Event.Builder b = Event.builder(EventType.LINK_PATTERN_MATCHED)
                    .source("inv")
                    .message("link.pattern.matched " + datasetId + " — " + matches + " matches"
                            + (truncated ? " (truncated)" : "") + (refusal != null ? " (refused)" : ""))
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("link.pattern.matched").actionCategory("analysis")
                    .target("dataset", datasetId)
                    .attr("dataset", datasetId).attr("matches", matches).attr("truncated", truncated);
            if (refusal != null) b.attr("refusal", refusal);
            EventLog.current().emit(b);
        } catch (RuntimeException ignore) {
            // best effort — the audit must never fail the analyst's query
        }
    }

    private static String ident(Map<String, Object> body, String key, boolean required) {
        String v = ApiContext.str(body, key);
        if (v == null) {
            if (required) throw new ApiException(422, "body must include '" + key + "'");
            return null;
        }
        if (!SAFE_IDENT.matcher(v).matches())
            throw new ApiException(422, "unsafe column identifier '" + v + "' for " + key);
        return v;
    }
}
