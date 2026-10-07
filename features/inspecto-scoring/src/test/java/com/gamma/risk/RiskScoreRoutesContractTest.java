package com.gamma.risk;

import com.gamma.control.RouteModule;
import com.gamma.control.testkit.RouteModuleContract;

/** {@link RiskScoreRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-1 P5a): no host, no processor boot. */
class RiskScoreRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new RiskScoreRoutes();
    }
}
