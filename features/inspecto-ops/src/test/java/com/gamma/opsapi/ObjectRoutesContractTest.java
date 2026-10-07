package com.gamma.opsapi;

import com.gamma.spi.http.RouteModule;
import com.gamma.control.testkit.RouteModuleContract;

/** {@link ObjectRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-1 P5a): no host, no processor boot. */
class ObjectRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new ObjectRoutes();
    }

    /** Ungated mutating routes, each already declared with the same reason in CapabilityManifest.EXEMPTIONS. */
    @Override
    protected java.util.Map<String, String> exemptMutatingRoutes() {
        return java.util.Map.of(
                "POST /objects/([^/]+)/comments", "collaboration: adds a comment; the disposition is untouched",
                "POST /objects/([^/]+)/attachments", "collaboration: attaches evidence",
                "POST /objects/([^/]+)/links", "collaboration: correlates two objects; neither state changes",
                "DELETE /objects/([^/]+)/links", "collaboration: removes a correlation link",
                "POST /objects/([^/]+)/rca", "collaboration: seeds an RCA skeleton as comments",
                "PUT /objects/([^/]+)/findings", "collaboration: operator 2026-09-25, Findings values open to anyone who can see the Case");
    }
}
