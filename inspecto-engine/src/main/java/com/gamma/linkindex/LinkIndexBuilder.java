package com.gamma.linkindex;

import java.nio.file.Path;

/**
 * Builds or refreshes the Link Analysis Index for one configured Dataset mapping - the SPI behind the
 * {@code link-index} Platform Service ({@link LinkIndexAccess}).
 *
 * <p>A {@code ServiceLoader} SPI because the Index lives in the OPTIONAL Link Analysis modules, which this engine
 * does not depend on (maven-enforcer and {@code tools/check-module-deps.mjs}); the {@code inspecto-geo-link} bridge
 * implements it. Absent the bridge no implementation is found and the Job fails closed. Same shape as
 * {@link com.gamma.alert.InvestigationMeasureProbe}.
 */
public interface LinkIndexBuilder {

    /**
     * Decide the delegated principal's authority afresh, read the index {@code plan} advice, run the one build the
     * server itself recommends, and say what happened. A refusal is an {@link LinkIndexAccess.Outcome}, not an
     * exception; an exception means nothing could be decided.
     *
     * @param writeRoot the Space's write root (where the Link Analysis index lives)
     * @param dataRoot  the Space's data root (may be null)
     */
    LinkIndexAccess.Outcome build(Path writeRoot, Path dataRoot, LinkIndexAccess.Request request);
}
