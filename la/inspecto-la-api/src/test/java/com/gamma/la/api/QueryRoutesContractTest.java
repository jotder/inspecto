package com.gamma.la.api;

import com.gamma.control.testkit.RouteModuleContract;
import com.gamma.spi.http.RouteModule;

/** {@link QueryRoutes} against the platform test kit's RouteModule TCK: no host, no processor boot. */
class QueryRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new QueryRoutes();
    }

    /** Ungated mutating routes, each already declared with the same reason in CapabilityManifest.EXEMPTIONS. */
    @Override
    protected java.util.Map<String, String> exemptMutatingRoutes() {
        return java.util.Map.of(
                "POST /inv/datasets/([^/]+)/query", "read-shaped: runs one query-builder question over a Dataset (LA-QUERY-BUILDER-1); persists nothing");
    }
}
