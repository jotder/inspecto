package com.gamma.la.api;

import com.gamma.spi.http.RouteModule;
import com.gamma.control.testkit.RouteModuleContract;

/** {@link InvestigationRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. */
class InvestigationRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new InvestigationRoutes();
    }

    /** Ungated mutating routes, each already declared with the same reason in CapabilityManifest.EXEMPTIONS. */
    @Override
    protected java.util.Map<String, String> exemptMutatingRoutes() {
        return java.util.Map.of(
                "POST /inv/investigations/([^/]+)/replay", "read-shaped: re-evaluates a sealed Investigation log (LA-10); persists nothing",
                "POST /inv/expand/preview", "read-shaped: evaluates an unsaved Investigation's seed / expand steps (LA-UNSAVED-INVESTIGATION-1); persists nothing");
    }
}
