package com.gamma.la.api;

import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;

import java.util.Set;

/**
 * The Entity profile pages' two reads - {@code GET /inv/entity-types/{id}/profile} and
 * {@code GET /inv/entities/{type}/{key}/profile} - Standard and up (D-LAE4, operator 2026-10-25). Their own route module so
 * the edition feature {@code entityPages} gates exactly these two: Basic answers them 503 while the Entity Type list,
 * resolve and suggest on {@link EntityProfileRoutes} stay open (the Start box, Add data and "Open in Link Analysis" read
 * them). The handlers, gates, masking and audit are {@link EntityProfileRoutes}'.
 */
public final class EntityPageRoutes implements RouteModule {

    @Override
    public Set<String> featureIds() {
        // geoLink: the Space's Link Analysis module switch. entityPages: the EDITION feature - never gate the pages through geoLink,
        // which every Link Analysis module shares.
        return Set.of("geoLink", "entityPages");
    }

    @Override
    public void register(ApiContext api) {
        api.get("/inv/entity-types/([^/]+)/profile", (e, m) -> EntityProfileRoutes.typeProfile(api, e, m.group(1)));
        api.get("/inv/entities/([^/]+)/([^/]+)/profile",
                (e, m) -> EntityProfileRoutes.entityProfile(api, e, m.group(1), m.group(2)));
    }
}
