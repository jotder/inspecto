package com.gamma.la.api;

import com.gamma.control.RouteModule;
import com.gamma.control.testkit.RouteModuleContract;

/** {@link IndexRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. */
class IndexRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new IndexRoutes();
    }

    /** Ungated mutating routes, each already declared with the same reason in CapabilityManifest.EXEMPTIONS. */
    @Override
    protected java.util.Map<String, String> exemptMutatingRoutes() {
        return java.util.Map.of(
                "POST /inv/index/builds/([^/]+)/cancel", "self-service: stops an index build only for its STARTER or an administrator (IndexBuildService.cancel is the gate: 403 otherwise, 409 once finished); frees compute and disk, returns no data");
    }
}
