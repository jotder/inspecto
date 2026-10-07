package com.gamma.recon;

import com.gamma.spi.http.ComponentDeleteHook;

import java.nio.file.Path;

/** Deleting a {@code reconciliation} component deletes its run state ({@code <write-root>/recon-state/<id>.json}) too (R2-03). */
public final class ReconComponentDeleteHook implements ComponentDeleteHook {
    @Override public String type() { return "reconciliation"; }

    @Override
    public void afterDelete(Path writeRoot, String id) throws java.io.IOException {
        // Operational run state beside the registry, not config: the component delete that calls this hook has already
        // passed the maker-checker hold in ComponentRoutes. ConfigWriteFunnelTest#WRITERS carries this site with that reason.
        new ReconStateStore(writeRoot).delete(id);
    }
}
