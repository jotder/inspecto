package com.gamma.la.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.access.WriteGates;
import com.gamma.audit.Event;
import com.gamma.audit.EventSink;
import com.gamma.entitystore.LaLimit;
import com.gamma.la.core.InvestigationStore;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.la.core.OutboundPort;
import com.gamma.la.core.OutboundPorts;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/**
 * <b>Third-party integration, outbound</b> (LA-EDITIONS-1 phase 6, ED-6; Standard and above): ONE generic connector, no
 * named adapters.
 *
 * <ul>
 *   <li>{@code GET  /inv/integration} — the Space's settings ({@link LaIntegration}); {@code canAdminister}.</li>
 *   <li>{@code PUT  /inv/integration} — replace them; {@code canAdminister}. Gates: 403 → write root 503 → 422 → atomic write.</li>
 *   <li>{@code POST /inv/investigations/{id}/handoff} — push the Investigation's Dossier bundle and findings to the
 *       Space's external case manager and link the case id it returns; {@code canManageIncidents}, lead only.</li>
 * </ul>
 *
 * <p><b>The wire is the platform's, never a new client.</b> The send goes through {@link OutboundPort} (the bridge's
 * {@code HostOutboundPort}): a registered {@code https} Connection (the webhook sink's rules: no tunnel, no proxy, a
 * {@code ${…}} token reference), the Space's egress allowlist with the address pinned, no redirects, and the Collector
 * {@code RetryPolicy} - retrying an I/O failure, a 5xx, 408 or 429 under ONE {@code Idempotency-Key}.
 *
 * <p><b>What leaves follows the Space's masking mode</b> (D-U6) exactly as the Dossier bundle export does; a caller holding
 * {@code canRevealLinkEntities} may send {@code {"reveal": true}} to hand off raw ids, and then the payload's
 * {@code masking.mode} reads {@code revealed} and the audit row names who lifted it.
 *
 * <p><b>The returned case id is linked as an external reference</b> ({@code system} = the configured system,
 * {@code type} = {@code case}): append-only, outside the sealed header, {@code trusted:false} and granting nothing - the
 * D-6 reference model. Nothing about the external case is ever fetched back.
 */
public final class IntegrationRoutes implements RouteModule {

    /** Gated by the geoLink feature: a Space whose modules.toon disables it answers 404 MODULE_DISABLED here. */
    @Override
    public java.util.Set<String> featureIds() {
        return java.util.Set.of("geoLink", "investigations");
    }

    /** The reference {@code type} a linked external case is recorded under. */
    static final String CASE_TYPE = "case";

    @Override
    public void register(ApiContext api) {
        // ⚠ String LITERALS on purpose — CapabilityManifestTest's scanner matches only a literal argument.
        api.get("/inv/integration", ApiContext.withCapability("canAdminister", (e, m) -> describe(api.writeRoot())));
        api.put("/inv/integration", ApiContext.withCapability("canAdminister", (e, m) -> replace(api, e, api.body(e))));
        api.post("/inv/investigations/([^/]+)/handoff", ApiContext.withCapability("canManageIncidents",
                (e, m) -> handoff(api, e, m.group(1), api.body(e))));
    }

