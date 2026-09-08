package com.gamma.opsapi;

import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.ops.ObjectService;
import com.gamma.ops.ObjectServiceAccess;

/**
 * How this module's routes reach the concrete {@link ObjectService} (EDG-01 cell 7, 2026-09-08).
 *
 * <p>Core exposes only the {@code com.gamma.objects.ObjectAccess} seam — nine narrow methods in core
 * types — because {@code com.gamma.ops} is an optional edition module. The {@code /objects}, {@code /notes},
 * {@code /queues} and {@code /tags} routes need far more than that (workflow transitions, case rules,
 * queues, merge/split, RCA application), so they take the seam back down to the implementation this module
 * itself installed.
 *
 * <p>⚠ Deliberately NOT a static holder. Resolving through {@code api.service()} keeps the lookup
 * per-Space — a multi-Space runtime has one engine per Space — and a global would have quietly served one
 * Space's objects to another.
 */
final class OpsEngine {

    private OpsEngine() {}

    /**
     * This request's Space's engine.
     *
     * <p>⚠ Throws 503, not 404, when the seam is absent or is some other implementation: these routes are
     * only ever registered by this module, so reaching one without an engine means a jar was bundled
     * without its {@code ObjectEngineProvider} — a deployment fault worth naming, not a missing resource.
     */
    static ObjectService of(ApiContext api) {
        return api.service().objects()
                .filter(ObjectServiceAccess.class::isInstance)
                .map(access -> ((ObjectServiceAccess) access).service())
                .orElseThrow(() -> new ApiException(503,
                        "Operational objects are not available in this bundle - the inspecto-ops module is "
                                + "installed but registered no ObjectEngineProvider."));
    }
}
