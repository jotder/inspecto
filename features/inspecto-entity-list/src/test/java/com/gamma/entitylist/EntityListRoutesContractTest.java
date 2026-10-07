package com.gamma.entitylist;

import com.gamma.spi.http.RouteModule;
import com.gamma.control.testkit.RouteModuleContract;

/** {@link EntityListRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-1 P5a): no host, no processor boot. */
class EntityListRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new EntityListRoutes();
    }

    /** Ungated mutating routes, each already declared with the same reason in CapabilityManifest.EXEMPTIONS. */
    @Override
    protected java.util.Map<String, String> exemptMutatingRoutes() {
        return java.util.Map.of(
                "POST /entity-lists/([^/]+)/match", "read-shaped: matches body values against an Entity List; persists nothing");
    }
}
