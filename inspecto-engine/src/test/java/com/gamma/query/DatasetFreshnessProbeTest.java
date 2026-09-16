package com.gamma.query;

import com.gamma.event.Event;
import com.gamma.event.EventQuery;
import com.gamma.event.EventStore;
import com.gamma.signal.DatasetWriteSignal;
import com.gamma.signal.Ref;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The DUCKLE-C1 freshness clock: what counts as a publication, and the cold-start scan.
 *
 * <p>The events here are built the way {@link DatasetWriteSignal} builds them — a {@code SIGNAL} event
 * whose {@code signalType} attribute is {@code dataset.write} and whose payload carries the Dataset id.
 * ⚠ Built through {@code Signal.toEvent()} rather than hand-assembled, so a change to that encoding
 * breaks this test instead of silently making the probe blind.
 */
class DatasetFreshnessProbeTest {

    private static Event write(String dataset, long ts) {
        com.gamma.signal.Signal s = new com.gamma.signal.Signal(
                null, DatasetWriteSignal.TYPE, java.time.Instant.ofEpochMilli(ts),
                com.gamma.signal.Severity.INFO, Ref.of("dataset", dataset), Ref.of("dataset", dataset),
                null, null, null, null, DatasetWriteSignal.TYPE,
                java.util.Map.of("dataset", dataset, "rows", 10L), 1);
        return s.toEvent();
    }

    @Test
    void theBusSubscriberIsTheSteadyStatePathAndNeverGoesBackwards() {
        DatasetFreshnessProbe probe = new DatasetFreshnessProbe((EventStore) null);
        var sub = probe.subscriber();

        assertTrue(probe.apply("sales_ds").isEmpty(), "never published ⇒ unknown, not fresh");

        sub.accept(write("sales_ds", 5_000L));
        assertEquals(OptionalLong.of(5_000L), probe.apply("sales_ds"));

        // Subscribers are not ordered: an out-of-order older write must not move the clock backwards,
        // which would report a Dataset stale that had in fact just published.
        sub.accept(write("sales_ds", 1_000L));
        assertEquals(OptionalLong.of(5_000L), probe.apply("sales_ds"));

        sub.accept(write("sales_ds", 9_000L));
        assertEquals(OptionalLong.of(9_000L), probe.apply("sales_ds"));
    }

    @Test
    void anEventThatIsNotADatasetWriteIsNotAPublication() {
        DatasetFreshnessProbe probe = new DatasetFreshnessProbe((EventStore) null);
        var sub = probe.subscriber();
        sub.accept(Event.builder(com.gamma.event.EventType.ALERT_FIRED)
                .ts(9_000L).message("dataset sales_ds is unhappy").build());
        assertTrue(probe.apply("sales_ds").isEmpty(),
                "only dataset.write announces a publication — ⛔ an alert ABOUT a Dataset is not one");
    }

    @Test
    void theColdStartScanRecoversAPublicationFromBeforeThisProcess() {
        // 🔴 sales_ds_archive is a different Dataset whose id CONTAINS the one we ask for. The store
        // query narrows with a substring match on the compact "dataset:<id>" source, so this row comes
        // back from the query and must be rejected on the exact payload value. Without that check the
        // probe would report sales_ds fresh on a sales_ds_archive publication.
        DatasetFreshnessProbe probe = new DatasetFreshnessProbe(store(new AtomicInteger(), null,
                write("sales_ds", 4_000L), write("sales_ds", 7_000L), write("sales_ds_archive", 99_000L)));
        assertEquals(OptionalLong.of(7_000L), probe.apply("sales_ds"), "the NEWEST publication wins");
        assertEquals(OptionalLong.of(99_000L), probe.apply("sales_ds_archive"));
        assertTrue(probe.apply("never_written_ds").isEmpty());
    }

    /** A minimal {@link EventStore} — {@code InMemoryEventStore} is final, so it cannot be spied on. */
    private static EventStore store(AtomicInteger scans, RuntimeException fail, Event... events) {
        List<Event> rows = List.of(events);
        return new EventStore() {
            @Override public void append(Event event) { throw new UnsupportedOperationException(); }
            @Override public List<Event> query(EventQuery q) {
                scans.incrementAndGet();
                if (fail != null) throw fail;
                return rows.stream().filter(q::matches).toList();
            }
            @Override public List<Event> recent(int limit) { return rows; }
        };
    }

    @Test
    void theScanRunsAtMostOncePerDatasetIncludingWhenItFindsNothing() {
        AtomicInteger scans = new AtomicInteger();
        DatasetFreshnessProbe probe = new DatasetFreshnessProbe(store(scans, null));

        assertTrue(probe.apply("ghost_ds").isEmpty());
        assertTrue(probe.apply("ghost_ds").isEmpty());
        assertTrue(probe.apply("ghost_ds").isEmpty());
        assertEquals(1, scans.get(), "⚠ a MISS is cached too — freshness is evaluated on a timer, so "
                + "re-scanning would put a store query on a once-a-minute loop for every Dataset that "
                + "has genuinely never published. It is safe because any later publication arrives on "
                + "the bus, so a cached miss can only be corrected upwards.");

        // ...and the bus still corrects it.
        probe.subscriber().accept(write("ghost_ds", 3_000L));
        assertEquals(OptionalLong.of(3_000L), probe.apply("ghost_ds"));
        assertEquals(1, scans.get());
    }

    @Test
    void anUnreadableStoreAnswersUnknownRatherThanThrowing() {
        DatasetFreshnessProbe probe = new DatasetFreshnessProbe(
                store(new AtomicInteger(), new IllegalStateException("store is down")));
        // ⛔ This runs inside a once-a-minute sweep that also evaluates every other rule: a clock that
        // throws would take the whole sweep down with it.
        assertTrue(probe.apply("sales_ds").isEmpty());
    }
}
