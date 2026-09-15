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
 * Same per-test EventLog isolation idiom as {@link PipelineConsignmentSignalTest}.
 */
class DatasetWriteSignalTest {

    @Test
    void emitLandsAQueryableDatasetWriteSignal() {
        String space = "dataset-write-signal-test-" + UUID.randomUUID();
        EventLog log = EventLog.create();
        EventLog.register(space, log);
        org.slf4j.MDC.put(EventLog.SPACE_MDC_KEY, space);
        try {
            DatasetWriteSignal.emit("orders_rollup", 42,
                    Ref.of("materialize", "orders_rollup"), "nightly_orders");

            List<Signal> signals = Signals.query(log.store(), DatasetWriteSignal.TYPE,
                    null, null, null, null, 10);
            assertEquals(1, signals.size());
            Signal sig = signals.get(0);
            assertEquals("dataset.write", sig.type());
            assertEquals("dataset", sig.subject().kind());
            assertEquals("orders_rollup", sig.subject().id());
            assertEquals("orders_rollup", sig.payload().get("dataset"));
            assertEquals(42L, ((Number) sig.payload().get("rows")).longValue());
            assertEquals("materialize:orders_rollup", sig.payload().get("producer"),
                    "the compact kind:id string stays in the payload for readers that had it");
            assertNotNull(sig.payload().get("at"));
            // DATASET-SELF-TRIGGER-1: the producer is STRUCTURED on the Signal's own actor slot (null until
            // now), and the owning pipeline is a separate value — not flattened into the producer name.
            assertNotNull(sig.actor(), "the producer rides the actor Ref");
            assertEquals("materialize", sig.actor().kind());
            assertEquals("orders_rollup", sig.actor().id());
            assertEquals("nightly_orders", sig.payload().get(DatasetWriteSignal.PAYLOAD_PIPELINE),
                    "the owning pipeline is what the scheduler's self-loop guard matches on");
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
            DatasetWriteSignal.emit("sparse_store", -1, null, null);

            List<Signal> signals = Signals.query(log.store(), DatasetWriteSignal.TYPE,
                    null, null, null, null, 10);
            assertEquals(1, signals.size());
            assertEquals(-1L, ((Number) signals.get(0).payload().get("rows")).longValue());
            assertFalse(signals.get(0).payload().containsKey("producer"),
                    "an absent producer is omitted, never a null in the payload");
            assertFalse(signals.get(0).payload().containsKey(DatasetWriteSignal.PAYLOAD_PIPELINE),
                    "an unowned write omits the pipeline too — and that means 'suppress nothing'");
            assertNull(signals.get(0).actor(), "no producer ⇒ no actor Ref");
        } finally {
            org.slf4j.MDC.remove(EventLog.SPACE_MDC_KEY);
            EventLog.unregister(space);
        }
    }
}
