package com.gamma.la.api;

import com.gamma.access.WriteGates;
import com.gamma.audit.Event;
import com.gamma.audit.EventSink;
import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.entitystore.MaskTokens;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code GET /inv/entities/{type}/{key}/summary?dataset=} (LA-ENTITY-SUMMARY-1): one entity's summary over one Dataset's links -
 * {@link EntitySummary}. Basic and up (plan Q-1): the geoLink feature only; the full Entity profile pages stay Standard (D-LAE4).
 *
 * <p>Gate order: write root 503 → no {@code dataset} 422 → unknown Entity Type 404 → a raw key for a type masked under the
 * Space's mode 422 (the URL takes the alias, as the Entity profile's does: a raw identifier never travels in a URL) → an alias
 * this server never minted 422 → Dataset 404 (R3) → a Dataset that maps no From and To 422. Counterparts leave as exploration
 * aliases when the Space masks; a read past the Space's statement timeout answers {@code assessed:false} with why. Audited as
 * {@code LINK_ENTITY_SUMMARIZED}, never with the key.
 */
public final class EntitySummaryRoutes implements RouteModule {

    @Override
    public Set<String> featureIds() {
        return Set.of("geoLink");
    }

    @Override
    public void register(ApiContext api) {
        api.get("/inv/entities/([^/]+)/([^/]+)/summary", (e, m) -> summary(api, e, m.group(1), m.group(2)));
    }

    private static Object summary(ApiContext api, HttpExchange ex, String type, String urlKey) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "entity summary");
        String datasetId = ApiContext.query(ex, "dataset");
        if (datasetId == null || datasetId.isBlank())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'dataset' is required");
        EntityTypes.EntityType t = EntityProfileRoutes.type(writeRoot, type);
        String mode = LinkAnalysisSettings.forRoot(writeRoot).effectiveMaskingMode();
        if (EntityProfiles.maskedUnder(mode, t) && (urlKey == null || !urlKey.startsWith(MaskTokens.TOKEN_PREFIX)))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "Entity Type '" + type + "' is masked: its summary takes "
                    + "the alias (masked:<16 hex>), never a raw value in a URL - POST /inv/entities/" + type + "/resolve first");
        EntityProfileRoutes.Resolved key = EntityProfileRoutes.resolveKey(writeRoot, t, mode, urlKey);
        PresetRoutes.Target target = PresetRoutes.target(api, ex, writeRoot, datasetId.trim());
        if (target.roles().from() == null || target.roles().to() == null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + target.datasetId() + "' does not say which columns "
                    + "link one entity to another (a Mapping's From and To)");
        ValueMeasures.Fences fences = ValueMeasures.Fences.of(writeRoot);
        // a masked queried type masks every id the read returns, whatever the columns are classified as - as the Entity profile
        // does (EntityProfiles.related): its counterparts are the same people as its own key
        ExplorationMasking mask = key.masked()
                ? ExplorationMasking.forced(writeRoot, mode, "the Entity Type '" + t.id() + "' is masked")
                : ExplorationMasking.of(writeRoot, target.datasetId(),
                        Map.of("sourceCol", target.roles().from(), "targetCol", target.roles().to()));

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("type", t.id());
        entity.put("key", key.shown());
        entity.put("masked", key.masked());
        out.put("entity", entity);
        out.put("dataset", target.datasetId());
        try {
            Map<String, Object> s = EntitySummary.summary(target, t, key.key(), unitOf(writeRoot, target), fences.timeoutSeconds());
            for (String list : List.of("counterpartsByLinks", "counterpartsByAmount"))
                if (s.get(list) instanceof List<?> l) {
                    List<Map<String, Object>> shown = new ArrayList<>();
                    for (Object o : l) {
                        @SuppressWarnings("unchecked") Map<String, Object> c = new LinkedHashMap<>((Map<String, Object>) o);
                        c.put("entity", mask.out(c.get("entity")));
                        shown.add(c);
                    }
                    s.put(list, shown);
                }
            out.put("assessed", true);
            out.put("reason", null);
            out.putAll(s);
        } catch (Exception e) {
            out.put("assessed", false);
            out.put("reason", EntityProfiles.isTimeout(String.valueOf(e.getMessage())) || e instanceof java.sql.SQLTimeoutException
                    ? "reading this entity's links took longer than this Space allows (" + fences.timeoutSeconds() + " s)"
                    : EntityProfiles.reason(e));
            out.put("retryable", true);
        }
        out.put("masking", mask.describe());
        boolean found = out.get("links") instanceof Long n && n > 0;
        try {
            EventSink.current().emit(Event.builder(LinkEventTypes.LINK_ENTITY_SUMMARIZED).source("inv")
                    .message("link.entity.summarized " + type + (found ? "" : " (not seen)"))
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("link.entity.summarized").actionCategory("analysis").target("entity_type", type)
                    .attr("entityType", type).attr("dataset", target.datasetId()).attr("found", found)
                    .attr("assessed", out.get("assessed")));
        } catch (RuntimeException ignored) {
            // best effort (LA-04) - the read already succeeded
        }
        return out;
    }

    /** The primary Measure's unit as the Dataset's column states it, or null. */
    private static String unitOf(Path writeRoot, PresetRoutes.Target t) {
        if (t.roles().amount() == null) return null;
        Map<String, Object> d = DatasetProviders.require().dataset(writeRoot, t.datasetId()).orElse(Map.of());
        if (d.get("columns") instanceof List<?> cols)
            for (Object o : cols)
                if (o instanceof Map<?, ?> c && c.get("name") instanceof String n && n.equalsIgnoreCase(t.roles().amount())
                        && c.get("unit") instanceof String u && !u.isBlank()) return u.trim();
        return null;
    }
}
