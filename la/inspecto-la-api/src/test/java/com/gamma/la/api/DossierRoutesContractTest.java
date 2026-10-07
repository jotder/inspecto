package com.gamma.la.api;

import com.gamma.control.RouteModule;
import com.gamma.control.testkit.RouteModuleContract;

/** {@link DossierRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. */
class DossierRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new DossierRoutes();
    }

    /** Ungated mutating routes, each already declared with the same reason in CapabilityManifest.EXEMPTIONS. */
    @Override
    protected java.util.Map<String, String> exemptMutatingRoutes() {
        return java.util.Map.of(
                "POST /inv/investigations/([^/]+)/dossier/verify", "read-shaped: checks a Dossier manifest against the store (LA-12); persists nothing");
    }
}
