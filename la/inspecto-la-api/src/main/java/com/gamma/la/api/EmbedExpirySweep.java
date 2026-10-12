package com.gamma.la.api;

import com.gamma.la.core.InvestigationStore;
import com.gamma.la.core.InvestigationStores;

import java.nio.file.Path;
import java.time.Instant;

/**
 * The scheduled sweep of expired embed grants (LA-EMBED-EXPIRY-SWEEP-1): drops the token hash of every expired grant of every
 * Investigation of one Space, so an expired hash never lingers until someone mints or lists. Only the hash goes; the row and the audit
 * trail stay ({@link EmbedGrants#purgeExpired}). The host decides WHEN and for WHICH Spaces (the per-Space {@code geoLink} gate); this
 * class is the work, and it never throws.
 */
public final class EmbedExpirySweep {
    private EmbedExpirySweep() {}

    /** Sweep the Space whose write root is {@code writeRoot}; returns how many Investigations were swept (0 when the kill switch is off). */
    public static int sweep(Path writeRoot, Instant now) {
        if (writeRoot == null || !EmbedGrants.enabled()) return 0;
        int swept = 0;
        try {
            InvestigationStore store = InvestigationStores.of(writeRoot);
            for (String id : store.ids()) {
                try {
                    EmbedGrants.purgeExpired(store, id, now);
                    swept++;
                } catch (java.io.IOException | RuntimeException oneInvestigation) {
                    // one Investigation's failure never stops the rest
                }
            }
        } catch (java.io.IOException | RuntimeException unavailable) {
            // a store that is down (503 elsewhere) is retried at the next tick
        }
        return swept;
    }
}
