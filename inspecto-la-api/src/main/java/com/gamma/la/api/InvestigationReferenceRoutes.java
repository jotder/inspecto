package com.gamma.la.api;

import com.gamma.la.core.InvestigationEvaluator;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.la.core.SnapshotStore;
import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.ErrorCodes;
import com.gamma.control.RouteModule;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * D-6 — <b>external references</b> on an Investigation: {@code {system, type, id, url?, label?}} pointers to things in
 * other systems (a Case in a ticketing tool, a SAR number, a CRM record). Integration is BY REFERENCE (feasibility
 * plan §7.6): nothing is copied in, nothing is fetched, no installation trusts another.
 *
 * <ul>
 *   <li>{@code GET  /inv/investigations/{id}/references} — the references, in the order added. The read gate
 *       ({@link InvestigationRoutes#openForRead}: owner, or a member of the linked Case).</li>
 *   <li>{@code POST /inv/investigations/{id}/references} — append one; owner-only, {@code canManageIncidents}.</li>
 * </ul>
 *
 * <p>⛔ <b>A reference is never trusted data.</b> It is caller text. It is never dereferenced (the server makes no
 * call to {@code url}), never merged into the Working Set or the sealed log, never read as an id the analysis acts
 * on, and it grants nothing — in particular a reference naming a Case does NOT share the Investigation with that
 * Case's team; only {@code PUT …/case} does, and that checks the Case. Every record carries {@code trusted:false}
 * so a consumer cannot forget. {@code url} must be an absolute {@code http}/{@code https} URL with no embedded
 * credentials (a {@code javascript:} or {@code file:} URL would be an XSS or local-read vector in any UI that
 * renders it as a link).
 *
 * <p><b>Append-only, outside the sealed header</b> ({@code references.jsonl}, {@link SnapshotStore#appendReference}):
 * the header is write-once, and a reference is a relationship, not evidence — so adding one never invalidates an issued
 * Dossier. There is no edit and no delete; a wrong reference is superseded by a new one. A duplicate
 * {@code (system, type, id)} is a 409, and an Investigation holds at most {@link #MAX} references (409).
 */
public final class InvestigationReferenceRoutes implements RouteModule {

    /** The most references one Investigation may carry. */
    static final int MAX = 200;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final int ID_MAX = 256, URL_MAX = 2048, LABEL_MAX = 200;

    @Override
    public void register(ApiContext api) {
        // ⚠ String LITERALS on purpose — CapabilityManifestTest's scanner matches only a literal argument.
        api.get("/inv/investigations/([^/]+)/references", (e, m) -> list(api, e, m.group(1)));
        api.post("/inv/investigations/([^/]+)/references", ApiContext.withCapability("canManageIncidents",
                (e, m) -> add(api, e, m.group(1), api.body(e))));
    }

    /** {@code GET …/references} — gates: write root 503 → 422 id → 403 → 404 absent / not owner / R3. */
    private static Object list(ApiContext api, HttpExchange ex, String id) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.openForRead(api, ex, id);
        return describe(inv.id(), parse(inv.store().readReferences(id)));
    }

    /**
     * {@code POST …/references} — body {@code {system, type, id, url?, label?}}. Gates: {@code canManageIncidents} 403 →
     * write root 503 → 422 id → owner-only 404 → 422 field → 409 duplicate / full → append.
     */
    private static Object add(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.open(api, ex, id);
        String system = name(body, "system"), type = name(body, "type");
        String refId = text(body, "id", ID_MAX, true);
        String url = text(body, "url", URL_MAX, false);
        String label = text(body, "label", LABEL_MAX, false);
        if (url != null) checkUrl(url);

        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("key", InvestigationEvaluator.sha256(system + "\n" + type + "\n" + refId));   // FIRST: appendReference matches the line prefix
        rec.put("system", system);
        rec.put("type", type);
        rec.put("id", refId);
        if (url != null) rec.put("url", url);
        if (label != null) rec.put("label", label);
        rec.put("addedBy", ApiContext.actor(ex));
        rec.put("addedAt", Instant.now().toString());
        SnapshotStore.Appended r = inv.store().appendReference(id, String.valueOf(rec.get("key")),
                ApiContext.JSON.writeValueAsString(rec), MAX);
        if (r == SnapshotStore.Appended.DUPLICATE)
            throw new ApiException(409, ErrorCodes.CONFLICT, "reference " + system + "/" + type + "/" + refId + " is already recorded");
        if (r == SnapshotStore.Appended.FULL)
            throw new ApiException(409, ErrorCodes.CONFLICT, "an investigation holds at most " + MAX + " external references");
        List<Map<String, Object>> all = parse(inv.store().readReferences(id));
        int seq = all.size();
        emit(ex, LinkEventTypes.LINK_INVESTIGATION_REFERENCE_ADDED, "link.investigation.reference.added",
                "link.investigation.reference.added — " + id + " ← " + system + "/" + type,
                b -> b.attr("investigationId", id).attr("system", system).attr("type", type).attr("id", refId)
                        .attr("seq", seq));
        return describe(id, all);
    }

    // ── shared with the Dossier bundle ─────────────────────────────────────────────────────────────────

    /** The stored lines as records, each stamped with its 1-based {@code seq} and {@code trusted:false}. */
    static List<Map<String, Object>> parse(List<String> lines) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        int seq = 0;
        for (String line : lines) {
            @SuppressWarnings("unchecked") Map<String, Object> m = ApiContext.JSON.readValue(line, Map.class);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("seq", ++seq);
            r.putAll(m);
            r.put("trusted", false);
            out.add(r);
        }
        return out;
    }

    private static Map<String, Object> describe(String id, List<Map<String, Object>> refs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("investigationId", id);
        out.put("references", refs);
        out.put("count", refs.size());
        out.put("max", MAX);
        out.put("note", "pointers only — never fetched, never trusted, and they grant no access");
        return out;
    }

    private static String name(Map<String, Object> body, String field) {
        String v = text(body, field, 64, true);
        if (!NAME.matcher(v).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + field + "' must match " + NAME.pattern());
        return v;
    }

    private static String text(Map<String, Object> body, String field, int max, boolean required) {
        Object raw = body.get(field);
        if (raw == null || raw instanceof String s && s.isBlank()) {
            if (required) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include '" + field + "'");
            return null;
        }
        if (!(raw instanceof String s))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + field + "' must be a string");
        String v = s.strip();
        if (v.length() > max)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + field + "' is at most " + max + " characters");
        for (int i = 0; i < v.length(); i++)
            if (Character.isISOControl(v.charAt(i)))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + field + "' must not contain control characters");
        return v;
    }

    private static void checkUrl(String url) {
        try {
            URI u = new URI(url);
            String scheme = u.getScheme();
            if (!u.isAbsolute() || scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || u.getHost() == null)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'url' must be an absolute http or https URL");
            if (u.getUserInfo() != null)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'url' must not embed credentials");
        } catch (URISyntaxException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'url' is not a valid URL");
        }
    }

    /** Best-effort audit (LA-04 pattern): an audit failure never fails the write. */
    private static void emit(HttpExchange ex, String type, String action, String message,
                             UnaryOperator<Event.Builder> attrs) {
        try {
            Event.Builder b = Event.builder(type).source("inv").message(message)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action(action).actionCategory("analysis");
            EventLog.current().emit(attrs.apply(b));
        } catch (RuntimeException ignored) {
            // best effort — the reference is already appended
        }
    }
}
