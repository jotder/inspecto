package com.gamma.la.api;

import com.gamma.control.RouteModule;
import com.gamma.control.testkit.RouteModuleContract;

/** {@link GraphRunRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. */
class GraphRunRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new GraphRunRoutes();
    }

    /** Ungated mutating routes, each already declared with the same reason in CapabilityManifest.EXEMPTIONS. */
    @Override
    protected java.util.Map<String, String> exemptMutatingRoutes() {
        return java.util.Map.of(
                "POST /inv/graph/runs/([^/]+)/cancel", "self-service: stops a graph run only for its STARTER or an administrator (404 to anyone else, 409 once finished); frees compute and returns no data");
    }
}
