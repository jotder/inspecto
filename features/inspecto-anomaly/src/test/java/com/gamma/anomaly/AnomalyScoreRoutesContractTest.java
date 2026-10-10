package com.gamma.anomaly;

import com.gamma.control.testkit.RouteModuleContract;
import com.gamma.spi.http.RouteModule;

/** {@link AnomalyScoreRoutes} against the platform test kit's RouteModule TCK: no host, no processor boot. */
class AnomalyScoreRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new AnomalyScoreRoutes();
    }
}
