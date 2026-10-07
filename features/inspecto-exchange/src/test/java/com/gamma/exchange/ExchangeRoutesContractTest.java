package com.gamma.exchange;

import com.gamma.spi.http.RouteModule;
import com.gamma.control.testkit.RouteModuleContract;

/**
 * {@link ExchangeRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor
 * boot. Possible only since the host-wide installs moved to {@link ExchangeBootHook}; every mutating Exchange route
 * is capability-gated, so nothing is exempt.
 */
class ExchangeRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new ExchangeRoutes();
    }
}
