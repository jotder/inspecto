package com.gamma.service;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.inspector.MultiCollectorProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>The scale-out plan's §7 gate</b> — "one run per pipeline per trigger, across processes".
 *
 * <h3>What this proves that nothing else did</h3>
 * {@code CollectorServiceIngestLockTest.pollCycleSkipsAClaimedPipelineRatherThanBlocking} already proves a
 * poll cycle skips a claimed pipeline — but it reflects the guard <em>out of the same service</em> and
 * holds it in the same heap. {@code DbRunLeaseTest} proves two {@code DbRunLease} instances over one
 * database exclude each other — but at the lease, with no service attached. <b>Neither proves the wiring
 * in between</b>: that a really-booted {@code CollectorService}, which was never told another process
 * exists, honours a claim held by that other process.
 *
 * <p>That is the claim here. The holder is an independently-opened lease over the same Space — exactly
 * what a second pod looks like from this one — and the pod under test discovers it only through the
 * shared database.
 *
 * <h3>⚠ Three of §7's own premises were amended when this was written (2026-09-12)</h3>
 * <ol>
 *   <li>🔴 <b>No Postgres, and deliberately no Docker gate.</b> §7 says "against one Postgres", but the
 *       lease's {@code db} backend defaults to a local <b>DuckDB</b> file
 *       ({@code SpaceRoot.runLeaseDbUrl()}) and the SQL is dialect-neutral. Over a {@code @TempDir} this
 *       gate runs on every machine and every CI run. ⛔ Do not "restore" a Postgres gate: a gate that
 *       skips everywhere is how this one went unwritten while phases A and B shipped.</li>
 *   <li>🔴 <b>No classloader-per-pod harness.</b> §7/S4 assumed one was needed to stop two "pods" secretly
 *       sharing a heap. It is not needed <em>for this assertion</em>: the holder is a separate lease
 *       object over a separate connection, and the only channel between it and the pod under test is the
 *       database. ⚠ A shared heap cannot make this pass — see the mutation note below.</li>
 *   <li>🔴 <b>No latch fixture, because no race.</b> §7 frames the claim as two pods racing. Racing needs a
 *       run that can be held open, which does not exist. Holding the <em>claim</em> instead asserts the
 *       same invariant deterministically. ⛔ Do not rewrite this as a timing race; a flaky test in the
 *       gate position is worse than none.</li>
 * </ol>
 *
 * <h3>⛔ What this does NOT cover</h3>
 * The <b>operator-trigger</b> path ({@code /runs/{pipeline}/trigger}), which deliberately <b>blocks</b>
 * ({@code runGuard.acquire}) rather than skipping — so two operator triggers legitimately both complete
 * and assert nothing about exclusion. ⛔ Do not "extend" this test onto that route expecting a skip; the
 * blocking is the designed behaviour for an operator, and the exclusion claim lives on the cycle path.
 *
 * <p>⚠ Two full {@code ControlApi} instances over HTTP <b>are</b> covered, by
 * {@link #twoControlPlanesOverHttpBothSkipAHeldPipeline} — via {@code POST /trigger}, the HTTP door onto
 * the cycle path ({@code RunRoutes:128}). §7 assumed this needed a classloader harness and a latch
 * fixture; it needed neither.
 *
 * <h3>Why a degraded lease cannot fake a pass</h3>
 * ⚠ If the DB lease failed to open, both sides would fall back to <b>separate heap guards</b>, the holder
 * would gate nothing, the cycle would ingest, and {@link #aPodSkipsAPipelineAnotherProcessHolds} would
 * FAIL. Degradation breaks this test rather than hiding in it — which is the right direction.
 */
class RunLeaseContractTest {

    private static final String CSV = "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n";

    @AfterEach
    void clearBackend() {
        System.clearProperty("run.lease.backend");
        com.gamma.config.safety.DiscoveredRoots.clear();   // process-global — never leak to another test
    }

    /** A Space dir holding one pipeline with a seeded inbox, discovered the way the real boot discovers it. */
    private static void seedSpace(Path root, String id) throws Exception {
        Path config = root.resolve(id).resolve("config");
        Files.createDirectories(config);
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));
        Path inbox = config.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"), CSV);
    }

    /**
     * Output files this Space has produced, excluding the inbox (whose seed file is itself a {@code .csv}).
     *
     * <p>⚠ The measure of "ran once" has to be the DATA, not {@code RunResult.total()}: that counts
     * pipeline <b>runs</b> in a cycle, and two sequential cycles both legitimately run — the lease excludes
     * concurrent runs, never sequential ones. Asserting {@code total()} summed to 1 across two pods looked
     * right and was wrong; it cost a red test before the distinction was clear.
     */
    private static long outputCsvCount(Path spaceDir) throws Exception {
        if (!Files.exists(spaceDir)) return 0;
        try (var s = Files.walk(spaceDir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".csv"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/inbox/"))
                    .count();
        }
    }

    /**
     * {@code POST /trigger} — the HTTP door onto the POLL-CYCLE path ({@code RunRoutes:128} →
     * {@code runAllOnce}), which SKIPS a claimed pipeline.
     *
     * <p>⛔ Not {@code /runs/{pipeline}/trigger}: that is the operator path and it deliberately
     * <b>blocks</b> ({@code runGuard.acquire}), so two operator triggers legitimately both complete and
     * would assert nothing about exclusion.
     */
    private static int postTrigger(int port) throws Exception {
        java.net.http.HttpRequest req = java.net.http.HttpRequest
                .newBuilder(java.net.URI.create("http://localhost:" + port + "/api/v1/trigger"))
                .method("POST", java.net.http.HttpRequest.BodyPublishers.noBody())
                .build();
        return java.net.http.HttpClient.newHttpClient()
                .send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    /** A lease opened independently of any service — this is what another pod IS, from here. */
    private static RunLease foreignPod(Path root, String id) {
        return ServiceStores.openRunLease(SpaceRoot.under(root.resolve(id)), DbRunLease.SCOPE_RUN);
    }

    private static void close(RunLease lease) throws Exception {
        if (lease instanceof AutoCloseable c) c.close();
    }

    /**
     * 🔴 <b>The gate.</b> A pod that was never told another process exists must skip a pipeline that
     * process holds — and must run it once the holder lets go.
     *
     * <p>⚠ The cycle is submitted to another thread deliberately, the same ordering
     * {@code CollectorServiceIngestLockTest} documents: the poll path never blocks, but keeping the
     * claim-holding thread separate from the cycle keeps that a property under test rather than an
     * assumption of the harness.
     */
    @Test
    void aPodSkipsAPipelineAnotherProcessHolds(@TempDir Path root) throws Exception {
        System.setProperty("run.lease.backend", "db");   // ⚠ must precede discover: leases open at construction
        seedSpace(root, "alpha");

        ExecutorService ex = Executors.newSingleThreadExecutor();
        RunLease otherPod = null;
        try (SpaceManager pod = SpaceManager.discover(root)) {
            CollectorService svc = pod.space(SpaceId.of("alpha")).orElseThrow().service();
            String pipeline = svc.pipelines().get(0).name();

            otherPod = foreignPod(root, "alpha");
            RunLease.Claim held = otherPod.tryAcquire(pipeline);
            assertNotNull(held, "the other process took the lease first");

            Future<MultiCollectorProcessor.RunResult> cycle = ex.submit(svc::runAllOnce);
            assertEquals(0, cycle.get(10, SECONDS).total(),
                    "⛔ THE GATE: this pod never learned of the other process except through the lease "
                            + "database, and must skip rather than double-ingest");

            held.close();
            assertEquals(1, svc.runAllOnce().total(),
                    "...and the very same pipeline runs once the other process releases it — the lease "
                            + "gates the run, it does not poison the pipeline");
        } finally {
            close(otherPod);
            ex.shutdownNow();
        }
    }

    /**
     * ⛔ The discriminator that stops the test above passing for the wrong reason: an unrelated pipeline
     * name held by the other process must NOT gate this one. Without this, a lease that refused
     * everything would look identical to a lease that refuses the right thing.
     */
    @Test
    void anotherProcessHoldingADIFFERENTPipelineDoesNotGateThisOne(@TempDir Path root) throws Exception {
        System.setProperty("run.lease.backend", "db");
        seedSpace(root, "alpha");

        RunLease otherPod = null;
        try (SpaceManager pod = SpaceManager.discover(root)) {
            CollectorService svc = pod.space(SpaceId.of("alpha")).orElseThrow().service();

            otherPod = foreignPod(root, "alpha");
            RunLease.Claim held = otherPod.tryAcquire("some_other_pipeline");
            assertNotNull(held);
            try {
                assertEquals(1, svc.runAllOnce().total(),
                        "a claim on an unrelated pipeline must not stop this one — exclusion is "
                                + "per-pipeline, never fleet-wide");
            } finally {
                held.close();
            }
        } finally {
            close(otherPod);
        }
    }

    /**
     * 🔴 Two really-booted pods over one Space both honour the same held claim.
     *
     * <p>This is as close to §7's "two control planes" as is deterministic without a latch: two
     * independent {@link SpaceManager}s, each having booted the Space and opened its <b>own</b> lease, and
     * a third process holding the pipeline. ⛔ Both must skip. If either ran, that pod's guard was not
     * reaching the shared database — the exact defect the gate exists to catch.
     */
    @Test
    void twoBootedPodsBothHonourOneHeldClaim(@TempDir Path root) throws Exception {
        System.setProperty("run.lease.backend", "db");
        seedSpace(root, "alpha");

        RunLease otherPod = null;
        try (SpaceManager podA = SpaceManager.discover(root);
             SpaceManager podB = SpaceManager.discover(root)) {

            CollectorService a = podA.space(SpaceId.of("alpha")).orElseThrow().service();
            CollectorService b = podB.space(SpaceId.of("alpha")).orElseThrow().service();
            assertNotSame(a, b, "two discoveries are two independent services — not one shared instance");

            String pipeline = a.pipelines().get(0).name();
            otherPod = foreignPod(root, "alpha");
            RunLease.Claim held = otherPod.tryAcquire(pipeline);
            assertNotNull(held);
            try {
                assertEquals(0, a.runAllOnce().total(), "pod A honours the third process's claim");
                assertEquals(0, b.runAllOnce().total(), "pod B honours it too");
            } finally {
                held.close();
            }
            // ⚠ Once released, BOTH pods' cycles legitimately RUN — the lease excludes concurrent runs,
            // never sequential ones, and `RunResult.total()` counts pipeline RUNS, not ingestions. The
            // invariant that actually matters is on the DATA: the one file is ingested exactly once.
            assertEquals(1, a.runAllOnce().total(), "pod A takes the freed lease and runs");
            long afterA = outputCsvCount(root.resolve("alpha"));
            assertTrue(afterA >= 1, "pod A actually ingested the file, so the next assertion is not vacuous");

            b.runAllOnce();
            assertEquals(afterA, outputCsvCount(root.resolve("alpha")),
                    "⛔ pod B must not RE-INGEST a file pod A already consumed — more output here is the "
                            + "double-ingestion this whole workstream exists to prevent");
        } finally {
            close(otherPod);
        }
    }

    /**
     * 🔴 <b>§7's "two control planes", over real HTTP.</b> Two {@link com.gamma.control.ControlApi}
     * instances, each with its own {@link SpaceManager} and its own lease, both driven through
     * {@code POST /trigger} while a third process holds the pipeline. ⛔ Neither may ingest.
     *
     * <p>⚠ {@code startAll()} is deliberately NOT called: it runs a cycle immediately, which would ingest
     * the file BEFORE the claim is taken and leave this test asserting nothing. Nothing here runs on a
     * timer — every cycle is one explicit HTTP call, so the assertions are deterministic.
     *
     * <p>⚠ Asserted on OUTPUT rather than the response envelope: the data invariant is the one that
     * matters, and it keeps this test out of {@code com.gamma.control}'s package-private helpers (this
     * class must live in {@code com.gamma.service} to reach {@code ServiceStores}/{@code DbRunLease}).
     */
    @Test
    void twoControlPlanesOverHttpBothSkipAHeldPipeline(@TempDir Path root) throws Exception {
        System.setProperty("run.lease.backend", "db");
        seedSpace(root, "alpha");

        RunLease otherPod = null;
        try (SpaceManager podA = SpaceManager.discover(root);
             SpaceManager podB = SpaceManager.discover(root)) {

            com.gamma.control.ControlApi apiA = new com.gamma.control.ControlApi(podA, 0);
            com.gamma.control.ControlApi apiB = new com.gamma.control.ControlApi(podB, 0);
            apiA.start();
            apiB.start();
            try {
                assertNotEquals(apiA.port(), apiB.port(), "two control planes, two ports — really two");

                String pipeline = podA.space(SpaceId.of("alpha")).orElseThrow()
                        .service().pipelines().get(0).name();
                otherPod = foreignPod(root, "alpha");
                RunLease.Claim held = otherPod.tryAcquire(pipeline);
                assertNotNull(held, "a third process holds the pipeline");
                try {
                    assertTrue(postTrigger(apiA.port()) < 300, "pod A accepted the cycle request");
                    assertTrue(postTrigger(apiB.port()) < 300, "pod B accepted the cycle request");
                    assertEquals(0, outputCsvCount(root.resolve("alpha")),
                            "⛔ NEITHER control plane may ingest while another process holds the "
                                    + "pipeline — this is §7's one-run-per-trigger claim, end to end");
                } finally {
                    held.close();
                }

                assertTrue(postTrigger(apiA.port()) < 300);
                assertTrue(outputCsvCount(root.resolve("alpha")) >= 1,
                        "...and the work happens once the holder releases — the lease gated it, it did "
                                + "not break it");
            } finally {
                apiA.close();
                apiB.close();
                com.gamma.metrics.MetricRegistry.global().reset();   // process-wide: don't leak series
            }
        } finally {
            close(otherPod);
        }
    }
}
