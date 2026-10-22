package com.gamma.la.api;

import com.gamma.control.testkit.RouteModuleContract;
import com.gamma.spi.http.RouteModule;

/** {@link EntityProfileRoutes} against the platform test kit's RouteModule TCK: no host, no processor boot. */
class EntityProfileRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new EntityProfileRoutes();
    }

    /** The ungated POST routes, declared with the same reasons in CapabilityManifest.EXEMPTIONS. */
    @Override
    protected java.util.Map<String, String> exemptMutatingRoutes() {
        return java.util.Map.of("POST /inv/entities/([^/]+)/resolve",
                "read-shaped: resolves one Entity key (raw value in the body) to the alias its Entity page takes; reads no Dataset, persists nothing",
                "POST /inv/entities/([^/]+)/suggest",
                "read-shaped: lists the values of an Entity Type that start a graph, by a prefix sent in the body (the Start box's type-ahead); reads the caller's viewable Datasets, persists nothing");
    }
}
