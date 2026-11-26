package com.gamma.insights;

import com.gamma.access.ComponentAccess;
import com.gamma.access.WriteGates;
import com.gamma.control.BiSpecRunner;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.sun.net.httpserver.HttpExchange;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Insight feed, feedback and replay preview (BI-INSIGHTS-1 P3, plan §11.9).
 *
 * <ul>
 *   <li>{@code GET /insights?profile=&run=latest|all&band=&limit=} — a Profile's Insights, best score first, the
 *       readers' current feedback laid over {@code status}. Read-shaped, like {@code /bi/query}: whoever may view the
 *       Dataset {@code insights_<profile>} (else 404, indistinguishable from none).</li>
 *   <li>{@code POST /insights/{profile}/{insightId}/feedback} {@code {status: useful|not_useful|expected, note?}} —
 *       recorded for the Insight's type × slice; the next run writes it into {@code status} and novelty.</li>
 *   <li>{@code POST /insights/preview} {@code {profile: {...}, periods?, asOf?}} — the unsaved Profile scanned as of
 *       each of the last {@code periods} buckets, AS THE CALLER ({@link BiSpecRunner#run}). Nothing is saved.</li>
 * </ul>
 */
public final class InsightsRoutes implements RouteModule {

    /** The longest window {@code /bi/query} accepts ({@code window.days}). */
    static final int MAX_WINDOW_DAYS = 3660;
    static final int DEFAULT_LIMIT = 50, MAX_LIMIT = 500, PREVIEW_SHOWN = 50, MAX_NOTE = 500;

    @Override
    public Set<String> featureIds() { return Set.of("insights"); }

    @Override
    public void register(ApiContext api) {
        api.get("/insights", (e, m) -> feed(api, e));
        api.post("/insights/preview", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> preview(api, e, api.body(e))));
        api.post("/insights/([^/]+)/([^/]+)/feedback", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> feedback(api, e, m.group(1), m.group(2), api.body(e))));
    }

    private static ApiException bad(String msg) {
        return new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, msg);
    }

    private static ApiException notFound(String profile) {
        return new ApiException(404, ErrorCodes.NOT_FOUND, "no Insights for insight-profile '" + profile + "'");
    }

    /** The Insights store of {@code profile} when the caller may view its Dataset, else 404; {@code null} before a run. */
    private static Path visibleStore(ApiContext api, HttpExchange ex, String profile) {
        if (profile == null || !profile.matches("[A-Za-z0-9_][A-Za-z0-9_-]{0,63}")) throw notFound(String.valueOf(profile));
        Path writeRoot = WriteGates.requireWriteRoot(api, "Insights");
        Map<String, Object> ds = new ComponentStore(writeRoot.resolve("registry")).get("dataset", InsightStore.store(profile))
                .map(ComponentRegistry.Component::content).orElse(null);
        if (ds == null || !ComponentAccess.canView(ex, ds)) throw notFound(profile);
        return api.dataRoot() == null ? null : InsightStore.existing(api.dataRoot(), profile);
    }

    private Object feed(ApiContext api, HttpExchange ex) throws Exception {
        String profile = ApiContext.query(ex, "profile");
        if (profile == null) throw bad("query parameter 'profile' is required");
        String run = ApiContext.query(ex, "run");
        if (run != null && !run.equals("latest") && !run.equals("all")) throw bad("'run' is latest or all");
        String band = ApiContext.query(ex, "band");
        if (band != null && !List.of("normal", "elevated", "high").contains(band)) throw bad("'band' is normal, elevated or high");
        int limit = DEFAULT_LIMIT;
        if (ApiContext.query(ex, "limit") != null) {
            try {
                limit = Integer.parseInt(ApiContext.query(ex, "limit"));
            } catch (NumberFormatException e) {
                limit = -1;
            }
            if (limit < 1 || limit > MAX_LIMIT) throw bad("'limit' must be 1-" + MAX_LIMIT);
        }
        Path dir = visibleStore(api, ex, profile);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profile", profile);
        if (dir == null) {
            out.put("insights", List.of());
            out.put("runs", 0);
            return out;
        }
        List<Map<String, Object>> rows;
        Map<String, Map<String, Object>> fb;
        synchronized (InsightStore.lock(dir)) {
            rows = InsightStore.read(dir);
            fb = InsightStore.feedback(dir);
        }
        String latest = rows.stream().max(Comparator.comparing((Map<String, Object> r) -> runAt(r)))
                .map(r -> String.valueOf(r.get("run_id"))).orElse(null);
        out.put("runs", rows.stream().map(r -> r.get("run_id")).distinct().count());
        out.put("latestRun", latest);
        List<Map<String, Object>> shown = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (!"all".equals(run) && !String.valueOf(r.get("run_id")).equals(latest)) continue;
            if (band != null && !band.equals(r.get("band"))) continue;
            Map<String, Object> f = fb.get(String.valueOf(r.get("insight_key")));
            if (f != null) {
                r.put("status", f.get("status"));
                r.put("feedback", f);
            }
            r.put("explanation", InsightStore.parse(r.get("explanation")));
            r.put("query", InsightStore.parse(r.get("query")));
            shown.add(r);
        }
        shown.sort(Comparator.comparing((Map<String, Object> r) -> runAt(r)).reversed()
                .thenComparing(r -> -((Number) r.getOrDefault("score", 0.0)).doubleValue()));
        out.put("total", shown.size());
        out.put("insights", shown.subList(0, Math.min(limit, shown.size())));
        return out;
    }

    /** ISO instants do not sort as text (".5Z" after ".55Z"), so compare them parsed. */
    private static Instant runAt(Map<String, Object> r) {
        return Instant.parse(String.valueOf(r.get("run_at")));
    }

    private Object feedback(ApiContext api, HttpExchange ex, String profile, String insightId, Map<String, Object> body)
            throws Exception {
        Path dir = visibleStore(api, ex, profile);
        String status = ApiContext.str(body, "status");
        if (status == null || !InsightStore.STATUSES.contains(status))
            throw bad("'status' must be one of " + InsightStore.STATUSES);
        String note = ApiContext.str(body, "note");
        if (note != null && note.length() > MAX_NOTE) throw bad("'note' is at most " + MAX_NOTE + " characters");
        if (dir == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no Insight '" + insightId + "'");
        String key;
        synchronized (InsightStore.lock(dir)) {
            key = InsightStore.read(dir).stream().filter(r -> insightId.equals(r.get("insight_id")))
                    .map(r -> String.valueOf(r.get("insight_key"))).findFirst().orElse(null);
        }
        if (key == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no Insight '" + insightId + "'");
        String actor = ApiContext.subject(ex).map(s -> s.id()).orElse("anonymous");
        InsightStore.putFeedback(dir, key, status, note, actor, Instant.now());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("insight", insightId);
        out.put("key", key);
        out.put("status", status);
        out.put("actor", actor);
        return out;
    }

    private Object preview(ApiContext api, HttpExchange ex, Map<String, Object> body) throws Exception {
        if (!(body.get("profile") instanceof Map<?, ?> raw)) throw bad("body must include 'profile' (the Profile's content)");
        @SuppressWarnings("unchecked") Map<String, Object> content = (Map<String, Object>) raw;
        InsightProfile p;
        try {
            p = InsightProfile.parse("preview", content);
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
        int periods = body.get("periods") == null ? 12 : body.get("periods") instanceof Number n ? n.intValue() : -1;
        if (periods < 4 || periods > 52) throw bad("'periods' must be 4-52");
        int buckets = p.lookback() + periods - 1;
        if (!p.dimensions().isEmpty() && (long) buckets * p.maxValues() > InsightProfile.MAX_ROWS)
            throw bad("('lookback' + 'periods') × 'maxValues' exceeds " + InsightProfile.MAX_ROWS + " rows per dimension");
        if (java.time.temporal.ChronoUnit.DAYS.between(InsightScan.step(p.grain(), LocalDate.of(2000, 1, 1), -buckets),
                LocalDate.of(2000, 1, 1)) > MAX_WINDOW_DAYS)
            throw bad("'lookback' + 'periods' spans more than " + MAX_WINDOW_DAYS + " days, the longest window a query reads");
        LocalDate asOf;
        try {
            asOf = body.get("asOf") == null ? LocalDate.now(BiSpecRunner.zone(WriteGates.requireWriteRoot(api, "Insights")))
                    : LocalDate.parse(String.valueOf(body.get("asOf")));
        } catch (DateTimeParseException e) {
            throw bad("'asOf' must be a date YYYY-MM-DD");
        }
        InsightScan.Buckets b = InsightScan.buckets(p.grain(), asOf, buckets);
        InsightScan.Fetched f = InsightScan.fetch(p, b, q -> BiSpecRunner.run(api, ex, BiSpecRunner.parse(api, q)));

        Map<String, List<Integer>> reported = new HashMap<>();
        List<Map<String, Object>> perPeriod = new ArrayList<>();
        List<Map<String, Object>> all = new ArrayList<>();
        int total = 0;
        for (int k = 0; k < periods; k++) {
            final int now = k;
            InsightScan.Result r = InsightScan.scan(p, f, k, k + p.lookback(), key -> {
                int n = 0;
                for (int j : reported.getOrDefault(key, List.of())) if (j >= now - p.noveltyRuns()) n++;
                return n;
            }, Map.of());
            for (InsightScan.Insight i : r.insights()) {
                reported.computeIfAbsent(i.key(), x -> new ArrayList<>()).add(k);
                Map<String, Object> m = InsightScanJobType.row(p, i, "replay-" + k, Instant.EPOCH, null);
                m.remove("run_at");
                m.remove("first_seen");
                m.remove("run_id");
                m.put("asOf", b.label(k + p.lookback() - 1));
                m.put("explanation", i.explanation());
                m.put("query", i.query());
                all.add(m);
            }
            total += r.insights().size();
            perPeriod.add(Map.of("period", b.label(k + p.lookback() - 1), "count", r.insights().size()));
        }
        all.sort(Comparator.comparing((Map<String, Object> m) -> -((Number) m.get("score")).doubleValue()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periods", periods);
        out.put("perPeriod", perPeriod);
        out.put("total", total);
        out.put("perPeriodAverage", (double) total / periods);
        out.put("insights", all.subList(0, Math.min(PREVIEW_SHOWN, all.size())));
        out.put("skipped", f.skipped());
        if (f.masking() != null) out.put("masking", f.masking());
        return out;
    }
}
