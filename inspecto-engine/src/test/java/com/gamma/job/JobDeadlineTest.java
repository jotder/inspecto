package com.gamma.job;

import com.gamma.etl.ConsignmentEventBus;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The generic Job Run deadline (platform-services R1, operator 2026-10-06): a hung Run is FAILED at its
 * deadline and interrupted, a Run finishing in time is untouched, a definition's {@code deadline_seconds:}
 * overrides the type default and is capped at the ceiling, and the built-in long types keep room for their
 * own timeouts.
 */
class JobDeadlineTest {

    @AfterEach
    void clearCeiling() {
        System.clearProperty(JobDeadline.CEILING_PROPERTY);
    }

    /** A test type whose Run sleeps {@code sleepMs}; {@code swallow} returns SUCCESS even when interrupted. */
    private static JobTypeProvider sleeper(String id, Duration typeDeadline, long sleepMs, boolean swallow,
                                           AtomicBoolean interrupted) {
        JobTypeDescriptor d = new JobTypeDescriptor(id, id, "test", List.of());
        return new JobTypeProvider() {
            @Override public JobTypeDescriptor descriptor() { return d; }
            @Override public Duration deadline() { return typeDeadline; }
            @Override public Job create(JobConfig c) {
                return new Job() {
                    @Override public String name() { return c.name(); }
                    @Override public String type() { return id; }
                    @Override public JobResult run() throws Exception {
                        try {
                            Thread.sleep(sleepMs);
                        } catch (InterruptedException e) {
                            interrupted.set(true);
                            if (!swallow) throw e;
                        }
                        return JobResult.ok("slept", sleepMs);
                    }
                };
            }
        };
    }

    private static JobConfig cfg(String name, String type, Map<String, String> params) {
        return new JobConfig(name, type, null, null, true, false, params, null, null);
    }

