package com.gamma.la.api;

import com.gamma.spi.http.RouteModule;
import com.gamma.control.testkit.RouteModuleContract;

/** {@link PresetRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. */
class PresetRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new PresetRoutes();
    }

    /** Ungated mutating routes, each already declared with the same reason in CapabilityManifest.EXEMPTIONS. */
    @Override
    protected java.util.Map<String, String> exemptMutatingRoutes() {
        return java.util.Map.of(
                "POST /inv/presets/([^/]+)/run", "read-shaped: runs one preset query over a Dataset (LA-PRESET-QUERIES-1); persists nothing");
    }
}
