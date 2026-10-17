package com.gamma.la.api;

import com.gamma.spi.http.RouteModule;
import com.gamma.control.testkit.RouteModuleContract;

/** {@link InvestigationInboundRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. */
class InvestigationInboundRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new InvestigationInboundRoutes();
    }

    /** The one ungated mutating route, declared with the same reason in CapabilityManifest.EXEMPTIONS. */
    @Override
    protected java.util.Map<String, String> exemptMutatingRoutes() {
        return java.util.Map.of("POST /inv/deep-links/resolve",
                "read-shaped: validates an Open in Inspecto LA link's entity, window and Investigation; reads no Dataset, persists nothing");
    }
}
