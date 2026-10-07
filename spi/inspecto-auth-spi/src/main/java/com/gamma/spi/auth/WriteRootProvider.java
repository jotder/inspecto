package com.gamma.spi.auth;

import java.nio.file.Path;

/**
 * The one thing {@code WriteGates.requireWriteRoot} needs from an {@code ApiContext}: the write root, or {@code null}
 * when the control plane is read-only. Split out in D-1 step 3 so {@code WriteGates} (which the auth classes use for
 * {@code safeName}) no longer names {@code ApiContext}; {@code ApiContext} extends it, so every caller that passes an
 * {@code ApiContext} compiles unchanged.
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public interface WriteRootProvider {
    /** The bound space's write root, or {@code null} when none is configured (read-only). */
    Path writeRoot();
}
