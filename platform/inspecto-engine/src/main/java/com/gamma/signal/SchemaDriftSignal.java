package com.gamma.signal;

import com.gamma.etl.SchemaDrift;
import com.gamma.event.EventLog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits the {@code quality.schema_drift} Signal — the REPORT half of {@code quality.schema.drift}
 * ({@code SP-DQ-06}). One WARN Signal per batch, never per file: a feed whose shape changed carries the
 * change in every member, and a Signal per member would be N copies of one fact. Same ambient
 * {@link EventLog#current()} idiom and the same "never break the batch" guard as
 * {@link PipelineConsignmentSignal}; {@code correlationId = batchId} so triage lands it beside the
 * batch's own {@code pipeline.batch.committed}.
 */
public final class SchemaDriftSignal {

    public static final String TYPE = "quality.schema_drift";

    private SchemaDriftSignal() {
    }

    /** Emit one Signal describing every drifted member of {@code batchId}; a no-op on an empty list. */
    public static void emit(String pipeline, String batchId, List<SchemaDrift.Report> drifted) {
        if (drifted == null || drifted.isEmpty()) return;
        List<Map<String, Object>> files = new ArrayList<>();
        for (SchemaDrift.Report r : drifted) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("file", r.file());
            f.put("declaredWidth", r.declaredWidth());
            f.put("observedWidth", r.observedWidth());
            f.put("observed", r.observed());
            f.put("added", r.added());
            f.put("missing", r.missing());
            f.put("namesCompared", r.namesCompared());
            files.add(f);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pipeline", pipeline);
        payload.put("batchId", batchId);
        payload.put("files", files);
        payload.put("count", files.size());

        Signal signal = new Signal(null, TYPE, Instant.now(), Severity.WARN,
                Ref.of("pipeline", pipeline), Ref.of("pipeline", pipeline),
                batchId, null, null, null, TYPE, payload, 1);
        try {
            EventLog.current().emit(signal.toEvent());
        } catch (RuntimeException ignored) {
            // an observability sink must never break the batch it is describing
        }
    }
}
