package com.gamma.la.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The index-build lifecycle (LA separation D-3 step 4, design 3.1): submit, progress, cancel, result, over
 * {@link IndexBuilder}. Host-free and modelled on {@code GraphRunService}: a bounded executor, an in-memory run table, a
 * terminal hook. It never sees a Subject and never calls a view gate - the caller passes a {@link RelationSource} that
 * already stands behind the gate, and the service takes the trusted relation SQL and base fingerprint from it.
 *
 * <p><b>States.</b> {@code QUEUED -> RUNNING -> COMPLETED | CANCELLED | FAILED}; the three terminals are final and
 * observable until retention drops them. A build that already published a version is {@code COMPLETED} even when a cancel
 * arrived in the same instant - the version exists, so the state says so.
 *
 * <p><b>One live build per (dataset, mapping).</b> A second start of the same index while one is queued or running is
 * refused ({@link Refused.Kind#DUPLICATE}); two builds of one directory would race for its version numbers.
 *
 * <p><b>Failure names a class, never a message.</b> {@code FAILED} carries the exception CLASS name only: the message of
 * a failed build can quote a value from the relation. Nothing else is stored from the failure.
 *
 * <p><b>Disk budget.</b> When {@link Request#maxDiskBytes()} is positive, {@link #submit} counts the relation's rows
 * ({@link IndexBuilder#countRows}) and refuses ({@link Refused.Kind#OVER_BUDGET}) an {@linkplain #estimateBytes estimate}
 * above it, with the estimate in the message, BEFORE a run exists or a byte is written. 0 = no limit and no extra pass.
 * The duplicate check runs FIRST (no I/O); the count is bounded by {@link #ESTIMATE_TIMEOUT_MS} (a timeout refuses,
 * {@link Refused.Kind#ESTIMATE_TIMEOUT} - the budget is never silently skipped) and at most {@link #MAX_CONCURRENT_ESTIMATES}
 * run at once ({@link Refused.Kind#ESTIMATE_BUSY}), so parallel submits cannot pin every request thread.
 */
public final class IndexBuildService implements AutoCloseable {

    /** Measured by D-3 step 1 (design 5.1): out + in + nodes took 34 bytes per edge at 10^8 edges. */
    public static final long BYTES_PER_EDGE = 34;

    public enum Status {
        QUEUED, RUNNING, COMPLETED, CANCELLED, FAILED;

        public boolean terminal() {
            return this != QUEUED && this != RUNNING;
        }
    }

    /**
     * Sizing and retention. {@code threads} builds run at once; {@code queue} more may wait; beyond that a submit is
     * refused. {@code maxRuns} bounds only FINISHED runs (retention never drops a live one).
     */
    public record Limits(int threads, int queue, long runTtlMs, int maxRuns) {
        public Limits {
            if (threads < 1 || queue < 1 || maxRuns < 1)
                throw new IllegalArgumentException("threads, queue and maxRuns must be >= 1");
        }

        public static Limits standard() {
            return new Limits(1, 4, 3_600_000L, 100);
        }
    }

    /**
     * What the caller's gate resolved: the trusted relation SELECT and a fingerprint of the base data it reads.
     * {@code deltaSql} (APPEND only) maps the added input files to the relation over just them; null = this Dataset cannot be appended to.
     */
    public record Relation(String relationSql, String baseFingerprint, List<IndexManifest.InputFile> inputFiles,
                           Function<List<String>, String> deltaSql) {
        public Relation {
            Objects.requireNonNull(relationSql, "relationSql");
        }

        public Relation(String relationSql, String baseFingerprint, List<IndexManifest.InputFile> inputFiles) {
            this(relationSql, baseFingerprint, inputFiles, null);
        }

        public Relation(String relationSql, String baseFingerprint) {
            this(relationSql, baseFingerprint, null, null);
        }
    }

    /**
     * Dataset id to its {@link Relation}, called ONCE, on the submitting thread, inside {@link #submit}. The implementation is
     * the caller's gate: it throws (unchecked) for a Dataset the caller may not build over, and the exception reaches
     * the caller unchanged - the service neither catches nor interprets it.
     */
    @FunctionalInterface
    public interface RelationSource {
        Relation resolve(String datasetId);
    }

    /**
     * One build to start. {@code maxDiskBytes} (0 = no limit) and {@code keepVersions} are the caller's CURRENT policy, read per
     * request, so a changed setting applies to the next build and not only to a freshly created service.
     */
    public record Request(String owner, String datasetId, IndexMapping mapping, RelationSource source, long maxDiskBytes,
                          int keepVersions, IndexBuilder.Mode mode) {
        /** A FULL build under the given policy. */
        public Request(String owner, String datasetId, IndexMapping mapping, RelationSource source, long maxDiskBytes, int keepVersions) {
            this(owner, datasetId, mapping, source, maxDiskBytes, keepVersions, IndexBuilder.Mode.FULL);
        }

        public Request {
            if (mode == null) mode = IndexBuilder.Mode.FULL;
            if (owner == null || owner.isBlank()) throw new IllegalArgumentException("a build needs an owner");
            if (datasetId == null || datasetId.isBlank()) throw new IllegalArgumentException("a build needs a dataset");
            Objects.requireNonNull(mapping, "mapping");
            Objects.requireNonNull(source, "source");
            if (maxDiskBytes < 0 || keepVersions < 1) throw new IllegalArgumentException("maxDiskBytes must be >= 0 and keepVersions >= 1");
        }

        /** The default policy: no disk limit, {@link IndexStore#DEFAULT_KEEP_VERSIONS} versions kept. */
        public Request(String owner, String datasetId, IndexMapping mapping, RelationSource source) {
            this(owner, datasetId, mapping, source, 0L, IndexStore.DEFAULT_KEEP_VERSIONS);
        }
    }

    /** Where a RUNNING build is (the builder's own callback): {@code phase} is "" until it reports one. */
    public record Progress(String phase, int step, int steps) { }

    /**
     * An immutable view of one build. {@code result} is non-null only for {@code COMPLETED}; {@code failure} (an exception
     * CLASS name) only for {@code FAILED}.
     */
    public record RunView(String id, String owner, String datasetId, String mappingHash, IndexBuilder.Mode mode, Status status, Progress progress,
                          boolean cancelRequested, String failure, IndexBuilder.Result result, long createdAt, long finishedAt,
                          long elapsedMs) { }

    /** Why the service refused to do what was asked. The message is safe to show: it names no row value. */
    public static final class Refused extends RuntimeException {
        public enum Kind { NOT_FOUND, FORBIDDEN, TERMINAL, REJECTED, DUPLICATE, OVER_BUDGET, ESTIMATE_TIMEOUT, ESTIMATE_BUSY, NOT_APPLICABLE }

        private final Kind kind;

        Refused(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    /** The longest a budget estimate (a full row count on the request thread) may run before the submit is refused. */
    public static final long ESTIMATE_TIMEOUT_MS = 10_000L;
    /** How many budget estimates may run at once in one service. */
    public static final int MAX_CONCURRENT_ESTIMATES = 2;
    private final java.util.concurrent.Semaphore estimates = new java.util.concurrent.Semaphore(MAX_CONCURRENT_ESTIMATES);
    private volatile long estimateTimeoutMs = ESTIMATE_TIMEOUT_MS;

    /** How many estimates are running right now (tests). */
    int estimatesInFlight() {
        return MAX_CONCURRENT_ESTIMATES - estimates.availablePermits();
    }

    /** Test seam: shortens the estimate timeout. */
    void estimateTimeoutMs(long ms) {
        this.estimateTimeoutMs = ms;
    }

    /** How long an idle worker thread lives before it ends (a new build starts a fresh one): an unused service holds no threads. */
    public static final long WORKER_KEEPALIVE_MS = 30_000L;
    /** A published version younger than this is never garbage-collected (design 2.4: the longest Graph Run lifetime, 1 h). */
    static final Duration GC_MIN_AGE = Duration.ofHours(1);

    private final Path indexRoot;
    private final Function<IndexBuilder.Request, IndexBuilder.Result> builder;
    private final Limits limits;
    private final LongSupplier clock;
    private final Consumer<RunView> onTerminal;
    private final ThreadPoolExecutor pool;
    private final Map<String, Run> runs = new LinkedHashMap<>();

    public IndexBuildService(Path indexRoot, Limits limits) {
        this(indexRoot, limits, System::currentTimeMillis, null);
    }

    /**
     * @param indexRoot  the directory holding every index of this Space ({@code <Space write root>/la-index})
     * @param clock      epoch millis; drives retention only (elapsed build time is monotonic)
     * @param onTerminal null = none; called exactly once per build by the thread that moves it to its terminal state, with the
     *                   final view, outside every lock; an exception it throws is swallowed - a hook never changes an outcome
     */
    public IndexBuildService(Path indexRoot, Limits limits, LongSupplier clock, Consumer<RunView> onTerminal) {
        this(indexRoot, limits, clock, onTerminal, WORKER_KEEPALIVE_MS);
    }

    IndexBuildService(Path indexRoot, Limits limits, LongSupplier clock, Consumer<RunView> onTerminal, long workerKeepAliveMs) {
        this(indexRoot, limits, clock, onTerminal, workerKeepAliveMs, IndexBuilder::build);
    }

    /** {@code builder} is a test seam: tests drive the state machine with a controllable fake; production is {@link IndexBuilder#build}. */
    public IndexBuildService(Path indexRoot, Limits limits, LongSupplier clock, Consumer<RunView> onTerminal, long workerKeepAliveMs,
                      Function<IndexBuilder.Request, IndexBuilder.Result> builder) {
        this.builder = builder;
        this.indexRoot = Objects.requireNonNull(indexRoot, "indexRoot");
        this.limits = limits;
        this.clock = clock;
        this.onTerminal = onTerminal;
        AtomicInteger n = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(limits.threads(), limits.threads(), workerKeepAliveMs, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(limits.queue()), r -> {
            Thread t = new Thread(r, "la-index-build-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        this.pool.allowCoreThreadTimeOut(true);
    }

    public Limits limits() {
        return limits;
    }

    public Path indexRoot() {
        return indexRoot;
    }

    /** How many builds are QUEUED or RUNNING right now; zero means the service holds nothing in flight. */
    public int active() {
        synchronized (runs) {
            int n = 0;
            for (Run r : runs.values()) if (!r.status().terminal()) n++;
            return n;
        }
    }

    /** When the most recent build finished (epoch millis of the service clock; 0 = none retained). */
    public long lastActivityMillis() {
        synchronized (runs) {
            long t = 0;
            for (Run r : runs.values()) if (r.status().terminal()) t = Math.max(t, r.finishedAt);
            return t;
        }
    }

    /** The disk a build over {@code rows} relation rows is expected to need: rows x {@value #BYTES_PER_EDGE} x 2 (the x2 is the safety factor). */
    public static long estimateBytes(long rows) {
        return Math.multiplyExact(Math.multiplyExact(rows, BYTES_PER_EDGE), 2L);
    }

    /**
     * Starts a build and returns its view at once ({@code QUEUED}).
     *
     * @throws Refused {@code DUPLICATE} (the same index is already building), {@code OVER_BUDGET} (the estimate is above
     *                 {@link Request#maxDiskBytes()}), {@code REJECTED} (every worker busy and the queue full)
     */
    public RunView submit(Request req) {
        Relation rel = req.source().resolve(req.datasetId());          // the caller's gate; throws for a dataset it may not build over
        String hash = req.mapping().hash();
        String key = new IndexStore(indexRoot, req.datasetId(), hash).directory().toString();   // the on-disk identity (case-folded, normalised)
        synchronized (runs) {                                           // cheap and I/O-free, so a duplicate never pays for the count
            sweepRuns(clock.getAsLong());
            Run dup = liveRun(key);
            if (dup != null) throw duplicate(dup, req.owner());
        }
        String estimateSql = rel.relationSql();
        if (req.mode() != IndexBuilder.Mode.FULL) {
            IndexPlan.Plan plan = precheck(req, rel);
            if (req.mode() == IndexBuilder.Mode.APPEND) estimateSql = rel.deltaSql().apply(plan.added());
        }
        if (req.maxDiskBytes() > 0 && req.mode() != IndexBuilder.Mode.COMPACT) {
            if (!estimates.tryAcquire())
                throw new Refused(Refused.Kind.ESTIMATE_BUSY, "too many index size estimates are running (" + MAX_CONCURRENT_ESTIMATES
                        + ") - try again shortly");
            long rows;
            try {
                rows = IndexBuilder.countRows(estimateSql, estimateTimeoutMs);
            } catch (IndexBuilder.EstimateTimeoutException e) {
                throw new Refused(Refused.Kind.ESTIMATE_TIMEOUT, "the index size estimate timed out after " + estimateTimeoutMs
                        + " ms, so index.max_disk_bytes = " + req.maxDiskBytes() + " cannot be checked; set index.max_disk_bytes to 0"
                        + " (no limit) to build without a budget, or build over a smaller Dataset");
            } finally {
                estimates.release();
            }
            long estimate = estimateBytes(rows);
            if (estimate > req.maxDiskBytes())
                throw new Refused(Refused.Kind.OVER_BUDGET, "the index is estimated at " + estimate + " bytes (" + rows + " rows x "
                        + BYTES_PER_EDGE + " bytes x 2), above index.max_disk_bytes = " + req.maxDiskBytes()
                        + "; raise the budget, or build over a smaller Dataset");
        }
        long now = clock.getAsLong();
        Run run = new Run("ib-" + UUID.randomUUID(), req, rel, hash, key, now);
        synchronized (runs) {
            sweepRuns(now);
            Run dup = liveRun(key);
            if (dup != null) throw duplicate(dup, req.owner());
            runs.put(run.id, run);
        }
        try {
            pool.execute(() -> execute(run));
        } catch (RejectedExecutionException e) {
            synchronized (runs) {
                runs.remove(run.id);
            }
            throw new Refused(Refused.Kind.REJECTED, "the index build queue is full (" + limits.threads() + " running, "
                    + limits.queue() + " waiting) - try again shortly");
        }
        return run.view();
    }

    /**
     * The submit-time gate of APPEND and COMPACT: the same {@link IndexPlan} the builder re-checks, so a request that cannot succeed
     * is refused at once ({@link Refused.Kind#NOT_APPLICABLE}, with the reason and the way forward) instead of becoming a FAILED run.
     * Returns the plan (null for COMPACT, which needs none).
     */
    private IndexPlan.Plan precheck(Request req, Relation rel) {
        IndexStore store = new IndexStore(indexRoot, req.datasetId(), req.mapping().hash());
        IndexManifest live;
        try {
            live = store.current().isEmpty() ? null : IndexManifest.read(store.current().get());
        } catch (IOException | IllegalArgumentException unreadable) {
            live = null;
        }
        if (live == null)
            throw new Refused(Refused.Kind.NOT_APPLICABLE, "the index has no readable published version to " + req.mode().name().toLowerCase(java.util.Locale.ROOT)
                    + " - run a full build first");
        if (req.mode() == IndexBuilder.Mode.COMPACT) {
            if (live.deltas().isEmpty()) throw new Refused(Refused.Kind.NOT_APPLICABLE, "the live version has no deltas to compact");
            return null;
        }
        String duck;
        try {
            duck = IndexBuilder.duckdbVersion();
        } catch (RuntimeException unknown) {
            duck = null;
        }
        IndexPlan.Plan plan = IndexPlan.classify(live, rel.inputFiles(), IndexBuilder.relationSqlHash(rel.relationSql()), BucketFunction.NAME, duck);
        if (!plan.appendable())
            throw new Refused(Refused.Kind.NOT_APPLICABLE, "an append is not possible now ("
                    + (plan.reasons().isEmpty() ? "no input file was added since the live version" : String.join(", ", plan.reasons())) + ") - "
                    + (plan.recommended() == IndexPlan.Action.COMPACT ? "compact first" : plan.reasons().isEmpty() ? "the index covers the Dataset" : "run a full build"));
        if (rel.deltaSql() == null)
            throw new Refused(Refused.Kind.NOT_APPLICABLE, "this Dataset's relation is not row-wise over its input files, so its index cannot be appended to"
                    + " - run a full build");
        return plan;
    }

    /** The queued or running build of this index key, or null. Caller holds {@code runs}. */
    private Run liveRun(String key) {
        for (Run r : runs.values())
            if (r.key.equals(key) && !r.status().terminal()) return r;
        return null;
    }

    /** The build id and status are the starter's: another caller (both share one key) is told only that a build is running. */
    private static Refused duplicate(Run r, String caller) {
        String what = r.owner.equals(caller)
                ? "an index build for this Dataset and mapping is already " + r.status().name().toLowerCase(java.util.Locale.ROOT) + " (" + r.id + ")"
                : "a build of this index is already running";
        return new Refused(Refused.Kind.DUPLICATE, what);
    }

    /** The build's current view. @throws Refused {@code NOT_FOUND} when it never existed or retention dropped it */
    public RunView get(String id) {
        return find(id).view();
    }

    /**
     * Asks a build to stop. A QUEUED build is cancelled at once and never starts; a RUNNING build is cancelled through the
     * builder's token (the running statement is cancelled too) and ends {@code CANCELLED} with nothing published; asking
     * twice is idempotent. Only the starter or an administrator may cancel.
     *
     * @throws Refused {@code NOT_FOUND}, {@code FORBIDDEN}, or {@code TERMINAL} when the build has already finished
     */
    public RunView cancel(String id, String caller, boolean admin) {
        Run r = find(id);
        if (!admin && !r.owner.equals(caller))
            throw new Refused(Refused.Kind.FORBIDDEN, "only the build's starter or an administrator may cancel it");
        if (r.requestCancel(clock.getAsLong())) fire(r);        // cancelled while queued: this call ended it
        return r.view();
    }

    /** Blocks up to {@code timeoutMs} for the build to finish and returns its view (still not terminal if the wait ran out). */
    public RunView await(String id, long timeoutMs) throws InterruptedException {
        Run r = find(id);
        r.done.await(timeoutMs, TimeUnit.MILLISECONDS);
        return r.view();
    }

    @Override
    public void close() {
        pool.shutdownNow();
        List<Run> live = new ArrayList<>();
        synchronized (runs) {
            for (Run r : runs.values()) if (!r.status().terminal()) live.add(r);
        }
        for (Run r : live) {            // stop the DuckDB statements of running builds; end the queued ones the pool dropped
            try {
                if (r.requestCancel(clock.getAsLong())) fire(r);
            } catch (Refused ignored) {
                // already finished
            }
        }
    }

    // -- execution ------------------------------------------------------------------------------------------------

    private void execute(Run r) {
        if (!r.start()) return;                                       // cancelled while queued
        long t0 = System.nanoTime();
        try {
            IndexStore store = new IndexStore(indexRoot, r.datasetId, r.mappingHash, r.keepVersions, java.time.Clock.systemUTC());
            IndexBuilder.Options opt = new IndexBuilder.Options(null, null, null, r.token,
                    p -> r.progress = new Progress(p.phase(), p.step(), p.steps()));
            IndexBuilder.Result result = builder.apply(new IndexBuilder.Request(r.datasetId, r.mapping, r.relation.relationSql(),
                    store, r.relation.baseFingerprint(), opt, r.relation.inputFiles(), r.mode, r.relation.deltaSql()));
            try {
                store.gc(GC_MIN_AGE);                                  // best effort: a failed sweep never fails a published build
            } catch (IOException | RuntimeException ignored) {
                // the next build sweeps again
            }
            end(r, Status.COMPLETED, null, result, (System.nanoTime() - t0) / 1_000_000L);
        } catch (IndexBuilder.CancelledException e) {
            end(r, Status.CANCELLED, null, null, (System.nanoTime() - t0) / 1_000_000L);
        } catch (RuntimeException | Error e) {
            // a cancel that surfaced as another exception (a closed connection) is still a cancel
            if (r.cancelRequested) end(r, Status.CANCELLED, null, null, (System.nanoTime() - t0) / 1_000_000L);
            else end(r, Status.FAILED, e.getClass().getSimpleName(), null, (System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private void end(Run r, Status s, String failure, IndexBuilder.Result res, long elapsed) {
        if (r.finish(s, failure, res, elapsed, clock.getAsLong())) fire(r);
    }

    private void fire(Run r) {
        if (onTerminal == null) return;
        try {
            onTerminal.accept(r.view());
        } catch (RuntimeException ignored) {
            // a hook never changes a build's outcome
        }
    }

    // -- run table --------------------------------------------------------------------------------------------------

    private Run find(String id) {
        synchronized (runs) {
            sweepRuns(clock.getAsLong());
            Run r = runs.get(id);
            if (r == null) throw new Refused(Refused.Kind.NOT_FOUND, "no index build '" + id + "' (it never existed, or was dropped after retention)");
            return r;
        }
    }

    /** Drops finished builds past their TTL, then the oldest finished ones while the table is over {@code maxRuns}. Caller holds {@code runs}. */
    private void sweepRuns(long now) {
        for (Iterator<Run> it = runs.values().iterator(); it.hasNext(); ) {
            Run r = it.next();
            if (r.status().terminal() && now - r.finishedAt >= limits.runTtlMs()) it.remove();
        }
        for (Iterator<Run> it = runs.values().iterator(); it.hasNext() && runs.size() >= limits.maxRuns(); ) {
            if (it.next().status().terminal()) it.remove();
        }
    }

    // -- one build ----------------------------------------------------------------------------------------------------

    private static final class Run {
        final String id, owner, datasetId, mappingHash, key;
        final IndexMapping mapping;
        final Relation relation;
        final int keepVersions;
        final IndexBuilder.Mode mode;
        final long createdAt;
        final IndexBuilder.CancelToken token = new IndexBuilder.CancelToken();
        final CountDownLatch done = new CountDownLatch(1);
        volatile Progress progress = new Progress("", 0, 0);
        volatile boolean cancelRequested;
        volatile long finishedAt;
        private Status status = Status.QUEUED;
        private String failure;
        private IndexBuilder.Result result;
        private long elapsedMs;

        Run(String id, Request req, Relation relation, String mappingHash, String key, long now) {
            this.id = id;
            this.owner = req.owner();
            this.datasetId = req.datasetId();
            this.mapping = req.mapping();
            this.relation = relation;
            this.keepVersions = req.keepVersions();
            this.mode = req.mode();
            this.mappingHash = mappingHash;
            this.key = key;
            this.createdAt = now;
        }

        synchronized Status status() {
            return status;
        }

        /** QUEUED to RUNNING; false when it was cancelled while queued. */
        synchronized boolean start() {
            if (status != Status.QUEUED) return false;
            status = Status.RUNNING;
            return true;
        }

        /** Returns true when this call ended the build (it was still QUEUED). */
        synchronized boolean requestCancel(long now) {
            if (status.terminal())
                throw new Refused(Refused.Kind.TERMINAL, "index build " + id + " already finished (" + status + ")");
            cancelRequested = true;
            if (status == Status.QUEUED) {
                finishLocked(Status.CANCELLED, null, null, 0, now);
                return true;
            }
            token.cancel();                      // RUNNING: the builder's flag + the running statement
            return false;
        }

        /** Returns true when this call moved the build to its terminal state. */
        synchronized boolean finish(Status s, String fail, IndexBuilder.Result res, long elapsed, long now) {
            if (status.terminal()) return false;
            finishLocked(s, fail, res, elapsed, now);
            return true;
        }

        private void finishLocked(Status s, String fail, IndexBuilder.Result res, long elapsed, long now) {
            status = s;
            failure = fail;
            result = res;
            elapsedMs = elapsed;
            finishedAt = now;
            done.countDown();
        }

        synchronized RunView view() {
            return new RunView(id, owner, datasetId, mappingHash, mode, status, progress, cancelRequested, failure, result, createdAt,
                    status.terminal() ? finishedAt : 0, elapsedMs);
        }
    }
}
