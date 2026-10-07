package com.gamma.signal;

import com.gamma.event.EventLog;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits {@code audit.write_failed} — the one Signal that says an <b>audit record of truth</b> could not be
 * written ({@code JOBRUN-STORE-SWALLOWED-WRITES-1}).
 *
 * <h3>Why only the record of truth, and not every swallowed write</h3>
 * The job-run surface has three write paths and they are <b>not</b> the same kind of thing:
 * <ul>
 *   <li>{@code JobRunLedger.record} → {@code jobs_runs.csv}. This is the audit. Its javadoc calls it
 *       "the durable append-only audit", {@code DbJobRunStore.record} calls it "the record", and
 *       {@code lastStartTimes}/{@code lastSuccessTime} read it back as the misfire/catch-up baseline. If
 *       this append fails, the run <b>did not happen</b> as far as anything durable is concerned — that is
 *       a compliance gap, and the same class of gap {@code AUDIT-REFUSAL-GAP-1} was fixed to close.
 *       ⇒ It emits this Signal.</li>
 *   <li>{@code DbJobRunStore.record} / {@code recordSources} → the DuckDB run <b>projection</b>. Both
 *       javadocs already state they are best-effort <i>because the CSV is the record</i>. A projection that
 *       misses a row is a reporting gap that a re-sync repairs; it is exactly the {@code file_stages}
 *       "best-effort index" precedent, which was deliberate. ⇒ They stay log-only, by decision.</li>
 * </ul>
 *
 * <h3>Why a Signal and not a throw</h3>
 * The caller is {@code JobService}'s run-completion path, which has already finished the work. Throwing
 * there turns an audit hiccup — a full disk, a locked file — into a <b>failed job</b>, which is a worse
 * outcome than a recorded gap and is not something the caller can act on anyway. So the failure is
 * announced on the channel that already exists (the Event/Signal bus, reaching {@code /signals} and any
 * Alert Rule matching the type) rather than thrown. ⚠ {@link com.gamma.util.StoreHealth} was the other
 * candidate and is the wrong seam: it records what a store <b>opened</b> as, one entry per family replacing
 * the last — a per-write failure would either be overwritten by the next success or falsely pin the family
 * DEGRADED forever, and in a partitioned topology {@code StoreHealth.record} <b>throws</b>, which is the
 * outcome this seam exists to avoid.
 *
 * <p>Severity is {@code ERROR}, not {@code WARN}: unlike {@link SchemaDriftSignal} (a detector reporting on
 * data) this reports that the system's own record-keeping failed. Same "never break the caller" guard as
 * every other emitter here — an observability sink that throws would recreate the failure it describes.
 */
public final class AuditWriteSignal {

    public static final String TYPE = "audit.write_failed";

    private AuditWriteSignal() {
    }

    /**
     * Announce that an audit write failed.
     *
     * @param audit   which audit could not be written, e.g. {@code "jobs_runs.csv"}
     * @param subject what the lost record was about — the job name; becomes the Signal's subject
     * @param cause   the failure, never rethrown
     */
    public static void emit(String audit, String subject, Throwable cause) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("audit", audit);
        payload.put("subject", subject);
        payload.put("error", cause == null ? null : cause.toString());

        Signal signal = new Signal(null, TYPE, Instant.now(), Severity.ERROR,
                Ref.of("audit", audit), Ref.of("job", subject),
                null, null, null, null,
                "Audit write to " + audit + " failed for " + subject
                        + " — this run is missing from the durable record", payload, 1);
        try {
            EventLog.current().emit(signal.toEvent());
        } catch (RuntimeException ignored) {
            // An audit-failure report that throws recreates the failure it is describing. The WARN log at
            // the call site remains the last resort.
        }
    }
}
