package com.gamma.service;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the S4/E1 map-leak fix: within a single long-lived {@link CollectorService}
 * (i.e. per space), deleting a pipeline must prune its per-pipeline bookkeeping — the
 * {@link PipelineScheduler} cadence/coalescer maps and the {@code paused} set — so a space with high
 * pipeline churn (register/rename/delete without a restart) cannot accumulate orphan entries.
 *
 * <p>Space <em>teardown</em> was already clean (every space-id-keyed static map is unwound by
 * {@code SpaceManager.delete} → {@code CollectorService.close}); this test covers the narrower
 * pipeline-delete-within-a-space path that {@code unregisterPipeline} previously missed. The scheduler
 * maps are private, so — like {@link CollectorServiceIngestLockTest} — the test reads them reflectively.
 */
class CollectorServicePipelineForgetTest {

    private static final String CSV = "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n";

    private static Path source(Path root) throws Exception {
        Path toon = TestConfigs.csv(root, PipelineConfigBatchTest.miniSchema()).write();
        Path inbox = root.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"), CSV);
        return toon;
    }

    @Test
    void unregisterPrunesTheSchedulerCadenceEntry(@TempDir Path dir) throws Exception {
        Path a = source(dir);
        try (CollectorService svc = new CollectorService(List.of(a), 3600, 1)) {
            String id = svc.pipelines().get(0).name();
            svc.runAllOnce();                                    // a cycle stamps lastRunAtMs[id]
            Map<String, ?> cadence = cadenceMap(svc);
            assertTrue(cadence.containsKey(id), "a run should stamp the pipeline's cadence baseline");

            assertTrue(svc.unregisterPipeline(a.toAbsolutePath().normalize()));
            assertFalse(cadence.containsKey(id),
                    "unregisterPipeline must prune the cadence entry — otherwise it leaks under churn");
        }
    }

    @Test
    void unregisterDropsAPausedThenDeletedPipeline(@TempDir Path dir) throws Exception {
        Path a = source(dir);
        try (CollectorService svc = new CollectorService(List.of(a), 3600, 1)) {
            String id = svc.pipelines().get(0).name();
            assertTrue(svc.pause(id));
            Set<String> paused = pausedSet(svc);
            assertTrue(paused.contains(id), "precondition: the pipeline is paused");

            assertTrue(svc.unregisterPipeline(a.toAbsolutePath().normalize()));
            assertFalse(paused.contains(id),
                    "unregisterPipeline must drop the paused entry of a deleted pipeline");
        }
    }

    /**
     * Reference Phase-2 P3: a {@code produces: reference} pipeline with {@code refresh_seconds > 0} gets a
     * periodic compaction timer, and deleting it must cancel that timer — otherwise compaction of a store
     * whose pipeline is gone keeps firing forever (the same leak class as the cadence map above).
     */
    @Test
    void unregisterCancelsTheReferenceRefreshTimer(@TempDir Path dir) throws Exception {
        Path a = source(dir.resolve("plain"));
        Path b = referenceSource(dir.resolve("ref"));
        try (CollectorService svc = new CollectorService(List.of(a), 3600, 1)) {
            String id = svc.registerPipeline(b);
            Map<String, ?> timers = referenceRefreshTimers(svc);
            assertTrue(timers.containsKey(id),
                    "registering a reference producer with refresh_seconds should arm its compaction timer");
            var future = (java.util.concurrent.ScheduledFuture<?>) timers.get(id);

            assertTrue(svc.unregisterPipeline(b.toAbsolutePath().normalize()));
            assertFalse(timers.containsKey(id), "unregisterPipeline must prune the timer entry");
            assertTrue(future.isCancelled(), "…and actually cancel the scheduled task, not just forget it");
        }
    }

    /** A {@code refresh_seconds > 0} Reference producer — a plain CSV pipeline plus the P0 `reference:` block. */
    private static Path referenceSource(Path root) throws Exception {
        Path toon = source(root);
        Files.writeString(toon, Files.readString(toon).replace("name: TEST_ETL", "name: CUSTOMER_DIM")
                + """
                produces: reference
                reference:
                  load: upsert
                  key[1]: ID
                  refresh_seconds: 3600
                """);
        return toon;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> referenceRefreshTimers(CollectorService svc) throws Exception {
        Field f = CollectorService.class.getDeclaredField("referenceRefreshTimers");
        f.setAccessible(true);
        return (Map<String, ?>) f.get(svc);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> cadenceMap(CollectorService svc) throws Exception {
        Field schedField = CollectorService.class.getDeclaredField("pipelineScheduler");
        schedField.setAccessible(true);
        Object scheduler = schedField.get(svc);
        Field mapField = PipelineScheduler.class.getDeclaredField("lastRunAtMs");
        mapField.setAccessible(true);
        return (Map<String, ?>) mapField.get(scheduler);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> pausedSet(CollectorService svc) throws Exception {
        Field f = CollectorService.class.getDeclaredField("paused");
        f.setAccessible(true);
        return (Set<String>) f.get(svc);
    }
}
