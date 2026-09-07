package com.gamma.metricsapi;

import com.gamma.control.ApiContext;
import com.gamma.control.RouteModule;
import com.gamma.metrics.MetricRegistry;

/**
 * The Prometheus scrape endpoint, {@code GET /metrics} (EDITIONS {@code CP-13}, "not for Personal";
 * EDG-01 cell 5, 2026-09-07).
 *
 * <p>⚠ <b>Only the EXPOSITION moved, and it could not be otherwise.</b> {@link MetricRegistry} is written to
 * from fourteen files outside its own package — {@code CollectorProcessor}, {@code JobService},
 * {@code PipelineScheduler}, {@code EventLog} and more — so the instrumentation is load-bearing core
 * plumbing that stays exactly where it is. What Personal loses is the HTTP surface, not the counters. This
 * is the one cell of EDG-01 that is a partial by nature rather than by choice, and the plan says so.
 *
 * <p>🔴 <b>Why that partial is still the point.</b> {@code /metrics} is in {@code ControlApi.PUBLIC_PATHS},
 * i.e. deliberately unauthenticated (scrapers carry no token), and Personal ships no authenticator at all
 * and binds every interface by default. So on a Personal install this route served the deployment's full
 * operational telemetry to any host that could reach the port — while the matrix cell said the feature was
 * not in the edition. Closing that is the security justification EDG-01 was ranked P1 on.
 *
 * <p>⛔ {@code /metrics/acquisition} is NOT here: it belongs to {@code AcquisitionRoutes} and is
 * Data-Acquisition telemetry, a core product capability (EDITIONS {@code SP-ACQ-*}), not CP-13.
 */
public final class MetricsRoutes implements RouteModule {

    /** Prometheus text exposition, version 0.0.4 — the content type a scraper expects. */
    private static final String EXPOSITION = "text/plain; version=0.0.4; charset=utf-8";

    @Override
    public void register(ApiContext api) {
        api.get("/metrics", (e, m) -> ApiContext.respondText(e, MetricRegistry.global().scrape(), EXPOSITION));
    }
}
