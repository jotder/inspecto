package com.gamma.ops.cases;

import com.gamma.spi.http.RouteModule;
import com.gamma.control.testkit.RouteModuleContract;

/** {@link CaseRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P7 step 2): every mutating Case route is capability-gated, so no exemptions. */
class CaseRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new CaseRoutes();
    }
}
