package com.gamma.control;

/**
 * The core's answer for {@code GET /metrics} when the optional {@code inspecto-metrics} module is
 * <b>absent</b> (EDITIONS {@code CP-13}, "not for Personal"; EDG-01 cell 5, 2026-09-07).
 *
 * <p>Same contract as {@link AbsentGeoLinkRoutes} and {@link AbsentExchangeRoutes}: registered LAST through
 * {@link ApiContext#stub}, only where {@link ApiContext#hasRoute} reports the path unclaimed, so a scraper
 * gets <b>503 with an explanation</b> rather than a bare 404 that reads like a wrong URL.
 *
 * <p>⚠ {@code /metrics} stays in {@link ControlApi}'s {@code PUBLIC_PATHS}, so this 503 is returned without
 * authentication — which is right: a scraper carries no token, and "this bundle does not expose metrics" is
 * not information worth withholding. Nothing about the deployment's telemetry leaks, because the registry is
 * never read here.
 */
final class AbsentMetricsRoutes implements RouteModule {

    static final String MESSAGE = "The Prometheus metrics endpoint is not installed in this bundle - it is "
            + "provided by the optional inspecto-metrics module (Standard edition and above). Instrumentation "
            + "still runs; only the HTTP exposition is edition-gated.";

    @Override
    public void register(ApiContext api) {
        if (api.hasRoute("GET", "/metrics")) return;   // the real module is here — nothing to stub
        api.stub("GET", "/metrics", (e, m) -> { throw new ApiException(503, MESSAGE); });
    }
}
