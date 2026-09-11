package com.gamma.service;

/**
 * Exclusion for one pipeline's run — the seam phase B of the enterprise scale-out plan (§5.2) extracts
 * from {@link PipelineRunGuard}, so that "at most one run of this pipeline at a time" can mean *across
 * processes* rather than only within one JVM.
 *
 * <p>Two implementations: {@link PipelineRunGuard} (a binary semaphore per pipeline — the default, and
 * what Personal and single-node Standard keep using unchanged) and {@link DbRunLease} (a row per
 * pipeline with a TTL, so a lease abandoned by a dead pod is reclaimable).
 *
 * <h3>⚠ The Space is bound at CONSTRUCTION, never passed per call — and that is the whole design</h3>
 * 🔴 A pipeline id is unique only <b>within a Space</b>. Today's guards get away with a bare pipeline-id
 * key purely because {@code CollectorService} (and therefore its guard) is instantiated once per Space:
 * the Space scoping is implicit in <em>which instance you are holding</em>, not in the key. A shared
 * lease table has no such boundary — two Spaces both running a pipeline called {@code orders} would
 * collide on one row and each would block the other's runs.
 *
 * <p>So a {@code RunLease} is <b>bound to one Space when it is created</b>, and its methods stay
 * {@code (pipeline)}. Two consequences, both deliberate:
 * <ul>
 *   <li>Every existing call site compiles unchanged — no Space argument threaded through the scheduler.</li>
 *   <li>An implementation <b>cannot forget</b> to scope its key, because it was handed the Space before
 *       it could answer a single question. ⛔ Do not "simplify" this into {@code tryAcquire(space, id)}:
 *       that is the shape that let {@code IntakeGovernor} ship process-wide state with no Space key.</li>
 * </ul>
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>{@link #tryAcquire} never blocks and returns {@code null} when the pipeline is already claimed —
 *       the poll cycle <b>skips</b>, never queues, or ticks pile runs up behind a slow pipeline.</li>
 *   <li>{@link #acquire} blocks until the pipeline is free — the operator-trigger path.</li>
 *   <li>A {@link Claim} is released exactly once, by whichever thread ran the work, in a
 *       {@code finally}. ⛔ A claim is <b>not reentrant</b>.</li>
 * </ul>
 *
 * @since 5.x
 */
interface RunLease {

    /** A held claim. Released exactly once, in a {@code finally} — by whichever thread ran the work. */
    interface Claim extends AutoCloseable {
        @Override void close();
    }

    /**
     * Claim {@code pipeline} without blocking.
     *
     * @return the claim, or {@code null} when this pipeline is already running (caller must skip it)
     */
    Claim tryAcquire(String pipeline);

    /** Claim {@code pipeline}, blocking until it is free. */
    Claim acquire(String pipeline);

    /** True when {@code pipeline} is currently claimed (diagnostics / tests — ⛔ never a gate). */
    boolean isRunning(String pipeline);

    /** Drop {@code pipeline}'s bookkeeping so nothing grows without bound as pipelines are unregistered. */
    void forget(String pipeline);
}
