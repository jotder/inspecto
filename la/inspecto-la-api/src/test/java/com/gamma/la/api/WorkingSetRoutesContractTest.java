package com.gamma.la.api;

import com.gamma.control.RouteModule;
import com.gamma.control.testkit.RouteModuleContract;

/** {@link WorkingSetRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. */
class WorkingSetRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new WorkingSetRoutes();
    }
}