    // ── settings ─────────────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> describe(Path root) {
        Map<String, Object> doc = root == null ? new LinkedHashMap<>() : LaIntegration.raw(root);
        LaIntegration.Settings s = LaIntegration.read(root);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("handoffConfigured", s.handoff() != null);
        out.put("webhooksConfigured", s.webhooks() != null);
        out.put("installed", OutboundPorts.active().isPresent());
        out.put("settings", doc);
        out.put("events", LaIntegration.EVENTS);
        out.put("placeholders", new TreeSet<>(LaIntegration.PLACEHOLDERS));
        out.put("note", "secrets are never stored here: the token is the Connection's ${...} reference and the signing "
                + "secret is a ${...} reference resolved at send time");
        return out;
    }

    private static Object replace(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "link analysis integration settings");
        Map<String, Object> before = LaIntegration.raw(root);
        Map<String, Object> after;
        try {
            after = LaIntegration.write(root, body);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, invalid.getMessage());
        }
        String b = ApiContext.JSON.writeValueAsString(before), a = ApiContext.JSON.writeValueAsString(after);
        emit(ex, LinkEventTypes.LINK_INTEGRATION_CHANGED, "link.integration.changed",
                "link.integration.changed — " + ApiContext.actor(ex) + " replaced the integration settings",
                e -> e.actionCategory("configuration").attr("before", b).attr("after", a));
        return describe(root);
    }

    // ── case hand-off ────────────────────────────────────────────────────────────────────────────────────

    /**
     * {@code POST …/handoff} — body {@code {reveal?}}; {@code ?at=&snapshots=} as the bundle export. Gates:
     * {@code canManageIncidents} 403 → write root 503 → 422 id → lead-only 403/404 → 422 body → {@code reveal} without
     * {@code canRevealLinkEntities} 403 → no hand-off configured 409 → no outbound port 503 → send: refused 422 (nothing
     * sent) · failed after retries 502 · answered without a case id 502 · reference list full 409 → link + audit.
     */
    @SuppressWarnings("unchecked")
    private static Object handoff(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        DossierRoutes.Opened inv = DossierRoutes.openAsLead(api, ex, id);
        for (String k : body.keySet())
            if (!"reveal".equals(k))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unknown key '" + k + "' (expected reveal)");
        Object rv = body.get("reveal");
        if (rv != null && !(rv instanceof Boolean))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'reveal' must be true or false");
        boolean reveal = Boolean.TRUE.equals(rv);
        if (reveal) ApiContext.requireCapability(ex, "canRevealLinkEntities");
        LaIntegration.Settings settings = LaIntegration.read(inv.writeRoot());
        LaIntegration.Handoff h = settings.handoff();
        if (h == null)
            throw new ApiException(409, ErrorCodes.CONFLICT, "no case hand-off is configured for this Space — an administrator "
                    + "sets one with PUT /inv/integration");
        OutboundPort port = OutboundPorts.active().orElseThrow(() -> new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE,
                "outbound integration is not installed in this bundle (inspecto-geo-link) — nothing was sent"));

        Map<String, Object> bundle = DossierBundleRoutes.build(api, ex, inv, reveal);
        Map<String, Object> dossier = (Map<String, Object>) bundle.get("dossier");
        Map<String, Object> header = inv.inv().header();
        Map<String, Object> masking = new LinkedHashMap<>((Map<String, Object>) bundle.get("masking"));
        masking.put("note", reveal ? "masking was lifted for this hand-off by a holder of canRevealLinkEntities: the bundle "
                + "carries raw entity ids" : "entity ids follow the Space's masking mode: a masked id leaves as masked:<16 hex>, "
                + "a stable pseudonym the case manager cannot reverse");
        String seal = String.valueOf(((Map<String, Object>) bundle.get("seal")).get("value"));
        Map<String, Object> findings = new LinkedHashMap<>();
        findings.put("summary", dossier.get("summary"));
        findings.put("temporalFindings", dossier.get("temporalFindings"));
        findings.put("comparisons", dossier.get("comparisons"));

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("investigation.id", id);
        context.put("investigation.purpose", header.get("purpose"));
        context.put("investigation.owner", header.get("owner"));
        context.put("investigation.dataset", header.get("dataset"));
        context.put("bundle", bundle);
        context.put("findings", findings);
        context.put("masking", masking);
        context.put("seal", seal);
        context.put("manifestRoot", ((Map<String, Object>) bundle.get("custody")).get("manifestRoot"));
        context.put("handedOffBy", ApiContext.actor(ex));
        context.put("handedOffAt", Instant.now().toString());
        Object payload = LaIntegration.render(h.template() != null ? h.template() : defaultTemplate(), context);
        String json = ApiContext.JSON.writeValueAsString(payload);

        OutboundPort.Result r = port.send(inv.writeRoot(), h.connection(), json, Map.of("Idempotency-Key", "handoff:" + id + ":" + seal),
                h.authHeader(), settings.retry());
        String maskMode = String.valueOf(masking.get("mode"));
        if (r.refused()) {
            failed(ex, id, h, r, "refused: " + r.error());
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "nothing was sent: " + r.error());
        }
        if (!r.delivered()) {
            String why = r.status() == null ? "no answer (" + r.error() + ")" : "HTTP " + r.status();
            failed(ex, id, h, r, why + " after " + r.attempts() + " attempt(s)");
            throw new ApiException(502, ErrorCodes.INTERNAL, "the case manager answered " + why + " after " + r.attempts()
                    + " attempt(s) — nothing was linked");
        }
        int maxId = SpaceLimits.get(inv.writeRoot(), LaLimit.INPUT_REFERENCE_ID_LENGTH);
        String caseId = caseId(r.body(), h.caseIdPointer(), maxId);
        if (caseId == null) {
            failed(ex, id, h, r, "delivered, but the answer carries no usable case id at " + h.caseIdPointer());
            throw new ApiException(502, ErrorCodes.INTERNAL, "the case manager accepted the hand-off (HTTP " + r.status()
                    + ") but its answer carries no usable case id at " + h.caseIdPointer() + " — nothing was linked");
        }
        String caseUrl = caseUrl(r.body(), h.caseUrlPointer(), SpaceLimits.get(inv.writeRoot(), LaLimit.INPUT_REFERENCE_URL_LENGTH));
        int max = SpaceLimits.get(inv.writeRoot(), LaLimit.INPUT_REFERENCES);
        InvestigationStore.Appended linked = InvestigationReferenceRoutes.append(ex, inv.inv(), h.system(), CASE_TYPE, caseId,
                caseUrl, "hand-off at step " + bundle.get("at"), max);
        if (linked == InvestigationStore.Appended.FULL) {
            failed(ex, id, h, r, "delivered as case " + caseId + ", but the Investigation's reference list is full");
            throw new ApiException(409, ErrorCodes.CONFLICT, "the case manager created case '" + caseId + "', but this "
                    + "investigation already holds " + max + " external references — the case id was not linked");
        }
        emit(ex, LinkEventTypes.LINK_INVESTIGATION_HANDED_OFF, "link.investigation.handed-off",
                "link.investigation.handed-off — " + id + " → " + h.system() + "/" + caseId,
                e -> e.attr("investigationId", id).attr("system", h.system()).attr("externalCaseId", caseId)
                        .attr("connection", h.connection()).attr("status", r.status()).attr("attempts", r.attempts())
                        .attr("masking", maskMode).attr("seal", seal));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("investigationId", id);
        out.put("delivered", true);
        out.put("system", h.system());
        out.put("externalCaseId", caseId);
        out.put("url", caseUrl);
        out.put("linked", linked == InvestigationStore.Appended.ADDED ? "added" : "already linked");
        out.put("status", r.status());
        out.put("attempts", r.attempts());
        out.put("masking", masking);
        out.put("seal", seal);
        return out;
    }

    /** The payload when the Space names no template: everything, under stable keys. */
    static Map<String, Object> defaultTemplate() {
        Map<String, Object> inv = new LinkedHashMap<>();
        inv.put("id", "{{investigation.id}}");
        inv.put("purpose", "{{investigation.purpose}}");
        inv.put("owner", "{{investigation.owner}}");
        inv.put("dataset", "{{investigation.dataset}}");
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("source", "inspecto-la");
        t.put("kind", "investigation.handoff");
        t.put("investigation", inv);
        t.put("findings", "{{findings}}");
        t.put("masking", "{{masking}}");
        t.put("seal", "{{seal}}");
        t.put("handedOffBy", "{{handedOffBy}}");
        t.put("handedOffAt", "{{handedOffAt}}");
        t.put("bundle", "{{bundle}}");
        return t;
    }

    /** The case id at {@code pointer} of a JSON answer: a non-blank text or a whole number, bounded, no control characters. */
    static String caseId(String body, String pointer, int maxLength) {
        JsonNode n = at(body, pointer);
        if (n == null || !(n.isTextual() || n.isIntegralNumber())) return null;
        String v = n.asText().strip();
        if (v.isEmpty() || v.length() > maxLength) return null;
        for (int i = 0; i < v.length(); i++) if (Character.isISOControl(v.charAt(i))) return null;
        return v;
    }

    /** The case URL at {@code pointer}, kept only when it is an absolute http(s) URL with no credentials (else dropped). */
    private static String caseUrl(String body, String pointer, int maxLength) {
        if (pointer == null) return null;
        JsonNode n = at(body, pointer);
        if (n == null || !n.isTextual() || n.asText().length() > maxLength) return null;
        try {
            InvestigationReferenceRoutes.checkUrl(n.asText());
            return n.asText();
        } catch (ApiException notAUrl) {
            return null;
        }
    }

    private static JsonNode at(String body, String pointer) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode n = ApiContext.JSON.readTree(body).at(pointer);
            return n.isMissingNode() || n.isNull() ? null : n;
        } catch (IOException | IllegalArgumentException notJson) {
            return null;
        }
    }

    private static void failed(HttpExchange ex, String id, LaIntegration.Handoff h, OutboundPort.Result r, String reason) {
        emit(ex, LinkEventTypes.LINK_INVESTIGATION_HANDOFF_FAILED, "link.investigation.handoff.failed",
                "link.investigation.handoff.failed — " + id + ": " + reason,
                e -> e.attr("investigationId", id).attr("connection", h.connection()).attr("reason", reason)
                        .attr("status", r.status() == null ? "" : String.valueOf(r.status())).attr("attempts", r.attempts()));
    }

    /** Best-effort audit (LA-04 pattern): an audit failure never fails the request. */
    private static void emit(HttpExchange ex, String type, String action, String message, UnaryOperator<Event.Builder> attrs) {
        try {
            Event.Builder b = Event.builder(type).source("inv").message(message)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action(action).actionCategory("analysis");
            EventSink.current().emit(attrs.apply(b));
        } catch (RuntimeException ignored) {
            // best effort
        }
    }
}
