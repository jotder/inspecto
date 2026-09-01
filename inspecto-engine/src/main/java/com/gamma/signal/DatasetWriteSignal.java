package com.gamma.signal;

import com.gamma.event.EventLog;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits the {@code dataset.write} Signal at the moment a Dataset's data becomes <b>visible</b> —
 * post-swap in {@code MaterializeTask}, post-{@code record} in
 * {@code ConsignmentProcessJobType.persistSummaries}, and any future Dataset-producing sink
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
     * Announce that {@code dataset}'s data just became visible.
     *
     * @param dataset  the Dataset id (the {@code ComponentStore} {@code dataset} kind id / store name)
     * @param rows     rows made visible by this write, {@code -1} when the writer does not know
     * @param producer the writing pipeline/job name
     */
    public static void emit(String dataset, long rows, String producer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dataset", dataset);
        payload.put("rows", rows);
        payload.put("at", Instant.now().toString());
        if (producer != null && !producer.isBlank()) payload.put("producer", producer);

        Signal signal = new Signal(null, TYPE, Instant.now(), Severity.INFO,
                Ref.of("dataset", dataset), Ref.of("dataset", dataset),
                null, null, null, null, TYPE, payload, 1);
        try {
            EventLog.current().emit(signal.toEvent());
        } catch (RuntimeException ignored) {
            // an observability sink must never break the write it is announcing
        }
    }
}
