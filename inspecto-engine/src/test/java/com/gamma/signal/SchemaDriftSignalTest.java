package com.gamma.signal;

import com.gamma.etl.SchemaDrift;
import com.gamma.event.EventLog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The REPORT half of {@code quality.schema.drift}: one {@code quality.schema_drift} WARN Signal per
 * batch, correlated on the batch id. Same per-test EventLog isolation idiom as
 * {@link PipelineConsignmentSignalTest}.
 */
class SchemaDriftSignalTest {

    private static SchemaDrift.Report report(String file) {
        return new SchemaDrift.Report(file, 3, 0, 4, List.of("A", "B", "C", "D"), List.of("D"), List.of(), true);
    }

    @Test
    void oneSignalPerBatchCarriesEveryDriftedFile() {
        String space = "schema-drift-signal-test-" + UUID.randomUUID();
        EventLog log = EventLog.create();
        EventLog.register(space, log);
        org.slf4j.MDC.put(EventLog.SPACE_MDC_KEY, space);
        try {
            SchemaDriftSignal.emit("EV_ETL", "b-42", List.of(report("a.csv"), report("b.csv")));

            List<Signal> signals = Signals.query(log.store(), SchemaDriftSignal.TYPE, null, null, null, null, 10);
            assertEquals(1, signals.size(), "one Signal per batch, never per file");
            Signal sig = signals.get(0);
            assertEquals("quality.schema_drift", sig.type());
            assertEquals(Severity.WARN, sig.severity());
            assertEquals("b-42", sig.correlationId(), "correlated on the batch so triage lands it beside the commit");
            assertEquals("pipeline", sig.subject().kind());
            assertEquals("EV_ETL", sig.subject().id());
            assertEquals(2, ((Number) sig.payload().get("count")).intValue());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files = (List<Map<String, Object>>) sig.payload().get("files");
            assertEquals("a.csv", files.get(0).get("file"));
            assertEquals(List.of("D"), files.get(0).get("added"));
            assertEquals(4, ((Number) files.get(0).get("observedWidth")).intValue());
        } finally {
            org.slf4j.MDC.remove(EventLog.SPACE_MDC_KEY);
            EventLog.unregister(space);
        }
    }

    @Test
    void nothingDriftedEmitsNothing() {
        String space = "schema-drift-signal-test-" + UUID.randomUUID();
        EventLog log = EventLog.create();
        EventLog.register(space, log);
        org.slf4j.MDC.put(EventLog.SPACE_MDC_KEY, space);
        try {
            SchemaDriftSignal.emit("EV_ETL", "b-43", List.of());
            assertTrue(Signals.query(log.store(), SchemaDriftSignal.TYPE, null, null, null, null, 10).isEmpty());
        } finally {
            org.slf4j.MDC.remove(EventLog.SPACE_MDC_KEY);
            EventLog.unregister(space);
        }
    }
}
