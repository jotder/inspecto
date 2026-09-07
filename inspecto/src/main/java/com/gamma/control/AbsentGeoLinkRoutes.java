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
 * <p>⚠ The five {@code (method, pattern)} pairs below are the module's public surface, kept in step by
 * {@code NoGeoLinkShipsInThePersonalBuildTest} (they must 503 on the default build) and by the module's own
 * HTTP tests (they must 200 with the module present). A path added to the module and not here 404s on
 * Personal instead of 503ing — the test that catches that is the module's, so keep both lists in sync.
 */
final class AbsentGeoLinkRoutes implements RouteModule {

    static final String MESSAGE = "Geo map and link analysis are not installed in this bundle - they are "
            + "provided by the optional inspecto-geo-link module (Standard edition and above).";

    private static final String[][] SURFACE = {
            {"POST", "/geo/projection"},
            {"POST", "/geo/routes"},
            {"POST", "/inv/projection"},
            {"POST", "/inv/projection/neighbors"},
            {"GET",  "/inv/schema/relationships"},
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
