package com.gamma.signal;

import com.gamma.etl.ConsignmentEvent;
import com.gamma.event.EventLog;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits the canonical {@code pipeline.batch.committed|failed} Signal for a terminal batch onto the
 * current space's ledger. This is the observability tail formerly inlined in
 * {@code etl.ConsignmentAuditWriter.emitBatchSignal}; it was lifted here — above {@code com.gamma.etl} — so
 * the ETL layer stays free of the {@code event}/{@code signal} packages and can be a foundation layer.
 * The composition root wires it via
 * {@code ConsignmentAuditWriter.setTerminalBatchSink(PipelineConsignmentSignal::emit)}.
 *
 * <p>Uses {@link EventLog#current()} — the established ambient idiom for code with no injected
 * per-space handle (mirrors {@code ReportJob}'s {@code REPORT_READY} emission). It is additive to the
 * {@code ConsignmentEventBus} fan-out and to {@code JobService.mirrorPipelineCommit}'s {@code pipeline.commit}
 * mirror (a different signal type); none of those are replaced.
 */
public final class PipelineConsignmentSignal {

    private PipelineConsignmentSignal() {
    }

    /** Build the canonical Signal from a terminal {@link ConsignmentEvent} and emit it onto the current ledger. */
    public static void emit(ConsignmentEvent event) {
        boolean success = "SUCCESS".equals(event.status());
        // A PARKED Consignment (Phase 4 S4b) is neither committed nor failed — it stopped at a
        // disabled Step by operator intent, so reporting it "failed" would teach operators something
        // broke when nothing did (the 503-vs-error lesson, applied to Signals).
        String type = success ? "pipeline.batch.committed"
                : "PARKED".equals(event.status()) ? "pipeline.batch.parked" : "pipeline.batch.failed";

        // Event's payload immutability (Map.copyOf, Event.java) rejects null values, so only put the
        // optional error-detail fields when present — mirrors ConsignmentEvent's own null-ability.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", event.status());
        payload.put("outputRows", event.outputRows());
        payload.put("durationMs", event.durationMs());
        payload.put("rejectedCount", event.rejectedCount());
        payload.put("partitions", event.partitions());
        if (event.error() != null && !event.error().isBlank()) payload.put("error", event.error());
        if (event.offendingFile() != null) payload.put("offendingFile", event.offendingFile());
        payload.put("errorRows", event.errorRows());

        Signal signal = new Signal(null, type, Instant.now(), success ? Severity.INFO : Severity.WARN,
                Ref.of("pipeline", event.pipeline()), Ref.of("pipeline", event.pipeline()),
                event.batchId(), null, null, null, type, payload, 1);
        try {
            EventLog.current().emit(signal.toEvent());
        } catch (RuntimeException ignored) {
            // an observability sink must never break the batch commit it is announcing
        }
    }

    /**
     * X1: a Consignment exhausted its bounded COMMIT retries and its files were quarantined under
     * {@code retry_exhausted}. CRITICAL, once per exhausted Consignment — the "poison never drops
     * silently" handoff: this is the one Signal an alert rule needs to catch a batch that will never
     * commit on its own. Correlated on the batch id like every other {@code pipeline.batch.*} Signal.
     */
    public static void emitRetryExhausted(String pipeline, String batchId, int attempts,
                                          java.util.List<String> files, String lastError) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attempts", attempts);
        payload.put("files", java.util.List.copyOf(files));
        if (lastError != null && !lastError.isBlank()) payload.put("error", lastError);
        Signal signal = new Signal(null, "pipeline.batch.retry_exhausted", Instant.now(), Severity.CRITICAL,
                Ref.of("pipeline", pipeline), Ref.of("pipeline", pipeline),
                batchId, null, null, null, "pipeline.batch.retry_exhausted", payload, 1);
        try {
            EventLog.current().emit(signal.toEvent());
        } catch (RuntimeException ignored) {
            // an observability sink must never break the recovery bookkeeping it is announcing
        }
    }
}
