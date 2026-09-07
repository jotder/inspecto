package com.gamma.control;

/**
 * The core's answer for the Operational Event Viewer feed when the optional {@code inspecto-events} module
 * is <b>absent</b> (EDITIONS {@code CP-13} second half, "not for Personal"; EDG-01 cell 6, 2026-09-08).
 *
 * <p>Same contract as {@link AbsentExchangeRoutes} and {@link AbsentMetricsRoutes}, and the same two
 * orderings: registered LAST, after {@code ServiceLoader} discovery, through {@link ApiContext#stub} so the
 * paths answer <b>503 with an explanation</b> rather than 404 — while staying invisible to
 * {@link ApiContext#hasRoute}, which is what {@code /bootstrap}'s {@code features.events} is derived from.
 *
 * <p>⚠ <b>Order matters within this class too.</b> {@code GET /events/([^/]+)} is a catch-all that would
 * swallow {@code /events/search}, {@code /events/export} and {@code /events/views} if it were stubbed
 * first, because route matching is FIRST-MATCH. It is listed last here for exactly the reason
 * {@code EventRoutes} registers it last — the stub surface is not just a set of paths, it is an ordered
 * one, and reordering this array is a behaviour change.
 *
 * <p>⛔ <b>Event RECORDING is not gated and must never be.</b> {@code EventStore}/{@code EventLog} stay in
 * {@code inspecto-event}, so a Personal install still writes its audit trail — a compliance obligation
 * (AUDIT-CSV-1 / compliance G10), not an edition feature. This class removes the ability to READ the feed
 * back over HTTP, nothing more. {@code AuditTrailTest} and the re-seated audit tests still prove recording
 * works on the default build, reading the store directly rather than through this surface.
 *
 * <p>⚠ Unlike cell 5's {@code /metrics}, this 503 is reachable by a browser: the Events screen is a real
 * product surface. The UI is expected to keep people away from it rather than let them hit this — the nav
 * entry is dropped and the Ops lens home falls back — so a 503 arriving here means a UI gate was missed,
 * and the message says which module supplies the feed so that is diagnosable from the response alone.
 */
final class AbsentEventsRoutes implements RouteModule {

    static final String MESSAGE = "The operational events feed is not installed in this bundle - it is "
            + "provided by the optional inspecto-events module (Standard edition and above). Events are "
            + "still RECORDED, including the audit trail; only reading the feed back over HTTP is "
            + "edition-gated.";

    /**
     * Mirrors {@code EventRoutes}' surface, in its registration order. ⚠ The {@code /events/([^/]+)}
     * catch-all stays LAST — see the class note on first-match ordering.
     */
    private static final String[][] SURFACE = {
            {"GET",  "/events"},
            {"GET",  "/events/search"},
            {"GET",  "/events/export"},
            {"GET",  "/events/views"},
            {"POST", "/events/views"},
            {"POST", "/events/views/([^/]+)/delete"},
            {"GET",  "/events/([^/]+)"},
    };

    @Override
    public void register(ApiContext api) {
        for (String[] r : SURFACE) {
            if (api.hasRoute(r[0], r[1])) continue;   // the real module is here — nothing to stub
            api.stub(r[0], r[1], (e, m) -> { throw new ApiException(503, MESSAGE); });
        }
    }
}
