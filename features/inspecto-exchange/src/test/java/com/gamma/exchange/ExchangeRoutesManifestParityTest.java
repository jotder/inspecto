package com.gamma.exchange;

import com.gamma.control.testkit.ModuleRoutesParity;
import org.junit.jupiter.api.Test;

/**
 * The module's {@code module.toon} {@code provides.routes} must list EXACTLY the routes {@code ExchangeRoutes}
 * registers - both directions (MODULE-REORG-1 P3b); a route added here and not in the manifest 404s where the module is
 * absent instead of answering 503. Driven on the test kit's fake context since the host installs moved to
 * {@code ExchangeBootHook} (MODULE-REORG-P5-TCKS); no {@code ControlApi} boot.
 */
class ExchangeRoutesManifestParityTest {

    @Test
    void theManifestSurfaceMatchesWhatThisModuleRegisters() {
        ModuleRoutesParity.assertParity(ExchangeRoutes.class, "inspecto-exchange");
    }
}
