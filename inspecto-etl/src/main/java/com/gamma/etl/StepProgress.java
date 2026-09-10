package com.gamma.etl;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.gamma.util.CurrentSpace;

/**
 * Live per-step progress, per pipeline — "Consignment X is at step 3/5" (G6,
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

    /** Where a pipeline is right now: step {@code index} of {@code total} for a Consignment. */
    public record Snapshot(String consignmentId, String step, int index, int total, String startedAt) {}

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /**
     * Live snapshots keyed by <b>(space, pipeline)</b>, not by pipeline alone.
     *
     * <p>🔴 The space half was added 2026-09-10 (SPACE-UNKEYED-STATICS-1): two Spaces running a same-named
     * pipeline — which is what applying one Space template twice produces — overwrote each other's step
     * snapshot, so one tenant's screen reported the other's progress. The key is composed here rather than
     * threaded through callers because the space is <b>not in scope</b> at the fifteen call sites: the
     * pipeline config's identity record carries no space, and the graph executor tracks by graph name with
     * no config at hand. Composing it internally left every call site unchanged.
     */
    private static final ConcurrentMap<String, Snapshot> CURRENT = new ConcurrentHashMap<>();

    /** The (space, pipeline) key. Separator is the escape, never a raw NUL — a raw one makes the whole
     *  file invisible to every recursive ripgrep (see tools/check-nul-bytes.mjs). */
    private static String key(String pipeline) {
        return CurrentSpace.id() + '\0' + pipeline;
    }

    /** Drop every snapshot belonging to {@code spaceId} — called on space deletion, like the other
     *  per-space registries, so a removed space cannot leak a snapshot for the process lifetime. */
    public static void forgetSpace(String spaceId) {
        if (spaceId != null) CURRENT.keySet().removeIf(k -> k.startsWith(spaceId + '\0'));
    }

    private StepProgress() {}

    /** Record that {@code pipeline} is now at {@code step} ({@code index} of {@code total}, 1-based). */
    public static void track(String pipeline, String consignmentId, String step, int index, int total) {
        if (pipeline == null || pipeline.isBlank()) return;
        CURRENT.put(key(pipeline), new Snapshot(consignmentId, step, index, total,
                LocalDateTime.now().format(TS)));
    }

    /** Drop the pipeline's snapshot (batch/run finished — success, empty, or failed alike). */
    public static void clear(String pipeline) {
        if (pipeline != null) CURRENT.remove(key(pipeline));
    }

    /** The step the pipeline is at right now, or {@code null} when nothing is running. */
    public static Snapshot current(String pipeline) {
        return pipeline == null ? null : CURRENT.get(key(pipeline));
    }
}
