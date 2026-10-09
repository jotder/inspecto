package com.gamma.screening;

import com.gamma.control.testkit.RouteModuleContract;
import com.gamma.spi.http.RouteModule;

/** {@link ScreeningRoutes} against the platform test kit's RouteModule TCK: no host, no processor boot. */
class ScreeningRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new ScreeningRoutes();
    }

    /** Ungated mutating routes, each already declared with the same reason in CapabilityManifest.EXEMPTIONS. */
    @Override
    protected java.util.Map<String, String> exemptMutatingRoutes() {
        return java.util.Map.of(
                "POST /screening/check", "read-shaped: scores body subjects against Entity Lists; persists nothing");
    }
}
