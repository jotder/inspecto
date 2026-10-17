package com.gamma.la.api;

import com.gamma.audit.Event;
import com.gamma.audit.EventSink;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.la.core.CasePort;
import com.gamma.la.core.CasePorts;
import com.gamma.la.core.InvestigationEvaluator;
import com.gamma.la.core.InvestigationMembers;
import com.gamma.la.core.InvestigationStore;
import com.gamma.la.core.InvestigationStores;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.EmbedGrant;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.auth.PublicPaths;
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

/**
 * {@code GET /public/investigations/{token}} - the read-only, masked, LIVE view of ONE Investigation for someone with no login
 * (LA-EMBED-VIEW-1). The token IS the credential: the route is exempt from platform authentication through the {@link PublicPaths}
 * registry, and the holder is an {@link EmbedGrant}, never a {@code Subject} - so no capability gate and no other route accepts it.
 *
 * <p>Every failure - disabled, malformed, unknown, tampered, expired, revoked, a deleted Investigation, a minter who is no longer a lead, a
 * closed Case, masking loosened since the mint - is the SAME {@code 404} (status, code and message), so the answer is no oracle; the
 * specific reason goes only to the audit row ({@code LINK_EMBED_DENIED}, with a hash prefix of the token, never the token). A grant whose
 * conditions no longer hold is also revoked on the spot, so the list shows it. The view is read from the SEALED log and sidecars only (no
 * Dataset read), masked exactly as an Investigation read masks it (the Space's {@code maskingMode}, D3, never a second path), and carries no
 * Dataset name, SQL, reveal, pending expand, draft or Case. GET only: any other verb is the host's 405.
 *
 * <p>Response headers: {@code Content-Security-Policy: frame-ancestors 'none'} and {@code X-Frame-Options: DENY} (link-only, D2),
 * {@code Cache-Control: no-store}, {@code Referrer-Policy: no-referrer} (the token is in the URL), {@code X-Content-Type-Options: nosniff},
 * and no CORS or cookie header. The token is kept out of every log line and audit row by {@link PublicPaths#redact}.
 */
public final class PublicInvestigationRoutes implements RouteModule {

    /**
     * Gated by the geoLink feature like every LA route module: a Space whose modules.toon disables it answers 404 MODULE_DISABLED here.
     * (That reveals the Space's module setting, never whether a token was valid.)
     */
    @Override
    public java.util.Set<String> featureIds() {
        return java.util.Set.of("geoLink", "investigations");
    }

    /** The one 404 every failure answers. */
    private static ApiException notFound() {
        return new ApiException(404, ErrorCodes.NOT_FOUND, "not found");
    }

    @Override
    public void register(ApiContext api) {
        PublicPaths.registerSelfVerifying(EmbedGrants.PREFIX);   // exempt from platform auth; the token after it is redacted from every record
        api.get("/public/investigations/([^/]+)", (e, m) -> view(api, e, m.group(1)));
        PublicPaths.registerRedacted("/embed/inv/");   // the viewer page's path carries the same token
        com.gamma.spi.auth.EmbedShellSpaces.register(token -> spaceOfLiveGrant(api, token));
    }

    /**
     * The Space whose LIVE grant {@code token} is, for the viewer page's frame headers: only that Space's allow-list may frame the page.
     * Empty when the feature is off, the token is malformed, unknown, revoked or expired. Every Space is asked, like {@link #view}.
     */
    static java.util.Optional<Path> spaceOfLiveGrant(ApiContext api, String token) {
        if (!EmbedGrants.enabled()) return java.util.Optional.empty();
        Path found = null;
        Instant now = Instant.now();
        for (var space : api.attachedSpaceRoots().entrySet()) {
            if (space.getValue().contains("geoLink")) continue;
            try {
                if (EmbedGrants.resolve(InvestigationStores.of(space.getKey()), token, now).ok() && found == null) found = space.getKey();
            } catch (IOException | RuntimeException unavailable) {
                // a Space that is down only hides its own grants
            }
        }
        return java.util.Optional.ofNullable(found);
    }

    private static Object view(ApiContext api, HttpExchange ex, String token) throws IOException {
        try {
            return viewOf(api, ex, token);
        } finally {
            org.slf4j.MDC.remove(com.gamma.util.CurrentSpace.SPACE_MDC_KEY);   // bound below once the token's Space is known; never left on the thread
        }
    }

