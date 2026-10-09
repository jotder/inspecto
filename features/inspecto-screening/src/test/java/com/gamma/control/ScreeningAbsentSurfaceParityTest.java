package com.gamma.control;

import com.gamma.control.testkit.ModuleRoutesParity;
import org.junit.jupiter.api.Test;

/**
 * The module's {@code module.toon} {@code provides.routes} must list EXACTLY the routes its {@code RouteModule}
 * registers, both directions: the host stubs the manifest's list with 503 when this module is absent.
 */
class ScreeningAbsentSurfaceParityTest {

    @Test
    void theManifestSurfaceMatchesWhatThisModuleRegisters() throws Exception {
        ModuleRoutesParity.assertParity(Class.forName("com.gamma.screening.ScreeningRoutes"), "inspecto-screening");
    }
}
