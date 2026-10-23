package com.gamma.la.api;

import com.gamma.access.WriteGates;
import com.gamma.entitystore.EntityFactLog;
import com.gamma.entitystore.EntityListFacts;
import com.gamma.entitystore.EntityRegistry;
import com.gamma.entitystore.LaLimit;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /inv/exclusion-lists} (LA-GROW-EXCLUDE-1 phase 4): the exclusion lists an Investigation can apply, for the
 * graph's Exclusions chips — {@code {lists: [{id, title, entityType, ownerKind, autoApply, visibility, size}]}}, every
 * live (not retired) Entity List of purpose {@code exclusion}, category ({@code ownerKind: admin}) lists first, then by
 * title and id. Never members, so a restricted list shows its size only.
 *
 * <p>Read-gated like the Entity List reads: Space access only, and a 503 without a write root (the fact log lives under
 * it). Feature {@code geoLink}, so every edition serves it, Basic included. A broken fact chain is a 500
 * {@code INTEGRITY_VIOLATION}, as on every Entity List route.
 */
public final class ExclusionListRoutes implements RouteModule {

    @Override
    public java.util.Set<String> featureIds() {
        return java.util.Set.of("geoLink");
    }

    @Override
    public void register(ApiContext api) {
        api.get("/inv/exclusion-lists", (e, m) -> lists(api));
    }

    /**
     * Why an auto-apply list would refuse every Investigation create (LA-GROW-EXCLUDE-RESIDUALS-1 item 3): the two
     * refusals {@code InvestigationRoutes.sealList} makes of a live list, so Settings can warn the administrator first.
     * Null when the list would seal.
     */
    private static String sealProblem(Path root, EntityRegistry.EntityList l, int maxMembers) {
        if (EntityListFacts.type(root, l.entityType()).isEmpty())
            return "its Entity Type '" + l.entityType() + "' is no longer in force";
        if (l.members().size() > maxMembers)
            return "it has " + l.members().size() + " members; a list op seals at most " + maxMembers;
        return null;
    }

    private static Object lists(ApiContext api) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "exclusion lists");
        EntityFactLog.Log log = EntityListFacts.read(new EntityFactLog(root));
        List<EntityRegistry.EntityList> live = EntityRegistry.fold(log.facts(), log.headSeq()).values().stream()
                .filter(l -> "exclusion".equals(l.purpose()) && !l.retired())
                .sorted(Comparator.comparing((EntityRegistry.EntityList l) -> !l.adminOwned())
                        .thenComparing(l -> String.valueOf(l.title()))
                        .thenComparing(EntityRegistry.EntityList::id))
                .toList();
        int maxMembers = SpaceLimits.get(root, LaLimit.INPUT_LIST_MEMBERS);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (EntityRegistry.EntityList l : live) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.id());
            m.put("title", l.title());
            m.put("entityType", l.entityType());
            m.put("ownerKind", l.ownerKind());
            m.put("autoApply", l.autoApply());
            m.put("visibility", l.visibility());
            m.put("size", l.members().size());
            if (l.autoApply()) {
                String problem = sealProblem(root, l, maxMembers);
                if (problem != null) m.put("problem", problem);
            }
            out.add(m);
        }
        return Map.of("lists", out);
    }
}
