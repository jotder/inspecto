package com.gamma.regreporting;

import com.gamma.control.testkit.RouteModuleContract;
import com.gamma.spi.http.RouteModule;

/** {@link RegulatoryReportRoutes} against the platform test kit's RouteModule TCK: every mutating route is capability-gated. */
class RegulatoryReportRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new RegulatoryReportRoutes();
    }
}
