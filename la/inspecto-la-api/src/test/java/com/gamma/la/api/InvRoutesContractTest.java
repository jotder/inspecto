package com.gamma.la.api;

import com.gamma.control.RouteModule;
import com.gamma.control.testkit.RouteModuleContract;

/** {@link InvRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-1 P5a): no host, no processor boot. */
class InvRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new InvRoutes();
    }

    /** Ungated mutating routes, each already declared with the same reason in CapabilityManifest.EXEMPTIONS. */
    @Override
    protected java.util.Map<String, String> exemptMutatingRoutes() {
        return java.util.Map.of(
                "POST /inv/projection", "read-shaped: computes a projection from the body",
                "POST /inv/projection/neighbors", "read-shaped: computes neighbours from the body",
                "POST /inv/projection/multi", "read-shaped: multi-dataset projection; persists nothing",
                "POST /inv/schema/overlap-profile", "read-shaped: profiles column cardinality/overlap; persists nothing",
                "POST /inv/traversal/recursive-paths", "read-shaped: walks paths over a Dataset; persists nothing");
    }
}
