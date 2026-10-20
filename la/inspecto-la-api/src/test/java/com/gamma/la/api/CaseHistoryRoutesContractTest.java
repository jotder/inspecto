package com.gamma.la.api;

import com.gamma.spi.http.RouteModule;
import com.gamma.control.testkit.RouteModuleContract;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link CaseHistoryRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-1 P5a): no host, no processor boot.
 * Also pins the edition placement (D-LAE1): the module names {@code investigations}, so Basic refuses the route by edition.
 */
class CaseHistoryRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new CaseHistoryRoutes();
    }

    /** Ungated mutating route, already declared with the same reason in CapabilityManifest.EXEMPTIONS. */
    @Override
    protected java.util.Map<String, String> exemptMutatingRoutes() {
        return java.util.Map.of("POST /inv/case-history", "read-shaped: reads one Entity's case history; persists nothing");
    }

    @org.junit.jupiter.api.Test
    void caseHistoryIsStandardAndUpThroughTheInvestigationsFeature() {
        assertEquals(java.util.Set.of("geoLink", "investigations"), module().featureIds());
    }
}
