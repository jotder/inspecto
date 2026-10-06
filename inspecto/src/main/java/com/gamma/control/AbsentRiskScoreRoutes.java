package com.gamma.control;

/**
 * The core's answer for Risk Scores when the optional {@code inspecto-scoring} module is <b>absent</b>
 * (MODULE-REORG-1 P7, 2026-10-07: the Scoring half of the "Scoring and Lists" add-on of plan §8a left the processor for
 * a module of its own, bundled from the Professional edition up).
 *
 * <p>Same contract as {@link AbsentReconRoutes}, and the same two orderings: registered LAST, after
 * {@code ServiceLoader} discovery, through {@link ApiContext#stub} so the paths answer <b>503 with an
 * explanation</b> rather than 404 — while staying invisible to {@link ApiContext#hasRoute}, which is what
 * {@code /bootstrap}'s {@code features.scoring} is derived from.
 *
 * <p>⚠ The pairs mirror {@code RiskScoreRoutes}' surface. {@code ScoringAbsentSurfaceParityTest}, in the module,
 * asserts both directions of this list against the live registrations, and
 * {@code NoScoringShipsInThePersonalBuildTest} proves each 503s without the module. The OpenAPI skeleton generator
 * reads this table, so a route missing here also vanishes from {@code docs/api/openapi-v1.json}.
 */
final class AbsentRiskScoreRoutes implements RouteModule {

    static final String MESSAGE = "Risk Scores are not installed in this bundle - they are provided by the "
            + "optional inspecto-scoring module (Professional edition and above).";

    /** Package-private so the module's own parity test can compare it against the real registrations. */
    static final String[][] SURFACE = {
            {"GET",  "/risk-scores/([^/]+)/([^/]+)"},
            {"POST", "/risk-scores/preview"},
    };

    @Override
    public void register(ApiContext api) {
        for (String[] r : SURFACE) {
            if (api.hasRoute(r[0], r[1])) continue;   // the real module is here — nothing to stub
            api.stub(r[0], r[1], (e, m) -> { throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, MESSAGE); });
        }
    }
}
