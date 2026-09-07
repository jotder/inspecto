package com.gamma.control;

/**
 * The core's answer for cross-space exchange when the optional {@code inspecto-exchange} module is
 * <b>absent</b> (EDITIONS {@code SEC-10}, "not for Personal"; EDG-01 cell 4, 2026-09-07).
 *
 * <p>Same contract as {@link AbsentGeoLinkRoutes}, and the same two orderings: registered LAST, after
 * {@code ServiceLoader} discovery, through {@link ApiContext#stub} so the paths answer <b>503 with an
 * explanation</b> rather than 404 — while staying invisible to {@link ApiContext#hasRoute}, which is what
 * {@code /bootstrap}'s {@code features.exchange} is derived from. A stub that counted as a route would make
 * that flag claim the module is installed; that is exactly the bug cell 3b shipped and fixed.
 *
 * <p>⚠ The eleven pairs mirror {@code ExchangeRoutes}' surface and are kept in step from both sides:
 * {@code NoExchangeShipsInThePersonalBuildTest} proves each 503s without the module, and the module's own
 * four HTTP test classes prove they answer with it. A path added to one and not the other shows up as a 404
 * on Personal — in the core test, by design.
 *
 * <p>⛔ The six capability entries for these paths deliberately REMAIN in {@link CapabilityManifest} on every
 * edition: the grantable vocabulary is static so a role or policy file authored on Standard still validates
 * on Personal. Naming {@code canOfferDatasets} there grants nothing, because no route is behind it.
 */
final class AbsentExchangeRoutes implements RouteModule {

    static final String MESSAGE = "Cross-space exchange and sharing are not installed in this bundle - they "
            + "are provided by the optional inspecto-exchange module (Standard edition and above).";

    private static final String[][] SURFACE = {
            {"GET",  "/exchange/offers"},
            {"POST", "/exchange/offers"},
            {"POST", "/exchange/refresh"},
            {"POST", "/exchange/requests"},
            {"POST", "/exchange/grants/([^/]+)/(approve|deny|revoke)"},
            {"POST", "/exchange/grants/([^/]+)/pin"},
            {"POST", "/exchange/grants/([^/]+)/expiry"},
            {"GET",  "/exchange/grants"},
            {"GET",  "/exchange/datasets/([^/]+)/([^/]+)"},
            {"GET",  "/exchange/widgets/([^/]+)/([^/]+)"},
            {"GET",  "/exchange/views/([^/]+)/([^/]+)"},
    };

    @Override
    public void register(ApiContext api) {
        for (String[] r : SURFACE) {
            if (api.hasRoute(r[0], r[1])) continue;   // the real module is here — nothing to stub
            api.stub(r[0], r[1], (e, m) -> { throw new ApiException(503, MESSAGE); });
        }
    }
}