    private static Object viewOf(ApiContext api, HttpExchange ex, String token) throws IOException {
        harden(ex);
        ApiContext.attr(ex, ApiContext.ATTR_SUBJECT, null);   // the holder is never a Subject, even if the caller also sent a session
        Instant now = Instant.now();
        if (!EmbedGrants.enabled()) throw denied(ex, null, token, "DISABLED");

        // The token does not name its Space (it is <grantId>.<secret>; a Space hint would make the token longer, the id guessable per Space
        // and change nothing about the hash-at-rest), so the grant is looked up in EVERY attached Space whose geoLink module is on. Every
        // Space is always asked - no early exit - so a hit costs the same as a miss, and a Space that is down only hides its own grants.
        Path root = null;
        InvestigationStore store = null;
        EmbedGrants.Resolution r = null;
        boolean broken = false;
        for (var space : api.attachedSpaceRoots().entrySet()) {
            if (space.getValue().contains("geoLink")) continue;
            try {
                InvestigationStore candidate = InvestigationStores.of(space.getKey());
                EmbedGrants.Resolution found = EmbedGrants.resolve(candidate, token, now);
                if (root == null && (found.record() != null || found.ok())) {
                    root = space.getKey();
                    store = candidate;
                    r = found;
                } else if (r == null && root == null && found.denied() == EmbedGrants.Denied.MALFORMED) {
                    r = found;
                }
            } catch (IOException | RuntimeException unavailable) {
                broken = true;
            }
        }
        if (root == null) {
            if (r != null) throw denied(ex, null, token, r.denied().name());
            throw denied(ex, null, token, broken ? "STORE_UNAVAILABLE" : "UNKNOWN");
        }
        // The path names no Space, so the host bound none: bind the grant's own, or the VIEWED / DENIED / REVOKED rows below would go to the
        // in-memory global log and reach no Space's event store.
        api.spaceIdOf(root).ifPresent(id -> org.slf4j.MDC.put(com.gamma.util.CurrentSpace.SPACE_MDC_KEY, id));
        if (!r.ok()) throw denied(ex, r.record(), token, r.denied().name());   // a known grant: the row names it (id, Investigation), never the token
        Map<String, Object> rec = r.record();
        String grantId = String.valueOf(rec.get("grantId"));
        String invId = String.valueOf(rec.get("investigationId"));

        String rawHeader = store.header(invId).orElse(null);
        if (rawHeader == null) throw revokedAndDenied(ex, store, rec, token, "INVESTIGATION_GONE", now);
        @SuppressWarnings("unchecked") Map<String, Object> header = ApiContext.JSON.readValue(rawHeader, Map.class);
        InvestigationRoutes.Inv inv = new InvestigationRoutes.Inv(store, root, invId, header);
        String why = liveness(api, inv, rec, root);
        if (why != null) throw revokedAndDenied(ex, store, rec, token, why, now);

        EmbedGrant grant = new EmbedGrant(grantId, invId, String.valueOf(rec.get("issuedBy")),
                Instant.parse(String.valueOf(rec.get("issuedAt"))), Instant.parse(String.valueOf(rec.get("expiresAt"))),
                (String) rec.get("label"));
        ApiContext.attr(ex, ApiContext.ATTR_EMBED_GRANT, grant);

        Map<String, Object> out = build(inv, grant, rec);
        EmbedGrants.viewed(store, grantId, now);
        int step = out.get("head") instanceof Map<?, ?> h && h.get("step") instanceof Number n ? n.intValue() : 0;
        emit(LinkEventTypes.LINK_EMBED_VIEWED, grant.actor(), "link.embed.viewed", "link.embed.viewed - " + invId + " " + grantId, ex,
                b -> b.attr("investigationId", invId).attr("grantId", grantId).attr("issuedBy", grant.issuedBy()).attr("step", step)
                        .attr("uaHash", EmbedGrants.hashPrefix(String.valueOf(ex.getRequestHeaders().getFirst("User-Agent")))));
        return out;
    }

