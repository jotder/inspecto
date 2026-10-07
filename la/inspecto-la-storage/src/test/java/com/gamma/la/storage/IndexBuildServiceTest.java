package com.gamma.la.storage;

import com.gamma.la.storage.IndexBuildService.Limits;
import com.gamma.la.storage.IndexBuildService.Refused;
import com.gamma.la.storage.IndexBuildService.Relation;
import com.gamma.la.storage.IndexBuildService.RunView;
import com.gamma.la.storage.IndexBuildService.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The index-build lifecycle (D-3 step 4). The state-machine tests drive a controllable fake builder through the package-private
 * seam, so they wait on STATE (never on a fixed sleep); one test runs the real {@link IndexBuilder} end to end.
 */
class IndexBuildServiceTest {

    private static final IndexMapping M1 = new IndexMapping("s", "t", null, null, null, null, null);
    private static final IndexMapping M2 = new IndexMapping("s", "t", "k", null, null, null, null);
    private static final IndexMapping M3 = new IndexMapping("s", "t", "k", null, null, "w", null);
    private static final Relation REL = new Relation("SELECT 'a' AS s, 'b' AS t, 'x' AS k, 1.0 AS w", "fp");

    private final List<IndexBuildService> open = new CopyOnWriteArrayList<>();
    private final List<Fake> fakes = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() {
        fakes.forEach(f -> f.release = true);
        open.forEach(IndexBuildService::close);
    }

    /** A builder that blocks until released or cancelled (like the real one), then answers a canned result. */
    private class Fake implements Function<IndexBuilder.Request, IndexBuilder.Result> {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean release;
        volatile RuntimeException failWith;

        @Override
        public IndexBuilder.Result apply(IndexBuilder.Request req) {
            calls.incrementAndGet();
            IndexBuilder.CancelToken token = req.options().cancel();
            while (!release) {
                if (token.isCancelled()) throw new IndexBuilder.CancelledException();
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            if (failWith != null) throw failWith;
            return new IndexBuilder.Result(1, req.store().directory(), null, 2, 2, 0, 3, 16, Map.of(), 5);
        }
    }

    private IndexBuildService service(Path root, Limits limits, java.util.function.LongSupplier clock, List<RunView> terminals, Fake fake) {
        fakes.add(fake);
        IndexBuildService s = new IndexBuildService(root, limits, clock, terminals == null ? null : terminals::add, 1_000L, fake);
        open.add(s);
        return s;
    }

    private static Limits limits(int threads, int queue) {
        return new Limits(threads, queue, 3_600_000L, 100);
    }

    private static IndexBuildService.Request req(String owner, String dataset, IndexMapping m) {
        return new IndexBuildService.Request(owner, dataset, m, ds -> REL);
    }

    private static void until(BooleanSupplier c, String what) throws InterruptedException {
        long end = System.nanoTime() + 30_000_000_000L;
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(5);
        }
    }

    private static void running(IndexBuildService s, String id) throws InterruptedException {
        until(() -> s.get(id).status() == Status.RUNNING, "RUNNING");
    }

    // -- the real builder ------------------------------------------------------------------------------------------------

    @Test
    void aRealBuildCompletesPublishesAVersionAndTellsTheHookOnce(@TempDir Path tmp) throws Exception {
        List<RunView> terminals = new CopyOnWriteArrayList<>();
        IndexBuildService s = new IndexBuildService(tmp, Limits.standard(), System::currentTimeMillis, terminals::add);
        open.add(s);
        IndexBuildService.Request r = new IndexBuildService.Request("ana", "calls", M2, ds -> new Relation(
                "SELECT 'a' AS s, 'b' AS t, 'sms' AS k UNION ALL SELECT 'b', 'c', 'voice' UNION ALL SELECT 'a', 'c', NULL", "fp-1"));
        RunView v = s.submit(r);
        assertTrue(v.id().startsWith("ib-"));
        assertTrue(v.status() == Status.QUEUED || v.status() == Status.RUNNING || v.status() == Status.COMPLETED, v.status().toString());
        RunView done = s.await(v.id(), 60_000);
        assertEquals(Status.COMPLETED, done.status(), String.valueOf(done.failure()));
        assertEquals(3, done.result().edges());
        assertEquals(1, done.result().version());
        assertEquals("fp-1", done.result().manifest().baseFingerprint());
        assertEquals("calls", done.datasetId());
        assertEquals(M2.hash(), done.mappingHash());
        assertTrue(new IndexStore(tmp, "calls", M2.hash()).current().isPresent(), "the version is published under the index root");
        assertTrue(done.finishedAt() > 0);
        // a second build of the SAME index after the first finished is allowed and publishes the next version
        RunView again = s.await(s.submit(r).id(), 60_000);
        assertEquals(Status.COMPLETED, again.status());
        assertEquals(2, again.result().version());
        until(() -> terminals.size() >= 2, "the terminal hooks");
        assertEquals(2, terminals.size(), "exactly one terminal call per build");
        assertEquals(List.of(done.id(), again.id()), terminals.stream().map(RunView::id).sorted(java.util.Comparator.comparing(
                id -> id.equals(done.id()) ? 0 : 1)).toList());
    }

