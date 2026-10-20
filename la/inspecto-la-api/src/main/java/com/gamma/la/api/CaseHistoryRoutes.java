package com.gamma.la.api;

import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;

import java.util.Set;

/**
 * {@code POST /inv/case-history} - the Incidents and Cases that reference one Entity ({@link CaseHistory}).
 *
 * <p>Its own {@link RouteModule} because an edition gate is per module ({@code featureIds()}), and this route is the one
 * explorer-shaped read that belongs to the Standard-and-up half: it reads Case management, which Basic does not ship.
 * Until 2026-10-20 it sat in {@link InvRoutes} under {@code geoLink} alone and answered 503 on Basic only because there was
 * no Case module to read ("Case management is not installed"). {@code LA-EDITIONS-1} placement decision D-LAE1 (operator,
 * 2026-10-20) made that explicit: it names the {@code investigations} edition feature, so Basic answers
 * {@code 503 CAPABILITY_UNAVAILABLE} "available in Standard and above" at dispatch, and {@code /bootstrap} reports
 * {@code features.investigations: false} so the SPA does not offer the Case history section.
 */
public final class CaseHistoryRoutes implements RouteModule {

    @Override
    public Set<String> featureIds() {
        return Set.of("geoLink", "investigations");
    }

    @Override
    public void register(ApiContext api) {
        api.post("/inv/case-history", (e, m) -> CaseHistory.handle(api, e, api.body(e)));
    }
}
