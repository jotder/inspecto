package com.gamma.anomaly;

import com.gamma.control.testkit.ModuleRoutesParity;
import org.junit.jupiter.api.Test;

/**
 * The module's {@code module.toon} {@code provides.routes} must list EXACTLY the routes its {@code RouteModule}
 * registers — both directions. The host stubs the manifest's list with 503 when this module is absent.
 */
class AnomalyAbsentSurfaceParityTest {

    @Test
    void theManifestSurfaceMatchesWhatThisModuleRegisters() throws Exception {
        ModuleRoutesParity.assertParity(AnomalyScoreRoutes.class, "inspecto-anomaly");
    }
}
