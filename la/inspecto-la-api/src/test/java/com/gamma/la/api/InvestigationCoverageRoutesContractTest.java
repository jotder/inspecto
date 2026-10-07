package com.gamma.la.api;

import com.gamma.spi.http.RouteModule;
import com.gamma.control.testkit.RouteModuleContract;

/** {@link InvestigationCoverageRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. */
class InvestigationCoverageRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new InvestigationCoverageRoutes();
    }
}
