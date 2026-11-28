package com.gamma.job;

import com.gamma.audit.Event;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.event.EventLog;
import com.gamma.signal.Ref;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SIGNAL-BOARD-1 SB-2b: catch-up at boot of the Signals an {@code on_signal} Job missed while the service was down. */
class SignalCatchUpTest {

    private static final String TYPE = "probe.catchup";

    private static JobConfig listener(String name, String onSignal) {
        return new JobConfig(name, "maintenance", null, null, true, false, Map.of("task", "heartbeat"), onSignal, null);
    }

    private static JobService service(Path dir, Scheduler s, EventLog log, JobConfig... jobs) {
        JobService js = new JobService(List.of(jobs), new ConsignmentEventBus(), s, null, dir.resolve("audit").toString());
        js.eventLog(log);
        return js;
    }

    private static Event signal(String type, Instant at) {
        return new Signal(null, type, at, Severity.INFO, Ref.of("test", "probe"), null, null, null, null, null, "probe",
                Map.of(), 1).toEvent();
    }

    private static List<JobRun> signalRuns(JobService js, String job) {
        return js.runsFor(job).stream().filter(r -> r.trigger().startsWith("signal:")).toList();
    }

    private static <T> T await(Supplier<T> probe) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        T v;
        while ((v = probe.get()) == null && System.currentTimeMillis() < until) Thread.sleep(25);
        return v;
    }

    private static long missedSignals(EventLog log) {
        return log.store().recent(500).stream()
                .filter(e -> "job.signal.missed".equals(e.attributes().get(Signal.ATTR_TYPE))).count();
    }

    /** G8 closed: a Signal stored while the Job service was down starts the Job's Run at the next boot. */
    @Test
    void aSignalStoredWhileTheServiceWasDownStartsItsRunAtBoot(@TempDir Path dir) throws Exception {
        EventLog log = EventLog.create();
        try (Scheduler s = new Scheduler()) {
            try (JobService first = service(dir, s, log, listener("react", TYPE))) {
                first.start();   // a Job seen for the first time: its mark is "now"
                assertNotNull(first.signalMarkOf("react"));
            }
            Thread.sleep(5);
            Event missed = signal(TYPE, Instant.now());   // stored while no Job service listens
            log.emit(missed);
            try (JobService second = service(dir, s, log, listener("react", TYPE))) {
                assertTrue(second.runsFor("react").isEmpty());
                second.start();
                JobRun run = await(() -> signalRuns(second, "react").stream()
                        .filter(r -> "SUCCESS".equals(r.status())).findFirst().orElse(null));
                assertNotNull(run, "the missed Signal started a Run at boot: " + second.runsFor("react"));
                assertEquals("signal:" + TYPE, run.trigger());
                String id = Signal.fromEvent(missed).signalId();
                assertNotNull(await(() -> id.equals(markId(second, "react")) ? id : null),
                        "the mark moved past the replayed Signal once its Run finished");
            }
            // A third boot replays nothing: the mark is past the Signal.
            try (JobService third = service(dir, s, log, listener("react", TYPE))) {
                third.start();
                Thread.sleep(400);
                assertTrue(signalRuns(third, "react").isEmpty(), third.runsFor("react").toString());
            }
        }
    }

    /** D-SB5: a missed Signal older than the age limit is not replayed; one job.signal.missed Signal names it. */
    @Test
    void aMissedSignalOlderThanTheAgeLimitIsNotReplayedAndIsPostedAsMissed(@TempDir Path dir) throws Exception {
        Path config = Files.createDirectories(dir.resolve("config"));
        Files.writeString(config.resolve(SignalCatchUp.SETTINGS_FILE), "max_age_hours: 1\n");
        Path audit = Files.createDirectories(dir.resolve("audit"));
        Instant now = Instant.now();
        // The Job's mark is three hours old: it handled everything up to then, and the server was down since.
        Files.writeString(audit.resolve(SignalCatchUp.MARKS_FILE),
                "react\t" + now.minus(Duration.ofHours(3)).toEpochMilli() + "\tm\n");
        EventLog log = EventLog.create();
        Event stale = signal(TYPE, now.minus(Duration.ofHours(2)));   // beyond the one-hour limit
        Event fresh = signal(TYPE, now.minus(Duration.ofMinutes(10)));
        log.emit(stale);
        log.emit(fresh);
        try (Scheduler s = new Scheduler(); JobService js = service(dir, s, log, listener("react", TYPE))) {
            js.signalCatchUpSettings(config);
            js.start();
            JobRun run = await(() -> signalRuns(js, "react").stream()
                    .filter(r -> "SUCCESS".equals(r.status())).findFirst().orElse(null));
            assertNotNull(run, "the Signal inside the limit was replayed");
            Thread.sleep(300);
            assertEquals(1, signalRuns(js, "react").size(), "only the fresh Signal ran: " + js.runsFor("react"));
            Event posted = log.store().recent(500).stream()
                    .filter(e -> "job.signal.missed".equals(e.attributes().get(Signal.ATTR_TYPE))).findFirst().orElseThrow();
            Map<String, Object> p = Signal.fromEvent(posted).payload();
            assertEquals("react", p.get("job"));
            assertEquals(1, ((Number) p.get("count")).intValue());
            assertEquals(List.of(Signal.fromEvent(stale).signalId()), p.get("signalIds"));
            assertEquals(1, ((Number) p.get("maxAgeHours")).intValue());
            String freshId = Signal.fromEvent(fresh).signalId();
            assertNotNull(await(() -> freshId.equals(markId(js, "react")) ? freshId : null));
        }
    }

    /** A Job seen for the first time starts at "now": the history already on the ledger is not replayed. */
    @Test
    void aNewJobReplaysNoHistory(@TempDir Path dir) throws Exception {
        EventLog log = EventLog.create();
        log.emit(signal(TYPE, Instant.now().minus(Duration.ofMinutes(5))));
        log.emit(signal(TYPE, Instant.now()));
        try (Scheduler s = new Scheduler(); JobService js = service(dir, s, log, listener("fresh", TYPE))) {
            js.start();
            Thread.sleep(400);
            assertTrue(signalRuns(js, "fresh").isEmpty(), js.runsFor("fresh").toString());
            assertEquals(0, missedSignals(log));
            // A Job added later through a save starts at "now" too, and a changed on_signal re-starts the mark.
            js.upsertJob(listener("added", TYPE));
            assertNotNull(js.signalMarkOf("added"));
            SignalCatchUp.Mark before = js.signalMarkOf("added");
            Thread.sleep(5);
            js.upsertJob(listener("added", "probe.other"));
            assertTrue(js.signalMarkOf("added").compareTo(before) > 0, "a changed on_signal starts at now");
            js.removeJob("added");
            assertNull(js.signalMarkOf("added"));
        }
    }

    /** Two Jobs listening to one type catch up independently: each from its own mark. */
    @Test
    void eachJobCatchesUpFromItsOwnMark(@TempDir Path dir) throws Exception {
        Path audit = Files.createDirectories(dir.resolve("audit"));
        Instant now = Instant.now();
        Event early = signal(TYPE, now.minus(Duration.ofMinutes(20)));
        Event late = signal(TYPE, now.minus(Duration.ofMinutes(5)));
        // "behind" handled nothing since 30 minutes ago; "ahead" already handled the early Signal.
        Files.writeString(audit.resolve(SignalCatchUp.MARKS_FILE),
                "behind\t" + now.minus(Duration.ofMinutes(30)).toEpochMilli() + "\tm\n"
                        + "ahead\t" + Signal.fromEvent(early).at().toEpochMilli() + "\t" + Signal.fromEvent(early).signalId() + "\n");
        EventLog log = EventLog.create();
        log.emit(early);
        log.emit(late);
        Map<String, String> each = Map.of("task", "heartbeat", JobConfig.COALESCE, "false");
        try (Scheduler s = new Scheduler(); JobService js = service(dir, s, log,
                new JobConfig("behind", "maintenance", null, null, true, false, each, TYPE, null),
                new JobConfig("ahead", "maintenance", null, null, true, false, each, TYPE, null))) {
            js.start();
            assertNotNull(await(() -> signalRuns(js, "behind").stream().filter(r -> "SUCCESS".equals(r.status()))
                    .count() == 2 ? Boolean.TRUE : null), "behind replays both: " + js.runsFor("behind"));
            assertNotNull(await(() -> signalRuns(js, "ahead").stream().filter(r -> "SUCCESS".equals(r.status()))
                    .count() == 1 ? Boolean.TRUE : null), "ahead replays only the late one: " + js.runsFor("ahead"));
            Thread.sleep(300);
            assertEquals(1, signalRuns(js, "ahead").size(), js.runsFor("ahead").toString());
        }
    }

    // ── the low-water mark ───────────────────────────────────────────────────────────────────────────────

    private static Signal at(long ms, String id) {
        return new Signal(id, TYPE, Instant.ofEpochMilli(ms), Severity.INFO, null, null, null, null, null, null,
                "probe", Map.of(), 1);
    }

    @Test
    void theMarkNeverPassesASignalWhoseRunIsStillInFlight() {
        SignalCatchUp marks = new SignalCatchUp(null);
        marks.saved(null, listener("j", TYPE), Instant.ofEpochMilli(1_000));
        marks.queued("j", at(2_000, "a"), "run-a");
        marks.queued("j", at(3_000, "b"), "run-b");
        marks.ended("run-b", true);                    // b finished first
        assertEquals(1_000, marks.markOf("j").atMs(), "a is still in flight");
        marks.handled("j", at(4_000, "c"));            // a guard said no to c
        assertEquals(1_000, marks.markOf("j").atMs());
        marks.ended("run-a", true);
        assertEquals(new SignalCatchUp.Mark(4_000, "c"), marks.markOf("j"), "all handled: the mark is the newest");
    }

    /** Verification finding: a live Signal handled between the subscriber attaching and the replay must not move the
     *  mark past the backlog, or the missed Signals are neither replayed nor posted as missed. */
    @Test
    void aLiveSignalHandledBeforeTheReplayDoesNotSkipTheBacklog() {
        Instant cut = Instant.now();
        SignalCatchUp marks = new SignalCatchUp(null);
        marks.saved(null, listener("j", TYPE), cut.minus(Duration.ofMinutes(30)));
        EventLog log = EventLog.create();
        Event missed = signal(TYPE, cut.minus(Duration.ofMinutes(10)));
        log.emit(missed);
        List<JobConfig> jobs = List.of(listener("j", TYPE));
        marks.hold(jobs, cut);
        marks.handled("j", at(cut.toEpochMilli() + 5, "live"));   // the live subscriber, before the replay
        assertTrue(marks.markOf("j").atMs() < cut.toEpochMilli(), "the sentinel holds the mark before the cut");
        List<String> replayed = new java.util.ArrayList<>();
        marks.replay(log.store(), jobs, cut, Duration.ofHours(24), (c, s) -> {
            replayed.add(s.signalId());
            marks.handled(c.name(), s);
        }, (c, m) -> { throw new AssertionError("nothing is too old"); });
        assertEquals(List.of(Signal.fromEvent(missed).signalId()), replayed);
        assertEquals(new SignalCatchUp.Mark(cut.toEpochMilli() + 5, "live"), marks.markOf("j"),
                "released: the mark moves up to the live Signal handled meanwhile");
    }

    /** Re-verification finding: a live Signal stamped just BEFORE the cut, handled before the replay, must not skip the
     *  backlog either (it may itself be replayed: at least once). */
    @Test
    void aLiveSignalStampedJustBeforeTheCutDoesNotSkipTheBacklog() {
        Instant cut = Instant.now();
        SignalCatchUp marks = new SignalCatchUp(null);
        marks.saved(null, listener("j", TYPE), cut.minus(Duration.ofMinutes(30)));
        EventLog log = EventLog.create();
        Event missed = signal(TYPE, cut.minus(Duration.ofMinutes(10)));
        log.emit(missed);
        List<JobConfig> jobs = List.of(listener("j", TYPE));
        marks.hold(jobs, cut);
        marks.handled("j", at(cut.toEpochMilli() - 1, "late"));   // built before the cut, delivered live after it
        List<String> replayed = new java.util.ArrayList<>();
        marks.replay(log.store(), jobs, cut, Duration.ofHours(24), (c, s) -> {
            replayed.add(s.signalId());
            marks.handled(c.name(), s);
        }, (c, m) -> { throw new AssertionError("nothing is too old"); });
        assertEquals(List.of(Signal.fromEvent(missed).signalId()), replayed);
        assertEquals(new SignalCatchUp.Mark(cut.toEpochMilli() - 1, "late"), marks.markOf("j"));
    }

    @Test
    void aRunAPlannedStopSkippedPinsTheMarkSoTheNextBootReplaysIt(@TempDir Path dir) {
        String audit = dir.toString();
        SignalCatchUp marks = new SignalCatchUp(audit);
        marks.saved(null, listener("j", TYPE), Instant.ofEpochMilli(1_000));
        marks.queued("j", at(2_000, "a"), "run-a");
        marks.ended("run-a", false);                   // skipped: the Space was shutting down
        marks.handled("j", at(3_000, "b"));
        assertEquals(1_000, marks.markOf("j").atMs());
        assertEquals(1_000, new SignalCatchUp(audit).markOf("j").atMs(), "the durable mark survives a restart");
    }

    @Test
    void theAgeLimitDefaultsTo24HoursAndIsReadPerSpace(@TempDir Path dir) throws Exception {
        assertEquals(Duration.ofHours(24), SignalCatchUp.maxAgeFor(dir));
        Files.writeString(dir.resolve(SignalCatchUp.SETTINGS_FILE), "max_age_hours: 6\n");
        assertEquals(Duration.ofHours(6), SignalCatchUp.maxAgeFor(dir));
        Files.writeString(dir.resolve(SignalCatchUp.SETTINGS_FILE), "max_age_hours: 0\n");
        assertEquals(Duration.ofHours(24), SignalCatchUp.maxAgeFor(dir), "not positive reads as the default");
    }

    private static String markId(JobService js, String job) {
        SignalCatchUp.Mark m = js.signalMarkOf(job);
        return m == null ? null : m.signalId();
    }
}
