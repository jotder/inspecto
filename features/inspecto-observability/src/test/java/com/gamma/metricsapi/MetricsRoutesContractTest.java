package com.gamma.metricsapi;

import com.gamma.spi.http.RouteModule;
import com.gamma.control.testkit.RouteModuleContract;

/** {@link MetricsRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-1 P5a): no host, no processor boot. */
class MetricsRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new MetricsRoutes();
    }
}
