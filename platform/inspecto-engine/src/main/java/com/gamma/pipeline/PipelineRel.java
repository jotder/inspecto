package com.gamma.pipeline;

import com.gamma.api.PublicApi;

import java.util.Map;

/**
 * Edge relationship vocabulary for the {@link PipelineGraph} — the token model's three outlet kinds (X5-a,
 * 2026-11-17). An edge's {@code rel} is a plain string so the set stays open (a {@code transform.route} emits
 * operator-defined branch names, a contributed Step declares its own reject reasons), but the well-known
 * relationships are named here so the lift, the validator and the visualiser agree on spelling.
 *
 * <p><b>An edge carries a TOKEN, not rows</b> (pipeline spec §11, decision D2, 2026-08-31). What
 * travels between two nodes is control information naming where the data rests — a Consignment id and
 * its registered outputs — exactly what {@code ProcessorContext} offers a third-party Step: no row
 * stream, {@code read()} resolves by reference. Do not read {@link #DATA} as "records flow along this edge",
 * because nothing in the runtime does that.
 *
 * <p><b>Three outlet kinds</b>:
 * <ul>
 *   <li>{@link #DATA} — the main outlet: the token continues (the default when an edge omits {@code rel}).
 *       The token model first wrote it {@code main}; it is spelled {@code data} (X5 decision D4).</li>
 *   <li>{@code reject:<reason>} — rows a Step did not pass on. The built-in reasons are {@link #UNMATCHED},
 *       {@link #DROPPED}, {@link #INVALID} and {@link #DUPLICATE}; a contributed Step declares its own
 *       ({@link #reject(String)}). Until 4.0.0 the four built-ins were spelled bare ({@code dropped}, …):
 *       {@link #canonical(String)} maps those legacy spellings, and every persisted reader goes through it.</li>
 *   <li>{@code route:<key>} — content demux ({@link #route(String)}).</li>
 * </ul>
 * {@link #GAP} is the one remaining control edge (acquisition → its {@code gap} CONTROL node, X5 decision D5).
 * The outcome relations {@code success}, {@code failure} and {@code on_commit} were retired in 4.0.0: nothing
 * produced them, and a Consignment's outcome is a Signal ({@code pipeline.batch.committed} /
 * {@code .failed} / {@code .parked}). {@link PipelineCodec} drops an authored edge that still names one, with a
 * {@link PipelineValidator#RETIRED_RELATION} warning.
 *
 * <p>See {@code docs/okf/product/module/engine/spec-pipeline-model.md} (the token model — the design of record) and
 * {@code docs/okf/product/module/engine/architecture.md} §1 (the token rule and the relation
 * inventory). Provenance only, not maintained:
 * {@code docs/archived-documents/plans-archive/pipeline-spec.md} §11.
 */
@PublicApi(since = "4.0.0")
public final class PipelineRel {

    private PipelineRel() {}

    /**
     * The main outlet: the normal downstream token (the default when an edge omits {@code rel}). ⚠ Despite
     * the spelling, this does NOT mean records flow along the edge — see the token note on the class.
     */
    public static final String DATA = "data";

    /** A sequence gap was detected (acquisition → its {@code gap} CONTROL node). */
    public static final String GAP = "gap";

    /**
     * Prefix of a reject outlet, e.g. {@code reject:dropped} or a contributed Step's {@code reject:bad_score}
     * (S2-3). A Step rejects rows by emitting them to a {@code reject:<reason>} relation it declares in
     * {@link PipelineNodeType#emits()}, never by throwing — and {@code ConservationCheck} tags the relation
     * as diverted and reports its reason.
     */
    public static final String REJECT_PREFIX = "reject:";

    // ── the built-in reject reasons (the constant NAMES predate X5-a; their values are canonical) ──────
    /** Rows a parser could not match to a schema/column count (→ the quarantine sink). */
    public static final String UNMATCHED = REJECT_PREFIX + "unmatched";
    /** Rows {@code transform.filter} did not keep. */
    public static final String DROPPED = REJECT_PREFIX + "dropped";
    /** Rows failing {@code transform.validate}. */
    public static final String INVALID = REJECT_PREFIX + "invalid";
    /** Rows {@code transform.dedup.*} found to be duplicates. */
    public static final String DUPLICATE = REJECT_PREFIX + "duplicate";

    /** Pre-4.0.0 spelling → canonical spelling: the read alias for authored graphs and stored provenance. */
    private static final Map<String, String> LEGACY = Map.of(
            "unmatched", UNMATCHED, "dropped", DROPPED, "invalid", INVALID, "duplicate", DUPLICATE);

    /** Build a named reject relationship: {@code reject("bad_score")} → {@code "reject:bad_score"}. */
    public static String reject(String reason) {
        return REJECT_PREFIX + reason;
    }

    /** Whether {@code rel} is a named reject stream ({@code reject:*}). A legacy bare spelling is not — map it first. */
    public static boolean isReject(String rel) {
        return rel != null && rel.startsWith(REJECT_PREFIX) && rel.length() > REJECT_PREFIX.length();
    }

    /** The reason of a reject outlet ({@code reject:dropped} → {@code dropped}), or {@code null} if {@code rel} is not one. */
    public static String reason(String rel) {
        return isReject(rel) ? rel.substring(REJECT_PREFIX.length()) : null;
    }

    /**
     * The canonical spelling of {@code rel}: a pre-4.0.0 reject spelling ({@code dropped}, {@code invalid},
     * {@code duplicate}, {@code unmatched}) maps to its {@code reject:<reason>} form; anything else — canonical
     * already, a route, a contributed Step's own relation, or {@code null} — passes through unchanged.
     */
    public static String canonical(String rel) {
        return rel == null ? null : LEGACY.getOrDefault(rel, rel);
    }

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
