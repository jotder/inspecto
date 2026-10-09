package com.gamma.screening;

import com.gamma.access.WriteGates;
import com.gamma.entitystore.EntityListFacts;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>Screening</b> (SCREENING-1, {@code docs/superpower/screening-addon-plan.md}): Match Scores of subjects against
 * Entity Lists, and the review of the Screening Hits a {@code screening.run} raises.
 *
 * <ul>
 *   <li>{@code POST /screening/check} {@code {subjects:[{key?, name?, identifier?}], lists[], threshold?, maxMatches?}}
 *       → {@code {threshold, results:[{key, name, identifier, matches:[…]}]}}. Read-shaped (a {@code CapabilityManifest}
 *       exemption): it persists nothing, and it is a POST so names never ride in a URL.</li>
 *   <li>{@code GET /screening/hits?state=} → {@code {hits:[…]}}, newest first; {@code GET /screening/hits/{id}}.</li>
 *   <li>{@code POST /screening/hits/{id}/decide} {@code {decision: confirm|dismiss|escalate, reason, version}} → the
 *       hit. {@code canWorkIncidents} (SCR-D13).</li>
 * </ul>
 *
 * <p><b>Gates.</b> decide: {@code canWorkIncidents} (the wrapper) → no write root 503 → body 422 → unknown hit 404 →
 * forged / edited record 409 → stale {@code version} 409 → not allowed from its state 409 → save under the store
 * lock. No path-jail 403 can trigger from a caller: the id must match {@code sh-<14 digits>-<6 hex>} (else 404).
 */
public final class ScreeningRoutes implements RouteModule {

    static final int MAX_SUBJECTS = 1_000;
    static final int MAX_LISTS = 20;
    static final int MAX_VALUE_LENGTH = 512;

    @Override
    public Set<String> featureIds() {
        return Set.of("screening");
    }

    @Override
    public void register(ApiContext api) {
        // ⚠ String LITERALS on purpose — CapabilityManifestTest's scanner matches only a literal argument.
        api.post("/screening/check", (e, m) -> check(api, api.body(e)));
        api.get("/screening/hits", (e, m) -> hits(api, e));
        api.get("/screening/hits/([^/]+)", (e, m) -> one(api, m.group(1)));
        api.post("/screening/hits/([^/]+)/decide", ApiContext.withCapability("canWorkIncidents",
                (e, m) -> decide(api, e, m.group(1), api.body(e))));
    }

