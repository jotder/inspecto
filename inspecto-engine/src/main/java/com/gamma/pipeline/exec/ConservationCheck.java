package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;
import com.gamma.pipeline.PipelineEdge;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineRel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>T22 — the conservation invariant over data-plane provenance (§11.4).</b> Given a flow's per-(node,
 * relationship) record counts (collected by a {@link PipelineExecutor.ProvenanceCollector}), find the
 * <em>non-amplifying</em> nodes where {@code recordsIn != recordsOut} — i.e. records entered that did not
 * leave (silent <b>data loss</b>) or were unexpectedly multiplied (<b>amplification</b>).
 *
 * <p>It only evaluates nodes that conserve records by construction: the simple shapers (filter/validate/dedup
 * partition their input into kept + diverted; map/select/derive project 1:1) and a {@code route} in
 * <em>case</em> mode (each row goes to exactly one branch or is dropped). It deliberately skips the genuine
 * amplifiers — {@code transform.split} (UNNEST) and a {@code route} in <em>clone</em> mode — and {@code merge}
 * (multi-input). For a conserving node, {@code recordsIn} (the sum over its inbound edges of what the upstream
 * emitted on that relationship) must equal {@code recordsOut} (the sum of everything the node itself emitted).
 *
 * <p>Pure and side-effect-free so it is unit-testable directly; {@link com.gamma.job.PipelineJobRunner} feeds it the run's
 * counts and promotes each {@link Imbalance} to an event/alert.
 */
@PublicApi(since = "4.3.0")
public final class ConservationCheck {

    /** A node whose records were not conserved. {@code kind} is {@code "LOSS"} (in &gt; out) or {@code "AMPLIFICATION"}. */
    public record Imbalance(String node, long recordsIn, long recordsOut, String kind) {}

    /**
     * Phase 4 §2.4 — one recorded {@code (node, rel)} count at edge grain: the count a
     * {@link PipelineExecutor.ProvenanceCollector} recorded on that relationship, and whether it is a
     * <b>reject stream</b> (§2.6 — {@code dropped}/{@code invalid}/{@code duplicate}/{@code unmatched}: the
     * user never wires these, only tunes where they rest) rather than the main trunk or a named
     * {@code route:*} branch, which are ordinary content-routing, not diversion.
     */
    public record RelCount(String node, String rel, long records, boolean diverted) {}

    /** §2.6's reject-stream vocabulary: the diverted side of a record operator, never a user-wired edge. */
    private static final Set<String> REJECT_RELS = Set.of(
            PipelineRel.DROPPED, PipelineRel.INVALID, PipelineRel.DUPLICATE, PipelineRel.UNMATCHED);

    private static final Set<String> CONSERVING_EXACT = Set.of(
            "transform.map", "transform.filter", "transform.select", "transform.derive", "transform.validate");

    private ConservationCheck() {}

    /**
     * @param g      the pipeline that ran
     * @param counts per-(node, relationship) record counts, keyed {@code "<nodeId>|<rel>"} (as the provenance
     *               rows record them)
     * @return one {@link Imbalance} per conserving node whose in/out counts disagree (empty when all balance)
     */
    public static List<Imbalance> imbalances(PipelineGraph g, Map<String, Long> counts) {
        List<Imbalance> out = new ArrayList<>();
        Map<String, PipelineNode> byId = g.byId();
        for (PipelineNode n : g.nodes()) {
            if (!conserves(n)) continue;

            long in = 0;
            boolean sawInput = false;
            for (PipelineEdge e : g.edgesTo(n.id())) {
                Long c = counts.get(e.from() + "|" + e.rel());
                if (c != null) { in += c; sawInput = true; }
            }
            if (!sawInput) continue;     // node did not execute this run (no upstream counts)

            long outSum = 0;
            boolean sawOutput = false;
            String prefix = n.id() + "|";
            for (Map.Entry<String, Long> entry : counts.entrySet()) {
                if (entry.getKey().startsWith(prefix)) { outSum += entry.getValue(); sawOutput = true; }
            }
            if (!sawOutput) continue;    // node produced no relations (e.g. fully disabled downstream)

            if (in != outSum) out.add(new Imbalance(n.id(), in, outSum, in > outSum ? "LOSS" : "AMPLIFICATION"));
        }
        return out;
    }

    /**
     * Every recorded {@code (node, rel)} count at edge grain, tagged with whether it is a reject stream
     * (§2.6). {@code recordsIn}/{@code recordsOut} stay {@link #imbalances}'s per-node aggregation —
     * this is the per-relationship breakdown underneath it, so a caller (the Pipeline Document, a
     * per-file drill-down) can show <em>which</em> edge carried the diverted rows, not just that the
     * node's totals disagreed.
     *
     * @param counts per-(node, relationship) record counts, keyed {@code "<nodeId>|<rel>"}, as recorded
     *               by {@link PipelineExecutor.ProvenanceCollector}
     */
    public static List<RelCount> relCounts(Map<String, Long> counts) {
        List<RelCount> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            int i = e.getKey().indexOf('|');
            if (i < 0) continue;
            String node = e.getKey().substring(0, i);
            String rel = e.getKey().substring(i + 1);
            out.add(new RelCount(node, rel, e.getValue(), REJECT_RELS.contains(rel)));
        }
        return out;
    }

    /** Whether {@code n} conserves records (so an in≠out is a real imbalance, not expected amplification). */
    private static boolean conserves(PipelineNode n) {
        String t = n.type();
        if (CONSERVING_EXACT.contains(t) || t.startsWith("transform.dedup")) return true;
        if ("transform.route".equals(t)) {                  // case routes conserve; clone routes amplify (skip)
            Object mode = n.cfg("mode");
            return mode != null && "case".equalsIgnoreCase(mode.toString());
        }
        return false;
    }
}
