package com.gamma.job;

import com.gamma.etl.ConsignmentEventBus;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MODULE-REORG-1 P4f}: the task half of the Space's module gate. A {@code maintenance} Job whose {@code task:}
 * belongs to a switched-off module is recorded SKIPPED (config untouched) and runs again once the module is on; another
 * task of the same type is never affected.
 */
class JobTaskGateTest {

    private static JobConfig job(String name, String task) {
        return new JobConfig(name, JobType.MAINTENANCE, null, null, true, false, Map.of("task", task));
    }

    private static JobRun awaitRun(JobService js, String name, String status) throws Exception {
        long end = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < end) {
            for (JobRun r : js.runsFor(name)) if (status.equals(r.status())) return r;
            Thread.sleep(50);
        }
        throw new AssertionError("no " + status + " run of " + name + ": " + js.runsFor(name));
    }

    @Test
    void aGatedTaskIsSkippedAndResumesWhileAnUngatedTaskIsUntouched(@TempDir Path dir) throws Exception {
        AtomicBoolean off = new AtomicBoolean(true);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job("gated", "noop"), job("free", "heartbeat")), new ConsignmentEventBus(), s,
                     null, dir.resolve("audit").toString())) {
            js.jobTaskGate(task -> "noop".equals(task) && off.get() ? "module 'ops' is switched off in this Space" : null);
            js.start();
            assertTrue(js.trigger("gated"));
            JobRun skipped = awaitRun(js, "gated", "SKIPPED");
            assertTrue(skipped.message().contains("switched off in this Space"), skipped.message());
            assertTrue(js.trigger("free"));
            assertEquals("SUCCESS", awaitRun(js, "free", "SUCCESS").status(), "another task of the same type still runs");
            assertEquals(2, js.jobs().size(), "no config is removed");

            off.set(false);   // the module is switched back on
            assertTrue(js.trigger("gated"));
            assertEquals("SUCCESS", awaitRun(js, "gated", "SUCCESS").status(), "resumes with nothing re-created");
        }
    }
}
