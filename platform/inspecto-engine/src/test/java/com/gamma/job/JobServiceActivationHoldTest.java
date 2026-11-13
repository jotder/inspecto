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

            // Released: no restart, the next fire runs. The manual run records SUCCESS before it frees its exclusive
            // slot, so a fire submitted at once may be SKIPPED "previous run still in flight" — fire until one runs.
            why.set(null);
            JobRun ran = null;
            for (int i = 0; i < 40 && ran == null; i++) {
                js.submit("hb", "schedule");
                for (int j = 0; j < 20 && ran == null; j++) {
                    JobRun r = js.lastRunOf("hb").orElse(null);
                    if (r != null && "schedule".equals(r.trigger()) && "SUCCESS".equals(r.status())) ran = r;
                    else if (r != null && "schedule".equals(r.trigger()) && "SKIPPED".equals(r.status())) {
                        assertFalse(r.message().startsWith("held: "), "released Job still held: " + r.message());
                        Thread.sleep(100);   // let the slot free before the next fire
                        break;
                    }
                    else Thread.sleep(25);
                }
            }
            assertNotNull(ran, "no scheduled run succeeded after the release: last " + js.lastRunOf("hb").orElse(null));
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
