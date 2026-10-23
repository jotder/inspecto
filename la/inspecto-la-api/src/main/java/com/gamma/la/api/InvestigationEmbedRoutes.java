package com.gamma.la.api;

import com.gamma.access.RowScope;
import com.gamma.audit.Event;
import com.gamma.audit.EventSink;
import com.gamma.entitystore.LaLimit;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.la.core.DatasetProvider;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.InvestigationMembers;
import com.gamma.la.core.InvestigationStore;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.gamma.util.ColumnClassification;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minting, listing and revoking the read-only EMBED links of one Investigation (LA-EMBED-VIEW-1; operator decisions 2026-10-12
 * D1-D12). The link itself is served by {@link PublicInvestigationRoutes}.
 *
 * <ul>
 *   <li>{@code POST /inv/investigations/{id}/embeds} - body {@code {ttl_hours?, label?}}; gated {@code canShareInvestigation}; the
 *       caller must also be a LEAD of the Investigation (D11: the lead roles intersected with the verb). Answers the token ONCE.</li>
 *   <li>{@code GET /inv/investigations/{id}/embeds} - the grants, read-shaped for the Investigation's own readers: never a token or a hash.</li>
 *   <li>{@code POST /inv/investigations/{id}/embeds/{grantId}/revoke} - gated {@code canShareInvestigation}; immediate.</li>
 * </ul>
 *
 * <p>Gate order on a mint: capability (route) → kill switch {@code -Dla.embed.enabled} 503 → the Investigation's lead gate
 * ({@link InvestigationRoutes#open}: write root 503 · 404 · a non-lead member 403 · R3 / PDP 404) → body 422 (a frame allowlist is refused:
 * links are link-only, D2; {@code ttl_hours} above the Space's cap is 422, never clamped, D4) → the Space's refused classifications 422 →
 * the ABAC action {@code embed} 403 → mint. A link follows the Space's {@code maskingMode} (D3).
 */
public final class InvestigationEmbedRoutes implements RouteModule {

    /** Gated by the geoLink feature (404 MODULE_DISABLED where a Space switches it off) and the investigations edition feature (503 CAPABILITY_UNAVAILABLE on Basic, LA-EDITIONS-1). */
    @Override
    public java.util.Set<String> featureIds() {
        return java.util.Set.of("geoLink", "investigations");
    }


    @Override
    public void register(ApiContext api) {
        // ⚠ String LITERALS on purpose - CapabilityManifestTest's scanner matches only a literal argument.
        api.get("/inv/investigations/([^/]+)/embeds", (e, m) -> list(api, e, m.group(1)));
        api.post("/inv/investigations/([^/]+)/embeds", ApiContext.withCapability("canShareInvestigation",
                (e, m) -> mint(api, e, m.group(1), api.body(e))));
        api.post("/inv/investigations/([^/]+)/embeds/([^/]+)/revoke", ApiContext.withCapability("canShareInvestigation",
                (e, m) -> revoke(api, e, m.group(1), m.group(2))));
    }

    private static Object list(ApiContext api, HttpExchange ex, String id) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.openForRead(api, ex, id);
        Instant now = Instant.now();
        EmbedGrants.purgeExpired(inv.store(), id, now);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("investigationId", id);
        out.put("enabled", EmbedGrants.enabled());
        out.put("grants", EmbedGrants.summaries(inv.store(), id, now));
        out.put("max", SpaceLimits.get(inv.writeRoot(), LaLimit.INPUT_EMBED_ACTIVE_GRANTS));
        return out;
    }

    private static Object mint(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        if (!EmbedGrants.enabled())
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE,
                    "embed links are disabled; set -D" + EmbedGrants.ENABLED_PROPERTY + "=true to enable them");
        InvestigationRoutes.Inv inv = InvestigationRoutes.open(api, ex, id);   // lead only; R3 + PDP read; 404 for a non-member
        for (String k : body.keySet())
            if (!List.of("ttl_hours", "label", "scope", "masking_mode").contains(k))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, k.equals("frame_ancestors")
                        ? "embed links are link-only: they cannot be framed, so there is no frame_ancestors"
                        : "body has no field '" + k + "' (ttl_hours, label, scope, masking_mode)");
        LinkAnalysisSettings.Embed cfg = LinkAnalysisSettings.forRoot(inv.writeRoot()).effectiveEmbed();
        long ttl = ttlHours(body.get("ttl_hours"), cfg);
        String label = label(body.get("label"), SpaceLimits.get(inv.writeRoot(), LaLimit.INPUT_REFERENCE_LABEL_LENGTH));
        String scope = scope(body.get("scope"));
        String spaceMode = LinkAnalysisSettings.forRoot(inv.writeRoot()).effectiveMaskingMode();
        String linkMode = linkMasking(body.get("masking_mode"), spaceMode);

        String classification = classification(inv);
        List<String> refused = cfg.refuseClassificationsInForce();
        if (!refused.isEmpty() && classification != null && (refused.contains(classification) || DatasetProvider.UNKNOWN_LINEAGE.equals(classification)))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "this Investigation reads data classified "
                    + (DatasetProvider.UNKNOWN_LINEAGE.equals(classification) ? "with untraceable lineage" : classification)
                    + ", which this Space does not allow to be shared by link (embed.refuseClassifications)");

        Map<String, InvestigationMembers.Role> roles = InvestigationMemberStore.roles(inv.store(), id, inv.header().get("owner"));
        Map<String, Object> resource = new LinkedHashMap<>(InvestigationRoutes.resource(inv, roles));
        resource.put("classification", classification);
        if (!RowScope.allowed(ex, "embed", "investigation", resource))   // Advanced PDP, action embed: a policy can forbid sharing
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "denied by access policy");

        Instant now = Instant.now();
        String actor = ApiContext.actor(ex);
        String mode = spaceMode;
        EmbedGrants.Minted minted = EmbedGrants.mint(inv.store(), id, actor, now, ttl, label, mode, scope, linkMode,
                SpaceLimits.get(inv.writeRoot(), LaLimit.INPUT_EMBED_ACTIVE_GRANTS));
        Map<String, Object> rec = minted.record();
        emit(ex, LinkEventTypes.LINK_EMBED_MINTED, "link.embed.minted", "link.embed.minted - " + id + " " + rec.get("grantId"),
                b -> b.attr("investigationId", id).attr("grantId", rec.get("grantId")).attr("issuedBy", actor)
                        .attr("expiresAt", rec.get("expiresAt")).attr("ttlHours", ttl).attr("maskingMode", linkMode).attr("scope", scope));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("grantId", rec.get("grantId"));
        out.put("investigationId", id);
        out.put("token", minted.token());
        out.put("url", EmbedGrants.PREFIX + minted.token());
        out.put("expiresAt", rec.get("expiresAt"));
        out.put("label", label);
        out.put("maskingMode", linkMode);
        out.put("scope", scope);
        out.put("note", "the token is shown once and is not stored; anyone holding the link sees this Investigation, masked as the Space masks it, "
                + "until it expires or is revoked - and can copy what they see");
        return out;
    }

    private static Object revoke(ApiContext api, HttpExchange ex, String id, String grantId) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.open(api, ex, id);   // lead only
        Instant now = Instant.now();
        Map<String, Object> rec = inv.store().embedGrants(id).stream().map(EmbedGrants::parse)
                .filter(r -> grantId.equals(r.get("grantId"))).findFirst()
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no embed link '" + grantId + "' on investigation '" + id + "'"));
        boolean wasLive = rec.get("revokedAt") == null;
        Map<String, Object> revoked = EmbedGrants.revoke(inv.store(), grantId, ApiContext.actor(ex), "revoked-by-lead", now);
        if (wasLive)
            emit(ex, LinkEventTypes.LINK_EMBED_REVOKED, "link.embed.revoked", "link.embed.revoked - " + id + " " + grantId,
                    b -> b.attr("investigationId", id).attr("grantId", grantId).attr("reason", "revoked-by-lead")
                            .attr("revokedBy", ApiContext.actor(ex)));
        return EmbedGrants.summary(revoked, now);
    }

    /** {@code ttl_hours}: absent = the Space's default; a whole number from 1 up to the Space's cap, else 422 (never clamped). */
    private static long ttlHours(Object raw, LinkAnalysisSettings.Embed cfg) {
        if (raw == null) return cfg.defaultTtlHoursInForce();
        long v;
        if (raw instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) v = n.longValue();
        else throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'ttl_hours' must be a whole number of hours, got '" + raw + "'");
        if (!cfg.allows(v))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'ttl_hours' must be 1.." + cfg.maxTtlHoursInForce()
                    + " (this Space's maximum), got " + v);
        return v;
    }

    /** {@code scope}: absent = {@code full}; else one of {@link EmbedGrants#SCOPES}, 422 otherwise. */
    private static String scope(Object raw) {
        if (raw == null) return EmbedGrants.SCOPE_FULL;
        if (raw instanceof String s && EmbedGrants.SCOPES.contains(s)) return s;
        throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'scope' must be one of " + EmbedGrants.SCOPES + ", got '" + raw + "'");
    }

    /** {@code masking_mode}: absent = the Space's; else none|typed|all and never looser than the Space's (422), so a link can only be stricter. */
    private static String linkMasking(Object raw, String spaceMode) {
        if (raw == null) return spaceMode;
        if (!(raw instanceof String s) || !List.of("none", "typed", "all").contains(s))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'masking_mode' must be none, typed or all, got '" + raw + "'");
        if (!EntityMasking.stricter(spaceMode, s).equals(s) && !s.equals(spaceMode))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'masking_mode' " + s + " is looser than this Space's masking ("
                    + spaceMode + "); a link can be stricter, never looser");
        return s;
    }

    private static String label(Object raw, int maxLabel) {
        if (raw == null) return null;
        if (!(raw instanceof String s))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'label' must be a string");
        String v = s.strip();
        if (v.isEmpty()) return null;
        if (v.length() > maxLabel)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'label' is at most " + maxLabel + " characters");
        for (int i = 0; i < v.length(); i++)
            if (Character.isISOControl(v.charAt(i)))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'label' must not contain control characters");
        return v;
    }

    /**
     * The strictest classification (strictest-wins, {@link ColumnClassification}) of the Investigation's bound source and target
     * columns - the Dataset registry's {@code columns[].classification} and the pipeline schema's lineage - or null when none is
     * classified. {@link DatasetProvider#UNKNOWN_LINEAGE} when the lineage cannot be traced (fail closed).
     */
    @SuppressWarnings("unchecked")
    static String classification(InvestigationRoutes.Inv inv) {
        DatasetProvider provider = DatasetProviders.require();
        Map<String, Object> ds = provider.dataset(inv.writeRoot(), inv.dataset()).orElse(Map.of());
        Map<String, String> lineage = provider.schemaClassification(inv.writeRoot(), ds,
                cls -> ColumnClassification.SENSITIVE.contains(ColumnClassification.normalise(cls)));
        if (lineage.containsKey(DatasetProvider.UNKNOWN_LINEAGE)) return DatasetProvider.UNKNOWN_LINEAGE;
        List<String> classes = new ArrayList<>();
        for (String bound : List.of("sourceCol", "targetCol")) {
            Object name = inv.header().get(bound);
            if (name == null) continue;
            String lower = String.valueOf(name).trim().toLowerCase(Locale.ROOT);
            if (lineage.get(lower) != null) classes.add(lineage.get(lower));
            if (ds.get("columns") instanceof List<?> cols)
                for (Object o : cols)
                    if (o instanceof Map<?, ?> c && lower.equalsIgnoreCase(String.valueOf(c.get("name"))) && c.get("classification") != null)
                        classes.add(String.valueOf(c.get("classification")));
        }
        return ColumnClassification.strictest(classes, ColumnClassification.SENSITIVE::contains);
    }

    /** Best-effort audit (LA-04 pattern), structured attributes only. */
    private static void emit(HttpExchange ex, String type, String action, String message,
                             java.util.function.UnaryOperator<Event.Builder> attrs) {
        try {
            Event.Builder b = Event.builder(type).source("inv").message(message)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action(action).actionCategory("analysis");
            EventSink.current().emit(attrs.apply(b));
        } catch (RuntimeException ignored) {
            // best effort - the grant change is already recorded
        }
    }
}
