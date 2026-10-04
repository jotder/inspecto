package com.gamma.la.core;

import java.nio.file.Path;

/**
 * The one place a caller obtains the {@link InvestigationStore} of a Space. Today that is always the filesystem
 * implementation under the Space's write root; the backend selection ({@code investigations.backend=fs|db}, design D-IS8)
 * lands here, so no route ever names an implementation class.
 */
public final class InvestigationStores {

    private InvestigationStores() {}

    /** The Investigation store of the Space whose write root is {@code writeRoot}. Cheap: it holds no state of its own. */
    public static InvestigationStore of(Path writeRoot) {
        return new FsInvestigationStore(writeRoot);
    }
}
