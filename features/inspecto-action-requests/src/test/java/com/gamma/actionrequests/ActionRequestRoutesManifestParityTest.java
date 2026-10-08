package com.gamma.actionrequests;

import com.gamma.control.testkit.ModuleRoutesParity;
import org.junit.jupiter.api.Test;

/**
 * The module's {@code module.toon} {@code provides.routes} must list EXACTLY the routes its {@code RouteModule}
 * registers - both directions. The host stubs the manifest's list with 503 when this module is absent, so a route
 * added here and not in the manifest 404s there and is missing from {@code docs/api/openapi-v1.json}.
 */
class ActionRequestRoutesManifestParityTest {

    @Test
    void theManifestSurfaceMatchesWhatThisModuleRegisters() throws Exception {
        ModuleRoutesParity.assertParity(ActionRequestRoutes.class, "inspecto-action-requests");
    }
}
