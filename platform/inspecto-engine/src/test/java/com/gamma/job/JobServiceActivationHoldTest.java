package com.gamma.job;

import com.gamma.etl.ConsignmentEventBus;
import com.gamma.pipeline.ActivationGate;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SE-1b (D-SE12): a Job the installed activation check holds is not run by an automatic trigger — the fire is recorded
 * SKIPPED with the reason — while a manual run still goes (a held Job acts as a disabled one), and it runs again the
 * moment the check releases it.
 */
class JobServiceActivationHoldTest {

    @TempDir Path dir;

    @AfterEach
    void uninstall() {
        ActivationGate.install(null);
    }

    private static JobRun await(Supplier<JobRun> s, String status) throws Exception {
        for (int i = 0; i < 200; i++) {
            JobRun r = s.get();
            if (r != null && status.equals(r.status())) return r;
            Thread.sleep(25);
        }
        throw new AssertionError("no " + status + " run: last " + s.get());
    }

    @Test
    void aHeldJobSkipsItsScheduledFireRunsManuallyAndRunsAgainWhenReleased() throws Exception {
        AtomicReference<String> why = new AtomicReference<>("a dangling link");
        ActivationGate.install((space, kind, name) -> "job".equals(kind) && "hb".equals(name) ? why.get() : null);
        JobConfig hb = new JobConfig("hb", JobType.MAINTENANCE, null, null, true, false, Map.of("task", "heartbeat"));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(hb), new ConsignmentEventBus(), s, null, dir.resolve("audit").toString())) {
            js.start();

            js.submit("hb", "schedule");
            JobRun skipped = await(() -> js.lastRunOf("hb").orElse(null), "SKIPPED");
            assertEquals("held: a dangling link", skipped.message());
            assertEquals("schedule", skipped.trigger());

            assertTrue(js.trigger("hb"), "a manual run is not held, as for a disabled Job");
            assertEquals("manual", await(() -> js.lastRunOf("hb").orElse(null), "SUCCESS").trigger());

            why.set(null);   // released: no restart, the next fire runs
            js.submit("hb", "schedule");
            JobRun ran = await(() -> js.lastRunOf("hb").filter(r -> "schedule".equals(r.trigger())).orElse(null), "SUCCESS");
            assertEquals("schedule", ran.trigger());
        }
    }

    @Test
    void aCheckThatThrowsHoldsNothing() throws Exception {
        ActivationGate.install((space, kind, name) -> { throw new IllegalStateException("boom"); });
        assertNull(ActivationGate.reason("default", "job", "hb"));
    }

    @Test
    void onlyTheAutomaticTriggersAreHeld() {
        assertTrue(JobService.automatic("schedule"));
        assertTrue(JobService.automatic("catch-up"));
        assertTrue(JobService.automatic("event:orders"));
        assertTrue(JobService.automatic("signal:pipeline.commit"));
        assertFalse(JobService.automatic("manual"));
        assertFalse(JobService.automatic("manual:alice"));
        assertFalse(JobService.automatic(null));
    }
}
