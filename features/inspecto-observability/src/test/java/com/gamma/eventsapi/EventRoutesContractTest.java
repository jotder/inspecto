package com.gamma.eventsapi;

import com.gamma.spi.http.RouteModule;
import com.gamma.control.testkit.RouteModuleContract;

/** {@link EventRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-1 P5a): no host, no processor boot. */
class EventRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new EventRoutes();
    }
}
