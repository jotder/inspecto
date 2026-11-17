package com.gamma.inspector;

import com.gamma.etl.LineageRow;
import com.gamma.pipeline.PipelineRel;
import com.gamma.pipeline.exec.StepReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>X5 L3 — what one ingest Consignment's pass did, gathered while it runs</b>, turned into its
 * {@link StepReceipt}s once its status is final ({@link ConsignmentIngestor#process}; plan §3.5). The flat lane
 * runs no Steps, so it is told about in PHASES — {@code parse}, {@code transform}, one {@code sink:<n>} per
 * {@code sinks[]} destination ({@code n} = the destination's 0-based index, the lift's {@code __d<n>}) — built from
 * the counts the pass already holds ({@link IngestOutcome}) plus the few only this registry sees: how far the pass
 * got, why the write stayed flat, what the Decision Rules moved, each destination's rows and files, and on the graph
 * fork every walked Step's receipt.
 *
 * <p>The trail travels in the CALL CONTEXT ({@link #CURRENT}, a {@link ScopedValue} bound by
 * {@code ConsignmentIngestor.process} around the one pass it belongs to), never in a static map: a Consignment id
 * is not unique across Pipelines, Spaces or attempts, so a map keyed by it mixed the trails of two passes minting
 * the same id (X5 L3 review). A pass runs on one thread, so every recording call sees its own pass's trail; with
 * nothing bound (a workbench test run, a direct {@code writeAndTrace}) each call records into a throwaway. The
 * binding ends with the pass, whatever it throws, so nothing outlives it. Collection never throws and never
 * changes the pass; only the flush is switchable ({@code -Dconsignment.steps}).
 */
final class IngestTrail {

    static final String PARSE = "parse";
    static final String TRANSFORM = "transform";
    static final String SINK = "sink";
    /** The kind of a flat-lane phase receipt (plan §3.2). */
    static final String PHASE_KIND = "phase";

    private static final Logger log = LoggerFactory.getLogger(IngestTrail.class);

    /** The trail of the pass running on this thread; unbound ⇒ nothing is recorded anywhere. */
    static final ScopedValue<Trail> CURRENT = ScopedValue.newInstance();

    private IngestTrail() {}

    /** One Consignment's facts. Synchronised: a multi-segment pass may write from several threads. */
    static final class Trail {
        private int phase;                                       // 0 parse, 1 transform, 2 sink — the furthest reached
        private final Instant[] phaseAt = new Instant[3];
        private boolean graph;                                   // a write went through the graph fork
        private String flatReason;                               // why a write stayed flat (the last one wins)
        private long transformIn = -1;                           // rows entering the Decision Rules, -1 = none reached
        private final Map<String, Long> moved = new LinkedHashMap<>();
        private final Map<Integer, long[]> sinks = new TreeMap<>();   // dest → {rows, files, ms}
        private final Map<Integer, Instant> sinkAt = new TreeMap<>(); // dest → first start
        private Integer sinkOpen;                                // a flat destination whose write started, not ended
        private Instant sinkOpenAt;
        private final Map<String, StepReceipt> walked = new LinkedHashMap<>();

        /** Whether any pass has recorded into this trail. */
        synchronized boolean recorded() {
            return phase > 0 || graph || flatReason != null || transformIn >= 0 || !sinks.isEmpty()
                    || sinkOpen != null || !walked.isEmpty();
        }

        synchronized void phase(String p) {
            int i = PARSE.equals(p) ? 0 : TRANSFORM.equals(p) ? 1 : 2;
            if (phaseAt[i] == null) phaseAt[i] = Instant.now();
            phase = Math.max(phase, i);
        }
    }

    private static Trail current() {
        return CURRENT.isBound() ? CURRENT.get() : new Trail();
    }

    // ── collection (the pass) ───────────────────────────────────────────────────────────────────────

    /** The pass reached {@code phase} ({@link #TRANSFORM} / {@link #SINK}); a phase never goes back. */
    static void phase(String phase) {
        current().phase(phase);
    }

    /**
     * One write reached the lane fork: {@code rowsIn} rows met the Decision Rules, which moved {@code moved}
     * out (outlet → rows); {@code flatReason} says why it stayed flat, {@code null} when it took the graph fork.
     */
    static void fork(long rowsIn, Map<String, Long> moved, String flatReason) {
        Trail t = current();
        synchronized (t) {
            if (rowsIn >= 0) t.transformIn = Math.max(t.transformIn, 0) + rowsIn;
            if (moved != null) moved.forEach((k, v) -> t.moved.merge(k, v, Long::sum));
            if (flatReason == null) t.graph = true;
            else t.flatReason = flatReason;
        }
        t.phase(SINK);
    }

    /** The flat lane starts writing destination {@code dest}. */
    static void sinkStarted(int dest) {
        Trail t = current();
        synchronized (t) {
            t.sinkOpen = dest;
            t.sinkOpenAt = Instant.now();
            t.sinkAt.putIfAbsent(dest, t.sinkOpenAt);
        }
    }

    /** The flat lane wrote {@code rows} rows in {@code files} files to destination {@code dest}. */
    static void sinkWritten(int dest, long rows, long files) {
        Trail t = current();
        synchronized (t) {
            long ms = t.sinkOpenAt == null ? 0 : Duration.between(t.sinkOpenAt, Instant.now()).toMillis();
            long[] s = t.sinks.computeIfAbsent(dest, k -> new long[3]);
            s[0] += rows;
            s[1] += files;
            s[2] += ms;
            t.sinkOpen = null;
            t.sinkOpenAt = null;
        }
    }

    /**
     * One graph-fork walk's receipts. A Consignment written in several calls (one per chunk or segment) walks
     * several times; a Step met again is merged into one receipt per (Consignment, Step, attempt) — rows summed,
     * the worst outcome kept (FAILED, then PARKED, then OK).
     */
    static void walked(String batchId, com.gamma.pipeline.exec.StepReceiptRecorder recorder) {
        try {
            List<StepReceipt> receipts = recorder.receipts();
            Trail t = current();
            synchronized (t) {
                for (StepReceipt r : receipts) t.walked.merge(r.step(), r, IngestTrail::merge);
            }
        } catch (RuntimeException e) {
            // a record of the walk, never a reason to change its outcome (called from a finally)
            log.warn("Consignment {}: could not keep the graph walk's Step Receipts: {}", batchId, e.getMessage());
        }
    }

    /** {@code SELECT count(*)} of {@code table} — a metadata read on a materialised table; -1 when unreadable. */
    static long rows(java.sql.Connection conn, String table) {
        try (java.sql.Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT count(*) FROM " + com.gamma.util.SqlIdent.q(table))) {
            return rs.next() ? rs.getLong(1) : -1;
        } catch (java.sql.SQLException | RuntimeException e) {
            return -1;
        }
    }

    private static StepReceipt merge(StepReceipt a, StepReceipt b) {
        Map<String, Long> outlets = new LinkedHashMap<>();
        for (StepReceipt.Outlet o : a.outlets()) outlets.merge(o.outlet(), o.rows(), Long::sum);
        for (StepReceipt.Outlet o : b.outlets()) outlets.merge(o.outlet(), o.rows(), Long::sum);
        List<StepReceipt.Outlet> merged = new ArrayList<>();
        outlets.forEach((k, v) -> merged.add(new StepReceipt.Outlet(k, v)));
        StepReceipt.Outcome outcome = rank(b.outcome()) > rank(a.outcome()) ? b.outcome() : a.outcome();
        StepReceipt.Diagnostics diag = b.outcome() == StepReceipt.Outcome.FAILED && a.outcome() != StepReceipt.Outcome.FAILED
                ? b.diag() : (a.diag().code() != null ? a.diag() : b.diag());
        StepReceipt.Pointer ref = a.ref() == null ? b.ref() : b.ref() == null ? a.ref()
                : new StepReceipt.Pointer(a.ref().kind(), a.ref().table(), a.ref().path(), a.ref().files() + b.ref().files());
        return new StepReceipt(a.v(), a.consignmentId(), a.pipeline(), a.lane(), a.runId(), a.seq(), a.step(), a.kind(),
                outcome, a.in() + b.in(), merged, ref, a.schema() != null ? a.schema() : b.schema(), diag,
                a.startedAt() != null ? a.startedAt() : b.startedAt(), a.ms() + b.ms(), a.simulated(),
                a.truncated() || b.truncated());
    }

    private static int rank(StepReceipt.Outcome o) {
        return switch (o) {
            case FAILED -> 4;
            case PARKED -> 3;
            case OK -> 2;
            case SKIPPED -> 1;
            case NOT_REACHED -> 0;
        };
    }

    // ── the receipts (the status-final point) ────────────────────────────────────────────────────────

    /**
     * The Consignment's receipts, in trail order: {@code parse}, {@code transform}, then the graph fork's walked
     * Steps (lane {@code ingest-graph}) and/or one {@code sink:<n>} per flat destination (lane {@code ingest-flat}).
     * The phases before the fork are told on the graph lane when every write took it, else on the flat lane.
     *
     * <p>Failure (plan §3.4): when the pass itself failed ({@code o.status()} FAILED), the Step it failed at is
     * FAILED with {@code error} (already scrubbed of values) and every later one NOT_REACHED — a walked Step that
     * threw names itself; otherwise the furthest phase the pass reached. A failure no Step owns (the commit tail,
     * a park, a lease loss — the pass itself succeeded) marks nothing FAILED. An {@code EMPTY} pass has a
     * {@code parse} receipt alone: nothing was left to transform or write.
     *
     * @param error the Consignment's scrubbed failure text ({@code ""} when none)
     * @param store the table the batch writes (a sink receipt's {@code outputs} pointer)
     * @param simulated a dry run: every receipt {@code simulated}, and none points at anything
     */
    static List<StepReceipt> receipts(Trail t, String consignmentId, String pipeline, String runId, IngestOutcome o,
                                      String error, int sinkCount, String store, boolean simulated) {
        synchronized (t) {
            return build(t, consignmentId, pipeline, runId, o, error, sinkCount, store, simulated);
        }
    }

    private static List<StepReceipt> build(Trail t, String consignmentId, String pipeline, String runId,
                                           IngestOutcome o, String error, int sinkCount, String store,
                                           boolean simulated) {
        boolean failed = "FAILED".equals(o.status());
        boolean walkFailed = t.walked.values().stream().anyMatch(r -> r.outcome() == StepReceipt.Outcome.FAILED);
        // The phase the pass failed at (0 parse, 1 transform, 2 a flat destination), or -1: never one a walked Step
        // already owns, and on the write tail only a destination that was being written — a throw between them,
        // like the commit tail after a pass that succeeded, is no Step's.
        int failedAt = !failed || walkFailed ? -1 : t.phase < 2 ? t.phase : (t.sinkOpen != null ? 2 : -1);
        boolean flatSinks = !t.graph || !t.sinks.isEmpty() || t.sinkOpen != null;
        String head = t.graph && !flatSinks ? StepReceipt.LANE_INGEST_GRAPH : StepReceipt.LANE_INGEST_FLAT;
        StepReceipt.Diagnostics failure = new StepReceipt.Diagnostics(StepReceipt.STEP_FAILED,
                error == null || error.isBlank() ? null : error, Map.of());
        Instant end = Instant.now();
        Instant parseAt = o.batchStart() == null ? t.phaseAt[0]
                : o.batchStart().atZone(ZoneId.systemDefault()).toInstant();
        Seq seq = new Seq(consignmentId, pipeline, runId, simulated);
        List<StepReceipt> out = new ArrayList<>();

        // parse — rows read, and the records the parser sent to the reject sidecar
        long data = o.totalInputRows();
        long rejects = o.memberAudits().stream().mapToLong(MemberAudit::errorRows).sum();
        long rejectFiles = o.memberAudits().stream().filter(m -> m.errorRows() > 0).count();
        List<StepReceipt.Outlet> parseOut = new ArrayList<>(List.of(new StepReceipt.Outlet(PipelineRel.DATA, data)));
        if (rejects > 0) parseOut.add(new StepReceipt.Outlet(PipelineRel.UNMATCHED, rejects));
        Map<String, Long> casts = o.castFailures() >= 0 ? Map.of("castFailures", o.castFailures()) : Map.of();
        StepReceipt.Diagnostics parseDiag = failedAt == 0 ? failure
                : t.flatReason != null && !t.graph ? new StepReceipt.Diagnostics(StepReceipt.LANE_FLAT, t.flatReason, casts)
                : new StepReceipt.Diagnostics(null, null, casts);
        out.add(seq.ran(head, PARSE, failedAt == 0, data + rejects, parseOut,
                rejects > 0 ? new StepReceipt.Pointer(StepReceipt.Pointer.REJECTS, null, null, rejectFiles) : null,
                parseDiag, parseAt, t.phaseAt[1] != null ? t.phaseAt[1] : end));
        if ("EMPTY".equals(o.status())) return out;

        // transform — what the Decision Rules moved are its outlets; what is left goes on as data
        if (failedAt == 0) {
            out.add(seq.notReached(head, TRANSFORM));
        } else {
            long in = t.transformIn >= 0 ? t.transformIn : data;
            List<StepReceipt.Outlet> outlets = new ArrayList<>();
            if (failedAt != 1) {
                long movedRows = t.moved.values().stream().mapToLong(Long::longValue).sum();
                outlets.add(new StepReceipt.Outlet(PipelineRel.DATA, Math.max(0, in - movedRows)));
                t.moved.forEach((k, v) -> outlets.add(new StepReceipt.Outlet(k, v)));
            }
            out.add(seq.ran(head, TRANSFORM, failedAt == 1, in, outlets, null,
                    failedAt == 1 ? failure : StepReceipt.Diagnostics.NONE,
                    t.phaseAt[1], t.phaseAt[2] != null ? t.phaseAt[2] : end));
        }

        // the graph fork's walked Steps, numbered on their own lane after any phase told there
        for (StepReceipt r : t.walked.values())
            out.add(new StepReceipt(StepReceipt.VERSION, consignmentId, pipeline, StepReceipt.LANE_INGEST_GRAPH,
                    runId, seq.next(StepReceipt.LANE_INGEST_GRAPH), r.step(), r.kind(), r.outcome(), r.in(),
                    r.outlets(), simulated ? null : r.ref(), r.schema(), r.diag(), r.startedAt(), r.ms(), simulated,
                    r.truncated()));

        // the flat lane's destinations, one sink:<n> per sinks[] entry
        if (!flatSinks) return out;
        String flat = StepReceipt.LANE_INGEST_FLAT;
        for (int d = 0; d < Math.max(1, sinkCount); d++) {
            String step = SINK + ":" + d;
            long[] w = t.sinks.get(d);
            if (w == null && !failed && !t.graph && t.sinks.isEmpty() && t.sinkOpen == null && sinkCount <= 1)
                // a write that bypassed the fork (the plugin lane's generation mode): its counts are the outcome's
                w = new long[]{o.lineage().stream().mapToLong(LineageRow::rowCount).sum(), o.outputs().size(), 0};
            if (failedAt == 2 && t.sinkOpen == d) {
                out.add(seq.ran(flat, step, true, w == null ? 0 : w[0], List.of(), null, failure, t.sinkAt.get(d), end));
            } else if (w != null) {
                out.add(new StepReceipt(StepReceipt.VERSION, consignmentId, pipeline, flat, runId, seq.next(flat), step,
                        PHASE_KIND, StepReceipt.Outcome.OK, w[0], List.of(),
                        simulated ? null : new StepReceipt.Pointer(StepReceipt.Pointer.OUTPUTS, store, null, w[1]),
                        null, StepReceipt.Diagnostics.NONE, iso(t.sinkAt.get(d)), w[2], simulated, false));
            } else if (failed && !t.graph) {
                out.add(seq.notReached(flat, step));
            }
        }
        return out;
    }

    /** Builds the phase receipts, numbering each lane on its own (the key is (Consignment, run, lane, seq)). */
    private static final class Seq {
        final String consignmentId, pipeline, runId;
        final boolean simulated;
        int flat, graph;

        Seq(String consignmentId, String pipeline, String runId, boolean simulated) {
            this.consignmentId = consignmentId;
            this.pipeline = pipeline;
            this.runId = runId;
            this.simulated = simulated;
        }

        int next(String lane) {
            return StepReceipt.LANE_INGEST_GRAPH.equals(lane) ? ++graph : ++flat;
        }

        StepReceipt ran(String lane, String step, boolean failed, long in, List<StepReceipt.Outlet> outlets,
                        StepReceipt.Pointer ref, StepReceipt.Diagnostics diag, Instant from, Instant to) {
            long ms = from == null || to == null ? 0 : Math.max(0, Duration.between(from, to).toMillis());
            return new StepReceipt(StepReceipt.VERSION, consignmentId, pipeline, lane, runId, next(lane), step,
                    PHASE_KIND, failed ? StepReceipt.Outcome.FAILED : StepReceipt.Outcome.OK, in, outlets,
                    simulated ? null : ref, null, diag, iso(from), ms, simulated, false);
        }

        StepReceipt notReached(String lane, String step) {
            return new StepReceipt(StepReceipt.VERSION, consignmentId, pipeline, lane, runId, next(lane), step,
                    PHASE_KIND, StepReceipt.Outcome.NOT_REACHED, 0, List.of(), null, null,
                    StepReceipt.Diagnostics.NONE, null, 0, simulated, false);
        }
    }

    private static String iso(Instant at) {
        return at == null ? null : at.toString();
    }

    /** The receipt a failure is named on — the first FAILED one — or {@code null}. */
    static StepReceipt failedStep(List<StepReceipt> receipts) {
        for (StepReceipt r : receipts) if (r.outcome() == StepReceipt.Outcome.FAILED) return r;
        return null;
    }
}
