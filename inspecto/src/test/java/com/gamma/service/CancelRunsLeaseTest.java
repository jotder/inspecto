package com.gamma.service;

import com.gamma.inspector.CommitFence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HA-GRACEFUL-STOP-LEASE-RELEASE-1, lease side: a cancel never releases the lease - the run thread does, after
 * it has stopped - so a survivor claims strictly after the old writer is gone, and well inside the TTL; a run
 * that does not stop keeps the lease (TTL path).
 */
class CancelRunsLeaseTest {

    private static final Duration TTL = Duration.ofSeconds(60);

    private static String urlIn(Path dir) {
        return "jdbc:duckdb:" + dir.resolve("lease.db").toString().replace('\\', '/');
    }

    /** A stand-in run thread: "writes" while not cancelled, polls its claim like a checkpoint, releases in a finally. */
    private static Thread runThread(RunLease.Claim claim, AtomicLong writesAfterCancel, AtomicBoolean ended,
                                    AtomicBoolean cancelSeen, boolean cooperative) {
        return new Thread(() -> {
            try (claim) {
                while (true) {
                    if (cooperative && claim.cancelled()) { cancelSeen.set(true); return; }
                    if (cancelSeen.get()) writesAfterCancel.incrementAndGet();
                    if (!cooperative && ended.get()) return;
                    Thread.onSpinWait();
                }
            }
        });
    }

    @Test
    void gracefulCloseReleasesOnlyAfterTheRunThreadStoppedAndASurvivorTakesOverFast(@TempDir Path dir) throws Exception {
        String url = urlIn(dir);
        try (DbRunLease holderDb = DbRunLease.open(url, null, null, "s1", DbRunLease.SCOPE_RUN, "pod-a", TTL);
             DbRunLease survivor = DbRunLease.open(url, null, null, "s1", DbRunLease.SCOPE_RUN, "pod-b", TTL)) {
            RunLease.Claim claim = holderDb.tryAcquire("orders");
            assertNotNull(claim);
            AtomicLong writesAfterCancel = new AtomicLong();
            AtomicBoolean cancelSeen = new AtomicBoolean(), ended = new AtomicBoolean();
            Thread run = runThread(claim, writesAfterCancel, ended, cancelSeen, true);
            run.start();

            assertNull(survivor.tryAcquire("orders"), "the holder is mid-run: the survivor is refused");
            long t0 = System.nanoTime();
            holderDb.cancelRuns();                              // close(): cancel, then wait for the thread
            run.join(5_000);
            assertFalse(run.isAlive(), "the run stopped at its next checkpoint");
            RunLease.Claim taken = survivor.tryAcquire("orders");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("HA-CANCEL measured: cancel -> survivor holds the lease in " + ms + " ms (TTL "
                    + TTL.toMillis() + " ms)");
            assertNotNull(taken, "the claim was released once the thread stopped");
            assertTrue(ms < 5_000, "well under the TTL: " + ms + " ms");
            assertEquals(0, writesAfterCancel.get(), "the old run wrote nothing after the cancel point");
            taken.close();
        }
    }

    @Test
    void aRunThatDoesNotStopKeepsItsLeaseSoASurvivorIsStillRefused(@TempDir Path dir) throws Exception {
        String url = urlIn(dir);
        try (DbRunLease holderDb = DbRunLease.open(url, null, null, "s1", DbRunLease.SCOPE_RUN, "pod-a", TTL);
             DbRunLease survivor = DbRunLease.open(url, null, null, "s1", DbRunLease.SCOPE_RUN, "pod-b", TTL)) {
            RunLease.Claim claim = holderDb.tryAcquire("orders");
            AtomicBoolean ended = new AtomicBoolean(), cancelSeen = new AtomicBoolean();
            Thread run = runThread(claim, new AtomicLong(), ended, cancelSeen, false);   // ignores the cancel
            run.start();
            try {
                holderDb.cancelRuns();
                run.join(300);                                  // the bounded wait expires
                assertTrue(run.isAlive(), "harness: the run is still going");
                assertTrue(claim.cancelled());
                assertEquals(CommitFence.State.HELD, claim.state(), "cancel is not a lease verdict");
                assertNull(survivor.tryAcquire("orders"),
                        "the lease is NOT released while the writer lives - the TTL path, fail safe");
            } finally {
                ended.set(true);
                run.join(5_000);
            }
            RunLease.Claim after = survivor.tryAcquire("orders");
            assertNotNull(after, "and is released by the run itself once it finally ends");
            after.close();
        }
    }

    @Test
    void anUncancelledClaimIsUnchangedAndDoubleCloseIsHarmless() {
        RunLease lease = new PipelineRunGuard();
        RunLease.Claim c = lease.tryAcquire("p");
        assertEquals(CommitFence.State.HELD, c.state());
        assertFalse(c.cancelled());
        lease.cancelRuns();
        lease.cancelRuns();                                     // double close of the service
        assertTrue(c.cancelled());
        c.close();
        c.close();
        assertNotNull(lease.tryAcquire("p"), "released exactly once");
    }
}
