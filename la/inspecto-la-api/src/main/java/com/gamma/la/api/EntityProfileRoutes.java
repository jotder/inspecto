package com.gamma.la.api;

import com.gamma.access.WriteGates;
import com.gamma.audit.Event;
import com.gamma.audit.EventSink;
import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.entitystore.MaskTokens;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The Entities pages' reads (plan {@code la-entity-profile-plan.md} §4.5, P1-P3): {@code GET /inv/entity-types} (the types in
 * force, their bindings and a cached headline - never a query), {@code GET /inv/entity-types/{id}/profile?sections=} (the
 * Entity Type profile, aggregates only) and {@code GET /inv/entities/{type}/{key}/profile} (one Entity, by raw value or
 * exploration alias), and {@code POST /inv/entities/{type}/resolve} (a raw value, in the body, to the key the Entity page takes).
 * The computation is {@link EntityProfiles}.
 *
 * <p>Gated like the exploration reads ({@code POST /inv/projection}): the geoLink feature, a write root (503), R3
 * {@code canView} on every Dataset read (a binding on a Dataset the caller cannot view is not there for them) and no capability
 * beyond an authenticated subject. An unknown Entity Type is 404. Audited like them, best effort and after the read: one
 * {@code LINK_ENTITY_*} event per call, never carrying an Entity key (raw or alias).
 *
 * <p>Masking: a key leaves as the exploration alias ({@code masked:<16 hex>}, the Space's one key) whenever the type is
 * masked under the Space's {@code maskingMode}; an alias handed in is resolved through the same book the exploration reads
 * mint into ({@link ExplorationMasking}), so the node dialog's id opens the right Entity page.
 */
public final class EntityProfileRoutes implements RouteModule {

    @Override
    public Set<String> featureIds() {
        return Set.of("geoLink");
    }

    @Override
    public void register(ApiContext api) {
        api.get("/inv/entity-types", (e, m) -> list(api, e));
        api.get("/inv/entity-types/([^/]+)/profile", (e, m) -> typeProfile(api, e, m.group(1)));
        api.get("/inv/entities/([^/]+)/([^/]+)/profile", (e, m) -> entityProfile(api, e, m.group(1), m.group(2)));
        api.post("/inv/entities/([^/]+)/resolve", (e, m) -> resolve(api, e, m.group(1), api.body(e)));
    }

    private static Object list(ApiContext api, HttpExchange ex) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "entity types");
        List<EntityTypes.EntityType> types = LinkAnalysisSettings.forRoot(writeRoot).effectiveEntityTypes();
        EntityTypeBindings.Resolved resolved = EntityTypeBindings.resolve(ex, writeRoot, types);
        List<Map<String, Object>> items = new ArrayList<>();
        int bindings = 0;
        for (EntityTypes.EntityType t : types) {
            Map<String, Object> m = EntityProfiles.typeMap(t);
            List<Map<String, Object>> bs = new ArrayList<>();
            for (EntityTypeBindings.Binding b : resolved.of(t.id())) bs.add(b.toMap());
            bindings += bs.size();
            m.put("bindings", bs);
            m.put("headline", EntityProfiles.headline(writeRoot, t, resolved));
            items.add(m);
        }
        int n = bindings;
        audit(ex, LinkEventTypes.LINK_ENTITY_TYPES_LISTED, "link.entity_types.listed",
                "link.entity_types.listed - " + items.size() + " types, " + n + " bindings",
                b -> b.attr("types", items.size()).attr("bindings", n));
        return Map.of("items", items);
    }

    private static Object typeProfile(ApiContext api, HttpExchange ex, String id) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "entity type profile");
        List<String> sections = sections(ApiContext.query(ex, "sections"));
        EntityProfiles.Frame f = frame(api, ex, writeRoot, id);
        Map<String, Object> out = EntityProfiles.typeProfile(f, sections);
        audit(ex, LinkEventTypes.LINK_ENTITY_TYPE_PROFILED, "link.entity_type.profiled",
                "link.entity_type.profiled " + id + " - " + String.join(",", sections) + (Boolean.TRUE.equals(out.get("cached")) ? " (cached)" : ""),
                b -> b.target("entity_type", id).attr("entityType", id).attr("sections", String.join(",", sections))
                        .attr("datasets", f.datasets().size()).attr("cached", out.get("cached")).attr("truncated", out.get("truncated")));
        return out;
    }

    /**
     * {@code GET /inv/entities/{type}/{key}/profile}. For a type masked under the Space's mode the key must be the alias: a raw
     * value is refused 422 BEFORE anything is read, pointing at {@code POST /inv/entities/{type}/resolve}, so a raw identifier
     * is never looked up through a URL (operator 2026-10-15: never in a URL or a log). The refusal never repeats the key.
     */
    private static Object entityProfile(ApiContext api, HttpExchange ex, String type, String urlKey) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "entity profile");
        EntityTypes.EntityType t = type(writeRoot, type);
        String mode = LinkAnalysisSettings.forRoot(writeRoot).effectiveMaskingMode();
        boolean masked = EntityProfiles.maskedUnder(mode, t);
        if (masked && (urlKey == null || !urlKey.startsWith(MaskTokens.TOKEN_PREFIX)))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "Entity Type '" + type + "' is masked: its Entity profile "
                    + "takes the alias (masked:<16 hex>), never a raw value in a URL - POST /inv/entities/" + type
                    + "/resolve with {\"key\": ...} first");
        Resolved r = resolveKey(writeRoot, t, mode, urlKey);
        EntityProfiles.Frame f = frame(api, ex, writeRoot, type);
        Map<String, Object> out = EntityProfiles.entityProfile(f, r.key(), r.shown());
        boolean found = out.get("where") instanceof List<?> w && !w.isEmpty();
        audit(ex, LinkEventTypes.LINK_ENTITY_PROFILED, "link.entity.profiled",
                "link.entity.profiled " + type + (found ? "" : " (not seen)"),
                b -> b.target("entity_type", type).attr("entityType", type).attr("found", found)
                        .attr("datasets", f.datasets().size()).attr("assessed", out.get("assessed")));
        return out;
    }

    /**
     * {@code POST /inv/entities/{type}/resolve} - body {@code {key}}, a raw value or an exploration alias - answers
     * {@code {type, key, masked}}: the alias of the normalised key for a type masked under the Space's mode (the key the Entity
     * page's URL takes), else the normalised value. The raw value travels in the body only; nothing is read from any Dataset.
     */
    private static Object resolve(ApiContext api, HttpExchange ex, String type, Map<String, Object> body) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "entity key resolve");
        EntityTypes.EntityType t = type(writeRoot, type);
        String mode = LinkAnalysisSettings.forRoot(writeRoot).effectiveMaskingMode();
        Object k = body.get("key");
        if (!(k instanceof String key) || key.isBlank())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include a non-empty string 'key'");
        Resolved r = resolveKey(writeRoot, t, mode, key);
        audit(ex, LinkEventTypes.LINK_ENTITY_KEY_RESOLVED, "link.entity.key_resolved", "link.entity.key_resolved " + type,
                b -> b.target("entity_type", type).attr("entityType", type).attr("masked", r.masked())
                        .attr("from", key.startsWith(MaskTokens.TOKEN_PREFIX) ? "alias" : "value"));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("type", type);
        out.put("key", r.shown());
        out.put("masked", r.masked());
        return out;
    }

    /** A key resolved: the normalised typed key the profile reads by, and the key the caller is shown. */
    private record Resolved(String key, String shown, boolean masked) {}

    /** Raw value or alias to the normalised key; 422 for an alias this server never minted or a key that folds to nothing. */
    private static Resolved resolveKey(Path writeRoot, EntityTypes.EntityType t, String mode, String given) {
        if (given == null || given.isBlank())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the Entity key is empty");
        boolean alias = given.startsWith(MaskTokens.TOKEN_PREFIX);
        String raw = alias ? ExplorationMasking.forced(writeRoot, mode, "entity key").in(given) : given;
        String key = EntityTypes.normalise(t.normaliser(), raw);
        if (key.isEmpty())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the key normalises to nothing under '" + t.normaliser()
                    + "' - it is not an Entity of type '" + t.id() + "'");
        boolean masked = EntityProfiles.maskedUnder(mode, t);
        String shown = !masked ? key : alias ? given : ExplorationMasking.alias(writeRoot, key);
        return new Resolved(key, shown, masked);
    }

    private static EntityTypes.EntityType type(Path writeRoot, String id) {
        return LinkAnalysisSettings.forRoot(writeRoot).effectiveEntityTypes().stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no entity type '" + id + "'"));
    }

    /** The type's frame, 404 for a type not in force. */
    private static EntityProfiles.Frame frame(ApiContext api, HttpExchange ex, Path writeRoot, String id) {
        List<EntityTypes.EntityType> types = LinkAnalysisSettings.forRoot(writeRoot).effectiveEntityTypes();
        EntityTypes.EntityType type = types.stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no entity type '" + id + "'"));
        return new EntityProfiles.Frame(api, writeRoot, type, EntityTypeBindings.resolve(ex, writeRoot, types));
    }

    /** {@code sections=a,b} - every section when absent; an unknown name is a 422 naming it. */
    static List<String> sections(String raw) {
        if (raw == null || raw.isBlank()) return EntityProfiles.SECTIONS;
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            if (!EntityProfiles.SECTIONS.contains(t))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unknown section '" + t + "' - one of " + EntityProfiles.SECTIONS);
            if (!out.contains(t)) out.add(t);
        }
        return out.isEmpty() ? EntityProfiles.SECTIONS : out;
    }

    /** Best effort, after the read: the audit must never fail the analyst's read. */
    private static void audit(HttpExchange ex, String type, String action, String message, UnaryOperator<Event.Builder> attrs) {
        try {
            Event.Builder b = Event.builder(type).source("inv").message(message)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action(action).actionCategory("analysis");
            EventSink.current().emit(attrs.apply(b));
        } catch (RuntimeException ignore) {
            // best effort
        }
    }
}
