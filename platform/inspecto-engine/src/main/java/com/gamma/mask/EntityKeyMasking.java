package com.gamma.mask;

import java.nio.file.Path;
import java.util.Map;

/**
 * D-P8 mask on read of an entity key (ANOMALY-DETECTION-1 D-AD5, operator 2026-10-10): shared by every per-entity
 * score read so Risk Scores and Anomaly Scores present a key identically — raw when the caller may reveal it, else
 * the Space {@code masked:} token ({@link EvidenceMasker#tokenFor}), plus a {@code keyMasked} flag.
 */
public final class EntityKeyMasking {
    private EntityKeyMasking() {}

    /** Put {@code entityKey} and {@code keyMasked} into {@code out}. */
    public static void put(Map<String, Object> out, Path writeRoot, String entityKey, boolean reveal) {
        out.put("entityKey", reveal ? entityKey : EvidenceMasker.forSpace(writeRoot).tokenFor(entityKey));
        out.put("keyMasked", !reveal);
    }
}
