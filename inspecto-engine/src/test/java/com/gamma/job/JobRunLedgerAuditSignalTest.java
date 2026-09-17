package com.gamma.job;

import com.gamma.event.EventLog;
import com.gamma.signal.AuditWriteSignal;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import com.gamma.signal.Signals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code JOBRUN-STORE-SWALLOWED-WRITES-1}: a failed append to {@code jobs_runs.csv} loses the durable record
 * of a run, so it must leave an operator-visible {@code audit.write_failed} Signal — and must still not
 * throw, because the run has already completed and failing it would be the worse outcome.
 *
 * <p>The failure is forced the only way {@link com.gamma.util.CsvLedger} can fail deterministically on every
 * platform: {@code jobs_runs.csv} is a <b>directory</b>, so the per-append {@code FileWriter} throws.
 */
class JobRunLedgerAuditSignalTest {

    private static final JobRun RUN = new JobRun(
            "run-1", "nightly_load", "pipeline", "schedule",
            "2026-09-17 01:00:00", "2026-09-17 01:00:05", "SUCCESS", 5000, "ok");

    /** Record one run inside an isolated EventLog and return the audit-failure Signals it left. */
    private static List<Signal> recordInIsolation(JobRunLedger ledger) {
        String space = "jobrun-audit-signal-test-" + UUID.randomUUID();
        EventLog log = EventLog.create();
        EventLog.register(space, log);
        org.slf4j.MDC.put(EventLog.SPACE_MDC_KEY, space);
        try {
            ledger.record(RUN);   // ⛔ must not throw: that is half of what this row decided
            return Signals.query(log.store(), AuditWriteSignal.TYPE, null, null, null, null, 10);
        } finally {
            org.slf4j.MDC.remove(EventLog.SPACE_MDC_KEY);
            EventLog.unregister(space);
        }
    }

    @Test
    void aFailedAuditAppendEmitsAnAuditWriteFailedSignalAndDoesNotThrow(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("jobs_runs.csv"));   // the append target is now unwritable
        JobRunLedger ledger = new JobRunLedger(dir.toString(), null);

        List<Signal> signals = recordInIsolation(ledger);

        assertEquals(1, signals.size(), "one audit.write_failed Signal for the lost record: " + signals);
        Signal sig = signals.get(0);
        assertEquals(Severity.ERROR, sig.severity(), "the system's own record-keeping failed — not a WARN");
        assertEquals("jobs_runs.csv", sig.payload().get("audit"));
        assertEquals("nightly_load", sig.payload().get("subject"), "which run is missing from the record");
        assertNotNull(sig.payload().get("error"), "the cause travels with the Signal");

        // The in-memory history is still served: the audit gap must not also blind the Control API.
        assertEquals(List.of(RUN), ledger.runsFor(RUN.job()),
                "the run is still in the bounded history the Control API reads");
    }

    @Test
    void aSuccessfulAuditAppendEmitsNoSignal(@TempDir Path dir) {
        List<Signal> signals = recordInIsolation(new JobRunLedger(dir.toString(), null));

        assertEquals(List.of(), signals, "a healthy audit write is silent — the Signal means a record was LOST");
        assertTrue(Files.exists(dir.resolve("jobs_runs.csv")), "and the audit row was actually written");
    }
}
