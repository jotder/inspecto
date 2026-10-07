package com.gamma.recon;

import com.gamma.control.RouteModule;
import com.gamma.control.testkit.RouteModuleContract;

/** {@link ReconRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-1 P5a): no host, no processor boot. */
class ReconRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new ReconRoutes();
    }

    /** Ungated mutating routes, each already declared with the same reason in CapabilityManifest.EXEMPTIONS. */
    @Override
    protected java.util.Map<String, String> exemptMutatingRoutes() {
        return java.util.Map.of(
                "POST /recon/columns", "read-shaped: lists comparable columns for a draft",
                "POST /recon/breaks", "read-shaped: computes breaks for a draft; persists nothing",
                "POST /recon/rows", "read-shaped: lists the raw rows behind one key; persists nothing",
                "POST /recon/run", "stateless-compute: triggers nothing, persists nothing");
    }
}
