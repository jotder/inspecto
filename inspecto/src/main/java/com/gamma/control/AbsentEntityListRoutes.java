package com.gamma.control;

/**
 * The core's answer for Entity Lists when the optional {@code inspecto-entity-list} module is <b>absent</b>
 * (SEP-08 of the Link Analysis separation, 2026-10-01: Entity Lists and the shared fact log left
 * {@code inspecto-geo-link} for a module of their own, bundled in exactly the editions that ship geo-link).
 *
 * <p>Same contract as {@link AbsentGeoLinkRoutes}, and the same two orderings: registered LAST, after
 * {@code ServiceLoader} discovery, through {@link ApiContext#stub} so the paths answer <b>503 with an
 * explanation</b> rather than 404 — while staying invisible to {@link ApiContext#hasRoute}, which is what
 * {@code /bootstrap}'s {@code features.entityList} is derived from.
 *
 * <p>⚠ The six pairs mirror {@code EntityListRoutes}' surface. {@code EntityListAbsentSurfaceParityTest}, in the
 * module, asserts both directions of this list against the live registrations, and
 * {@code NoGeoLinkShipsInThePersonalBuildTest} proves each 503s without the module. A comment asking two lists to
 * be kept in step is not a mechanism (the lesson of {@link AbsentGeoLinkRoutes}); the parity test is.
 */
final class AbsentEntityListRoutes implements RouteModule {

    static final String MESSAGE = "Entity Lists are not installed in this bundle - they are provided by the "
            + "optional inspecto-entity-list module (Professional edition and above).";

    /** Package-private so the module's own parity test can compare it against the real registrations. */
    static final String[][] SURFACE = {
            {"GET",  "/entity-lists"},
            {"GET",  "/entity-lists/([^/]+)"},
            {"POST", "/entity-lists"},
            {"POST", "/entity-lists/([^/]+)/members"},
            {"POST", "/entity-lists/([^/]+)/retire"},
            {"POST", "/entity-lists/([^/]+)/match"},
    };

    @Override
    public void register(ApiContext api) {
        for (String[] r : SURFACE) {
            if (api.hasRoute(r[0], r[1])) continue;   // the real module is here — nothing to stub
            api.stub(r[0], r[1], (e, m) -> { throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, MESSAGE); });
        }
    }
}
