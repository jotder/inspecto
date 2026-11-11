package com.gamma.job;

import com.gamma.etl.ConsignmentEventBus;
import com.gamma.module.Entitlements;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PRODUCT-ASSEMBLY-1 PA-7b (D-PA24): a read-only install runs no Job - the fire is recorded SKIPPED with the reason - and runs
 * them again the moment it is no longer read-only. Read-only here is a trial build without its entitlement, the case that needs
 * no signed file.
 */
class JobServiceReadOnlyTest {

    @TempDir Path dir;

    @AfterEach
    void restore() {
        System.clearProperty(Entitlements.FILE_PROPERTY);
        Entitlements.reset();
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
    void aReadOnlyInstallRecordsAFireAsSkippedAndRunsAgainOnceItIsNot() throws Exception {
        System.setProperty(Entitlements.FILE_PROPERTY, dir.resolve("absent.toon").toString());
        Entitlements.reset();
        Entitlements.requireFile("trial build has no entitlement");
        JobConfig hb = new JobConfig("hb", JobType.MAINTENANCE, null, null, true, false, Map.of("task", "heartbeat"));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(hb), new ConsignmentEventBus(), s, null, dir.resolve("audit").toString())) {
            js.start();
            js.trigger("hb");
            JobRun skipped = await(() -> js.lastRunOf("hb").orElse(null), "SKIPPED");
            assertTrue(skipped.message().startsWith("read-only: this trial build has no entitlement"), skipped.message());

            Entitlements.reset();   // the trial requirement lifted (as a valid entitlement would): writable again
            assertTrue(js.trigger("hb"));
            assertEquals("SUCCESS", await(() -> js.lastRunOf("hb").orElse(null), "SUCCESS").status());
        }
    }
}
