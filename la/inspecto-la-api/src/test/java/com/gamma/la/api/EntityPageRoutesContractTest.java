package com.gamma.la.api;

import com.gamma.control.testkit.RouteModuleContract;
import com.gamma.spi.http.RouteModule;

/** {@link EntityPageRoutes} against the platform test kit's RouteModule TCK: no host, no processor boot. */
class EntityPageRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new EntityPageRoutes();
    }
}