    private static JobRun runOnce(Path dir, JobTypeProvider type, JobConfig c, long waitMs) throws Exception {
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString())) {
            js.registerJobType(type);
            js.upsertJob(c);
            String runId = js.triggerRun(c.name(), "test").orElseThrow();
            long until = System.nanoTime() + waitMs * 1_000_000L;
            JobRun r = js.runById(runId).orElseThrow();
            while ("RUNNING".equals(r.status()) && System.nanoTime() < until) {
                Thread.sleep(20);
                r = js.runById(runId).orElseThrow();
            }
            return r;
        }
    }

    @Test
    void aHungJobIsFailedAtItsDeadlineAndInterrupted(@TempDir Path dir) throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean();
        long t0 = System.nanoTime();
        JobRun r = runOnce(dir, sleeper("t.hang", Duration.ofMillis(300), 60_000, false, interrupted),
                cfg("hang", "t.hang", Map.of()), 10_000);
        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("deadline exceeded"), r.message());
        assertTrue(interrupted.get(), "the Run's thread is interrupted at its deadline");
        assertTrue((System.nanoTime() - t0) / 1_000_000L < 10_000, "failed at the deadline, not after the sleep");
    }

    @Test
    void aJobSwallowingTheInterruptIsStillFailed(@TempDir Path dir) throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean();
        JobRun r = runOnce(dir, sleeper("t.swallow", Duration.ofMillis(300), 60_000, true, interrupted),
                cfg("swallow", "t.swallow", Map.of()), 10_000);
        assertTrue(interrupted.get());
        assertEquals("FAILED", r.status(), "a body that returns SUCCESS after its deadline is still FAILED");
        assertTrue(r.message().contains("deadline exceeded"), r.message());
    }

    @Test
    void aJobFinishingInTimeIsUnaffected(@TempDir Path dir) throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean();
        JobRun r = runOnce(dir, sleeper("t.quick", Duration.ofSeconds(5), 50, false, interrupted),
                cfg("quick", "t.quick", Map.of()), 10_000);
        assertEquals("SUCCESS", r.status(), r.message());
        assertEquals("slept", r.message());
        assertFalse(interrupted.get());
    }

    @Test
    void aDefinitionOverrideIsHonoured(@TempDir Path dir) throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean();
        // type default 30 min; the definition shortens it to 0.3 s
        JobRun r = runOnce(dir, sleeper("t.def", JobDeadline.DEFAULT, 60_000, false, interrupted),
                cfg("def", "t.def", Map.of(JobConfig.DEADLINE_SECONDS, "0.3")), 10_000);
        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("after 0.3 s"), r.message());
        // ...and a definition can LENGTHEN a short type default
        AtomicBoolean i2 = new AtomicBoolean();
        JobRun ok = runOnce(dir.resolve("b"), sleeper("t.def2", Duration.ofMillis(100), 400, false, i2),
                cfg("def2", "t.def2", Map.of(JobConfig.DEADLINE_SECONDS, "5")), 10_000);
        assertEquals("SUCCESS", ok.status(), ok.message());
    }

    @Test
    void aDefinitionOverrideIsCappedAtTheCeiling(@TempDir Path dir) throws Exception {
        System.setProperty(JobDeadline.CEILING_PROPERTY, "1");
        AtomicBoolean interrupted = new AtomicBoolean();
        JobRun r = runOnce(dir, sleeper("t.cap", JobDeadline.DEFAULT, 60_000, false, interrupted),
                cfg("cap", "t.cap", Map.of(JobConfig.DEADLINE_SECONDS, "3600")), 10_000);
        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("after 1 s"), r.message());
        System.clearProperty(JobDeadline.CEILING_PROPERTY);
        assertEquals(Duration.ofHours(24), JobDeadline.resolve(JobDeadline.DEFAULT,
                cfg("x", "t", Map.of(JobConfig.DEADLINE_SECONDS, "999999"))), "default ceiling is 24 h");
        assertEquals(Duration.ofHours(24), JobDeadline.resolve(Duration.ofDays(3), cfg("x", "t", Map.of())),
                "a type default is capped too");
    }

    @Test
    void aMalformedDeadlineIsRefused(@TempDir Path dir) throws Exception {
        for (String bad : List.of("0", "-5", "soon", "NaN"))
            assertThrows(IllegalArgumentException.class, () -> JobDeadline.parse(bad), bad);
        Map<String, Object> job = new HashMap<>(Map.of("name", "n", "type", "sample.hello",
                JobConfig.DEADLINE_SECONDS, "soon"));
        assertThrows(IllegalArgumentException.class, () -> JobConfig.fromMap(Map.of("job", job)));
        job.put(JobConfig.DEADLINE_SECONDS, "90");
        assertEquals("90", JobConfig.fromMap(Map.of("job", job)).params().get(JobConfig.DEADLINE_SECONDS));
        // a config built around fromMap still never runs under a bad deadline: REJECTED, the body never starts
        AtomicBoolean interrupted = new AtomicBoolean();
        JobRun r = runOnce(dir, sleeper("t.bad", JobDeadline.DEFAULT, 10, false, interrupted),
                cfg("bad", "t.bad", Map.of(JobConfig.DEADLINE_SECONDS, "soon")), 10_000);
        assertEquals("REJECTED", r.status(), r.message());
    }

    /** The built-in long types default to the ceiling, so their own timeouts still govern (operator 2026-10-06). */
    @Test
    void builtInDefaultsLeaveRoomForTheExistingPerJobTimeouts(@TempDir Path dir) throws Exception {
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString())) {
            JobTypeRegistry reg = registryOf(js);
            for (String id : List.of("pipeline", "enrich", "maintenance", "recon.run", "consignment.process",
                    "objectstore.export", "publish.postgres", "la.index.build"))
                assertEquals(Duration.ofHours(24), reg.deadline(id), id);
            for (String id : List.of("sample.hello", "report", "alert.evaluate"))
                assertEquals(Duration.ofMinutes(30), reg.deadline(id), id);
            assertTrue(reg.deadline(LaIndexBuildJob.TYPE).toSeconds() > LaIndexBuildJob.DEFAULT_TIMEOUT_SECONDS,
                    "la.index.build's own 3600 s wait still fires first");
            assertTrue(reg.deadline(PostgresPublishJobType.TYPE_ID).toSeconds() > 3600,
                    "publish.postgres's per-statement timeout (max 3600 s) still fires first");
        }
    }

    private static JobTypeRegistry registryOf(JobService js) throws Exception {
        var f = JobService.class.getDeclaredField("registry");
        f.setAccessible(true);
        return (JobTypeRegistry) f.get(js);
    }
}
