package com.gamma.la.api;

import com.gamma.control.testkit.RouteModuleContract;
import com.gamma.spi.http.RouteModule;

/** {@link EntitySummaryRoutes} against the platform test kit's RouteModule TCK: no host, no processor boot. */
class EntitySummaryRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new EntitySummaryRoutes();
    }
}
