package com.gamma.la.api;

import com.gamma.spi.http.ApiContext;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.la.core.InvestigationEvaluator;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The {@code temporal} op (LA-INVESTIGATION-OPS-DEFERRED-1): a burst / periodicity finding set sealed into the Investigation
 * log. A marker like {@code compare}: the Working Set and its hash do not move; the scan ({@code POST /inv/pattern/temporal}'s
 * own, {@link PatternRoutes#scan}) runs live at append over the Investigation's bound edge columns, the findings are narrowed
 * to the Working Set at that position (a link finding whose source and target are both admitted - any kind; an entity finding
 * for an admitted entity), and the result is frozen into the entry with a fingerprint. Replay never re-reads the Dataset; a fork
 * or a promote re-seals over the NEW Working Set, as {@code compare} does. The Investigation's window is not applied: like the
 * stateless route this reads the Dataset's whole time range, wall-clock as written.
 */
final class TemporalFindings {

    private TemporalFindings() {}

    /** Validate and normalise the op's parameters: the scan knobs only (no ids; the columns are the Investigation's own). */
    static Map<String, Object> params(Map<String, Object> body) {
        if (body.containsKey("ids"))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'temporal' applies to the whole Working Set, not ids");
        return PatternRoutes.temporalKnobs(body);
    }

    /** The scan, narrowed to {@code state}; RAW ids (the caller masks). */
    static Map<String, Object> compute(ApiContext api, HttpExchange ex, InvestigationRoutes.Inv inv,
                                       InvestigationEvaluator.State state, Map<String, Object> params) {
        Map<String, Object> h = inv.header();
        if (h.get("timeCol") == null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "this Investigation has no time column - create it with 'timeCol' to scan for bursts or periodicity");
        Map<String, Object> req = new LinkedHashMap<>(params);
        req.put("dataset", inv.dataset());
        req.put("sourceCol", h.get("sourceCol"));
        req.put("targetCol", h.get("targetCol"));
        req.put("timeCol", h.get("timeCol"));
        req.put("limit", 1_000);   // the route's maximum: the op's own limit cuts AFTER the Working Set narrowing
        Map<String, Object> scan;
        try {
            scan = PatternRoutes.scan(api, ex, req);
        } catch (IOException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "temporal scan over dataset '" + inv.dataset() + "' failed: " + e.getMessage());
        }
        boolean entity = "entity".equals(params.get("series"));
        Set<String> links = new HashSet<>();
        for (InvestigationEvaluator.Link l : state.links.values()) links.add(l.source() + "\u0000" + l.target());
        List<Map<String, Object>> kept = new ArrayList<>();
        int outside = 0;
        for (Object o : (List<?>) scan.get("results")) {
            @SuppressWarnings("unchecked") Map<String, Object> f = (Map<String, Object>) o;
            boolean in = entity ? state.entities.containsKey(String.valueOf(f.get("entity")))
                    : links.contains(f.get("source") + "\u0000" + f.get("target"));
            if (in) kept.add(f); else outside++;
        }
        int limit = ((Number) params.get("limit")).intValue();
        boolean cut = kept.size() > limit;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", inv.dataset());
        out.put("params", params);
        out.put("workingSet", Map.of("entities", state.entities.size(), "links", state.links.size()));
        out.put("count", Math.min(limit, kept.size()));
        out.put("results", cut ? new ArrayList<>(kept.subList(0, limit)) : kept);
        out.put("outsideWorkingSet", outside);
        out.put("truncated", cut || Boolean.TRUE.equals(scan.get("truncated")));
        out.put("rowCapped", scan.get("rowCapped"));
        out.put("skippedNoTime", scan.get("skippedNoTime"));
        out.put("timeNote", scan.get("timeNote"));
        out.put("servedFrom", scan.get("source"));
        out.put("readAt", Instant.now().toString());
        return out;
    }

    /**
     * The sealed form: JSON-normalised (what is hashed is what the log stores and replay reads back), {@code sealed: true}
     * and a {@code fingerprint} over the content.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> seal(Map<String, Object> raw) {
        try {
            Map<String, Object> out = ApiContext.JSON.readValue(InvestigationEvaluator.canonical(raw), Map.class);
            out.put("sealed", true);
            out.put("basis", "Burst / periodicity findings over the Dataset's whole time range (wall-clock as written), narrowed to the "
                    + "Working Set at this log position; read live when the step was appended and sealed into the log: what the Dataset said then.");
            out.put("fingerprint", fingerprint(out));
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("a finding set is plain JSON", e);
        }
    }

    /** SHA-256 over the content of a sealed finding set (not its prose {@code basis}, {@code readAt}, {@code servedFrom}, nor the fingerprint itself). */
    static String fingerprint(Map<?, ?> sealed) {
        Map<String, Object> content = new TreeMap<>();
        for (String k : List.of("dataset", "params", "workingSet", "count", "results", "outsideWorkingSet", "truncated", "rowCapped", "skippedNoTime"))
            content.put(k, sealed.get(k));
        return InvestigationEvaluator.sha256(InvestigationEvaluator.canonical(content));
    }

    /** The finding set without the findings - for the log view, where sealed bodies stay out. */
    static Map<String, Object> summary(Map<?, ?> sealed) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("dataset", "params", "workingSet", "count", "outsideWorkingSet", "truncated", "readAt", "fingerprint"))
            out.put(k, sealed.get(k));
        return out;
    }

    /** A {@code temporal}'s line (counts only; the findings are in the entry). */
    static String line(Map<String, Object> e, Map<String, Object> p) {
        boolean burst = "burst".equals(p.get("mode"));
        String scope = "entity".equals(p.get("series")) ? "entities" : "links";
        String line = "Scanned the Working Set's " + scope + " for " + (burst
                ? "bursts (" + p.get("minEvents") + "+ events within " + p.get("windowSeconds") + " s)"
                : "periodic series (" + p.get("minEvents") + "+ events, gap CV at most " + p.get("maxCv") + ")");
        if (!(e.get("temporalFindings") instanceof Map<?, ?> c)) return line + ".";
        return line + ": " + c.get("count") + " finding" + (Integer.valueOf(1).equals(c.get("count")) ? "" : "s")
                + (Boolean.TRUE.equals(c.get("truncated")) ? " (truncated)" : "")
                + ". Sealed at this step (fingerprint " + c.get("fingerprint") + "); the Working Set does not change.";
    }
}
