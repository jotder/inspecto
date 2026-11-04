package com.gamma.la.api;

import com.gamma.control.testkit.RouteModuleContract;
import com.gamma.spi.http.RouteModule;

/** {@link DiscoverRoutes} against the platform test kit's RouteModule TCK: no host, no processor boot. */
class DiscoverRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new DiscoverRoutes();
    }
}
