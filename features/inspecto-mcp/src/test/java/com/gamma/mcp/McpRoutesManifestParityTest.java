package com.gamma.mcp;

import com.gamma.control.testkit.ModuleRoutesParity;
import org.junit.jupiter.api.Test;

/**
 * The module's {@code module.toon} {@code provides.routes} must list EXACTLY the routes {@link McpRoutes} registers -
 * both directions. The host stubs the manifest's list with 503 when this module is absent (Basic), so a route added
 * here and not in the manifest would 404 there and be missing from {@code docs/api/openapi-v1.json}.
 */
class McpRoutesManifestParityTest {

    @Test
    void theManifestSurfaceMatchesWhatThisModuleRegisters() throws Exception {
        ModuleRoutesParity.assertParity(McpRoutes.class, "inspecto-mcp");
    }
}
