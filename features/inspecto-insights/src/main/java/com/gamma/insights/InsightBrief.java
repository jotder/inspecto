package com.gamma.insights;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Dashboard Brief of one Insight Profile (BI-INSIGHTS-1 P4, plan {@code 2026-11-27-bi-insights-p4-language-plan.md}
 * §4.1): the top Insights of the latest run in plain words — the stored templated sentences, nothing a model wrote.
 * One home for the selection, shared by {@code GET /insights/brief} and, later, the narration and the Report page.
 *
 * <p>Selection: latest run ({@code run_at} max); status other than {@code not_useful} / {@code expected} (the readers'
 * current feedback laid over the stored status); band {@code elevated} or {@code high}; best score first, ties by
 * {@code insight_id}; at most {@code max}. Nothing qualifies → {@code summary} "Nothing unusual in &lt;period&gt;",
 * never padded with lower bands. A latest run older than two grains of the Profile adds a {@code note} ahead of the
 * lines. A pure function: stored rows in, a map out; the caller's right to view the Profile is checked by the route.
 */
final class InsightBrief {

    private InsightBrief() {}

    static final int DEFAULT_MAX = 5, CAP = 10, MAX_PROFILES = 5;
    private static final Set<String> BANDS = Set.of("elevated", "high");
    private static final Set<String> DISMISSED = Set.of("not_useful", "expected");

    /** @param rows the Profile's stored rows (every run kept); @param feedback key → {status…}; @param grain day|week|month */
    static Map<String, Object> select(String profile, String grain, List<Map<String, Object>> rows,
                                      Map<String, Map<String, Object>> feedback, int max, Instant now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profile", profile);
        Map<String, Object> latest = rows.stream().max(Comparator.comparing((Map<String, Object> r) -> runAt(r))
                .thenComparing(r -> String.valueOf(r.get("run_id")))).orElse(null);
        if (latest == null) {
            out.put("lines", List.of());
            out.put("note", "No scan has run for insight-profile '" + profile + "' yet - run its insight.scan Job.");
            return out;
        }
        String runId = String.valueOf(latest.get("run_id"));
        List<Map<String, Object>> ofRun = rows.stream().filter(r -> runId.equals(String.valueOf(r.get("run_id")))).toList();
        Instant at = runAt(latest);
        String period = ofRun.stream().map(r -> String.valueOf(r.get("period"))).max(String::compareTo).orElse("");
        out.put("runId", runId);
        out.put("runAt", at.toString());
        out.put("period", period);
        out.put("header", "Brief for " + profile + " - scanned " + at + ", period " + period);

        List<Map<String, Object>> picked = new ArrayList<>();
        for (Map<String, Object> r : ofRun) {
            Map<String, Object> f = feedback.get(String.valueOf(r.get("insight_key")));
            Object status = f != null ? f.get("status") : r.get("status");
            if (status != null && DISMISSED.contains(String.valueOf(status))) continue;
            if (!BANDS.contains(String.valueOf(r.get("band")))) continue;
            picked.add(r);
        }
        picked.sort(Comparator.comparing((Map<String, Object> r) -> -score(r))
                .thenComparing(r -> String.valueOf(r.get("insight_id"))));
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> r : picked.subList(0, Math.min(Math.max(1, Math.min(max, CAP)), picked.size())))
            lines.add(line(profile, r));
        out.put("lines", lines);

        long days = Duration.between(at, now).toDays();
        boolean stale = Duration.between(at, now).compareTo(twoGrains(grain)) > 0;
        out.put("stale", stale);
        if (stale) out.put("note", "Last scan was " + days + " days ago");
        if (lines.isEmpty()) out.put("summary", "Nothing unusual in " + period);
        return out;
    }

    private static Duration twoGrains(String grain) {
        return Duration.ofDays(switch (grain == null ? "day" : grain) { case "week" -> 14; case "month" -> 62; default -> 2; });
    }

    private static Map<String, Object> line(String profile, Map<String, Object> r) {
        Map<String, Object> l = new LinkedHashMap<>();
        String id = String.valueOf(r.get("insight_id"));
        l.put("insightId", id);
        l.put("band", r.get("band"));
        l.put("score", r.get("score"));
        l.put("sentence", sentence(r));
        l.put("type", r.get("insight_type"));
        l.put("dimension", r.get("dimension"));
        l.put("slice", r.get("slice_value"));
        l.put("period", r.get("period"));
        l.put("value", r.get("value"));
        l.put("expected", r.get("expected"));
        l.put("delta", r.get("delta"));
        l.put("link", "/studio/insights?profile=" + profile + "&insight=" + id);
        l.put("query", InsightStore.parse(r.get("query")));
        return l;
    }

    private static String sentence(Map<String, Object> r) {
        if (InsightStore.parse(r.get("explanation")) instanceof Map<?, ?> m && m.get("sentence") != null)
            return String.valueOf(m.get("sentence"));
        return r.get("insight_type") + " in " + r.get("measure");
    }

    private static double score(Map<String, Object> r) {
        return r.get("score") instanceof Number n ? n.doubleValue() : 0;
    }

    private static Instant runAt(Map<String, Object> r) {
        return Instant.parse(String.valueOf(r.get("run_at")));
    }
}
