package com.gamma.event;

import com.gamma.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the {@link EventLog} facade (store swap with startup-event draining + the
 * {@code inspecto_events_total} metric) and end-to-end SLF4J capture through the
 * {@link EventStoreAppender} configured in {@code logback.xml} — the "everything except DEBUG" rule.
 */
class EventLogAndAppenderTest {

    @Test
    void installStoreDrainsRetainedEventsAndEmitBumpsMetric() {
        InMemoryEventStore first = new InMemoryEventStore(100);
        EventLog.global().installStore(first);
        EventLog.global().emit(Event.builder(EventType.SERVICE_STARTED).message("early-startup").build());

        InMemoryEventStore second = new InMemoryEventStore(100);
        EventLog.global().installStore(second);   // swap — early event must carry over
        assertSame(second, EventLog.global().store());
        assertEquals(1, second.recent(1000).stream().filter(e -> "early-startup".equals(e.message())).count(),
                "startup event drained into the newly installed store");

        EventLog.global().emit(Event.builder(EventType.LOG).message("metered").build());
        assertTrue(MetricRegistry.global().scrape().contains("inspecto_events_total"),
                "emit increments the events counter");
    }

    @Test
    void aReleasedStoreIsNotDrainedIntoTheNextOne() {
        EventLog log = EventLog.create();
        InMemoryEventStore closed = new InMemoryEventStore(100);
        log.installStore(closed);
        log.emit(Event.builder(EventType.AUDIT).message("closed-owner"));
        log.releaseStore(closed);
        assertNotSame(closed, log.store());

        InMemoryEventStore next = new InMemoryEventStore(100);
        log.installStore(next);
        log.emit(Event.builder(EventType.AUDIT).message("own"));
        List<Event> held = next.recent(1000);
        assertEquals(List.of("own"), held.stream().map(Event::message).toList(), "nothing carried from a released store");
        assertEquals(1, AuditChain.seq(held.get(0)), "the new store's chain starts at genesis");

        log.releaseStore(closed);   // no longer installed: a no-op
        assertSame(next, log.store());
    }

    @Test
    void slf4jCaptureRecordsInfoAndAboveButNotDebug() {
        InMemoryEventStore store = new InMemoryEventStore(1000);
        EventLog.global().installStore(store);

        Logger log = LoggerFactory.getLogger("test.capture.Marker");
        log.debug("DBGMARK-should-not-capture");   // below threshold → console only
        log.info("INFMARK");
        log.warn("WRNMARK");
        log.error("ERRMARK");

        List<Event> captured = store.query(EventQuery.builder().textContains("MARK").limit(100).build());
        List<String> messages = captured.stream().map(Event::message).toList();
        assertTrue(messages.contains("INFMARK"), "INFO captured");
        assertTrue(messages.contains("WRNMARK"), "WARN captured");
        assertTrue(messages.contains("ERRMARK"), "ERROR captured");
        assertTrue(store.query(EventQuery.builder().textContains("DBGMARK").limit(100).build()).isEmpty(),
                "DEBUG must not be captured into the event store");

        Event err = captured.stream().filter(e -> "ERRMARK".equals(e.message())).findFirst().orElseThrow();
        assertEquals(EventLevel.ERROR, err.level(), "log level mapped");
        assertEquals("test.capture.Marker", err.source(), "logger name → source");
        assertEquals(EventType.LOG, err.type());
        assertEquals(Thread.currentThread().getName(), err.attributes().get("thread"));
    }

    @Test
    void capturesExceptionSummaryWithoutFullStackTrace() {
        InMemoryEventStore store = new InMemoryEventStore(1000);
        EventLog.global().installStore(store);
        LoggerFactory.getLogger("test.capture.Ex")
                .error("EXMARK boom", new IllegalStateException("kaboom"));
        Event e = store.query(EventQuery.builder().textContains("EXMARK").limit(10).build()).get(0);
        assertEquals("java.lang.IllegalStateException", e.attributes().get("exception"));
        assertEquals("kaboom", e.attributes().get("exceptionMessage"));
    }

    /** ASSURE-AUDIT-CHAIN-RESIDUALS-1 (9) review: presentIds refusing for ONE carried audit row must not drop the
     *  rest of the carry-over. That row is left out (fail-closed: it may already be there) and announced. */
    @Test
    void aCarriedRowPresentIdsCannotResolveIsSkippedAndTheRestStillCarry() {
        EventLog log = EventLog.create();
        InMemoryEventStore first = new InMemoryEventStore(100);
        log.installStore(first);
        log.emit(Event.builder(EventType.AUDIT).message("unresolvable"));
        log.emit(Event.builder(EventType.AUDIT).message("audit-ok"));
        log.emit(Event.builder("JOB_STARTED").message("plain"));
        String bad = first.recent(10).stream().filter(e -> "unresolvable".equals(e.message())).findFirst()
                .orElseThrow().eventId();

        InMemoryEventStore d = new InMemoryEventStore(100);
        EventStore next = new EventStore() {
            @Override public void append(Event e) { d.append(e); }
            @Override public List<Event> query(EventQuery q) { return d.query(q); }
            @Override public List<Event> recent(int n) { return d.recent(n); }
            @Override public List<Event> page(int n, Long t, String id) { return d.page(n, t, id); }
            @Override public java.util.Set<String> presentIds(java.util.Collection<String> ids) {
                if (ids.contains(bad)) throw new IllegalStateException("cannot tell");
                return d.presentIds(ids);
            }
        };
        log.installStore(next);
        List<String> msgs = d.recent(100).stream().map(Event::message).toList();
        assertTrue(msgs.contains("audit-ok") && msgs.contains("plain"), "the rest carried: " + msgs);
        assertFalse(msgs.contains("unresolvable"), "the unresolvable audit row is not appended (maybe a duplicate)");
        assertTrue(msgs.stream().anyMatch(m -> m.contains(bad)), "and it is announced: " + msgs);
    }
}
