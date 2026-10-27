package com.gamma.job;

import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.event.EventLog;
import com.gamma.objects.ObjectAccess;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** D8: transient failures retry, permanent ones do not, and a final failure raises the alarm. */
class JobServiceRetryAlarmTest {

    private final List<Duration> pauses = new CopyOnWriteArrayList<>();

    @AfterEach
    void restore() {
        JobService.pause = d -> Thread.sleep(d.toMillis());
    }

    /** A Job that throws {@code failure} on its first {@code failFirst} calls, then succeeds. */
    private static JobTypeProvider failing(String id, AtomicInteger calls, int failFirst, Supplier<Exception> failure) {
        return JobTypeProvider.of(new JobTypeDescriptor(id, id, "test", List.of()), c -> new Job() {
            @Override public String name() { return c.name(); }
            @Override public String type() { return id; }
            @Override public JobResult run() throws Exception {
                if (calls.incrementAndGet() <= failFirst) throw failure.get();
                return JobResult.ok("fine", 0L);
            }
        });
    }

    private JobRun fire(Path dir, JobTypeProvider type, String name, String trigger, ObjectAccess objects,
                        List<Event> events) throws Exception {
        JobService.pause = pauses::add;
        BiConsumer<EventLog, Event> tap = (l, e) -> { if (EventType.JOB_FAILED.equals(e.type())) events.add(e); };
        EventLog.addTap(tap);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new ConsignmentEventBus(), s, null, dir.resolve("audit").toString())) {
            js.registerJobType(type);
            if (objects != null) js.objects(objects);
            js.upsertJob(new JobConfig(name, type.descriptor().id(), null, null, true, false,
                    Map.of("retries", "2", "retry_backoff_seconds", "1"), null, null));
            js.submit(name, trigger);
            long until = System.nanoTime() + 10_000_000_000L;
            JobRun r = null;
            while (System.nanoTime() < until) {
                r = js.lastRunOf(name).orElse(null);
                if (r != null && !"RUNNING".equals(r.status())) break;
                Thread.sleep(20);
            }
            Thread.sleep(100);
            return r;
        } finally {
            EventLog.removeTap(tap);
        }
    }

    private static ObjectAccess incidents(List<String> opened) {
        return (ObjectAccess) Proxy.newProxyInstance(ObjectAccess.class.getClassLoader(), new Class<?>[]{ObjectAccess.class},
                (p, m, a) -> {
                    if (m.getName().equals("hasActive")) return !opened.isEmpty();
                    if (m.getName().equals("open")) { opened.add(a[1] + "|" + a[2] + "|" + a[3]); return "INC-1"; }
                    return null;
                });
    }

    @Test
    void aTransientFailureIsRetriedWithBackoffAndThenSucceedsWithNoAlarm(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<Event> events = new CopyOnWriteArrayList<>();
        JobRun r = fire(dir, failing("t.flaky", calls, 2, () -> new IOException("share unavailable")),
                "flaky", "schedule", null, events);
        assertEquals("SUCCESS", r.status(), r.message());
        assertEquals(3, calls.get());
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), pauses);
        assertTrue(events.isEmpty());
    }

    @Test
    void aPermanentRefusalIsNeverRetriedAndRaisesTheEventAndOneIncident(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<Event> events = new CopyOnWriteArrayList<>();
        List<String> opened = new CopyOnWriteArrayList<>();
        JobRun r = fire(dir, failing("t.refused", calls, 99,
                () -> new IllegalArgumentException("sql.template job 'x' refused: only SELECT")),
                "refused", "schedule", incidents(opened), events);
        assertEquals("FAILED", r.status());
        assertEquals(1, calls.get(), "a guard refusal must not be retried");
        assertTrue(pauses.isEmpty());
        assertEquals(1, events.size());
        Event e = events.get(0);
        assertEquals(r.runId(), e.correlationId());
        assertTrue(e.message().contains("only SELECT"), e.message());
        assertEquals(1, opened.size());
        assertTrue(opened.get(0).contains("only SELECT"), opened.toString());
    }

    @Test
    void aTransientFailureThatExhaustsItsRetriesAlarmsOnceAfterAllAttempts(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<Event> events = new CopyOnWriteArrayList<>();
        JobRun r = fire(dir, failing("t.down", calls, 99, () -> new IOException("db down")), "down", "schedule", null, events);
        assertEquals("FAILED", r.status());
        assertEquals(3, calls.get(), "1 attempt + 2 retries");
        assertEquals(1, events.size());
    }

    @Test
    void aManualRunFailureIsNotAlarmed(@TempDir Path dir) throws Exception {
        List<Event> events = new CopyOnWriteArrayList<>();
        JobRun r = fire(dir, failing("t.manual", new AtomicInteger(), 99, () -> new IllegalStateException("bad")),
                "m1", "manual:rahul", null, events);
        assertEquals("FAILED", r.status());
        assertTrue(events.isEmpty());
    }
}
