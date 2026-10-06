package com.gamma.opsapi;

import com.gamma.control.RouteModule;
import com.gamma.control.testkit.RouteModuleContract;

/** {@link TagRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-1 P5a): no host, no processor boot. */
class TagRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new TagRoutes();
    }

    /** Ungated mutating routes, each already declared with the same reason in CapabilityManifest.EXEMPTIONS. */
    @Override
    protected java.util.Map<String, String> exemptMutatingRoutes() {
        return java.util.Map.of(
                "POST /tags/rules/([^/]+)/apply", "collaboration: operator 2026-09-16, applying a tag rule is a collaboration act",
                "POST /tags/assignments/([^/]+)/([^/]+)", "target-visibility-gated via AnnotationTargets",
                "DELETE /tags/assignments/([^/]+)/([^/]+)/([^/]+)", "target-visibility-gated via AnnotationTargets");
    }
}