    private Object check(ApiContext api, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "screening");
        List<Screener.Subject> subjects = subjects(body);
        List<String> listIds = lists(body);
        double threshold = threshold(body.get("threshold"));
        int maxMatches = maxMatches(body.get("maxMatches"));
        Instant now = Instant.now();
        List<Screener.Prepared> lists;
        try {
            lists = Screener.load(root, listIds, now);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Screener.Subject s : subjects) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("key", s.key());
            r.put("name", s.name());
            r.put("identifier", s.identifier());
            r.put("matches", Screener.screen(s, lists, threshold, maxMatches, now).stream().map(Screener::toMap).toList());
            results.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("threshold", threshold);
        out.put("results", results);
        return out;
    }

    private Object hits(ApiContext api, HttpExchange ex) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "screening hits");
        String state = ApiContext.query(ex, "state");
        if (state != null && !ScreeningHits.STATES.contains(state))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'state' must be one of "
                    + new java.util.TreeSet<>(ScreeningHits.STATES));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> rec : ScreeningHits.list(root))
            if (state == null || state.equals(rec.get("state"))) out.add(ScreeningHits.render(rec));
        return Map.of("hits", out);
    }

    private Object one(ApiContext api, String id) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "screening hits");
        Map<String, Object> rec = ScreeningHits.read(root, id);
        if (rec == null) throw notFound(id);
        return ScreeningHits.render(rec);
    }

    private Object decide(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "screening hit decision");
        String decision = ApiContext.str(body, "decision");
        String to = decision == null ? null : ScreeningHits.DECISIONS.get(decision);
        if (to == null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'decision' must be one of "
                    + new java.util.TreeSet<>(ScreeningHits.DECISIONS.keySet()));
        String reason = EntityListFacts.reason(body);
        if (!(body.get("version") instanceof Number v) || v.doubleValue() != Math.rint(v.doubleValue()))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'version', the hit's "
                    + "current version (an integer)");
        int version = v.intValue();
        String actor = ApiContext.actor(ex);
        Map<String, Object> rec;
        String from;
        synchronized (ScreeningHits.lock()) {
            rec = ScreeningHits.read(root, id);
            if (rec == null) throw notFound(id);
            if (ScreeningHits.invalid(rec))
                throw new ApiException(409, ErrorCodes.CONFLICT, "screening hit '" + id + "' failed its integrity check "
                        + "(a record no server wrote) and cannot be decided");
            int current = ((Number) rec.get("version")).intValue();
            if (current != version)
                throw new ApiException(409, ErrorCodes.CONFLICT, "screening hit '" + id + "' is at version " + current
                        + ", not " + version + " — it changed since you read it; reload and decide again");
            from = String.valueOf(rec.get("state"));
            if (!ScreeningHits.allowed(from, to))
                throw new ApiException(409, ErrorCodes.CONFLICT, "screening hit '" + id + "' is " + from
                        + " and cannot be moved to " + to);
            ScreeningHits.transition(rec, to, actor, reason);
            ScreeningHits.save(root, rec);
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("screeningHit", id);
        attrs.put("listId", rec.get("listId"));
        attrs.put("from", from);
        attrs.put("to", to);
        ScreeningHits.audit(actor, ApiContext.actorType(ex), "screening.hit.decided",
                "screening hit " + id + " " + from + " -> " + to, attrs);
        return ScreeningHits.render(rec);
    }

    // ── body parsing ──────────────────────────────────────────────────────────────────────────────────────

    private static List<Screener.Subject> subjects(Map<String, Object> body) {
        if (!(body.get("subjects") instanceof List<?> raw) || raw.isEmpty() || raw.size() > MAX_SUBJECTS)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'subjects' must list 1.." + MAX_SUBJECTS
                    + " subjects");
        List<Screener.Subject> out = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            if (!(raw.get(i) instanceof Map<?, ?> m))
                throw bad("'subjects[" + i + "]' must be an object {key?, name?, identifier?}");
            String key = text(m.get("key"), "subjects[" + i + "].key");
            String name = text(m.get("name"), "subjects[" + i + "].name");
            String identifier = text(m.get("identifier"), "subjects[" + i + "].identifier");
            if ((name == null || name.isBlank()) && (identifier == null || identifier.isBlank()))
                throw bad("'subjects[" + i + "]' needs a 'name' or an 'identifier'");
            out.add(new Screener.Subject(key != null ? key : String.valueOf(i), name, identifier));
        }
        return out;
    }

    private static String text(Object o, String what) {
        if (o == null) return null;
        if (!(o instanceof String s) || s.length() > MAX_VALUE_LENGTH)
            throw bad("'" + what + "' must be a string of at most " + MAX_VALUE_LENGTH + " characters");
        return s;
    }

    private static List<String> lists(Map<String, Object> body) {
        if (!(body.get("lists") instanceof List<?> raw) || raw.isEmpty() || raw.size() > MAX_LISTS)
            throw bad("'lists' must name 1.." + MAX_LISTS + " Entity Lists");
        List<String> out = new ArrayList<>();
        for (Object o : raw) {
            if (!(o instanceof String s) || !EntityListFacts.LIST_ID.matcher(s).matches())
                throw bad("'lists' must hold Entity List ids matching " + EntityListFacts.LIST_ID.pattern());
            out.add(s);
        }
        return out;
    }

    /** {@code threshold}: absent = the default; else a number in {@link Screener#MIN_THRESHOLD}..1. */
    static double threshold(Object raw) {
        if (raw == null) return Screener.DEFAULT_THRESHOLD;
        double t;
        if (raw instanceof Number n) t = n.doubleValue();
        else {
            try {
                t = Double.parseDouble(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                t = Double.NaN;
            }
        }
        if (!(t >= Screener.MIN_THRESHOLD && t <= 1.0))
            throw bad("'threshold' must be a number in " + Screener.MIN_THRESHOLD + "..1.0");
        return t;
    }

    static int maxMatches(Object raw) {
        if (raw == null) return Screener.DEFAULT_MAX_MATCHES;
        if (!(raw instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())
                || n.intValue() < 1 || n.intValue() > Screener.MAX_MATCHES)
            throw bad("'maxMatches' must be an integer in 1.." + Screener.MAX_MATCHES);
        return n.intValue();
    }

    private static ApiException bad(String message) {
        return new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, message);
    }

    private static ApiException notFound(String id) {
        return new ApiException(404, ErrorCodes.NOT_FOUND, "screening hit '" + id + "' not found");
    }
}
