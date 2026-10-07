package com.gamma.la.api;

import com.gamma.control.RouteModule;
import com.gamma.control.testkit.RouteModuleContract;

/** {@link ValueMeasureRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. */
class ValueMeasureRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new ValueMeasureRoutes();
    }
}