    @Test
    void aRealBuildOverAnEmptyRelationFailsAndNamesOnlyTheClass(@TempDir Path tmp) throws Exception {
        IndexBuildService s = new IndexBuildService(tmp, Limits.standard());
        open.add(s);
        RunView v = s.await(s.submit(new IndexBuildService.Request("ana", "empty", M1,
                ds -> new Relation("SELECT 'a' AS s, 'b' AS t WHERE 1 = 0", "fp"))).id(), 60_000);
        assertEquals(Status.FAILED, v.status());
        assertEquals("IndexBuildException", v.failure(), "the class, never the message");
        assertNull(v.result());
        assertTrue(new IndexStore(tmp, "empty", M1.hash()).current().isEmpty());
    }

    // -- the state machine -----------------------------------------------------------------------------------------------

    @Test
    void queuedThenRunningThenCompleted(@TempDir Path tmp) throws Exception {
        Fake f = new Fake();
        IndexBuildService s = service(tmp, limits(1, 2), System::currentTimeMillis, null, f);
        RunView v = s.submit(req("ana", "d", M1));
        assertTrue(v.status() == Status.QUEUED || v.status() == Status.RUNNING);
        running(s, v.id());
        assertFalse(s.get(v.id()).status().terminal());
        assertEquals(1, s.active());
        f.release = true;
        RunView done = s.await(v.id(), 30_000);
        assertEquals(Status.COMPLETED, done.status());
        assertEquals(0, s.active());
        assertNotNull(done.result());
    }

    @Test
    void aSecondStartOfTheSameIndexIsRefusedWhileOneIsLiveButAnotherMappingOrDatasetIsNot(@TempDir Path tmp) throws Exception {
        Fake f = new Fake();
        IndexBuildService s = service(tmp, limits(2, 4), System::currentTimeMillis, null, f);
        RunView first = s.submit(req("ana", "d", M1));
        Refused dup = assertThrows(Refused.class, () -> s.submit(req("bob", "d", M1)));
        assertEquals(Refused.Kind.DUPLICATE, dup.kind());
        assertFalse(dup.getMessage().contains(first.id()), "another viewer's build id is not leaked: " + dup.getMessage());
        assertTrue(assertThrows(Refused.class, () -> s.submit(req("ana", "d", M1))).getMessage().contains(first.id()),
                "the starter is told their own build id");
        assertDoesNotThrow(() -> s.submit(req("ana", "d", M2)), "another mapping is another index");
        assertDoesNotThrow(() -> s.submit(req("ana", "other", M1)), "another Dataset is another index");
        f.release = true;
        s.await(first.id(), 30_000);
        until(() -> s.active() == 0, "all builds finished");
        assertDoesNotThrow(() -> s.submit(req("ana", "d", M1)), "once the first finished, the same index may be built again");
    }

    private static final String SLOW_SQL = "SELECT CAST(a.range AS VARCHAR) AS s, CAST(b.range AS VARCHAR) AS t FROM range(100000000) a, "
            + "range(100000000) b WHERE (a.range * 31 + b.range) % 1000003 = 0";

