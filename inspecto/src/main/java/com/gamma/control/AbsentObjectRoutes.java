package com.gamma.control;

/**
 * The core's answer for every operational-object surface when the optional {@code inspecto-ops} module is
 * <b>absent</b> (EDITIONS {@code CP-11}, "not for Personal"; EDG-01 cell 7, 2026-09-08).
 *
 * <p>Same contract as {@link AbsentEventsRoutes} and {@link AbsentExchangeRoutes}: registered LAST, after
 * {@code ServiceLoader} discovery, through {@link ApiContext#stub} so each path answers <b>503 with an
 * explanation</b> rather than 404 — while staying invisible to {@link ApiContext#hasRoute}, which
 * {@code /bootstrap}'s {@code features.ops} is derived from.
 *
 * <p>⚠ <b>Order mirrors the module's registration order, and that is load-bearing.</b> Matching is
 * FIRST-MATCH, so {@code GET /objects/([^/]+)} and {@code DELETE /tags/([^/]+)} are catch-alls that would
 * swallow their literal siblings ({@code /objects/analytics}, {@code /tags/rules}) if stubbed earlier.
 * Reordering this array is a behaviour change, not a tidy-up.
 *
 * <p>⛔ <b>Three surfaces here are easy to mistake for core vocabulary and are not:</b>
 * {@code /rca/templates}, {@code /workflows/{type}} and {@code /findings/{type}} all live on the module's
 * {@code ObjectRoutes} because they describe operational objects. Personal therefore loses them too —
 * deliberately, since a findings spec or a workflow with no objects to apply to describes nothing.
 * ⚠ The {@code RcaTemplate} and {@code FindingsSpec} TYPES stay in core ({@code com.gamma.objects}) and
 * core still loads {@code *_rca.toon}; it is only the HTTP surface that leaves.
 */
final class AbsentObjectRoutes implements RouteModule {

    static final String MESSAGE = "Operational objects are not installed in this bundle - Alerts, "
            + "Incidents, Cases, Tasks and their notes, links, tags and queues are provided by the "
            + "optional inspecto-ops module (Standard edition and above).";

    /** Mirrors the four moved route families, in their registration order. ⚠ Catch-alls stay last. */
    private static final String[][] SURFACE = {
            // ── ObjectRoutes ────────────────────────────────────────────────────────────────────
            {"GET",    "/objects"},
            {"GET",    "/objects/analytics"},
            {"POST",   "/objects"},
            {"POST",   "/objects/([^/]+)/ack"},
            {"POST",   "/objects/([^/]+)/resolve"},
            {"POST",   "/objects/([^/]+)/transition"},
            {"POST",   "/objects/([^/]+)/assign"},
            {"POST",   "/objects/([^/]+)/watch"},
            {"POST",   "/objects/([^/]+)/unwatch"},
            {"GET",    "/objects/([^/]+)/watchers"},
            {"POST",   "/objects/([^/]+)/links"},
            {"GET",    "/objects/([^/]+)/links"},
            {"DELETE", "/objects/([^/]+)/links"},
            {"POST",   "/objects/([^/]+)/merge"},
            {"POST",   "/objects/([^/]+)/split"},
            {"GET",    "/objects/([^/]+)/graph"},
            {"POST",   "/objects/([^/]+)/comments"},
            {"GET",    "/objects/([^/]+)/comments"},
            {"POST",   "/objects/([^/]+)/attachments"},
            {"GET",    "/objects/([^/]+)/attachments"},
            {"POST",   "/objects/([^/]+)/rca"},
            {"GET",    "/objects/([^/]+)"},          // ⚠ catch-all — after every literal /objects/… above
            {"GET",    "/rca/templates"},
            {"GET",    "/workflows/([^/]+)"},
            {"GET",    "/findings/([^/]+)"},
            {"GET",    "/cases/rules"},
            {"POST",   "/cases/rules"},
            {"DELETE", "/cases/rules/([^/]+)"},
            {"POST",   "/cases/rules/([^/]+)/evaluate"},
            // ── NoteRoutes ──────────────────────────────────────────────────────────────────────
            {"GET",    "/notes/([^/]+)/([^/]+)"},
            {"GET",    "/notes/([^/]+)/([^/]+)/comments"},
            {"GET",    "/notes/([^/]+)/([^/]+)/attachments"},
            {"POST",   "/notes/([^/]+)/([^/]+)/comments"},
            {"POST",   "/notes/([^/]+)/([^/]+)/attachments"},
            // ── QueueRoutes ─────────────────────────────────────────────────────────────────────
            {"GET",    "/queues"},
            {"GET",    "/queues/([^/]+)"},
            {"POST",   "/queues"},
            // ── TagRoutes ───────────────────────────────────────────────────────────────────────
            {"GET",    "/tags"},
            {"POST",   "/tags"},
            {"POST",   "/tags/([^/]+)/rename"},
            {"GET",    "/tags/rules"},
            {"POST",   "/tags/rules"},
            {"DELETE", "/tags/rules/([^/]+)"},
            {"POST",   "/tags/rules/([^/]+)/apply"},
            {"GET",    "/tags/([^/]+)/targets"},
            {"GET",    "/tags/assignments/([^/]+)/([^/]+)"},
            {"POST",   "/tags/assignments/([^/]+)/([^/]+)"},
            {"DELETE", "/tags/assignments/([^/]+)/([^/]+)/([^/]+)"},
            {"DELETE", "/tags/([^/]+)"},             // ⚠ catch-all — after /tags/rules/… and /tags/assignments/…
    };

    @Override
    public void register(ApiContext api) {
        for (String[] r : SURFACE) {
            if (api.hasRoute(r[0], r[1])) continue;   // the real module is here — nothing to stub
            api.stub(r[0], r[1], (e, m) -> { throw new ApiException(503, MESSAGE); });
        }
    }
}
