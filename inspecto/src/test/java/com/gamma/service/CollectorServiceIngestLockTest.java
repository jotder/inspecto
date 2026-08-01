package com.gamma.service;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.inspector.MultiCollectorProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization tests for {@link PipelineRunGuard} — the <b>per-pipeline</b> ingest exclusion every
 * ingest entry path shares: the scheduled poll cycle ({@link CollectorService#runAllOnce()}) and the
 * operator trigger ({@link CollectorService#runPipeline(String)}).
 *
 * <p>Two invariants, and the difference between them is the whole point of the guard:
 * <ol>
 *   <li><b>Same pipeline ⇒ never overlaps.</b> A manual run waits for an in-flight run of that
 *       pipeline, so the second run re-reads the inbox only after the first has written its
 *       {@code .processed} markers — no double-ingest. (This is what the former global
 *       {@code ingestLock} actually protected.)</li>
 *   <li><b>Different pipelines ⇒ never block each other.</b> The old global lock was held across an
 *       entire cycle, so one slow pipeline delayed every other pipeline's run (head-of-line blocking).
 *       {@link #aSlowPipelineDoesNotBlockAnUnrelatedOne} is the regression guard for that fix — it is
 *       the test that fails if anyone re-widens the lock.</li>
 * </ol>
 *
 * <p>The guard is reflected out of the service and claimed <em>from the test thread</em>, with the
 * ingest call submitted to a separate executor. That ordering is load-bearing: claiming and then calling
 * ingest on the <em>same</em> thread would park the test thread itself on the claim it is holding, so the
 * test would hang instead of asserting.
 */
class CollectorServiceIngestLockTest {

    private static final String CSV = "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n";

    private static Path source(Path root) throws Exception {
        return source(root, "TEST_ETL");
    }

    /** A pipeline with a seeded inbox. Distinct {@code name}s matter: same id ⇒ one pipeline to the run guard. */
    private static Path source(Path root, String name) throws Exception {
        Path toon = TestConfigs.csv(root, PipelineConfigBatchTest.miniSchema()).name(name).write();
        Path inbox = root.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"), CSV);
        return toon;
    }

    private static long outputCsvCount(Path root) throws Exception {
        Path db = root.resolve("db");
        if (!Files.exists(db)) return 0;
        try (var s = Files.walk(db)) {
            return s.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".csv")).count();
        }
    }

    /** The one guard the poll cycle and every operator trigger claim from. Reflected so the test can hold a claim. */
    private static PipelineRunGuard runGuard(CollectorService svc) throws Exception {
        Field f = CollectorService.class.getDeclaredField("runGuard");
        f.setAccessible(true);
        return (PipelineRunGuard) f.get(svc);
    }

    @Test
    void manualTriggerBlocksWhileTheSamePipelineIsClaimed(@TempDir Path dir) throws Exception {
        Path a = source(dir.resolve("a"));
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (CollectorService svc = new CollectorService(List.of(a), 3600, 1)) {
            String name = svc.pipelines().get(0).name();
            PipelineRunGuard.Claim held = runGuard(svc).acquire(name);
            Future<?> trigger;
            try {
                trigger = ex.submit(() -> svc.runPipeline(name));
                assertThrows(TimeoutException.class, () -> trigger.get(500, MILLISECONDS),
                        "runPipeline must wait for an in-flight run of the SAME pipeline");
                assertEquals(0, outputCsvCount(dir.resolve("a")), "...and produce nothing while blocked");
            } finally {
                held.close();
            }
            trigger.get(5, SECONDS);
            assertTrue(outputCsvCount(dir.resolve("a")) >= 1, "the unblocked trigger produced output");
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void pollCycleSkipsAClaimedPipelineRatherThanBlocking(@TempDir Path dir) throws Exception {
        Path a = source(dir.resolve("a"));
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (CollectorService svc = new CollectorService(List.of(a), 3600, 1)) {
            String name = svc.pipelines().get(0).name();
            PipelineRunGuard.Claim held = runGuard(svc).acquire(name);
            try {
                // The cycle must SKIP a pipeline that is already running, not queue behind it: queueing
                // would pile runs up behind a slow pipeline, which is the backlog the cap exists to avoid.
                Future<MultiCollectorProcessor.RunResult> cycle = ex.submit(svc::runAllOnce);
                MultiCollectorProcessor.RunResult r = cycle.get(5, SECONDS);
                assertEquals(0, r.total(), "a claimed pipeline is skipped by the cycle, not run");
                assertEquals(0, outputCsvCount(dir.resolve("a")), "...and nothing was ingested");
            } finally {
                held.close();
            }
            assertEquals(1, svc.runAllOnce().total(), "once released it runs on the next cycle");
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void aSlowPipelineDoesNotBlockAnUnrelatedOne(@TempDir Path dir) throws Exception {
        Path a = source(dir.resolve("a"));                             // TEST_ETL — held below
        Path b = source(dir.resolve("b"), "TEST_ETL_B");                // unrelated pipeline
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (CollectorService svc = new CollectorService(List.of(a, b), 3600, 2)) {
            // Read the ids back from the service rather than assuming the config's literal casing —
            // the registered pipeline id is not necessarily the verbatim `name:` string.
            List<String> names = svc.pipelines().stream().map(p -> p.name()).toList();
            String aName = names.stream().filter(n -> !n.toUpperCase().endsWith("_B")).findFirst()
                    .orElseThrow(() -> new AssertionError("no non-_B pipeline in " + names));
            assertEquals(2, names.size(), "both pipelines registered under distinct ids: " + names);
            PipelineRunGuard.Claim held = runGuard(svc).acquire(aName);
            try {
                Future<MultiCollectorProcessor.RunResult> cycle = ex.submit(svc::runAllOnce);
                MultiCollectorProcessor.RunResult r = cycle.get(10, SECONDS);
                assertEquals(1, r.total(),
                        "the unrelated pipeline must still run while another is claimed (no head-of-line blocking)");
                assertTrue(outputCsvCount(dir.resolve("b")) >= 1, "the unrelated pipeline produced output");
                assertEquals(0, outputCsvCount(dir.resolve("a")), "the claimed pipeline did not run");
            } finally {
                held.close();
            }
        } finally {
            ex.shutdownNow();
        }
    }
}
