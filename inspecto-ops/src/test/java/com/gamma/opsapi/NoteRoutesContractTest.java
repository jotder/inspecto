package com.gamma.opsapi;

import com.gamma.control.RouteModule;
import com.gamma.control.testkit.RouteModuleContract;

/** {@link NoteRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-1 P5a): no host, no processor boot. */
class NoteRoutesContractTest extends RouteModuleContract {
    @Override
    protected RouteModule module() {
        return new NoteRoutes();
    }

    /** Ungated mutating routes, each already declared with the same reason in CapabilityManifest.EXEMPTIONS. */
    @Override
    protected java.util.Map<String, String> exemptMutatingRoutes() {
        return java.util.Map.of(
                "POST /notes/([^/]+)/([^/]+)/comments", "collaboration: adds a comment on any note-bearing object",
                "POST /notes/([^/]+)/([^/]+)/attachments", "collaboration: attaches evidence on any note-bearing object");
    }
}
