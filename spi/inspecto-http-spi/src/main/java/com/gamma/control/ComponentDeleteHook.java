package com.gamma.control;

import java.nio.file.Path;

/**
 * Contribution point for state an <b>optional</b> module keeps BESIDE a config component rather than inside it.
 * The processor's {@code DELETE /components/{type}/{id}} calls every registered hook for the deleted type after
 * the component is removed, so a re-created component of the same id never inherits the orphaned state (the
 * Reconciliation run state is the first user). Registered in
 * {@code META-INF/services/com.gamma.control.ComponentDeleteHook}.
 *
 * <p>A hook signals refusal the way {@code ReconStateStore} always did: {@link IllegalArgumentException} for an
 * unsafe id (mapped to 400) and {@link SecurityException} for a path-jail violation (mapped to 403).
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public interface ComponentDeleteHook {
    /** The component type this hook cleans up after, e.g. {@code "reconciliation"}. */
    String type();

    /** Remove the module's state for the deleted component {@code id} under the Space's write root. */
    void afterDelete(Path writeRoot, String id) throws java.io.IOException;
}
