package com.gamma.signal;

import com.gamma.event.EventLog;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code collector.file.problem} (LA-DATA-TRUST-1 S6, operator 2026-10-14): a File of a consignment was quarantined
 * ({@code QUARANTINED}), the consignment it rode in failed ({@code FAILED}), or it landed while losing rows
 * ({@code PARTIAL}, {@code error_rows > 0}). One Signal per File, emitted by the same audit write that produces the
 * {@code _status_*.csv} row, so the Signal and the ledger can never disagree. {@code EventAlertBridge} turns it into
 * ONE stored Alert per (pipeline, File, day). WARN severity.
 *
 * <p>The payload names the File through {@code FileNames.safe} only and carries counts - never a row value or an
 * entity key. {@code day} is the event day the Collector's {@code gap_detection.file_template} reads from the File
 * name, or {@code "-"} when the feed declares none.
 */
public final class FileProblemSignal {

    public static final String TYPE = "collector.file.problem";

    public static final String QUARANTINED = "QUARANTINED";
    public static final String FAILED = "FAILED";
    public static final String PARTIAL = "PARTIAL";

    private FileProblemSignal() {
    }

    public static void emit(String pipeline, String batchId, String kind, String day, String file, String status,
                            long parsedRows, long errorRows) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pipeline", pipeline);
        payload.put("batchId", batchId);
        payload.put("kind", kind);
        payload.put("day", day);
        payload.put("file", file);
        payload.put("status", status);
        payload.put("parsedRows", parsedRows);
        payload.put("errorRows", errorRows);
        Signal signal = new Signal(null, TYPE, Instant.now(), Severity.WARN,
                Ref.of("pipeline", pipeline), Ref.of("pipeline", pipeline),
                batchId, null, null, null, TYPE + " " + kind + " " + day, payload, 1);
        try {
            EventLog.current().emit(signal.toEvent());
        } catch (RuntimeException ignored) {
            // an observability sink must never break the audit write it describes
        }
    }
}
