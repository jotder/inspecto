package com.gamma.la.core;

import com.gamma.la.graph.GraphAborted;
import com.gamma.la.graph.RunControl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The graph-run lifecycle (LA separation D-4, design §3.1): submit → progress → cancel → result, over a
 * {@link GraphEngine}. LA-owned and host-free — a bounded executor, an in-memory run table and a result cache; nothing
 * of the platform's job service is used (D-4 Decision 1).
 *
 * <p><b>States.</b> {@code QUEUED → RUNNING → COMPLETED | CANCELLED | BUDGET_EXCEEDED | FAILED}; the four terminals are
 * final and observable until retention drops them.
 *
 * <p><b>Never a silent cap.</b> A run past its stated {@link GraphBudget} ends {@code BUDGET_EXCEEDED} carrying the
 * budget, the measured size and the reason, and <b>no result</b> — not a smaller answer shaped like a whole one. The size
 * is checked before any work; the deadline is checked at the engine's checkpoints, and once more when the engine returns
 * (an algorithm with no checkpoint, or one that finished past its deadline, is still over budget and its answer is
 * discarded). Only a {@code COMPLETED} result is ever stored or cached.
 *
 * <p><b>Cache.</b> A completed result is keyed by the working-set key, the caller's row-scope fingerprint, the
 * algorithm, the RESOLVED parameters and the weights spec — deterministic, so the same request is a hit. The scope
 * fingerprint is part of the key as a tripwire: today the key of a shared Relation does not vary with scope (the Working
 * Set is built from reads already filtered by the caller's row scope), but a result must never cross a scope boundary
 * if that ever changes. Results are RAW (unmasked); masking is the route's job, after the cache.
 *
 * <p>Thread-safe. Runs execute on daemon threads; {@link #close()} stops them.
 */
public final class GraphRunService implements AutoCloseable {

    public enum Status {
        QUEUED, RUNNING, COMPLETED, CANCELLED, BUDGET_EXCEEDED, FAILED;

        public boolean terminal() {
            return this != QUEUED && this != RUNNING;
        }
    }

    /** Why a run ended {@code BUDGET_EXCEEDED}. */
    public enum Exceeded { NODES, EDGES, TIMEOUT, WORK }

    /**
     * Sizing and retention. {@code threads} run concurrently; {@code queue} more may wait; beyond that a submit is refused.
     * {@code maxRuns} is SOFT by design: it bounds only FINISHED runs (retention never drops a live one), so the run table
     * holds at most {@code maxRuns + threads + queue} entries - the live part is already hard-bounded by the pool.
     */
    public record Limits(GraphBudget defaults, GraphBudget ceilings, int threads, int queue, long runTtlMs, int maxRuns,
                         long cacheTtlMs, int cacheEntries) {
        public static Limits standard() {
            return new Limits(new GraphBudget(50_000, 500_000, 30_000), new GraphBudget(500_000, 5_000_000, 300_000),
                    2, 16, 3_600_000L, 200, 600_000L, 32);
        }
    }

    /** One run to start. {@code input} is the materialised graph; {@code relationKey} identifies the Working Set it came from. */
    public record Request(String owner, String investigationId, String relationKey, String scopeFingerprint,
                          Algorithm algorithm, Map<String, ?> params, String weightsSpec, GraphInput input,
                          GraphBudget budget) {
        public Request {
            if (owner == null || owner.isBlank()) throw new IllegalArgumentException("a run needs an owner");
            if (algorithm == null || input == null) throw new IllegalArgumentException("a run needs an algorithm and an input");
            relationKey = relationKey == null ? "" : relationKey;
            scopeFingerprint = scopeFingerprint == null ? "" : scopeFingerprint;
            weightsSpec = weightsSpec == null ? "none" : weightsSpec;
            budget = budget == null ? GraphBudget.UNSTATED : budget;
        }
    }

    /** What a run has consumed so far: the input's size, the compute time and the engine's checkpoints. */
    public record Consumed(int nodes, int edges, long elapsedMs, long work) {}

    /**
     * Where a RUNNING run is: the engine's checkpoint count and the algorithm's own fraction. {@code known} is false for an
     * algorithm that never reports one (then {@code fraction} is 0 and means "unknown", not "just started"); a COMPLETED
     * run whose algorithm reports a fraction shows 1.
     */
    public record Progress(long work, double fraction, boolean known) {}

    /**
     * An immutable view of one run. {@code result} is non-null only when {@code status == COMPLETED}; {@code exceeded}
     * only for {@code BUDGET_EXCEEDED}; {@code failure} (an exception CLASS name — never its message, which could carry
     * an entity id) only for {@code FAILED}. {@code budgetClamped} says the request asked for more than a ceiling allows.
     * {@code relationKey} is the key of the Working Set the run was started on (what the audit trail names).
     */
    public record RunView(String id, String owner, String investigationId, String relationKey, Algorithm algorithm, String engine, Status status,
                          GraphBudget budget, boolean budgetClamped, Consumed consumed, Progress progress,
                          boolean cancelRequested, boolean cached, Exceeded exceeded, String failure, GraphResult result,
                          long createdAt, long finishedAt) {}

    private final GraphEngine engine;
    private final Limits limits;
    private final LongSupplier clock;
    private final Consumer<RunView> onTerminal;
    private final ThreadPoolExecutor pool;
    private final Map<String, Run> runs = new LinkedHashMap<>();
    private final Map<String, Cached> cache = new LinkedHashMap<>();

    public GraphRunService(GraphEngine engine, Limits limits) {
        this(engine, limits, System::currentTimeMillis);
    }

    /** {@code clock} (epoch millis) drives retention and cache expiry only; elapsed compute time is always monotonic. */
    public GraphRunService(GraphEngine engine, Limits limits, LongSupplier clock) {
        this(engine, limits, clock, null);
    }

    /**
     * {@code onTerminal} (null = none) is called exactly once per run, by the thread that moves it to its terminal
     * state - the submitting thread for a run that is terminal at submit or a cache hit, a worker otherwise, the
     * canceller for a run cancelled while queued - with the final view, outside every lock. It lets a caller audit the
     * end of an asynchronous run. An exception it throws is swallowed: a hook never changes a run's outcome.
     */
    public GraphRunService(GraphEngine engine, Limits limits, LongSupplier clock, Consumer<RunView> onTerminal) {
        this(engine, limits, clock, onTerminal, WORKER_KEEPALIVE_MS);
    }

    /** How long an idle worker thread lives before it ends (a new run starts a fresh one): an unused service holds no threads. */
    static final long WORKER_KEEPALIVE_MS = 30_000L;

    GraphRunService(GraphEngine engine, Limits limits, LongSupplier clock, Consumer<RunView> onTerminal, long workerKeepAliveMs) {
        this.engine = engine;
        this.limits = limits;
        this.clock = clock;
        this.onTerminal = onTerminal;
        AtomicInteger n = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(limits.threads(), limits.threads(), workerKeepAliveMs, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(limits.queue()), r -> {
            Thread t = new Thread(r, "la-graph-run-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        this.pool.allowCoreThreadTimeOut(true);
    }

    /**
     * How many runs are QUEUED or RUNNING right now. Zero means the service holds nothing in flight (finished runs may still
     * be retained), so an owner may close it when it has been unused for a while.
     */
    public int active() {
        synchronized (runs) {
            int n = 0;
            for (Run r : runs.values()) if (!r.status().terminal()) n++;
            return n;
        }
    }

    /**
     * When the most recent run of this service finished (epoch millis of the service clock; 0 = none retained). An owner
     * deciding to close an unused service must count this as activity: a finished run is still readable for the run TTL.
     */
    public long lastActivityMillis() {
        synchronized (runs) {
            long t = 0;
            for (Run r : runs.values()) if (r.status().terminal()) t = Math.max(t, r.finishedAt);
            return t;
        }
    }

    public GraphEngine engine() {
        return engine;
    }

    public Limits limits() {
        return limits;
    }

    /**
     * Starts a run and returns its view at once: {@code QUEUED}, a terminal {@code BUDGET_EXCEEDED} (the input is over
     * the budget — the engine is never reached), or a terminal {@code COMPLETED} straight from the cache.
     *
     * @throws InvalidGraphRequest the algorithm or its parameters are not runnable as asked, refused before a run exists
     * @throws GraphRunException   {@code REJECTED} when every worker is busy and the queue is full
     */
    public RunView submit(Request req) {
        Map<String, Object> params = req.algorithm().resolve(req.params());          // refuses BEFORE any state
        GraphBudget budget = req.budget().resolve(limits.defaults(), limits.ceilings());
        boolean clamped = limits.ceilings().clamps(req.budget());
        long now = clock.getAsLong();
        Run run = new Run("gr-" + UUID.randomUUID(), req, params, budget, clamped, now);

        int nodes = req.input().nodes().size(), edges = req.input().edges().size();
        if (nodes > budget.maxNodes() || edges > budget.maxEdges()) {
            end(run, Status.BUDGET_EXCEEDED, nodes > budget.maxNodes() ? Exceeded.NODES : Exceeded.EDGES, null, null, 0, now);
            run.release();
            return store(run).view();
        }
        String key = cacheKey(req, params);
        GraphResult hit = cached(key, now);
        if (hit != null) {
            run.cached = true;
            end(run, Status.COMPLETED, null, null, hit, 0, now);
            run.release();
            return store(run).view();
        }
        run.cacheKey = key;
        store(run);
        try {
            pool.execute(() -> execute(run));
        } catch (RejectedExecutionException e) {
            synchronized (runs) {
                runs.remove(run.id);
            }
            throw new GraphRunException(GraphRunException.Kind.REJECTED,
                    "the graph run queue is full (" + limits.threads() + " running, " + limits.queue() + " waiting) - try again shortly");
        }
        return run.view();
    }

    /** The run's current view. @throws GraphRunException {@code NOT_FOUND} when it never existed or retention dropped it */
    public RunView get(String id) {
        return find(id).view();
    }

    /** The caller's runs, newest first; {@code investigationId} null/blank = all of the caller's. */
    public List<RunView> list(String owner, String investigationId) {
        List<Run> mine = new ArrayList<>();
        synchronized (runs) {
            sweepRuns(clock.getAsLong());
            for (Run r : runs.values())
                if (r.owner.equals(owner) && (investigationId == null || investigationId.isBlank() || investigationId.equals(r.investigationId)))
                    mine.add(r);
        }
        List<RunView> out = new ArrayList<>(mine.size());
        for (Run r : mine) out.add(r.view());
        out.sort(Comparator.comparingLong(RunView::createdAt).reversed().thenComparing(RunView::id));
        return out;
    }

    /**
     * Asks a run to stop. A QUEUED run is cancelled at once and never reaches the engine; a RUNNING run stops at its next
     * checkpoint ({@code cancelRequested} is true in the meantime) and is idempotent to ask twice. Only the starter or an
     * administrator may cancel.
     *
     * @throws GraphRunException {@code NOT_FOUND}, {@code FORBIDDEN}, or {@code TERMINAL} when the run has already finished
     */
    public RunView cancel(String id, String caller, boolean admin) {
        Run r = find(id);
        if (!admin && !r.owner.equals(caller))
            throw new GraphRunException(GraphRunException.Kind.FORBIDDEN, "only the run's starter or an administrator may cancel it");
        if (r.requestCancel(clock.getAsLong())) fire(r);        // cancelled while queued: this call ended it
        return r.view();
    }

    /** Blocks up to {@code timeoutMs} for the run to finish and returns its view (still not terminal if the wait ran out). */
    public RunView await(String id, long timeoutMs) throws InterruptedException {
        Run r = find(id);
        r.done.await(timeoutMs, TimeUnit.MILLISECONDS);
        return r.view();
    }

    @Override
    public void close() {
        pool.shutdownNow();
        List<Run> waiting = new ArrayList<>();
        synchronized (runs) {
            for (Run r : runs.values()) if (r.status() == Status.QUEUED) waiting.add(r);
        }
        for (Run r : waiting) {                       // the pool dropped their tasks: end them, so no await() runs to its timeout
            try {
                r.requestCancel(clock.getAsLong());
            } catch (GraphRunException ignored) {
                // already moved on
            }
        }
    }

    // ── execution ────────────────────────────────────────────────────────────────────────────────────────────────

    private void execute(Run r) {
        RunControl ctl = r.start();
        if (ctl == null) return;                                       // cancelled while queued
        long t0 = System.nanoTime();
        try {
            GraphResult result = engine.run(r.algorithm, r.params, r.input, ctl);
            long elapsed = (System.nanoTime() - t0) / 1_000_000L;
            if (r.cancelRequested)
                end(r, Status.CANCELLED, null, null, null, elapsed, clock.getAsLong());
            else if (elapsed > r.budget.timeoutMs())
                end(r, Status.BUDGET_EXCEEDED, Exceeded.TIMEOUT, null, null, elapsed, clock.getAsLong());   // finished late: still over budget
            else if (end(r, Status.COMPLETED, null, null, result, elapsed, clock.getAsLong()))
                putCache(r.cacheKey, result);
        } catch (GraphAborted a) {
            long elapsed = (System.nanoTime() - t0) / 1_000_000L;
            long now = clock.getAsLong();
            switch (a.reason()) {
                case CANCELLED -> end(r, Status.CANCELLED, null, null, null, elapsed, now);
                case DEADLINE -> end(r, Status.BUDGET_EXCEEDED, Exceeded.TIMEOUT, null, null, elapsed, now);
                case BUDGET -> end(r, Status.BUDGET_EXCEEDED, Exceeded.WORK, null, null, elapsed, now);
            }
        } catch (RuntimeException | Error e) {
            end(r, Status.FAILED, null, e.getClass().getSimpleName(), null, (System.nanoTime() - t0) / 1_000_000L, clock.getAsLong());
        } finally {
            r.release();
        }
    }

    /** Moves {@code r} to a terminal state and, when this call did, tells the hook. */
    private boolean end(Run r, Status s, Exceeded ex, String fail, GraphResult res, long elapsed, long now) {
        boolean moved = r.finish(s, ex, fail, res, elapsed, now);
        if (moved) fire(r);
        return moved;
    }

    private void fire(Run r) {
        if (onTerminal == null) return;
        try {
            onTerminal.accept(r.view());
        } catch (RuntimeException ignored) {
            // a hook never changes a run's outcome
        }
    }

    // ── run table + cache ────────────────────────────────────────────────────────────────────────────────────────

    private Run store(Run run) {
        synchronized (runs) {
            sweepRuns(clock.getAsLong());
            runs.put(run.id, run);
        }
        return run;
    }

    private Run find(String id) {
        synchronized (runs) {
            sweepRuns(clock.getAsLong());
            Run r = runs.get(id);
            if (r == null) throw new GraphRunException(GraphRunException.Kind.NOT_FOUND, "no graph run '" + id + "' (it never existed, or was dropped after retention)");
            return r;
        }
    }

    /** Drops finished runs past their TTL, then the oldest finished runs while the table is over {@code maxRuns}. Caller holds {@code runs}. */
    private void sweepRuns(long now) {
        for (Iterator<Run> it = runs.values().iterator(); it.hasNext(); ) {
            Run r = it.next();
            if (r.status().terminal() && now - r.finishedAt >= limits.runTtlMs()) it.remove();
        }
        for (Iterator<Run> it = runs.values().iterator(); it.hasNext() && runs.size() >= limits.maxRuns(); ) {
            if (it.next().status().terminal()) it.remove();
        }
    }

    private record Cached(GraphResult result, long expires) {}

    private GraphResult cached(String key, long now) {
        synchronized (cache) {
            Cached c = cache.get(key);
            if (c == null) return null;
            if (c.expires() <= now) {
                cache.remove(key);
                return null;
            }
            return c.result();
        }
    }

    private void putCache(String key, GraphResult result) {
        synchronized (cache) {
            cache.remove(key);
            cache.put(key, new Cached(result, clock.getAsLong() + limits.cacheTtlMs()));
            for (Iterator<String> it = cache.keySet().iterator(); it.hasNext() && cache.size() > limits.cacheEntries(); ) {
                it.next();
                it.remove();
            }
        }
    }

    private static String cacheKey(Request r, Map<String, Object> resolved) {
        return r.relationKey() + '\u0001' + r.scopeFingerprint() + '\u0001' + r.algorithm().id() + '\u0001'
                + new TreeMap<>(resolved) + '\u0001' + r.weightsSpec();
    }

    // ── one run ──────────────────────────────────────────────────────────────────────────────────────────────────

    private final class Run {
        final String id, owner, investigationId, relationKey, weightsSpec;
        final Algorithm algorithm;
        final Map<String, Object> params;
        final GraphBudget budget;
        final boolean clamped;
        final long createdAt;
        final int nodes, edges;
        final CountDownLatch done = new CountDownLatch(1);
        volatile GraphInput input;                       // dropped when the run ends, so a finished run holds no graph
        volatile String cacheKey;
        volatile boolean cached;
        volatile boolean cancelRequested;
        volatile long finishedAt;
        private Status status = Status.QUEUED;
        private RunControl ctl;
        private long startNanos;                         // System.nanoTime() when execution began; guarded by this
        private Exceeded exceeded;
        private String failure;
        private GraphResult result;
        private long elapsedMs;

        Run(String id, Request req, Map<String, Object> params, GraphBudget budget, boolean clamped, long now) {
            this.id = id;
            this.owner = req.owner();
            this.investigationId = req.investigationId();
            this.relationKey = req.relationKey();
            this.weightsSpec = req.weightsSpec();
            this.algorithm = req.algorithm();
            this.params = params;
            this.budget = budget;
            this.clamped = clamped;
            this.createdAt = now;
            this.input = req.input();
            this.nodes = req.input().nodes().size();
            this.edges = req.input().edges().size();
        }

        synchronized Status status() {
            return status;
        }

        /** QUEUED → RUNNING and the live control (deadline counted from now); null if it was cancelled while queued. */
        synchronized RunControl start() {
            if (status != Status.QUEUED) return null;
            status = Status.RUNNING;
            startNanos = System.nanoTime();
            ctl = RunControl.withTimeout(budget.timeoutMs());
            return ctl;
        }

        /** Returns true when this call ended the run (it was still QUEUED). */
        synchronized boolean requestCancel(long now) {
            if (status.terminal())
                throw new GraphRunException(GraphRunException.Kind.TERMINAL, "graph run " + id + " already finished (" + status + ")");
            cancelRequested = true;
            if (status == Status.QUEUED) {
                finishLocked(Status.CANCELLED, null, null, null, 0, now);
                input = null;
                return true;
            }
            if (ctl != null) ctl.cancel();
            return false;
        }

        /** Moves to a terminal state unless already terminal; returns whether this call did. */
        synchronized boolean finish(Status s, Exceeded ex, String fail, GraphResult res, long elapsed, long now) {
            if (status.terminal()) return false;
            finishLocked(s, ex, fail, res, elapsed, now);
            return true;
        }

        private void finishLocked(Status s, Exceeded ex, String fail, GraphResult res, long elapsed, long now) {
            status = s;
            exceeded = ex;
            failure = fail;
            result = s == Status.COMPLETED ? res : null;     // a result exists ONLY for COMPLETED
            elapsedMs = elapsed;
            finishedAt = now;
            done.countDown();
        }

        void release() {
            input = null;
        }

        synchronized RunView view() {
            RunControl c = ctl;
            boolean known = c != null && c.fractionKnown();
            double fraction = !known ? 0 : status == Status.COMPLETED ? 1.0 : c.fraction();
            Progress p = new Progress(c == null ? 0 : c.work(), fraction, known);
            // live elapsed compute time for a RUNNING run (monotonic, from execution start - 0 while QUEUED); the final value after
            long elapsed = status == Status.RUNNING ? (System.nanoTime() - startNanos) / 1_000_000L : elapsedMs;
            return new RunView(id, owner, investigationId, relationKey, algorithm, engine.engineId(), status, budget, clamped,
                    new Consumed(nodes, edges, elapsed, p.work()), p, cancelRequested, cached, exceeded, failure,
                    result, createdAt, finishedAt);
        }
    }
}