    @Test
    void theDuplicateIsRefusedBeforeTheEstimateRuns(@TempDir Path tmp) {
        Fake f = new Fake();
        IndexBuildService s = service(tmp, limits(2, 4), System::currentTimeMillis, null, f);
        s.submit(req("ana", "d", M1));
        AtomicInteger resolves = new AtomicInteger();
        // a relation that cannot be counted: were the estimate to run first, this would fail with an IndexBuildException, not DUPLICATE
        Relation broken = new Relation("SELECT * FROM no_such_relation_anywhere", "fp");
        Refused dup = assertThrows(Refused.class, () -> s.submit(new IndexBuildService.Request("bob", "d", M1,
                ds -> {
                    resolves.incrementAndGet();
                    return broken;
                }, 1_000_000L, 2)));
        assertEquals(Refused.Kind.DUPLICATE, dup.kind());
        assertEquals(1, resolves.get(), "the gate ran, the count did not");
    }

    @Test
    void anEstimateThatTimesOutRefusesAndNeverSilentlySkipsTheBudget(@TempDir Path tmp) {
        Fake f = new Fake();
        IndexBuildService s = service(tmp, limits(1, 2), System::currentTimeMillis, null, f);
        s.estimateTimeoutMs(300);
        Relation slow = new Relation(SLOW_SQL, "fp");
        long t0 = System.nanoTime();
        Refused r = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> assertThrows(Refused.class,
                () -> s.submit(new IndexBuildService.Request("ana", "big", M1, ds -> slow, 1_000_000L, 2))));
        assertEquals(Refused.Kind.ESTIMATE_TIMEOUT, r.kind());
        assertTrue(r.getMessage().contains("timed out") && r.getMessage().contains("max_disk_bytes to 0"), r.getMessage());
        assertTrue((System.nanoTime() - t0) / 1_000_000L < 20_000L, "the statement was cancelled, not waited out");
        assertEquals(0, s.active());
        assertEquals(0, f.calls.get());
        assertEquals(0, s.estimatesInFlight(), "the permit is released after a timeout");
    }

    @Test
    void aBudgetOfZeroNeverCountsTheRelation(@TempDir Path tmp) {
        IndexBuildService s = service(tmp, limits(1, 2), System::currentTimeMillis, null, new Fake());
        Relation broken = new Relation("SELECT * FROM no_such_relation_anywhere", "fp");
        assertDoesNotThrow(() -> s.submit(new IndexBuildService.Request("ana", "d", M1, ds -> broken)), "max_disk_bytes = 0: no count");
    }

    @Test
    void estimatesAreCappedAndTheExtraCallerIsRefusedBusy(@TempDir Path tmp) throws Exception {
        Fake f = new Fake();
        IndexBuildService s = service(tmp, limits(1, 2), System::currentTimeMillis, null, f);
        s.estimateTimeoutMs(4_000);
        Relation slow = new Relation(SLOW_SQL, "fp");
        List<Thread> held = new ArrayList<>();
        for (int i = 0; i < IndexBuildService.MAX_CONCURRENT_ESTIMATES; i++) {
            String ds = "held" + i;
            Thread t = new Thread(() -> {
                try {
                    s.submit(new IndexBuildService.Request("ana", ds, M1, d -> slow, 1_000_000L, 2));
                } catch (RuntimeException ignored) {
                    // cancelled by close()
                }
            });
            t.setDaemon(true);
            t.start();
            held.add(t);
        }
        until(() -> s.estimatesInFlight() == IndexBuildService.MAX_CONCURRENT_ESTIMATES, "both estimates running");
        Refused busy = assertThrows(Refused.class, () -> s.submit(new IndexBuildService.Request("ana", "third", M1, d -> slow, 1_000_000L, 2)));
        assertEquals(Refused.Kind.ESTIMATE_BUSY, busy.kind());
    }

    @Test
    void theDuplicateKeyIsTheOnDiskIdentityNotTheSpelling(@TempDir Path tmp) throws Exception {
        Fake f = new Fake();
        IndexBuildService s = service(tmp, limits(2, 4), System::currentTimeMillis, null, f);
        s.submit(req("ana", "Calls", M1));
        assertEquals(Refused.Kind.DUPLICATE, assertThrows(Refused.class, () -> s.submit(req("ana", "calls", M1))).kind(),
                "'Calls' and 'calls' are ONE index directory (the store case-folds), so ONE live build");
    }

    @Test
    void cancellingARunningBuildEndsItCancelledAndTheHookHearsOnce(@TempDir Path tmp) throws Exception {
        List<RunView> terminals = new CopyOnWriteArrayList<>();
        Fake f = new Fake();
        IndexBuildService s = service(tmp, limits(1, 2), System::currentTimeMillis, terminals, f);
        RunView v = s.submit(req("ana", "d", M1));
        running(s, v.id());
        RunView c = s.cancel(v.id(), "ana", false);
        assertTrue(c.cancelRequested());
        RunView done = s.await(v.id(), 30_000);
        assertEquals(Status.CANCELLED, done.status());
        assertNull(done.result());
        assertNull(done.failure());
        until(() -> terminals.size() >= 1, "the terminal hook (it fires just after the state flips)");
        assertEquals(1, terminals.size());
        assertEquals(Status.CANCELLED, terminals.get(0).status());
        assertEquals(Refused.Kind.TERMINAL, assertThrows(Refused.class, () -> s.cancel(v.id(), "ana", false)).kind(), "a finished build cannot be cancelled");
    }

    @Test
    void aQueuedBuildIsCancelledAtOnceAndNeverReachesTheBuilder(@TempDir Path tmp) throws Exception {
        List<RunView> terminals = new CopyOnWriteArrayList<>();
        Fake f = new Fake();
        IndexBuildService s = service(tmp, limits(1, 2), System::currentTimeMillis, terminals, f);
        RunView first = s.submit(req("ana", "d1", M1));
        running(s, first.id());
        RunView queued = s.submit(req("ana", "d2", M1));
        assertEquals(Status.QUEUED, s.get(queued.id()).status());
        RunView c = s.cancel(queued.id(), "ana", false);
        assertEquals(Status.CANCELLED, c.status(), "ended by the cancel call itself");
        f.release = true;
        s.await(first.id(), 30_000);
        until(() -> s.active() == 0, "idle");
        assertEquals(1, f.calls.get(), "the cancelled queued build never ran");
        until(() -> terminals.size() >= 2, "both terminal hooks");
        assertEquals(2, terminals.size());
        assertEquals(1, terminals.stream().filter(t -> t.id().equals(queued.id())).count(), "its terminal call came once");
    }

    @Test
    void onlyTheStarterOrAnAdministratorMayCancelAndAnUnknownIdIsNotFound(@TempDir Path tmp) throws Exception {
        Fake f = new Fake();
        IndexBuildService s = service(tmp, limits(2, 2), System::currentTimeMillis, null, f);
        RunView v = s.submit(req("ana", "d", M1));
        running(s, v.id());
        assertEquals(Refused.Kind.FORBIDDEN, assertThrows(Refused.class, () -> s.cancel(v.id(), "bob", false)).kind());
        assertFalse(s.get(v.id()).cancelRequested(), "a refused cancel changed nothing");
        assertTrue(s.cancel(v.id(), "root", true).cancelRequested(), "an administrator may cancel");
        assertEquals(Refused.Kind.NOT_FOUND, assertThrows(Refused.class, () -> s.get("ib-nope")).kind());
        assertEquals(Refused.Kind.NOT_FOUND, assertThrows(Refused.class, () -> s.cancel("ib-nope", "ana", true)).kind());
    }

    @Test
    void aFullQueueIsRejectedAndTheRejectedBuildLeavesNothingBehind(@TempDir Path tmp) throws Exception {
        Fake f = new Fake();
        IndexBuildService s = service(tmp, limits(1, 1), System::currentTimeMillis, null, f);
        RunView a = s.submit(req("ana", "a", M1));
        running(s, a.id());
        s.submit(req("ana", "b", M1));                                   // fills the one waiting place
        Refused full = assertThrows(Refused.class, () -> s.submit(req("ana", "c", M1)));
        assertEquals(Refused.Kind.REJECTED, full.kind());
        assertTrue(full.getMessage().contains("queue is full"), full.getMessage());
        assertEquals(2, s.active(), "the rejected build is not in the table");
        f.release = true;
        until(() -> s.active() == 0, "idle");
        assertDoesNotThrow(() -> s.submit(req("ana", "c", M1)), "and the same index is not blocked as a duplicate of the rejected one");
    }

    @Test
    void aFailureNamesTheClassAndNeverTheMessage(@TempDir Path tmp) throws Exception {
        List<RunView> terminals = new CopyOnWriteArrayList<>();
        Fake f = new Fake();
        f.failWith = new IllegalStateException("row value 'MSISDN-9876543210' broke the copy");
        f.release = true;
        IndexBuildService s = service(tmp, limits(1, 2), System::currentTimeMillis, terminals, f);
        RunView v = s.await(s.submit(req("ana", "d", M1)).id(), 30_000);
        assertEquals(Status.FAILED, v.status());
        assertEquals("IllegalStateException", v.failure());
        assertFalse(v.toString().contains("9876543210"), "the view carries no value from the message");
        until(() -> terminals.size() == 1, "the terminal hook");
        assertFalse(terminals.get(0).toString().contains("9876543210"), "nor does the hook's view");
    }

    @Test
    void aThrowingHookNeverChangesTheOutcome(@TempDir Path tmp) throws Exception {
        Fake f = new Fake();
        f.release = true;
        fakes.add(f);
        IndexBuildService s = new IndexBuildService(tmp, limits(1, 2), System::currentTimeMillis, v -> {
            throw new IllegalStateException("audit down");
        }, 1_000L, f);
        open.add(s);
        assertEquals(Status.COMPLETED, s.await(s.submit(req("ana", "d", M1)).id(), 30_000).status());
    }

    @Test
    void theGateOfTheRelationSourceReachesTheCallerUnchangedAndNoRunExists(@TempDir Path tmp) {
        Fake f = new Fake();
        IndexBuildService s = service(tmp, limits(1, 2), System::currentTimeMillis, null, f);
        IllegalStateException gate = new IllegalStateException("no such dataset");
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> s.submit(new IndexBuildService.Request(
                "ana", "ghost", M1, ds -> { throw gate; })));
        assertTrue(thrown == gate, "the very exception the gate threw");
        assertEquals(0, s.active());
        assertEquals(0, f.calls.get());
    }

    // -- retention -------------------------------------------------------------------------------------------------------

    @Test
    void finishedBuildsAreDroppedAfterTheTtlButALiveOneNever(@TempDir Path tmp) throws Exception {
        AtomicLong now = new AtomicLong(1_000);
        Fake f = new Fake();
        IndexBuildService s = service(tmp, new Limits(2, 2, 10_000L, 100), now::get, null, f);
        RunView live = s.submit(req("ana", "live", M1));
        running(s, live.id());
        f.release = true;
        RunView done = s.await(s.submit(req("ana", "done", M1)).id(), 30_000);
        assertEquals(Status.COMPLETED, done.status());
        f.release = false;
        RunView live2 = s.submit(req("ana", "live2", M1));
        running(s, live2.id());
        now.addAndGet(9_999);
        assertEquals(Status.COMPLETED, s.get(done.id()).status(), "still inside the TTL");
        now.addAndGet(2);
        assertEquals(Refused.Kind.NOT_FOUND, assertThrows(Refused.class, () -> s.get(done.id())).kind(), "past the TTL");
        assertFalse(s.get(live2.id()).status().terminal(), "a live build is never dropped by retention");
    }

    @Test
    void theFinishedTableIsBoundedByMaxRuns(@TempDir Path tmp) throws Exception {
        Fake f = new Fake();
        f.release = true;
        IndexBuildService s = service(tmp, new Limits(1, 8, 3_600_000L, 3), System::currentTimeMillis, null, f);
        String first = null;
        for (int i = 0; i < 6; i++) {
            RunView v = s.await(s.submit(req("ana", "d" + i, M1)).id(), 30_000);
            if (first == null) first = v.id();
        }
        final String firstId = first;
        assertEquals(Refused.Kind.NOT_FOUND, assertThrows(Refused.class, () -> s.get(firstId)).kind(), "the oldest finished run was dropped");
    }

    // -- the disk budget -------------------------------------------------------------------------------------------------

    @Test
    void anEstimateOverTheBudgetIsRefusedUpFrontNamingTheEstimateAndNothingIsQueued(@TempDir Path tmp) {
        Fake f = new Fake();
        IndexBuildService s = service(tmp, limits(1, 2), System::currentTimeMillis, null, f);
        Relation thousand = new Relation("SELECT CAST(i AS VARCHAR) AS s, CAST(i + 1 AS VARCHAR) AS t FROM range(1000) r(i)", "fp");
        Refused over = assertThrows(Refused.class, () -> s.submit(new IndexBuildService.Request("ana", "big", M1, ds -> thousand, 1_000L, 2)));
        assertEquals(Refused.Kind.OVER_BUDGET, over.kind());
        long estimate = IndexBuildService.estimateBytes(1000);
        assertEquals(1000L * 34 * 2, estimate);
        assertTrue(over.getMessage().contains(String.valueOf(estimate)), over.getMessage());
        assertTrue(over.getMessage().contains("1000 rows") && over.getMessage().contains("max_disk_bytes = 1000"), over.getMessage());
        assertEquals(0, s.active());
        assertEquals(0, f.calls.get(), "refused before any worker ran");
    }

    @Test
    void anEstimateAtOrUnderTheBudgetPassesAndZeroMeansNoLimitAndNoCountingPass(@TempDir Path tmp) throws Exception {
        Fake f = new Fake();
        f.release = true;
        IndexBuildService exact = service(tmp.resolve("a"), limits(1, 2), System::currentTimeMillis, null, f);
        Relation thousand = new Relation("SELECT CAST(i AS VARCHAR) AS s, CAST(i + 1 AS VARCHAR) AS t FROM range(1000) r(i)", "fp");
        assertEquals(Status.COMPLETED, exact.await(exact.submit(new IndexBuildService.Request("ana", "ok", M1, ds -> thousand, 68_000L, 2)).id(), 30_000).status(),
                "an estimate equal to the budget is within it");
        // 0 = no limit: the relation is never counted, so SQL that would not even parse reaches the builder untouched
        IndexBuildService free = service(tmp.resolve("b"), limits(1, 2), System::currentTimeMillis, null, f);
        assertEquals(Status.COMPLETED, free.await(free.submit(new IndexBuildService.Request("ana", "free", M1,
                ds -> new Relation("THIS IS NOT SQL", "fp"))).id(), 30_000).status());
    }

    @Test
    void closeStopsRunningBuildsAndEndsQueuedOnesSoNoWaiterHangs(@TempDir Path tmp) throws Exception {
        List<RunView> terminals = new CopyOnWriteArrayList<>();
        Fake f = new Fake();
        IndexBuildService s = service(tmp, limits(1, 2), System::currentTimeMillis, terminals, f);
        RunView a = s.submit(req("ana", "a", M1));
        running(s, a.id());
        RunView b = s.submit(req("ana", "b", M1));
        s.close();
        assertEquals(Status.CANCELLED, s.await(a.id(), 30_000).status());
        assertEquals(Status.CANCELLED, s.await(b.id(), 30_000).status());
        until(() -> terminals.size() == 2, "both terminal calls");
    }

    /**
     * A builder that fails as soon as its thread is interrupted (as an interrupted DuckDB call does) must still end
     * CANCELLED at close(): the cancel must be recorded BEFORE the pool interrupts the worker. Deterministic: once close()
     * starts, the clock (read by close() before each cancel) stalls until the worker has reacted to its interrupt, so an
     * interrupt-first close() always loses the race this test pins.
     */
    @Test
    void closeRecordsTheCancelBeforeInterruptingSoAnInterruptFailureStillEndsCancelled(@TempDir Path tmp) throws Exception {
        java.util.concurrent.atomic.AtomicBoolean closing = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.CountDownLatch reacted = new java.util.concurrent.CountDownLatch(1);
        Fake f = new Fake() {
            @Override
            public IndexBuilder.Result apply(IndexBuilder.Request req) {
                try {
                    return super.apply(req);
                } finally {
                    reacted.countDown();
                }
            }
        };
        IndexBuildService s = service(tmp, limits(1, 2), () -> {
            if (closing.get() && Thread.currentThread().getName().equals("closer")) {
                try {
                    reacted.await(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return System.currentTimeMillis();
        }, null, f);
        RunView a = s.submit(req("ana", "a", M1));
        running(s, a.id());
        closing.set(true);
        Thread closer = new Thread(s::close, "closer");
        closer.start();
        closer.join(30_000);
        RunView end = s.await(a.id(), 30_000);
        assertEquals(Status.CANCELLED, end.status(), "failure=" + end.failure());
    }
}
