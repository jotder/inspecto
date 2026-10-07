package com.gamma.signal;

import com.gamma.event.EventLog;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits the {@code dataset.write} Signal at the moment a Dataset's data becomes <b>visible</b> —
 * post-swap in {@code MaterializeTask}, after the whole chain completes in
 * {@code ConsignmentProcessJobType} (⚠ moved there from inside {@code persistSummaries} by
 * {@code DATASET-PUBLISH-ON-FAILURE-1}, 2026-09-15: announcing mid-chain published writes that a later
 * step, or {@code persistDerivedTables}, could still abandon), and any future Dataset-producing sink
 * (ELT amendment Phase 3 <b>S3a</b>, design of record 2026-08-06). Payload
 * {@code {dataset, rows, at, producer}} where {@code producer} is the writing pipeline/job name.
 *
 * <p>Strictly <b>additive</b>, mirroring {@link PipelineConsignmentSignal}'s posture: it joins — never
 * replaces — the three pipeline-shaped commit mechanisms ({@code PipelineScheduler.onUpstreamCommit},
 * {@code JobService.mirrorPipelineCommit}'s {@code pipeline.commit} mirror, and
 * {@code pipeline.batch.committed|failed}), none of which carries a Dataset id. S3b subscribes the
 * scheduler to this type for the {@code {type: event, on: dataset, from: datasets/<id>}} trigger form.
 *
 * <p>Same ambient-ledger idiom ({@link EventLog#current()}) and the same guarantee: an observability
 * sink must never break the write it is announcing.
 */
public final class DatasetWriteSignal {

    /** The dotted Signal type S3b's scheduler subscription matches on. */
    public static final String TYPE = "dataset.write";

    private DatasetWriteSignal() {
    }

    /**
     * The payload key carrying the <b>owning pipeline</b> — the pipeline whose run produced this write, or
     * absent when no pipeline owns it (a cron or manually fired job).
     *
     * <p>⛔ Read by {@code CollectorService}'s subscriber and handed to
     * {@code PipelineScheduler.onDatasetWrite} as its self-loop guard. Emitter and reader must use this one
     * constant — the value used to be dropped between them, which is why the guard could not exist.
     */
    public static final String PAYLOAD_PIPELINE = "pipeline";

    /**
     * Announce that {@code dataset}'s data just became visible.
     *
     * <p>🔴 <b>{@code producer} is a structured {@link Ref}, and the owning pipeline is separate from it</b>
     * ({@code DATASET-SELF-TRIGGER-1}, decided 2026-09-15). It was a bare String before, and — worse — an
     * <em>inconsistent</em> one: {@code MaterializeTask} passed a JOB name while
     * {@code ConsignmentProcessJobType} passed a PROCESSOR COMPONENT ID, so nothing downstream could tell
     * what it had been handed. The two are now different {@code kind}s of one vocabulary, and the question
     * "which pipeline does this write belong to?" has its own answer instead of being guessed from a name
     * that might be any of three things.
     *
     * <p>⚠ That distinction is what makes a self-loop guard possible at all: comparing a job or processor id
     * against a pipeline name can never match, so the obvious guard silently never fired. See
     * {@link #PAYLOAD_PIPELINE}.
     *
     * @param dataset         the Dataset id (the {@code ComponentStore} {@code dataset} kind id / store name)
     * @param rows            rows made visible by this write, {@code -1} when the writer does not know
     * @param producer        what wrote it — {@code Ref.of("job", …)} / {@code Ref.of("processor", …)};
     *                        may be {@code null}
     * @param owningPipeline  the pipeline whose run produced this write, or {@code null} when none owns it
     *                        (a cron or manual job) — ⚠ {@code null} means "do not suppress", never
     *                        "suppress everything"
     */
    public static void emit(String dataset, long rows, Ref producer, String owningPipeline) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dataset", dataset);
        payload.put("rows", rows);
        payload.put("at", Instant.now().toString());
        // ⚠ The compact `kind:id` string stays in the payload for human readers and existing consumers of
        // this key; the structured Ref rides the Signal's own `actor` slot, which was null until now.
        if (producer != null && producer.id() != null && !producer.id().isBlank())
            payload.put("producer", producer.kind() == null
                    ? producer.id() : producer.kind() + ":" + producer.id());
        if (owningPipeline != null && !owningPipeline.isBlank())
            payload.put(PAYLOAD_PIPELINE, owningPipeline);

        Signal signal = new Signal(null, TYPE, Instant.now(), Severity.INFO,
                Ref.of("dataset", dataset), Ref.of("dataset", dataset),
                null, null, null, producer, TYPE, payload, 1);
        try {
            EventLog.current().emit(signal.toEvent());
        } catch (RuntimeException ignored) {
            // an observability sink must never break the write it is announcing
        }
    }
}
