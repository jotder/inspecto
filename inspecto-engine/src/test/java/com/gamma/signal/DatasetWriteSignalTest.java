package com.gamma.signal;

import com.gamma.event.EventLog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S3a (ELT amendment Phase 3, design of record 2026-08-06): a Dataset write publishes the
 * {@code dataset.write} Signal — the trigger substrate S3b's scheduler subscription matches on.
 * Additive to the three pipeline-shaped commit mechanisms, none of which carries a Dataset id.
 * Same per-test EventLog isolation idiom as {@link PipelineBatchSignalTest}.
 */
class DatasetWriteSignalTest {

    @Test
    void emitLandsAQueryableDatasetWriteSignal() {
        String space = "dataset-write-signal-test-" + UUID.randomUUID();
        EventLog log = EventLog.create();
        EventLog.register(space, log);
        org.slf4j.MDC.put(EventLog.SPACE_MDC_KEY, space);
        try {
            DatasetWriteSignal.emit("orders_rollup", 42, "materialize:orders_rollup");

            List<Signal> signals = Signals.query(log.store(), DatasetWriteSignal.TYPE,
                    null, null, null, null, 10);
            assertEquals(1, signals.size());
            Signal sig = signals.get(0);
            assertEquals("dataset.write", sig.type());
            assertEquals("dataset", sig.subject().kind());
            assertEquals("orders_rollup", sig.subject().id());
            assertEquals("orders_rollup", sig.payload().get("dataset"));
            assertEquals(42L, ((Number) sig.payload().get("rows")).longValue());
            assertEquals("materialize:orders_rollup", sig.payload().get("producer"));
            assertNotNull(sig.payload().get("at"));
        } finally {
            org.slf4j.MDC.remove(EventLog.SPACE_MDC_KEY);
            EventLog.unregister(space);
        }
    }

    /** An unknown row count and an absent producer must not poison the payload (Event rejects nulls). */
    @Test
    void unknownRowsAndAbsentProducerStillEmit() {
        String space = "dataset-write-signal-test-" + UUID.randomUUID();
        EventLog log = EventLog.create();
        EventLog.register(space, log);
        org.slf4j.MDC.put(EventLog.SPACE_MDC_KEY, space);
        try {
            DatasetWriteSignal.emit("sparse_store", -1, null);

            List<Signal> signals = Signals.query(log.store(), DatasetWriteSignal.TYPE,
                    null, null, null, null, 10);
            assertEquals(1, signals.size());
            assertEquals(-1L, ((Number) signals.get(0).payload().get("rows")).longValue());
            assertFalse(signals.get(0).payload().containsKey("producer"),
                    "an absent producer is omitted, never a null in the payload");
        } finally {
            org.slf4j.MDC.remove(EventLog.SPACE_MDC_KEY);
            EventLog.unregister(space);
        }
    }
}
