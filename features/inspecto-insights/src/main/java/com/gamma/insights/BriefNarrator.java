package com.gamma.insights;

import com.gamma.assist.AssistRequest;
import com.gamma.assist.AssistResult;
import com.gamma.assist.spi.AssistAgent;
import com.gamma.control.HostContext;
import com.gamma.spi.http.ApiContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Narrating one Profile's Brief (BI-INSIGHTS-1 P4 BR-2, shared with the Report Brief page, BR-4): the {@code insight-brief}
 * capability over the evidence the caller's own selection built, and the per-run cache (D-BI17, D-BI23) that makes the
 * screen, the route and a delivered PDF read identically for one scan run. Nothing here reads data: its input is a Brief
 * Profile map ({@link InsightBrief#select}) the caller already built under the right person's view check.
 */
final class BriefNarrator {

    private BriefNarrator() {}

    /** Narrations kept (D-BI17): small, since an entry is a few sentences and a new run replaces the old one. */
    private static final int CACHE_MAX = 64;
    private static final Map<String, Map<String, Object>> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
            return size() > CACHE_MAX;
        }
    };

    /** The route contexts of this process, so a Job (which has no request) can reach its Space's agent by data root. */
    private static final Set<ApiContext> HOSTS = Collections.newSetFromMap(new WeakHashMap<>());

    static void bind(ApiContext api) {
        synchronized (HOSTS) {
            HOSTS.add(api);
        }
    }

    /** The AI Assist agent of the Space whose data root is {@code dataRoot}, or empty (no agent, or no such Space). */
    static Optional<AssistAgent> agentFor(Path dataRoot) {
        synchronized (HOSTS) {
            for (ApiContext h : HOSTS)
                try {
                    if (h instanceof HostContext host && same(h.dataRoot(), dataRoot)) {
                        Optional<AssistAgent> a = host.service().assistAgent();
                        if (a.isPresent()) return a;
                    }
                } catch (RuntimeException closedOrSpaceless) {
                    // a context whose Space is gone (closed, or none hosted) cannot be the one asked about
                }
        }
        return Optional.empty();
    }

    private static boolean same(Path a, Path b) {
        return a != null && b != null && a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    /**
     * The narration of Profile Brief {@code p}: {@code {profile, runId, sentences[{n,text,cites,source}], modelBacked,
     * repaired, reason?}}. Cached once a model wrote it, by (data root, Profile, {@code run_id}, max, cited Insights).
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> narrate(AssistAgent agent, Path dataRoot, Map<String, Object> p, int max) {
        String profile = String.valueOf(p.get("profile"));
        List<Map<String, Object>> lines = p.get("lines") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profile", profile);
        out.put("runId", p.get("runId"));
        if (lines.isEmpty()) {
            out.put("sentences", List.of());
            out.put("modelBacked", false);
            out.put("repaired", false);
            return out;
        }
        String key = dataRoot + "|" + profile + "|" + p.get("runId") + "|" + max + "|"
                + lines.stream().map(x -> String.valueOf(x.get("insightId"))).toList();
        synchronized (CACHE) {
            Map<String, Object> hit = CACHE.get(key);
            if (hit != null) return hit;
        }
        Map<String, Object> reply = Map.of();
        String reason = null;
        try {
            AssistResult r = agent.assist(new AssistRequest("insight-brief", Map.of(),
                    Map.of("profile", profile, "evidence", lines), null));
            if (r.status() == AssistResult.Status.OK) reply = r.data();
            else reason = r.message() == null ? "no narrator" : r.message();
        } catch (RuntimeException e) {
            reason = "narration failed";
        }
        boolean model = Boolean.TRUE.equals(reply.get("modelBacked"));
        out.put("sentences", reply.get("sentences") instanceof List<?> s && !s.isEmpty() ? s : templated(lines));
        out.put("modelBacked", model);
        out.put("repaired", Boolean.TRUE.equals(reply.get("repaired")));
        if (reason == null) reason = reply.get("reason") instanceof String s ? s : null;
        if (reason != null) out.put("reason", reason);
        if (model) synchronized (CACHE) {
            CACHE.put(key, out);
        }
        return out;
    }

    /** The Brief's own sentences in the narration's shape, for a Profile the narrator could not answer. */
    static List<Map<String, Object>> templated(List<Map<String, Object>> lines) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("n", out.size() + 1);
            s.put("text", "[" + (out.size() + 1) + "] " + l.get("sentence"));
            s.put("cites", List.of(String.valueOf(l.get("insightId"))));
            s.put("source", "template");
            out.add(s);
        }
        return out;
    }
}
