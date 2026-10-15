package com.gamma.la.storage;

import com.gamma.la.storage.IndexBuildService.Host;
import com.gamma.la.storage.IndexBuildService.Limits;
import com.gamma.la.storage.IndexBuildService.Refused;
import com.gamma.la.storage.IndexBuildService.Relation;
import com.gamma.la.storage.IndexBuildService.RunView;
import com.gamma.la.storage.IndexBuildService.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code LA-CONFIG-LIMITS-1} P4 on the build service: the install-wide resources ({@link Host}: memory limit, bucket sizing)
 * reach the builder when a build STARTS; a live service is {@linkplain IndexBuildService#retune retuned} to new pool sizes; and
 * a build WAITS while ingest runs (CL-12) - observed through its state, never through a sleep.
 */
class IndexBuildServiceHostTest {

    private static final IndexMapping M = new IndexMapping("s", "t", null, null, null, null, null);
    private static final Relation REL = new Relation("SELECT 'a' AS s, 'b' AS t", "fp");

    private final List<IndexBuildService> open = new CopyOnWriteArrayList<>();
    private final List<Probe> probes = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() {
        probes.forEach(p -> p.release = true);
        open.forEach(IndexBuildService::close);
    }

    /** A builder that records the options it was given and blocks until released or cancelled. */
    private static final class Probe implements Function<IndexBuilder.Request, IndexBuilder.Result> {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<IndexBuilder.Options> options = new AtomicReference<>();
        volatile boolean release;

        @Override
        public IndexBuilder.Result apply(IndexBuilder.Request req) {
            calls.incrementAndGet();
            options.set(req.options());
            while (!release) {
                if (req.options().cancel().isCancelled()) throw new IndexBuilder.CancelledException();
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return new IndexBuilder.Result(1, req.store().directory(), null, 2, 2, 0, 3, 16, Map.of(), 5);
        }
    }

    private IndexBuildService service(Path root, Limits limits, Probe probe, java.util.function.Supplier<Host> host, BooleanSupplier ingest) {
        probes.add(probe);
        IndexBuildService s = new IndexBuildService(root, limits, System::currentTimeMillis, null, 1_000L, probe, host, ingest);
        s.ingestWaitForTest(5, 60_000);
        open.add(s);
        return s;
    }

    private static Limits limits(int threads, int queue) {
        return new Limits(threads, queue, 3_600_000L, 100);
    }

    private static IndexBuildService.Request req(String dataset) {
        return new IndexBuildService.Request("ana", dataset, M, ds -> REL);
    }

    private static void until(BooleanSupplier c, String what) throws InterruptedException {
        long end = System.nanoTime() + 30_000_000_000L;
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(5);
        }
    }

    // -- CL-12: a build waits while ingest runs ---------------------------------------------------------------------------

    @Test
    void aBuildWaitsQueuedWhileIngestRunsAndStartsOnceItEnds(@TempDir Path tmp) throws Exception {
        AtomicBoolean ingest = new AtomicBoolean(true);
        Probe probe = new Probe();
        IndexBuildService s = service(tmp, Limits.standard(), probe, () -> Host.DEFAULT, ingest::get);
        String id = s.submit(req("calls")).id();
        until(() -> IndexBuildService.WAITING_FOR_INGEST.equals(s.get(id).progress().phase()), "the ingest wait");
        assertEquals(Status.QUEUED, s.get(id).status(), "a build waiting for ingest is queued, not refused and not running");
        assertEquals(0, probe.calls.get(), "the builder is never reached while ingest runs");

        ingest.set(false);
        until(() -> probe.calls.get() == 1, "the build to reach the builder once ingest ends");
        assertEquals(Status.RUNNING, s.get(id).status());
        probe.release = true;
        assertEquals(Status.COMPLETED, s.await(id, 30_000).status());
    }

    @Test
    void aBuildWaitingForIngestIsCancelledAtOnceAndOneThatWaitsTooLongFailsNamingIngest(@TempDir Path tmp) throws Exception {
        Probe probe = new Probe();
        IndexBuildService s = service(tmp, Limits.standard(), probe, () -> Host.DEFAULT, () -> true);
        String id = s.submit(req("one")).id();
        until(() -> IndexBuildService.WAITING_FOR_INGEST.equals(s.get(id).progress().phase()), "the ingest wait");
        assertEquals(Status.CANCELLED, s.cancel(id, "ana", false).status());

        s.ingestWaitForTest(5, 50);
        RunView gaveUp = s.await(s.submit(req("two")).id(), 30_000);
        assertEquals(Status.FAILED, gaveUp.status());
        assertEquals("IngestStillActiveException", gaveUp.failure());
        assertEquals(0, probe.calls.get(), "neither build ever reached the builder");
    }

    // -- host resources, read when a build starts ---------------------------------------------------------------------------

    @Test
    void theMemoryLimitAndBucketSizingInForceReachTheBuilderWhenABuildStarts(@TempDir Path tmp) throws Exception {
        AtomicReference<Host> host = new AtomicReference<>(new Host("123MiB", new BucketFunction.Sizing(2_000L, 4, 8)));
        Probe probe = new Probe();
        probe.release = true;
        IndexBuildService s = service(tmp, Limits.standard(), probe, host::get, () -> false);
        assertEquals(Status.COMPLETED, s.await(s.submit(req("a")).id(), 30_000).status());
        assertEquals("123MiB", probe.options.get().memoryLimit());
        assertEquals(new BucketFunction.Sizing(2_000L, 4, 8), probe.options.get().sizing());

        host.set(Host.DEFAULT);                                           // a changed setting applies to the NEXT build
        assertEquals(Status.COMPLETED, s.await(s.submit(req("a")).id(), 30_000).status());
        assertNull(probe.options.get().memoryLimit(), "no limit stated: the shared DuckDB cap");
        assertEquals(BucketFunction.Sizing.DEFAULT, probe.options.get().sizing());
    }

    @Test
    void theMemoryLimitIsTheShareOfTheRamAndUnknownRamStatesNone() {
        assertEquals("8192MiB", IndexBuildService.memoryLimit(80, 10L << 30));
        assertEquals("1024MiB", IndexBuildService.memoryLimit(100, 1L << 30));
        assertEquals("1MiB", IndexBuildService.memoryLimit(1, 1L << 20), "never below 1 MiB");
        assertNull(IndexBuildService.memoryLimit(80, 0), "unknown RAM: fall back to the shared cap, never guess");
    }

    @Test
    void aFullBuildRecordsItsSizingAndAChangedSizingRefusesAnAppendForAFullBuild(@TempDir Path tmp) throws Exception {
        AtomicReference<Host> host = new AtomicReference<>(new Host(null, new BucketFunction.Sizing(1_000L, 4, 4)));
        IndexBuildService s = new IndexBuildService(tmp, Limits.standard(), System::currentTimeMillis, null, 1_000L, IndexBuilder::build,
                host::get, () -> false);
        open.add(s);
        List<IndexManifest.InputFile> one = List.of(new IndexManifest.InputFile("p1", 1, 1));
        List<IndexManifest.InputFile> two = List.of(one.get(0), new IndexManifest.InputFile("p2", 2, 2));
        String sql = "SELECT 'a' AS s, 'b' AS t UNION ALL SELECT 'b', 'c'";
        RunView full = s.await(s.submit(new IndexBuildService.Request("ana", "calls", M, ds -> new Relation(sql, "fp", one))).id(), 60_000);
        assertEquals(Status.COMPLETED, full.status(), String.valueOf(full.failure()));
        assertEquals(4, full.result().buckets(), "the sizing in force chose the bucket count");
        assertEquals("1000/4/4", full.result().manifest().bucketSizing(), "and the manifest records it");

        IndexBuildService.Request append = new IndexBuildService.Request("ana", "calls", M,
                ds -> new Relation(sql, "fp2", two, added -> "SELECT 'c' AS s, 'd' AS t"), 0L, 2, IndexBuilder.Mode.APPEND);
        host.set(Host.DEFAULT);                                           // the system admin changed the sizing
        Refused refused = assertThrows(Refused.class, () -> s.submit(append));
        assertEquals(Refused.Kind.NOT_APPLICABLE, refused.kind());
        assertTrue(refused.getMessage().contains("bucket_sizing_changed") && refused.getMessage().contains("run a full build"), refused.getMessage());

        host.set(new Host(null, new BucketFunction.Sizing(1_000L, 4, 4)));   // the sizing the index was built with: the append is sound
        assertEquals(Status.COMPLETED, s.await(s.submit(append).id(), 60_000).status());
    }

    // -- pool sizes applied to a live service ------------------------------------------------------------------------------

    @Test
    void retuneResizesTheWaitingLineAndTheWorkersOfALiveServiceAndAShrinkDrains(@TempDir Path tmp) throws Exception {
        Probe probe = new Probe();
        IndexBuildService s = service(tmp, limits(1, 1), probe, () -> Host.DEFAULT, () -> false);
        String first = s.submit(req("d1")).id();
        until(() -> s.get(first).status() == Status.RUNNING, "the first build to run");
        String second = s.submit(req("d2")).id();
        Refused full = assertThrows(Refused.class, () -> s.submit(req("d3")));
        assertTrue(full.getMessage().contains("(1 running, 1 waiting)"), full.getMessage());

        s.retune(limits(1, 2));
        String third = s.submit(req("d3")).id();                          // the longer line takes it
        assertEquals(Status.QUEUED, s.get(third).status());

        s.retune(limits(1, 1));                                           // a shrink drains: both waiting builds stay
        assertEquals(Status.QUEUED, s.get(second).status());
        Refused shrunk = assertThrows(Refused.class, () -> s.submit(req("d4")));
        assertTrue(shrunk.getMessage().contains("(1 running, 1 waiting)"), shrunk.getMessage());

        s.retune(limits(3, 1));                                           // more workers: the waiting builds start now
        until(() -> s.get(second).status() == Status.RUNNING && s.get(third).status() == Status.RUNNING, "the waiting builds to start");
        assertEquals(limits(3, 1), s.limits());
        probe.release = true;
        for (String id : List.of(first, second, third)) assertEquals(Status.COMPLETED, s.await(id, 30_000).status());
    }
}
