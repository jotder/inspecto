package com.gamma.actionrequests;

import com.gamma.control.testkit.RouteModuleContract;
import com.gamma.spi.http.RouteModule;

/** {@link ActionRequestRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P7): every mutating route is capability-gated. */
class ActionRequestRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new ActionRequestRoutes();
    }
}
