package com.gamma.pipeline.exec;

import com.gamma.pipeline.NodeCategory;
import com.gamma.pipeline.PipelineEdge;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNodeTypes;
import com.gamma.pipeline.PipelineRel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>X5 — builds one Consignment's {@link StepReceipt}s from a walk</b> (plan §3.5). A
 * {@link PipelineExecutor.WalkObserver} that collects in memory while the walk runs — never a durable write per
 * Step (the {@code StepProgress} rule) — and forwards every count to the {@link PipelineExecutor.ProvenanceCollector}
 * it wraps, so the provenance rows and the conservation check see exactly what they saw before. The caller
 * flushes {@link #receipts()} once per Consignment, success or failure.
 *
 * <p>Single-threaded: the walk calls it on its own thread.
 */
public final class StepReceiptRecorder implements PipelineExecutor.WalkObserver {

    private final PipelineGraph g;
    private final String consignmentId;
    private final String pipeline;
    private final String lane;
    private final String runId;
    private final boolean simulated;
    private final PipelineExecutor.ProvenanceCollector forward;
    /** {@code "<nodeId>|<rel>"} → rows, as the provenance rows key them. */
    private final Map<String, Long> counts = new LinkedHashMap<>();
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private static final class Entry {
        final String kind;
        final int seq;
        String startedAt;
        long t0;
        long ms;
        StepReceipt.Outcome outcome;
        StepReceipt.Schema schema;
        StepReceipt.Diagnostics diag = StepReceipt.Diagnostics.NONE;
        StepReceipt.Pointer ref;

        Entry(String kind, int seq) {
            this.kind = kind;
            this.seq = seq;
        }
    }

    /**
     * @param g             the graph being walked (for each Step's inbound edges)
     * @param consignmentId the Consignment
     * @param pipeline      the Pipeline id
     * @param lane          one of the {@code StepReceipt.LANE_*} values
     * @param runId         the run (attempt); {@code null} reads as {@code ""}
     * @param simulated     a dry run
     * @param forward       where each count also goes; {@code null} for nowhere
     */
    public StepReceiptRecorder(PipelineGraph g, String consignmentId, String pipeline, String lane, String runId,
                               boolean simulated, PipelineExecutor.ProvenanceCollector forward) {
        this.g = g;
        this.consignmentId = consignmentId;
        this.pipeline = pipeline;
        this.lane = lane;
        this.runId = runId == null ? "" : runId;
        this.simulated = simulated;
        this.forward = forward == null ? PipelineExecutor.ProvenanceCollector.NONE : forward;
    }

    @Override
    public void counts(String nodeId, String rel, long rows) {
        counts.put(nodeId + "|" + rel, rows);
        forward.record(nodeId, rel, rows);
    }

    @Override
    public void stepStarted(String nodeId, String kind, int seq) {
        Entry e = entries.computeIfAbsent(nodeId, k -> new Entry(kind, seq));
        e.startedAt = Instant.now().toString();
        e.t0 = System.nanoTime();
    }

    @Override
    public void schema(String nodeId, int columns, String fp) {
        Entry e = entries.get(nodeId);
        if (e != null) e.schema = new StepReceipt.Schema(columns, fp);
    }

    @Override
    public void stepEnded(String nodeId, StepReceipt.Outcome outcome, StepReceipt.Diagnostics diag) {
        Entry e = entries.get(nodeId);
        if (e == null) return;
        e.outcome = outcome;
        e.ms = (System.nanoTime() - e.t0) / 1_000_000L;
        if (diag != null) e.diag = diag;
    }

    @Override
    public void notReached(String nodeId, String kind, int seq) {
        entries.computeIfAbsent(nodeId, k -> new Entry(kind, seq)).outcome = StepReceipt.Outcome.NOT_REACHED;
    }

    /** Where {@code nodeId}'s data rests durably (e.g. a sink's registry rows); ignored for an unknown Step. */
    public void pointer(String nodeId, StepReceipt.Pointer ref) {
        Entry e = entries.get(nodeId);
        if (e != null) e.ref = ref;
    }

    /**
     * Write each conservation imbalance onto its Step's {@code diag} ({@code CONSERVATION_LOSS} /
     * {@code CONSERVATION_AMPLIFICATION}, with the in and out counts). A Step that already carries a diagnosis
     * (it failed) keeps it.
     */
    public void conservation(List<ConservationCheck.Imbalance> imbalances) {
        for (ConservationCheck.Imbalance im : imbalances) {
            Entry e = entries.get(im.node());
            if (e == null || e.diag.code() != null) continue;
            Map<String, Long> c = new LinkedHashMap<>();
            c.put("in", im.recordsIn());
            c.put("out", im.recordsOut());
            e.diag = new StepReceipt.Diagnostics(
                    "LOSS".equals(im.kind()) ? StepReceipt.CONSERVATION_LOSS : StepReceipt.CONSERVATION_AMPLIFICATION,
                    im.recordsIn() + " in, " + im.recordsOut() + " out (" + im.kind() + ")", c);
        }
    }

    /** Every {@code (node, rel)} count so far, keyed {@code "<nodeId>|<rel>"} — {@link ConservationCheck}'s input. */
    public Map<String, Long> counts() {
        return new LinkedHashMap<>(counts);
    }

    /**
     * This walk's receipts, in walk order. A Step that started and was never ended (the walk was cut off by
     * something it could not attribute) reads FAILED, never OK.
     */
    public List<StepReceipt> receipts() {
        List<Map.Entry<String, Entry>> ordered = new ArrayList<>(entries.entrySet());
        ordered.sort(Comparator.comparingInt(x -> x.getValue().seq));
        List<StepReceipt> out = new ArrayList<>(ordered.size());
        for (Map.Entry<String, Entry> me : ordered) {
            String nodeId = me.getKey();
            Entry e = me.getValue();
            StepReceipt.Outcome outcome = e.outcome == null ? StepReceipt.Outcome.FAILED : e.outcome;
            boolean ran = outcome == StepReceipt.Outcome.OK || outcome == StepReceipt.Outcome.FAILED;
            boolean sink = PipelineNodeTypes.isCategory(e.kind, NodeCategory.SINK);
            List<StepReceipt.Outlet> outlets = new ArrayList<>();
            if (ran && !sink) {
                String prefix = nodeId + "|";
                for (Map.Entry<String, Long> c : counts.entrySet())
                    if (c.getKey().startsWith(prefix))
                        outlets.add(new StepReceipt.Outlet(c.getKey().substring(prefix.length()), c.getValue()));
            }
            out.add(new StepReceipt(StepReceipt.VERSION, consignmentId, pipeline, lane, runId, e.seq, nodeId, e.kind,
                    outcome, ran ? rowsIn(nodeId, sink) : 0L, outlets, e.ref, e.schema, e.diag, e.startedAt,
                    e.startedAt == null ? 0L : e.ms, simulated, false));
        }
        return out;
    }

    /** A sink's own {@code data} count (what it consumed); else the sum over live inbound edges; a seed's own count. */
    private long rowsIn(String nodeId, boolean sink) {
        Long own = counts.get(nodeId + "|" + PipelineRel.DATA);
        if (sink) return own == null ? 0L : own;
        long in = 0;
        boolean saw = false;
        for (PipelineEdge edge : g.edgesTo(nodeId)) {
            Long c = counts.get(edge.from() + "|" + edge.rel());
            if (c != null) { in += c; saw = true; }
        }
        return saw ? in : (own == null ? 0L : own);
    }
}
