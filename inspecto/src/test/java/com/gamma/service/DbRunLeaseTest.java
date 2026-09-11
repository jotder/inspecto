package com.gamma.service;

import com.gamma.util.JdbcDrivers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DbRunLease} — phase B's cross-process exclusion.
 *
 * <p>⚠ Driven over <b>DuckDB</b>, not Postgres, so this coverage runs on every machine and in CI; the
 * Postgres round-trip lives in {@code PostgresStateStoreTest} and <b>skips entirely</b> without a
 * server. Both engines go through the same {@code JdbcDrivers.connect} and the SQL here is
 * dialect-neutral.
 *
 * <p>⚠ <b>Two leases over one DuckDB file, not one lease used twice</b> — that is what makes these tests
 * mean anything. A single instance would prove only that a map works; two instances sharing a database
 * are two <em>processes</em> as far as the lease is concerned, which is the entire claim.
 *
 * <p>⛔ <b>Known coverage gap, stated rather than papered over: the HEARTBEAT's fencing is not
 * independently tested.</b> {@code renewAll} carries the identical {@code owner = ? AND epoch = ?}
 * predicate that {@link #fencing_aStaleEpochCannotReleaseTheSameOwnersNewerLease} proves works, so it is
 * correct by construction — but a runtime test for it was attempted and DELETED: with both instances
 * heart-beating, the live renewer legitimately extends the lease, so the assertion could not separate
 * the stale renewer from the healthy one and failed even against correct code. A test that cannot
 * distinguish the thing it names is worse than an acknowledged gap. Closing it needs a seam to stop one
 * instance's renewer without closing its connection.
 */
class DbRunLeaseTest {

    private static final Duration TTL = Duration.ofMillis(1_500);

    private static String urlIn(Path dir) {
        return "jdbc:duckdb:" + dir.resolve("lease.db").toString().replace('\\', '/');
    }

    private static DbRunLease lease(String url, String space, String owner) throws Exception {
        return lease(url, space, DbRunLease.SCOPE_RUN, owner);
    }

    private static DbRunLease lease(String url, String space, String scope, String owner) throws Exception {
        return DbRunLease.open(url, null, null, space, scope, owner, TTL);
    }

    /** The base claim: one holder wins, a second process is refused. */
    @Test
    void oneProcessWinsAndTheOtherIsRefused(@TempDir Path dir) throws Exception {
        String url = urlIn(dir);
        try (DbRunLease podA = lease(url, "s1", "pod-a"); DbRunLease podB = lease(url, "s1", "pod-b")) {
            RunLease.Claim a = podA.tryAcquire("orders");
            assertNotNull(a, "the first process takes the lease");
            assertNull(podB.tryAcquire("orders"), "the second must be REFUSED — this is the whole feature");

            a.close();
            RunLease.Claim b = podB.tryAcquire("orders");
            assertNotNull(b, "and gets it once the holder releases");
            b.close();
        }
    }

    /**
     * 🔴 The property a bare pipeline-id key would break, and the reason the Space is bound at
     * construction. Two Spaces both running a pipeline called `orders` must not block each other.
     */
    @Test
    void twoSpacesWithTheSamePipelineNameDoNotBlockEachOther(@TempDir Path dir) throws Exception {
        String url = urlIn(dir);
        try (DbRunLease spaceOne = lease(url, "tenant-a", "pod-a");
             DbRunLease spaceTwo = lease(url, "tenant-b", "pod-a")) {
            RunLease.Claim a = spaceOne.tryAcquire("orders");
            RunLease.Claim b = spaceTwo.tryAcquire("orders");
            assertNotNull(a, "tenant-a claims its own orders pipeline");
            assertNotNull(b, "tenant-b's identically-named pipeline is a DIFFERENT pipeline and must "
                    + "not be blocked by tenant-a — keyed on the id alone, this is the IntakeGovernor bug");
            a.close();
            b.close();
        }
    }

    /**
     * 🔴 <b>The operator's decision of 2026-09-12, made testable.</b> Runs and remote acquisition are
     * deliberately independent activities — the scheduler holds two separate guards precisely so a slow
     * remote fetch does not stall a run. Sharing one lease key would silently collapse that: a fetch of
     * `orders` would block a run of `orders`.
     *
     * <p>⛔ If the {@code scope} column is ever removed from the key, THIS is the test that fails, and
     * the symptom in production would be pipelines mysteriously not running while an upstream is slow.
     */
    @Test
    void acquisitionAndExecutionDoNotBlockEachOther(@TempDir Path dir) throws Exception {
        String url = urlIn(dir);
        try (DbRunLease runs = lease(url, "s1", DbRunLease.SCOPE_RUN, "pod-a");
             DbRunLease acquires = lease(url, "s1", DbRunLease.SCOPE_ACQUIRE, "pod-a")) {

            RunLease.Claim fetching = acquires.tryAcquire("orders");
            assertNotNull(fetching, "a remote fetch of 'orders' starts");

            RunLease.Claim running = runs.tryAcquire("orders");
            assertNotNull(running,
                    "a RUN of the same pipeline must still be able to start — the two activities are "
                            + "independent, and collapsing the scope out of the key would block this");

            assertTrue(acquires.isRunning("orders"));
            assertTrue(runs.isRunning("orders"), "both are held at once, under different scopes");
            fetching.close();
            running.close();
        }
    }

    /** ...and within one scope the exclusion still holds, so the scope did not simply disable it. */
    @Test
    void theScopeDoesNotWeakenExclusionWithinAScope(@TempDir Path dir) throws Exception {
        String url = urlIn(dir);
        try (DbRunLease a = lease(url, "s1", DbRunLease.SCOPE_ACQUIRE, "pod-a");
             DbRunLease b = lease(url, "s1", DbRunLease.SCOPE_ACQUIRE, "pod-b")) {
            RunLease.Claim held = a.tryAcquire("orders");
            assertNotNull(held);
            assertNull(b.tryAcquire("orders"), "two acquisitions of one pipeline still exclude");
            held.close();
        }
    }

    /** A claim is not reentrant, in this process either. */
    @Test
    void aSecondClaimFromTheSameProcessIsRefused(@TempDir Path dir) throws Exception {
        try (DbRunLease pod = lease(urlIn(dir), "s1", "pod-a")) {
            RunLease.Claim first = pod.tryAcquire("orders");
            assertNotNull(first);
            assertNull(pod.tryAcquire("orders"), "a claim is deliberately not reentrant");
            first.close();
        }
    }

    /** Releasing twice must not hand the pipeline to two runners. */
    @Test
    void releaseIsIdempotent(@TempDir Path dir) throws Exception {
        try (DbRunLease pod = lease(urlIn(dir), "s1", "pod-a")) {
            RunLease.Claim c = pod.tryAcquire("orders");
            c.close();
            c.close();
            assertFalse(pod.isRunning("orders"));
            RunLease.Claim again = pod.tryAcquire("orders");
            assertNotNull(again, "still claimable after a double release");
            again.close();
        }
    }

    /**
     * A lease abandoned by a dead pod becomes reclaimable within the TTL — the property that makes the
     * standby automatic. Simulated by expiring the row directly, because the alternative is sleeping
     * through a real TTL in a unit test.
     */
    @Test
    void anExpiredLeaseIsReclaimedByAnotherProcess(@TempDir Path dir) throws Exception {
        String url = urlIn(dir);
        try (DbRunLease dead = lease(url, "s1", "pod-dead"); DbRunLease live = lease(url, "s1", "pod-live")) {
            RunLease.Claim held = dead.tryAcquire("orders");
            assertNotNull(held);
            assertNull(live.tryAcquire("orders"), "not yet — the lease is live");

            expire(url, "s1", "orders");

            assertFalse(dead.isRunning("orders"), "an expired lease does not read as running");
            RunLease.Claim stolen = live.tryAcquire("orders");
            assertNotNull(stolen, "a dead pod's lease must be reclaimable, or its pipelines freeze forever");
            stolen.close();
        }
    }

    /**
     * A paused pod's release must not free the lease a DIFFERENT owner now holds.
     *
     * <p>⚠ Note what actually blocks it here: the {@code owner} predicate, not the epoch — the two
     * processes have different owner ids. This test therefore does <b>not</b> exercise fencing, and a
     * mutation run proved it: defeating the epoch predicate left this test green. It is kept because the
     * scenario is the common one, but the fencing claim is carried by the test below.
     */
    @Test
    void aStaleOwnerCannotReleaseTheLeaseADifferentOwnerNowHolds(@TempDir Path dir) throws Exception {
        String url = urlIn(dir);
        try (DbRunLease paused = lease(url, "s1", "pod-paused"); DbRunLease taker = lease(url, "s1", "pod-taker")) {
            RunLease.Claim stale = paused.tryAcquire("orders");   // pod-paused holds it at epoch N
            assertNotNull(stale);

            expire(url, "s1", "orders");                          // ... and is paused past its TTL
            RunLease.Claim fresh = taker.tryAcquire("orders");    // pod-taker takes over at epoch N+1
            assertNotNull(fresh);

            stale.close();                                        // the paused pod finally wakes up

            assertTrue(taker.isRunning("orders"),
                    "the stale owner's release must be refused — it would otherwise free a lease another "
                            + "process is actively holding");
            assertNull(paused.tryAcquire("orders"), "and the lease is still not available to anyone else");
            fresh.close();
        }
    }

    /**
     * 🔴 <b>The fencing test — the epoch predicate is the ONLY thing that can pass this.</b>
     *
     * <p>Both leases carry the <b>same owner id</b>, which is the case the owner check cannot separate:
     * one pod whose connection dropped and was re-established, or any deployment that sets a stable owner
     * (a StatefulSet pod name is stable across restarts, and is a perfectly reasonable choice for
     * debuggability). The stale claim's {@code owner} matches the row exactly — so if the release is not
     * fenced on the epoch, it frees a lease that is genuinely held, and two runs of the pipeline proceed.
     *
     * <p>⚠ Verified by MUTATION on 2026-09-12: replacing the release predicate's {@code epoch = ?} with a
     * vacuous one (parameters still bound, so the statement still executes) leaves every other test in
     * this class green and fails only this one. ⛔ Do not delete it because "the owner check already
     * covers it" — it does not, and that is measured, not assumed.
     */
    @Test
    void fencing_aStaleEpochCannotReleaseTheSameOwnersNewerLease(@TempDir Path dir) throws Exception {
        String url = urlIn(dir);
        // ⚠ Same owner id on purpose — see the note above.
        try (DbRunLease first = lease(url, "s1", "pod-1"); DbRunLease second = lease(url, "s1", "pod-1")) {
            RunLease.Claim stale = first.tryAcquire("orders");            // epoch N
            assertNotNull(stale);

            expire(url, "s1", "orders");
            RunLease.Claim live = second.tryAcquire("orders");            // epoch N+1, SAME owner
            assertNotNull(live, "the expired lease is reclaimable");
            assertTrue(epochOf(url, "s1", "orders") > 0);

            stale.close();                                                // the stale holder wakes up

            assertTrue(second.isRunning("orders"),
                    "ONLY the epoch can refuse this release — the owner matches. Without fencing the "
                            + "live lease is freed and two runs of 'orders' proceed at once");
            live.close();
        }
    }

    /** The epoch must move on every takeover, or the fencing predicate has nothing to discriminate on. */
    @Test
    void theEpochAdvancesOnEveryAcquisition(@TempDir Path dir) throws Exception {
        String url = urlIn(dir);
        try (DbRunLease pod = lease(url, "s1", "pod-a")) {
            pod.tryAcquire("orders").close();
            long first = epochOf(url, "s1", "orders");
            pod.tryAcquire("orders").close();
            long second = epochOf(url, "s1", "orders");
            assertTrue(second > first, "epoch must be monotonic: " + first + " -> " + second);
        }
    }

    /**
     * ⛔ Fails CLOSED. An unreachable lease table must never read as "nobody holds it" — answering
     * "free" on an error is exactly the double-run this prevents.
     */
    @Test
    void anUnreachableLeaseTableRefusesTheRunRatherThanAllowingIt(@TempDir Path dir) throws Exception {
        DbRunLease pod = lease(urlIn(dir), "s1", "pod-a");
        pod.close();                                   // the connection is now shut
        assertNull(pod.tryAcquire("orders"),
                "with no database the answer must be 'refuse', never 'free'");
        assertFalse(pod.isRunning("orders"));
    }

    /** Different pipelines never contend — the exclusion is per pipeline, not global. */
    @Test
    void differentPipelinesDoNotContend(@TempDir Path dir) throws Exception {
        try (DbRunLease pod = lease(urlIn(dir), "s1", "pod-a")) {
            RunLease.Claim orders = pod.tryAcquire("orders");
            RunLease.Claim payments = pod.tryAcquire("payments");
            assertNotNull(orders);
            assertNotNull(payments, "a lease on one pipeline must not gate another");
            orders.close();
            payments.close();
        }
    }

    /**
     * ⛔ `forget` must NOT delete the row. The heap guard forgets to stop a map growing; here the row may
     * be another pod's LIVE lease, and this pod unregistering its copy of the pipeline is no reason to
     * free it.
     */
    @Test
    void forgetDoesNotDropAnotherProcessesLiveLease(@TempDir Path dir) throws Exception {
        String url = urlIn(dir);
        try (DbRunLease holder = lease(url, "s1", "pod-holder"); DbRunLease other = lease(url, "s1", "pod-other")) {
            RunLease.Claim c = holder.tryAcquire("orders");
            assertNotNull(c);

            other.forget("orders");

            assertTrue(holder.isRunning("orders"), "another pod's forget must not free a live lease");
            assertNull(other.tryAcquire("orders"));
            c.close();
        }
    }

    /** `acquire` blocks, then succeeds once the holder releases — the operator-trigger path. */
    @Test
    void acquireWaitsForTheHolderThenSucceeds(@TempDir Path dir) throws Exception {
        String url = urlIn(dir);
        try (DbRunLease holder = lease(url, "s1", "pod-holder"); DbRunLease waiter = lease(url, "s1", "pod-waiter")) {
            RunLease.Claim held = holder.tryAcquire("orders");
            assertNotNull(held);

            Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                held.close();
            });
            releaser.start();

            try (RunLease.Claim got = waiter.acquire("orders")) {
                assertNotNull(got, "acquire returns once the holder lets go");
            }
            releaser.join();
        }
    }

    // ── helpers that reach into the row, so a TTL need not be slept through ─────────

    private static void expire(String url, String space, String pipeline) throws Exception {
        try (Connection c = JdbcDrivers.connect(url, null, null);
             PreparedStatement ps = c.prepareStatement("UPDATE " + DbRunLease.TABLE
                     + " SET expires_at = 1 WHERE space = ? AND scope = ? AND pipeline = ?")) {
            ps.setString(1, space);
            ps.setString(2, DbRunLease.SCOPE_RUN);
            ps.setString(3, pipeline);
            ps.executeUpdate();
        }
    }

    private static long expiryOf(String url, String space, String pipeline) throws Exception {
        try (Connection c = JdbcDrivers.connect(url, null, null);
             PreparedStatement ps = c.prepareStatement("SELECT expires_at FROM " + DbRunLease.TABLE
                     + " WHERE space = ? AND scope = ? AND pipeline = ?")) {
            ps.setString(1, space);
            ps.setString(2, DbRunLease.SCOPE_RUN);
            ps.setString(3, pipeline);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        }
    }

    private static long epochOf(String url, String space, String pipeline) throws Exception {
        try (Connection c = JdbcDrivers.connect(url, null, null);
             PreparedStatement ps = c.prepareStatement("SELECT epoch FROM " + DbRunLease.TABLE
                     + " WHERE space = ? AND scope = ? AND pipeline = ?")) {
            ps.setString(1, space);
            ps.setString(2, DbRunLease.SCOPE_RUN);
            ps.setString(3, pipeline);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        }
    }
}
