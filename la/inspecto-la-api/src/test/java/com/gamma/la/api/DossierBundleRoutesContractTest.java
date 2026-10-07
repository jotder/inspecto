package com.gamma.la.api;

import com.gamma.control.RouteModule;
import com.gamma.control.testkit.RouteModuleContract;

/** {@link DossierBundleRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. */
class DossierBundleRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new DossierBundleRoutes();
    }

    /** Ungated mutating routes, each already declared with the same reason in CapabilityManifest.EXEMPTIONS. */
    @Override
    protected java.util.Map<String, String> exemptMutatingRoutes() {
        return java.util.Map.of(
                "POST /inv/investigations/([^/]+)/dossier/bundle/verify", "read-shaped: checks an exported Dossier bundle's seal, references and custody against the store (D-6); persists nothing");
    }
}
