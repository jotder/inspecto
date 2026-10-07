package com.gamma.job;

import com.gamma.etl.ConsignmentEventBus;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DUCKLE-C1 residual (1): the freshness sweep is a SYSTEM job — armed and disarmed by the platform,
 * listed like any other job, idempotent, and never allowed to clobber an authored job.
 */
class FreshnessSweepTest {

    private static JobService service(Path dir, List<JobConfig> configs) {
        return new JobService(configs, new ConsignmentEventBus(), new Scheduler(), null,
                dir.resolve("audit").toString());
    }

    @Test
    void armsAVisibleMinuteCadenceFreshnessScopedSystemJob(@TempDir Path dir) throws Exception {
        try (JobService js = service(dir, List.of())) {
            js.start();
            FreshnessSweep.reconcile(js, true);

            List<JobService.JobView> views = js.jobs();
            assertEquals(1, views.size());
            JobService.JobView v = views.get(0);
            assertEquals(FreshnessSweep.JOB_NAME, v.name());
            assertEquals("alert.evaluate", v.type());
            assertEquals("* * * * *", v.cron(), "a freshness limit is checked every minute");
            assertTrue(v.system(), "listed AS a system job — not a hidden thread, not an authored job");
            assertFalse(v.nextFire().isBlank(), "armed on the scheduler, so it has a next fire");
            assertEquals(Map.of("scope", "freshness"), js.jobConfig(FreshnessSweep.JOB_NAME).orElseThrow().params(),
                    "the narrow sweep — never the full ledger pass sixty times an hour");
            assertTrue(js.isSystemJob(FreshnessSweep.JOB_NAME));
        }
    }

    @Test
    void reArmingIsIdempotentAndDisarmingRemovesIt(@TempDir Path dir) throws Exception {
        try (JobService js = service(dir, List.of())) {
            js.start();
            FreshnessSweep.reconcile(js, true);
            FreshnessSweep.reconcile(js, true);
            FreshnessSweep.reconcile(js, true);
            assertEquals(1, js.jobs().size(), "every rule change re-derives the sweep; it must not multiply");

            FreshnessSweep.reconcile(js, false);
            assertTrue(js.jobs().isEmpty(), "the last maximumAge rule gone ⇒ the sweep is disarmed");
            assertFalse(js.has(FreshnessSweep.JOB_NAME));
            assertFalse(js.isSystemJob(FreshnessSweep.JOB_NAME));
            FreshnessSweep.reconcile(js, false);   // disarming twice is a no-op, not an error
        }
    }

    @Test
    void anAuthoredJobOfTheSameNameIsNeverClobberedOrRemoved(@TempDir Path dir) throws Exception {
        JobConfig authored = new JobConfig(FreshnessSweep.JOB_NAME, "maintenance", "0 2 * * *", null,
                true, false, Map.of("task", "heartbeat"), null, null);
        try (JobService js = service(dir, List.of(authored))) {
            js.start();
            FreshnessSweep.reconcile(js, true);
            assertEquals("maintenance", js.jobConfig(FreshnessSweep.JOB_NAME).orElseThrow().type(),
                    "an author's job wins; the platform refuses with a WARN instead of overwriting it");
            assertFalse(js.jobs().get(0).system());

            FreshnessSweep.reconcile(js, false);
            assertTrue(js.has(FreshnessSweep.JOB_NAME), "disarming the sweep must not delete an authored job");
        }
    }
}
