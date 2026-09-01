package com.gamma.signal;

import com.gamma.etl.ConsignmentAuditWriter;
import com.gamma.etl.ConsignmentEvent;
import com.gamma.etl.LineageRow;
import com.gamma.event.EventLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S1 (event-signal-backbone-plan §4.2): a terminal batch lands a canonical
 * {@code pipeline.batch.committed|failed} Signal on the ledger. Since WS-D increment 1 the Signal is
 * built by {@link PipelineConsignmentSignal} (above {@code com.gamma.etl}, so etl stays a foundation layer)
 * and wired into {@link ConsignmentAuditWriter#setTerminalBatchSink} by the composition root
 * ({@code CollectorProcessor}). This test wires the same sink and proves the Signal fires alongside
 * the existing {@code ConsignmentEventBus}-style {@link ConsignmentEvent} commitListener fan-out — one flush,
 * neither path regressing the other.
 */
class PipelineConsignmentSignalTest {

    @Test
    void flushEmitsACanonicalSignalAlongsideTheExistingBatchEvent(@TempDir Path dir) {
        // Isolate from the shared global EventLog (its ring buffer is flooded by the rest of the
        // reactor's tests) by registering a fresh per-test EventLog under a unique space id.
        String space = "pipeline-batch-signal-test-" + UUID.randomUUID();
        EventLog log = EventLog.create();
        EventLog.register(space, log);
        org.slf4j.MDC.put(EventLog.SPACE_MDC_KEY, space);
        try {
            ConsignmentAuditWriter w = new ConsignmentAuditWriter(
                    dir.resolve("s2.csv").toString(), dir.resolve("b2.csv").toString(),
                    dir.resolve("l2.csv").toString());
            AtomicReference<ConsignmentEvent> seen = new AtomicReference<>();
            w.setCommitListener(seen::set);
            w.setTerminalBatchSink(PipelineConsignmentSignal::emit);   // the composition-root wiring under test

            String batchId = "SIG-" + UUID.randomUUID();
            var fileRows = List.of(new ConsignmentAuditWriter.FileRow("t0", "t1", "good.csv", "SUCCESS",
                    5, 0, List.of("/db/out.csv"), List.of(10L), 5, "", batchId));
            var batchRow = new ConsignmentAuditWriter.ConsignmentRow(batchId, "sig_pipeline", "mini", "",
                    "t0", "t2", "SUCCESS", 1, 0, 5, 5, 1, 10L, 20, "");
            var lineage = List.of(new LineageRow(batchId, 0, "good.csv", "/db/out.csv", "year=2026", 5));

            w.flush(batchRow, fileRows, lineage);

            // existing path: ConsignmentEvent still fires unchanged
            ConsignmentEvent ev = seen.get();
            assertNotNull(ev, "the pre-existing ConsignmentEvent path must still fire");
            assertEquals("SUCCESS", ev.status());
            assertEquals(batchId, ev.batchId());

            // new path: a queryable pipeline.batch.committed Signal on the ledger
            List<Signal> signals = Signals.query(log.store(), "pipeline.batch.*",
                    null, null, null, batchId, 10);
            assertEquals(1, signals.size(), "exactly one signal for this batch's correlationId");
            Signal sig = signals.get(0);
            assertEquals("pipeline.batch.committed", sig.type());
            assertEquals(batchId, sig.correlationId());
            assertEquals("sig_pipeline", sig.subject().id());
            assertEquals(5L, ((Number) sig.payload().get("outputRows")).longValue());
        } finally {
            org.slf4j.MDC.remove(EventLog.SPACE_MDC_KEY);
            EventLog.unregister(space);
        }
    }
}
