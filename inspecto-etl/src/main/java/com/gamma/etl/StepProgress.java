package com.gamma.etl;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Live per-step progress, per pipeline/flow — "Consignment X is at step 3/5" (G6,
 * {@code consignment-chain-plan.md} S7).
 *
 * <p>The {@link IngestProgress} idiom one level up: {@code IngestProgress} answers <em>which
 * file</em> a mid-ingest pipeline is on, this answers <em>which step</em> of the chain the current
 * Consignment is in — the EL lane reports its parse → transform → sink boundaries, the job lane's
 * executor reports each node of its topological walk. Cleared when the batch/run finishes, so a
 * snapshot is only ever visible while work is live.
 *
 * <p><b>Deliberately in-memory and poll-read</b> (the S7 design decision): per-step lifecycle is a
 * live gauge, not history. Durable per-step history is the provenance matrix, terminal outcomes are
 * the {@code pipeline.batch.committed|failed} Signals — a per-step Signal would be a durable write
 * per step per Consignment on the claim-holding thread and could trigger jobs mid-batch (the
 * PROJECT_NOTES re-entrancy class), and a persisted snapshot is stale by construction. Two map ops
 * per step; the hot loop pays effectively nothing.
 */
public final class StepProgress {

    /** Where a pipeline/flow is right now: step {@code index} of {@code total} for a Consignment. */
    public record Snapshot(String consignmentId, String step, int index, int total, String startedAt) {}

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ConcurrentMap<String, Snapshot> CURRENT = new ConcurrentHashMap<>();

    private StepProgress() {}

    /** Record that {@code pipeline} is now at {@code step} ({@code index} of {@code total}, 1-based). */
    public static void track(String pipeline, String consignmentId, String step, int index, int total) {
        if (pipeline == null || pipeline.isBlank()) return;
        CURRENT.put(pipeline, new Snapshot(consignmentId, step, index, total,
                LocalDateTime.now().format(TS)));
    }

    /** Drop the pipeline's snapshot (batch/run finished — success, empty, or failed alike). */
    public static void clear(String pipeline) {
        if (pipeline != null) CURRENT.remove(pipeline);
    }

    /** The step the pipeline is at right now, or {@code null} when nothing is running. */
    public static Snapshot current(String pipeline) {
        return pipeline == null ? null : CURRENT.get(pipeline);
    }
}
