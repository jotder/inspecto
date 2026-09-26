package com.gamma.control;

/**
 * The core's answer for geo map + link analysis when the optional {@code inspecto-geo-link} module is
 * <b>absent</b> (EDITIONS {@code CP-09}, "not for Personal"; EDG-01 cell 3b, 2026-09-07).
 *
 * <p><b>Why a stub at all.</b> A path whose module is not on the classpath would otherwise 404 — and a 404
 * reads as "wrong URL", which sends a client (or an operator with a bookmark) looking for a typo that is not
 * there. EDITIONS §4's contract is that an absent capability <em>explains itself</em>: 503, naming what is
 * missing and which editions carry it. The SPA also hides the entries on {@code /bootstrap}'s
 * {@code features.geoLink}; this stub is the belt for every other client and for deep links.
 *
 * <p><b>Why it registers LAST and consults {@link ApiContext#hasRoute}.</b> Registration is first-match, so
 * this must run after {@code ServiceLoader} discovery, and it must skip any path the real module already
 * claimed — otherwise it would either shadow the module (registered before it) or trip the duplicate guard
 * (registered after it). {@code hasRoute} is the exact-string question that makes the skip precise.
 *
 * <p>⚠ The {@code (method, pattern)} pairs below are the module's public surface, kept in step by
 * {@code NoGeoLinkShipsInThePersonalBuildTest} (they must 503 on the default build) and by the module's own
 * HTTP tests (they must 200 with the module present). A path added to the module and not here 404s on
 * Personal instead of 503ing.
 *
 * <p>🔴 <b>That warning used to be the only safeguard, and it did not hold.</b> Four routes drifted:
 * {@code /inv/schema/overlap-profile} (LA-15) and the three {@code /inv/snapshots*} (LA-03) were added to the
 * module and not here, so on Personal they 404ed instead of 503ing — and, because the OpenAPI generator
 * derives its skeleton from THIS list (the core cannot see the optional module's own classes), they were
 * missing from {@code docs/api/openapi-v1.json} too, with the contract guard reporting green throughout. One
 * omission, two silent failures. {@code GeoLinkAbsentSurfaceParityTest} in the module now asserts both
 * directions of this list against the live registrations, because a comment asking two lists to be kept in
 * sync is not a mechanism.
 */
final class AbsentGeoLinkRoutes implements RouteModule {

    static final String MESSAGE = "Geo map and link analysis are not installed in this bundle - they are "
            + "provided by the optional inspecto-geo-link module (Professional edition and above).";

    /** Package-private so the module's own parity test can compare it against the real registrations. */
    static final String[][] SURFACE = {
            {"POST", "/geo/projection"},
            {"POST", "/geo/routes"},
            {"POST", "/inv/projection"},
            {"POST", "/inv/projection/neighbors"},
            {"POST", "/inv/projection/multi"},
            {"GET",  "/inv/schema/relationships"},
            {"POST", "/inv/schema/overlap-profile"},
            {"POST", "/inv/traversal/recursive-paths"},
            {"POST", "/inv/pattern/branching"},
            {"POST", "/inv/snapshots"},
            {"GET",  "/inv/snapshots"},
            {"POST", "/inv/snapshots/attach"},
            {"POST", "/inv/investigations"},
            {"POST", "/inv/investigations/([^/]+)/ops"},
            {"POST", "/inv/investigations/([^/]+)/undo"},
            {"POST", "/inv/investigations/([^/]+)/reorder"},
            {"POST", "/inv/investigations/([^/]+)/replay"},
            {"GET",  "/inv/investigations/([^/]+)/log"},
            {"POST", "/inv/investigations/([^/]+)/reveal"},
            {"POST", "/inv/investigations/([^/]+)/pending/([^/]+)/approve"},
            {"POST", "/inv/investigations/([^/]+)/pending/([^/]+)/deny"},
            {"GET",  "/inv/investigations/([^/]+)/dossier"},
            {"POST", "/inv/investigations/([^/]+)/dossier/verify"},
            {"GET",  "/inv/investigations/([^/]+)/working-set"},
            {"GET",  "/inv/investigations/([^/]+)/coverage"},
            {"POST", "/inv/investigations/([^/]+)/template"},
            {"GET",  "/inv/investigation-templates/([^/]+)"},
            {"POST", "/inv/investigation-templates/([^/]+)/instantiate"},
            {"GET",  "/inv/investigations/([^/]+)/measures"},
            {"POST", "/inv/investigations/([^/]+)/alert-rules"},
            {"GET",  "/inv/entity-lists"},
            {"GET",  "/inv/entity-lists/([^/]+)"},
            {"POST", "/inv/entity-lists"},
            {"POST", "/inv/entity-lists/([^/]+)/members"},
            {"POST", "/inv/entity-lists/([^/]+)/retire"},
    };

    @Override
    public void register(ApiContext api) {
        for (String[] r : SURFACE) {
            if (api.hasRoute(r[0], r[1])) continue;   // the real module is here — nothing to stub
            // api.stub, not api.post/get: a stub must occupy the route table (so the path 503s instead of
            // 404ing) WITHOUT making hasRoute() true — otherwise /bootstrap's derived feature flag would
            // report the module present because its own stand-in had claimed the pattern.
            api.stub(r[0], r[1], (e, m) -> { throw new ApiException(503, MESSAGE); });
        }
    }
}