    /**
     * Why a grant no longer holds, or null when it does: its minter is no longer a LEAD of the Investigation, the Investigation's linked
     * Case has closed (the Investigation is closed with it), or the Space's masking was loosened since the mint (a stricter mode never revokes).
     */
    private static String liveness(ApiContext api, InvestigationRoutes.Inv inv, Map<String, Object> rec, Path root) throws IOException {
        Map<String, InvestigationMembers.Role> roles = InvestigationMemberStore.roles(inv.store(), inv.id(), inv.header().get("owner"));
        if (roles.get(String.valueOf(rec.get("issuedBy"))) != InvestigationMembers.Role.LEAD) return "MINTER_NOT_LEAD";
        try {
            String caseRef = InvestigationCaseRoutes.caseRef(inv.store(), inv.id());
            if (caseRef != null) {
                CasePort port = CasePorts.available(api).orElse(null);
                if (port != null) {
                    Map<String, Object> c = port.summary(api, caseRef).orElse(null);
                    if (c != null && Boolean.TRUE.equals(c.get("closed"))) return "CASE_CLOSED";
                }
            }
        } catch (RuntimeException unavailable) {
            // a Case that cannot be asked about is not evidence the Investigation closed
        }
        String atMint = rec.get("maskingModeAtMint") == null ? null : String.valueOf(rec.get("maskingModeAtMint"));
        if (atMint != null && strictness(LinkAnalysisSettings.forRoot(root).effectiveMaskingMode()) < strictness(atMint)) return "MASKING_LOOSENED";
        return null;
    }

    /** How strict a masking mode is: {@code none} 0, {@code typed} 1, {@code all} 2 (an unknown mode is the strictest). */
    private static int strictness(String mode) {
        return switch (mode) {
            case "none" -> 0;
            case "typed" -> 1;
            default -> 2;
        };
    }

    /** The masked view: header, Working Set, notes and references - evaluated from the sealed log, masked as the Space masks. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> build(InvestigationRoutes.Inv inv, EmbedGrant grant, Map<String, Object> rec) throws IOException {
        String scope = rec.get("scope") == null ? EmbedGrants.SCOPE_FULL : String.valueOf(rec.get("scope"));
        String floor = rec.get("maskingMode") == null ? null : String.valueOf(rec.get("maskingMode"));
        List<Map<String, Object>> log = new ArrayList<>();
        for (String line : inv.logLines()) log.add(ApiContext.JSON.readValue(line, Map.class));
        InvestigationEvaluator.State s = InvestigationEvaluator.evaluate(log, -1, null);
        int headStep = log.isEmpty() ? 0 : ((Number) log.get(log.size() - 1).get("step")).intValue();
        Map<String, List<Map<String, Object>>> tables = WorkingSetRoutes.tables(s);
        EntityMasking mask = EntityMasking.of(inv, log, List.of(), floor);

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> hdr = new LinkedHashMap<>();
        hdr.put("id", inv.id());
        hdr.put("title", inv.header().get("title") == null ? null : mask.inText(String.valueOf(inv.header().get("title"))));
        hdr.put("purpose", inv.header().get("purpose") == null ? null : mask.inText(String.valueOf(inv.header().get("purpose"))));
        hdr.put("createdAt", inv.header().get("createdAt"));
        out.put("investigation", hdr);
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("step", headStep);
        head.put("workingSetHash", s.hash());
        out.put("head", head);

        List<Map<String, Object>> entities = new ArrayList<>();
        for (Map<String, Object> row : tables.get("entities")) {
            Map<String, Object> e = new LinkedHashMap<>();
            for (String k : List.of("entityId", "type", "hop", "hidden", "kept", "identity", "highConnectivity")) e.put(k, row.get(k));
            entities.add(e);
        }
        List<Map<String, Object>> links = new ArrayList<>();
        for (Map<String, Object> row : tables.get("links")) {
            Map<String, Object> l = new LinkedHashMap<>();
            for (String k : List.of("source", "target", "kind", "count")) l.put(k, row.get(k));
            links.add(l);
        }
        Map<String, Object> ws = new LinkedHashMap<>();
        ws.put("entities", entities);
        ws.put("links", links);
        ws.put("excludedCount", tables.get("excluded").size());
        out.put("workingSet", ws);

        List<Map<String, Object>> notes = new ArrayList<>();
        s.annotations.forEach((id, list) -> list.forEach(a -> {
            Map<String, Object> n = note(a, mask);
            n.put("entityId", id);
            notes.add(n);
        }));
        s.linkAnnotations.forEach((key, list) -> {
            String[] k = key.split("\u0000", -1);
            list.forEach(a -> {
                Map<String, Object> n = note(a, mask);
                n.put("source", k[0]);
                n.put("target", k[1]);
                n.put("kind", k[2]);
                notes.add(n);
            });
        });
        out.put("annotations", "working_set".equals(scope) ? List.of() : notes);
        if (!EmbedGrants.SCOPE_FULL.equals(scope)) out.put("references", List.of());
        else try {
            out.put("references", InvestigationReferenceRoutes.parse(inv.store().references(inv.id())));
        } catch (RuntimeException unreadable) {
            out.put("references", List.of());
        }
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("expiresAt", grant.expiresAt().toString());
        g.put("scope", scope);
        out.put("link", g);

        Map<String, Object> masked = (Map<String, Object>) InvestigationRoutes.masked(inv, out, floor);   // the Space's masking, the one path
        return masked;
    }

    private static Map<String, Object> note(InvestigationEvaluator.Annotation a, EntityMasking mask) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("step", a.step());
        n.put("note", mask.inText(a.note()));
        if (a.confidence() != null) n.put("confidence", a.confidence());
        return n;
    }

    // -- headers, refusals and audit -----------------------------------------------------------------

    /** Headers on EVERY answer of this route, the 404s included: link-only framing, no caching, no referrer, no CORS, no cookie. */
    private static void harden(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("Content-Security-Policy", "frame-ancestors 'none'");
        h.set("X-Frame-Options", "DENY");
        h.set("Cache-Control", "no-store");
        h.set("Referrer-Policy", "no-referrer");
        h.set("X-Content-Type-Options", "nosniff");
        for (String cors : List.of("Access-Control-Allow-Origin", "Access-Control-Allow-Methods", "Access-Control-Allow-Headers",
                "Access-Control-Expose-Headers", "Access-Control-Max-Age", "Access-Control-Allow-Credentials", "Set-Cookie"))
            h.remove(cors);
    }

