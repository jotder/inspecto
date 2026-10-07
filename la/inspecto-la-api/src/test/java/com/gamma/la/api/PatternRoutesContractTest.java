package com.gamma.la.api;

import com.gamma.control.RouteModule;
import com.gamma.control.testkit.RouteModuleContract;

/** {@link PatternRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. */
class PatternRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new PatternRoutes();
    }

    /** Ungated mutating routes, each already declared with the same reason in CapabilityManifest.EXEMPTIONS. */
    @Override
    protected java.util.Map<String, String> exemptMutatingRoutes() {
        return java.util.Map.of(
                "POST /inv/pattern/branching", "read-shaped: matches a branching motif over a Dataset (LA-14b); persists nothing",
                "POST /inv/pattern/temporal", "read-shaped: burst / periodicity over a Dataset's link event times; persists nothing");
    }
}
