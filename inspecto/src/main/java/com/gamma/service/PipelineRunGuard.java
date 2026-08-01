package com.gamma.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * <b>Per-pipeline ingest mutual exclusion.</b> Guarantees that two runs of the <em>same</em> pipeline
 * never overlap, while letting different pipelines run concurrently.
 *
 * <h3>Why this replaces the global ingest lock</h3>
 * The former single {@code ingestLock} in {@link CollectorService} was held for an entire poll cycle
 * across <em>every</em> active pipeline, so one slow pipeline delayed the next tick for all of them
 * (head-of-line blocking): a pipeline on a 5-second cadence waited behind a ten-minute one. But the
 * invariant that lock actually protected is <b>per-pipeline</b>, not global — it exists so a second
 * run cannot re-ingest the same inbox files before the first has written its {@code .processed}
 * markers. Narrowing the lock to the pipeline grain preserves that invariant exactly and drops the
 * over-broad serialisation.
 *
 * <p>Registry mutation ({@code registry} + {@link com.gamma.config.ConfigRegistry#rebuild}) keeps its
 * own short-held lock in {@link CollectorService} — it is not ingest exclusion and must not be
 * conflated with it.
 *
 * <h3>A claim is owned by a run, not by a thread — so not a {@code ReentrantLock}</h3>
 * {@link PipelineScheduler} takes a claim while <em>selecting</em> what to run (on the tick thread) and
 * releases it from the virtual thread that finished the run. A {@link java.util.concurrent.locks.ReentrantLock}
 * cannot express that: {@code unlock} from any thread but the locker throws
 * {@link IllegalMonitorStateException}. A binary {@link Semaphore} has no owner thread, so a claim can be
 * handed off with the work it guards.
 *
 * <p>Being non-reentrant is a second, deliberate gain. Under a reentrant lock, a run that re-entered its
 * own pipeline on the same thread — the shape a synchronous {@code BatchEventBus} delivery invites —
 * would <em>silently succeed</em> and double-ingest the inbox. Here it blocks instead: loud, and
 * recoverable, rather than quietly wrong. No production path re-enters (every trigger hand-off goes
 * through {@code triggerWorkers}); this only decides what happens if one is ever introduced.
 *
 * <h3>Two acquisition modes, deliberately different</h3>
 * <ul>
 *   <li>{@link #tryAcquire} — the <b>poll cycle</b>. A pipeline still running from an earlier tick is
 *       <em>skipped</em>, never queued: queueing would let ticks pile up runs behind a slow pipeline,
 *       which is the backlog the cycle is trying to avoid.</li>
 *   <li>{@link #acquire} — the <b>operator trigger</b> ({@code runPipeline}). Blocks until the
 *       pipeline is free, preserving today's documented behaviour: the waiting caller re-evaluates the
 *       inbox after acquiring, by which time the prior run's markers are durable, so already-ingested
 *       files are skipped rather than double-processed.</li>
 * </ul>
 *
 * <p>Distinct from {@link CollectorService}'s {@code running} set, which is <em>observability</em>
 * (what {@code InboxStatus} reports as in-flight). This class is the exclusion primitive; the set is a
 * readout. Do not use one for the other.
 *
 * <p>Permits are created on demand and retained per pipeline id. {@link #forget} drops one so the map
 * cannot leak under pipeline churn (the same reason {@code IntakeGovernor.forget} exists).
 */
final class PipelineRunGuard {

    /** One binary semaphore per pipeline id, created on first use. */
    private final Map<String, Semaphore> permits = new ConcurrentHashMap<>();

    /** A held claim. Released exactly once, in a {@code finally} — by whichever thread ran the work. */
    interface Claim extends AutoCloseable {
        @Override void close();
    }

    private Claim claimOf(String pipeline, Semaphore permit) {
        return new Claim() {
            /** Volatile because a claim is routinely taken on one thread and closed on another. */
            private volatile boolean released;
            @Override public void close() {
                // Idempotent, and that matters more here than under a lock: an unguarded second release
                // would raise the permit count to 2 and let two runs of this pipeline proceed at once.
                if (released) return;
                released = true;
                permit.release();
            }
            @Override public String toString() { return "Claim[" + pipeline + "]"; }
        };
    }

    private Semaphore permitFor(String pipeline) {
        return permits.computeIfAbsent(pipeline, k -> new Semaphore(1));
    }

    /**
     * Claim {@code pipeline} without blocking.
     *
     * @return the claim, or {@code null} when this pipeline is already running (caller must skip it)
     */
    Claim tryAcquire(String pipeline) {
        Semaphore permit = permitFor(pipeline);
        return permit.tryAcquire() ? claimOf(pipeline, permit) : null;
    }

    /** Claim {@code pipeline}, blocking until it is free. */
    Claim acquire(String pipeline) {
        Semaphore permit = permitFor(pipeline);
        permit.acquireUninterruptibly();
        return claimOf(pipeline, permit);
    }

    /** True when {@code pipeline} is currently claimed (diagnostics / tests — never a gate). */
    boolean isRunning(String pipeline) {
        Semaphore permit = permits.get(pipeline);
        return permit != null && permit.availablePermits() == 0;
    }

    /** Drop {@code pipeline}'s permit so the map cannot grow without bound as pipelines are unregistered. */
    void forget(String pipeline) {
        Semaphore permit = permits.get(pipeline);
        if (permit != null && permit.availablePermits() > 0) permits.remove(pipeline, permit);
    }
}