    /** Audit the refusal (reason + token hash prefix) and return the one uniform 404 for the caller to throw. */
    private static ApiException denied(HttpExchange ex, Map<String, Object> rec, String token, String reason) {
        Object grantId = rec == null ? null : rec.get("grantId");
        emit(LinkEventTypes.LINK_EMBED_DENIED, "embed:" + (grantId == null ? "unknown" : grantId), "link.embed.denied", "link.embed.denied", ex,
                b -> b.attr("reason", reason).attr("presentedHash", EmbedGrants.hashPrefix(token)).attr("grantId", grantId)
                        .attr("investigationId", rec == null ? null : rec.get("investigationId")));
        return notFound();
    }

    /** As {@link #denied}, after revoking the grant (its conditions no longer hold). */
    private static ApiException revokedAndDenied(HttpExchange ex, InvestigationStore store, Map<String, Object> rec, String token,
                                                 String reason, Instant now) {
        try {
            if (EmbedGrants.revoke(store, String.valueOf(rec.get("grantId")), "system", reason, now) != null)
                emit(LinkEventTypes.LINK_EMBED_REVOKED, "system", "link.embed.revoked", "link.embed.revoked", ex,
                        b -> b.attr("investigationId", rec.get("investigationId")).attr("grantId", rec.get("grantId"))
                                .attr("reason", reason).attr("revokedBy", "system"));
        } catch (IOException | RuntimeException ignored) {
            // the refusal stands whether or not the flag could be written
        }
        return denied(ex, rec, token, reason);
    }

    private static void emit(String type, String actor, String action, String message, HttpExchange ex,
                             java.util.function.UnaryOperator<Event.Builder> attrs) {
        try {
            Event.Builder b = Event.builder(type).source("inv").message(message).actor(actor).actorType("embed")
                    .action(action).actionCategory("analysis").ip(ApiContext.ip(ex));
            EventSink.current().emit(attrs.apply(b));
        } catch (RuntimeException ignored) {
            // best effort - auditing never fails the view
        }
    }
}
