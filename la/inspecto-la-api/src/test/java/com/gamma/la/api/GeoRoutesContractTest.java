package com.gamma.la.api;

import com.gamma.spi.http.RouteModule;
import com.gamma.control.testkit.RouteModuleContract;

/** {@link GeoRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-1 P5a): no host, no processor boot. */
class GeoRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new GeoRoutes();
    }

    /** Ungated mutating routes, each already declared with the same reason in CapabilityManifest.EXEMPTIONS. */
    @Override
    protected java.util.Map<String, String> exemptMutatingRoutes() {
        return java.util.Map.of(
                "POST /geo/projection", "read-shaped: computes a projection from the body",
                "POST /geo/routes", "read-shaped: computes routes from the body");
    }
}
