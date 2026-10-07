package com.gamma.opsapi;

import com.gamma.control.testkit.ModuleRoutesParity;
import org.junit.jupiter.api.Test;

/**
 * The module's {@code module.toon} {@code provides.routes} must list EXACTLY the routes its {@code RouteModule}s
 * register - both directions (MODULE-REORG-1 P3b). The host stubs the manifest's list with 503 when this module is
 * absent, so a route added here and not in the manifest 404s there and is missing from {@code docs/api/openapi-v1.json}.
 * Replaces the hand-kept {@code Absent*Routes.SURFACE} parity test.
 */
class OpsRoutesManifestParityTest {

    @Test
    void theManifestSurfaceMatchesWhatThisModuleRegisters() throws Exception {
        ModuleRoutesParity.assertParity(Class.forName("com.gamma.opsapi.ObjectRoutes"), "inspecto-ops");
    }
}
