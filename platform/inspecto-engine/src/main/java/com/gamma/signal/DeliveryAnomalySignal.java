package com.gamma.signal;

import com.gamma.audit.EventLog;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code collector.delivery.anomaly} (LA-DAILY-INGEST-1 T8, operator 2026-10-06): a daily feed's per-day manifest saw a part
 * re-delivered under the same name ({@code REDELIVERED}), a part re-sent under a new name ({@code RENAMED_CORRECTION}: the
 * day's rows are doubled), or a new part for a day that had looked complete ({@code PART_COUNT_CHANGED}). WARN severity; an
 * Alert Rule or an {@code on_signal} Job can route it. The payload names files through {@code FileNames.safe} only.
 */
public final class DeliveryAnomalySignal {

    public static final String TYPE = "collector.delivery.anomaly";

    private DeliveryAnomalySignal() {
    }

    public static void emit(String pipeline, String batchId, String kind, String day, String file, String previousFile,
                            String detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pipeline", pipeline);
        payload.put("batchId", batchId);
        payload.put("kind", kind);
        payload.put("day", day);
        payload.put("file", file);
        if (previousFile != null) payload.put("previousFile", previousFile);
        payload.put("detail", detail);
        Signal signal = new Signal(null, TYPE, Instant.now(), Severity.WARN,
                Ref.of("pipeline", pipeline), Ref.of("pipeline", pipeline),
                batchId, null, null, null, TYPE + " " + kind + " " + day, payload, 1);
        try {
            EventLog.current().emit(signal.toEvent());
        } catch (RuntimeException ignored) {
            // an observability sink must never break the commit it describes
        }
    }
}
