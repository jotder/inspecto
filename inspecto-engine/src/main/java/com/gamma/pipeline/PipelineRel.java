package com.gamma.pipeline;

import com.gamma.api.PublicApi;

/**
 * Edge relationship vocabulary for the {@link PipelineGraph}. An edge's {@code rel} is a plain
 * string so the set stays open (a {@code transform.route} emits operator-defined branch
 * names), but the well-known control + split relationships are named here so the lift, the
 * validator and the visualiser agree on spelling.
 *
 * <p><b>An edge carries a TOKEN, not rows</b> (pipeline spec §11, decision D2, 2026-08-31). What
 * travels between two nodes is control information naming where the data rests — a Consignment id and
 * its registered outputs — exactly what {@code ProcessorContext} offers a third-party Step: no row
 * stream, {@code read()} resolves by reference. ⚠ The constant below is still spelled {@code "data"}
 * and stays so: renaming it breaks two committed contracts (node-attributes, step-types) and belongs to
 * the major-bump window with the Step SPI. Only the MEANING is corrected here — do not read
 * {@code DATA} as "records flow along this edge", because nothing in the runtime does that.
 *
 * <p>The default is {@link #DATA} — the downstream token. <b>Control</b> relationships route a
 * Consignment on an outcome (failure / unmatched / gap / on_commit); <b>split</b> relationships are the
 * diverted side of a record operator (dropped / invalid / duplicate). Operator-defined content routes
 * use the {@link #ROUTE_PREFIX} ({@code route:emea}).
 *
 * <p>🔴 <b>Six of the ten never appear as a LIFTED edge</b>, and they are not one group.
 * {@code PipelineLift} builds only {@link #DATA}, {@link #UNMATCHED} (parse → quarantine),
 * {@link #GAP} (acquisition → gap) and {@code route:*}. {@link #DROPPED} / {@link #INVALID} /
 * {@link #DUPLICATE} are real at RUNTIME — {@code RowShaper} returns them as named relations and
 * {@code ConservationCheck} balances against them — they simply are not edges a lift draws.
 * {@link #SUCCESS} / {@link #FAILURE} / {@link #ON_COMMIT} are constructed nowhere: {@code ON_COMMIT}
 * is only ever READ (the executor and validator skip it as a cross-pipeline trigger), and the other two
 * are declared vocabulary with no producer at all. D2 moves that outcome set to Signals.
 *
 * <p>See {@code docs/superpower/pipeline-spec.md} §11 (the token model) and
 * {@code docs/okf/backend/pipeline-graph/pipeline-graph-design.md} §3.2 (edges) and §15 (the inventory that
 * fixed the split-relationship set).
 */
@PublicApi(since = "4.0.0")
public final class PipelineRel {

    private PipelineRel() {}

    /**
     * The normal downstream token (the default when an edge omits {@code rel}). ⚠ Despite the spelling,
     * this does NOT mean records flow along the edge — see the token note on the class.
     */
    public static final String DATA = "data";

    // ── control relationships (route a batch on an outcome) ──────────────────────
    /** Terminal batch success. */
    public static final String SUCCESS = "success";
    /** Terminal batch failure (→ quarantine / dead-letter). */
    public static final String FAILURE = "failure";
    /** Parser could not match a schema/column-count (→ quarantine or a fallback parser). */
    public static final String UNMATCHED = "unmatched";
    /** A sequence gap was detected (→ {@code gap}/{@code alert} node). */
    public static final String GAP = "gap";
    /** A batch committed (→ {@code enrichment} / a downstream flow trigger). Cross-flow only. */
    public static final String ON_COMMIT = "on_commit";

    // ── split relationships (the diverted side of a record operator) ─────────────
    /** Records dropped by {@code transform.filter}. */
    public static final String DROPPED = "dropped";
    /** Records failing {@code transform.validate}. */
    public static final String INVALID = "invalid";
    /** Records dropped by {@code transform.dedup.*}. */
    public static final String DUPLICATE = "duplicate";

    /** Prefix for operator-defined content-routing branches, e.g. {@code route:emea}. */
    public static final String ROUTE_PREFIX = "route:";

    /** Build a named content-route relationship: {@code route("emea")} → {@code "route:emea"}. */
    public static String route(String key) {
        return ROUTE_PREFIX + key;
    }

    /** Whether {@code rel} is a named content-route ({@code route:*}). */
    public static boolean isRoute(String rel) {
        return rel != null && rel.startsWith(ROUTE_PREFIX) && rel.length() > ROUTE_PREFIX.length();
    }

    /** The branch key of a {@code route:*} relationship, or {@code null} if {@code rel} is not a route. */
    public static String routeKey(String rel) {
        return isRoute(rel) ? rel.substring(ROUTE_PREFIX.length()) : null;
    }
}
