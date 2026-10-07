package com.gamma.la.api;

import com.gamma.la.core.LinkEventTypes;
import com.gamma.la.core.LinkTemporalDetector;
import com.gamma.la.storage.IndexReader.LinkTime;
import com.gamma.la.core.BranchingPatternEngine;
import com.gamma.la.core.PatternQueryCompiler;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.DatasetProvider;
import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.ErrorCodes;
import com.gamma.control.RouteModule;
import com.gamma.control.WriteGates;
import com.gamma.audit.Event;
import com.gamma.audit.EventLog;
import com.gamma.la.core.BranchingPatternEngine.Edge;
import com.gamma.la.core.PatternQueryCompiler.Compiled;
import com.gamma.la.core.PatternQueryCompiler.Stage;
import com.gamma.sql.SqlSandboxPolicy;
import com.gamma.util.DuckDbUtil;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
        api.post("/inv/pattern/temporal", (e, m) -> scan(api, e, api.body(e)));
    }

    // ── POST /inv/pattern/temporal — burst / periodicity over each link's event times ─────────────────────────────

    static final int TEMPORAL_MAX_ROWS = 200_000;
    private static final int TEMPORAL_DEFAULT_LIMIT = 200;
    private static final int TEMPORAL_MAX_LIMIT = 1_000;
    private static final long DAY_SECONDS = 86_400;

    /**
     * {@code POST /inv/pattern/temporal} — body {@code {dataset, sourceCol, targetCol, timeCol, mode: "burst"|"periodicity",
     * series?: "link"|"entity", filter?, limit?, windowSeconds?, minEvents?, maxCv?}}. The series is the event times of one LINK (a directed
     * source→target pair, the values as the Dataset spells them); {@code burst} takes {@code windowSeconds} (default 60, 1 to
     * 86 400) and {@code minEvents} (default 5, 2 to 1 000), {@code periodicity} takes {@code minEvents} (default 5, 3 to 1 000)
     * and {@code maxCv} (default 0.1, 0 to 1). Rows without a parseable time are skipped and counted ({@code skippedNoTime}).
     *
     * <p>Same gates as {@code /inv/pattern/branching} (write root 503 → body 422 → Dataset 404 by the view gate → unknown column
     * 422 → filter 422), plus D-U7 four-eyes ({@link InvRoutes#refuseIfSensitive}) for the rows it may read. Fences: one bound
     * statement of at most {@value #TEMPORAL_MAX_ROWS} rows (more ⇒ {@code rowCapped}, {@code truncated}), a
     * {@value #TIMEOUT_SECONDS} s timeout, a result limit. Read-shaped: persists nothing, audited as
     * {@code LINK_PATTERN_MATCHED}. Output is endpoints and times the Dataset's own viewer could read - it never adds a column.
     */
    static Map<String, Object> scan(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "temporal pattern");
        String datasetId = ApiContext.str(body, "dataset");
        if (datasetId == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'dataset'");
        String sourceCol = ident(body, "sourceCol", true);
        String targetCol = ident(body, "targetCol", true);
        String timeCol = ident(body, "timeCol", true);
        String mode = ApiContext.str(body, "mode");
        if (!"burst".equals(mode) && !"periodicity".equals(mode))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'mode' must be 'burst' or 'periodicity'");
        boolean burst = mode.equals("burst");
        String series = ApiContext.str(body, "series");
        if (series != null && !"link".equals(series) && !"entity".equals(series))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'series' must be 'link' or 'entity'");
        boolean entity = "entity".equals(series);
        long windowMs = 1000L * (long) boundedNumber(body, "windowSeconds", 60, 1, DAY_SECONDS);
        int minEvents = (int) boundedNumber(body, "minEvents", 5, burst ? 2 : 3, 1_000);
        double maxCv = boundedNumber(body, "maxCv", 0.1, 0, 1);
        int limit = (int) boundedNumber(body, "limit", TEMPORAL_DEFAULT_LIMIT, 1, TEMPORAL_MAX_LIMIT);
        for (String k : body.keySet())
            if (!TEMPORAL_KEYS.contains(k))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unknown field '" + k + "'");

        String relationSql = InvRoutes.relationFor(api, ex, writeRoot, datasetId);
        List<String> columns = InvRoutes.relationColumns(datasetId, relationSql);
        for (String col : List.of(sourceCol, targetCol, timeCol))
            if (!InvRoutes.containsIgnoreCase(columns, col))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unknown column '" + col + "' — not a column of dataset '" + datasetId + "'");
        String filterSql = body.get("filter") == null ? "TRUE" : InvRoutes.checkedFilterSql(body.get("filter"), columns, datasetId);
        InvRoutes.refuseIfSensitive(writeRoot, "a temporal scan (up to " + TEMPORAL_MAX_ROWS + " rows)", TEMPORAL_MAX_ROWS, 0);

        // LA-INVESTIGATION-OPS-DEFERRED-1: answered from the edge index when - and only when - it can answer exactly (IndexedTemporal),
        // else from the flat Dataset. Both hand the SAME ordered (source, target, time) rows to the one detection below.
        IndexedRead.Outcome<IndexedTemporal.Result> indexed = IndexedTemporal.attempt(writeRoot, api.dataRoot(), relationSql,
                new IndexedTemporal.Request(datasetId, sourceCol, targetCol, timeCol, body.get("filter"), TEMPORAL_MAX_ROWS),
                InvRoutes.traversalPolicy());
        try {
            List<LinkTime> rows = new ArrayList<>();
            boolean rowCapped;
            if (indexed.served()) {
                rows = indexed.result().rows();
                rowCapped = indexed.result().truncated();
            } else {
                String sql = "SELECT CAST(\"" + sourceCol + "\" AS VARCHAR) AS s, CAST(\"" + targetCol + "\" AS VARCHAR) AS t,"
                        + " epoch_ms(TRY_CAST(CAST(\"" + timeCol + "\" AS VARCHAR) AS TIMESTAMP)) AS ts FROM \"" + datasetId + "\""
                        + " WHERE \"" + sourceCol + "\" IS NOT NULL AND \"" + targetCol + "\" IS NOT NULL AND (" + filterSql + ")"
                        + " ORDER BY s, t, ts";
                SqlSandboxPolicy policy = SqlSandboxPolicy.withCaps(null, 0, TIMEOUT_SECONDS);
                DatasetProvider.Result r = DatasetProviders.require().run(new DatasetProvider.Request(
                        datasetId, relationSql, sql, TEMPORAL_MAX_ROWS, 0, List.of(), List.of(), List.of()), policy);
                for (Map<String, Object> row : r.rows())                       // rows arrive grouped by link, times ascending
                    rows.add(new LinkTime(String.valueOf(row.get("s")), String.valueOf(row.get("t")),
                            row.get("ts") instanceof Number ts ? ts.longValue() : null));
                rowCapped = r.truncated();
            }

            List<Found> found = new ArrayList<>();
            int skipped = 0;
            if (entity) {
                // an entity's series is every event it takes part in, as source or as target (a self-loop row is ONE event)
                Map<String, long[]> byEntity = new LinkedHashMap<>();
                Map<String, Integer> sizes = new LinkedHashMap<>();
                for (LinkTime row : rows) {
                    if (row.ms() == null) {
                        skipped++;
                        continue;
                    }
                    for (String who : row.source().equals(row.target()) ? List.of(row.source()) : List.of(row.source(), row.target())) {
                        long[] arr = byEntity.getOrDefault(who, new long[8]);
                        int n = sizes.getOrDefault(who, 0);
                        if (n == arr.length) arr = Arrays.copyOf(arr, n * 2);
                        arr[n] = row.ms();
                        byEntity.put(who, arr);
                        sizes.put(who, n + 1);
                    }
                }
                for (Map.Entry<String, long[]> e : byEntity.entrySet()) {
                    long[] times = Arrays.copyOf(e.getValue(), sizes.get(e.getKey()));
                    Arrays.sort(times);
                    collect(found, e.getKey(), null, times, burst, windowMs, minEvents, maxCv);
                }
            } else {
                String curS = null, curT = null;
                long[] times = new long[64];
                int n = 0;
                for (LinkTime row : rows) {
                    String s = row.source(), t = row.target();
                    if (curS != null && (!s.equals(curS) || !t.equals(curT))) {
                        collect(found, curS, curT, Arrays.copyOf(times, n), burst, windowMs, minEvents, maxCv);
                        n = 0;
                    }
                    curS = s;
                    curT = t;
                    if (row.ms() == null) {
                        skipped++;
                        continue;
                    }
                    if (n == times.length) times = Arrays.copyOf(times, n * 2);
                    times[n++] = row.ms();
                }
                if (curS != null) collect(found, curS, curT, Arrays.copyOf(times, n), burst, windowMs, minEvents, maxCv);
            }

            // strongest first, so a result cut to the limit keeps the most telling: bursts by event count, series by regularity
            Comparator<Found> strongest = burst ? Comparator.comparingLong((Found f) -> -f.events())
                    : Comparator.comparingDouble(Found::cv).thenComparingLong(f -> -f.events());
            found.sort(strongest.thenComparing(Found::s).thenComparing(f -> f.t() == null ? "" : f.t()).thenComparingLong(Found::startMs));
            boolean limited = found.size() > limit;
            List<Map<String, Object>> results = new ArrayList<>();
            for (Found f : found.subList(0, Math.min(limit, found.size()))) results.add(f.view());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("mode", mode);
            out.put("series", entity ? "entity" : "link");
            out.put("results", results);
            out.put("truncated", limited || rowCapped);
            out.put("rowCapped", rowCapped);
            out.put("skippedNoTime", skipped);
            out.put("timeNote", "Times are the Dataset's own wall-clock values read as written, in UTC-neutral milliseconds; "
                    + "gaps are exact differences of those values, so a time column read in a zone other than UTC is compared as stored");
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("maxRows", TEMPORAL_MAX_ROWS);
            f.put("maxResults", TEMPORAL_MAX_LIMIT);
            f.put("timeoutMs", TIMEOUT_SECONDS * 1000);
            out.put("fences", f);
            out.put("source", indexed.source());
            audit(ex, datasetId, results.size(), limited || rowCapped, null);
            return out;
        } catch (SQLException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "temporal query failed: " + DuckDbUtil.withoutPendingQueryPreamble(e.getMessage()));
        }
    }

    /**
     * The scan knobs of the {@code temporal} Investigation op ({@code mode}, {@code series}, {@code windowSeconds},
     * {@code minEvents}, {@code maxCv}, {@code limit}) validated with the route's own bounds (422, never clamped) and returned
     * with every default filled, so what is sealed states exactly what was asked. Columns come from the Investigation's header.
     */
    static Map<String, Object> temporalKnobs(Map<String, Object> body) {
        String mode = ApiContext.str(body, "mode");
        if (!"burst".equals(mode) && !"periodicity".equals(mode))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'mode' must be 'burst' or 'periodicity'");
        String series = ApiContext.str(body, "series");
        if (series != null && !"link".equals(series) && !"entity".equals(series))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'series' must be 'link' or 'entity'");
        boolean burst = mode.equals("burst");
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("mode", mode);
        k.put("series", series == null ? "link" : series);
        if (burst) k.put("windowSeconds", (long) boundedNumber(body, "windowSeconds", 60, 1, DAY_SECONDS));
        k.put("minEvents", (int) boundedNumber(body, "minEvents", 5, burst ? 2 : 3, 1_000));
        if (!burst) k.put("maxCv", boundedNumber(body, "maxCv", 0.1, 0, 1));
        k.put("limit", (int) boundedNumber(body, "limit", TEMPORAL_DEFAULT_LIMIT, 1, TEMPORAL_MAX_LIMIT));
        return k;
    }

    private static final Set<String> TEMPORAL_KEYS = Set.of("dataset", "sourceCol", "targetCol", "timeCol", "mode", "filter", "limit",
            "windowSeconds", "minEvents", "maxCv", "series");

    /** The Dataset's wall-clock value back as text: no zone is claimed, since none is known (see {@code timeNote}). */
    private static String wall(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC).toString();
    }

    private record Found(String s, String t, long events, double cv, long startMs, Map<String, Object> view) { }

    /** A finding's subject: the link's two ends, or - for an entity series ({@code t} null) - the one entity. */
    private static void put(Map<String, Object> v, String s, String t) {
        if (t == null) {
            v.put("entity", s);
            return;
        }
        v.put("source", s);
        v.put("target", t);
    }

    /** One link's (or, with {@code t} null, one entity's) series, evaluated; each burst / regular series found is appended to {@code out}. */
    private static void collect(List<Found> out, String s, String t, long[] times, boolean burst, long windowMs, int minEvents, double maxCv) {
        if (burst) {
            for (LinkTemporalDetector.Burst b : LinkTemporalDetector.bursts(times, windowMs, minEvents)) {
                Map<String, Object> v = new LinkedHashMap<>();
                put(v, s, t);
                v.put("start", wall(b.startMs()));
                v.put("end", wall(b.endMs()));
                v.put("events", b.events());
                out.add(new Found(s, t, b.events(), 0, b.startMs(), v));
            }
            return;
        }
        LinkTemporalDetector.Periodic p = LinkTemporalDetector.periodicity(times, minEvents, maxCv);
        if (p == null) return;
        Map<String, Object> v = new LinkedHashMap<>();
        put(v, s, t);
        v.put("events", p.events());
        v.put("periodSeconds", p.periodMs() / 1000.0);
        v.put("cv", p.cv());
        v.put("first", wall(p.firstMs()));
        v.put("last", wall(p.lastMs()));
        out.add(new Found(s, t, p.events(), p.cv(), p.firstMs(), v));
    }

    /** A numeric body field within {@code [min, max]}, defaulted when absent; anything else is a 422 (never silently clamped). */
    private static double boundedNumber(Map<String, Object> body, String key, double def, double min, double max) {
        Object v = body.get(key);
        if (v == null) return def;
        if (!(v instanceof Number n) || n.doubleValue() < min || n.doubleValue() > max)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + key + "' must be a number from " + js(min) + " to " + js(max));
        return n.doubleValue();
    }

    private Object branching(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "branching pattern");
        String datasetId = ApiContext.str(body, "dataset");
        if (datasetId == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'dataset'");
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
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unknown column '" + col + "' — not a column of dataset '" + datasetId + "'");
        }
        String filterSql = body.get("filter") == null ? "TRUE" : InvRoutes.checkedFilterSql(body.get("filter"), columns, datasetId);

        if (PatternQueryCompiler.needsTime(stages) && timeCol == null)
            return refusal(ex, datasetId, "This pattern has a time window or ordering — choose a time column in the Query panel first.");

        SqlSandboxPolicy policy = SqlSandboxPolicy.withCaps(null, 0, TIMEOUT_SECONDS);
        try {
            String refused = probe(datasetId, relationSql, sourceCol, targetCol, kindCol, timeCol, stages, filterSql, policy);
            if (refused != null) return refusal(ex, datasetId, refused);

            Compiled c = PatternQueryCompiler.legs(datasetId, sourceCol, targetCol, kindCol, timeCol, stages, filterSql);
            DatasetProvider.Result r = DatasetProviders.require().run(new DatasetProvider.Request(
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
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "pattern query failed: " + DuckDbUtil.withoutPendingQueryPreamble(e.getMessage()));
        }
    }

    /** The TS matcher's two threshold refusals, in its words, or null when every thresholded stage has legs. */
    private static String probe(String datasetId, String relationSql, String sourceCol, String targetCol, String kindCol,
                                String timeCol, List<Stage> stages, String filterSql, SqlSandboxPolicy policy)
            throws SQLException, IOException {
        if (PatternQueryCompiler.thresholdAttrs(stages).isEmpty()) return null;
        Compiled p = PatternQueryCompiler.refusalProbe(datasetId, sourceCol, targetCol, kindCol, timeCol, stages, filterSql);
        Map<String, Object> row = DatasetProviders.require().run(new DatasetProvider.Request(
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
            Event.Builder b = Event.builder(LinkEventTypes.LINK_PATTERN_MATCHED)
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
            if (required) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include '" + key + "'");
            return null;
        }
        if (!SAFE_IDENT.matcher(v).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unsafe column identifier '" + v + "' for " + key);
        return v;
    }
}
