package com.gamma.job;

/**
 * Cross-process exclusion for a named unit of work, as {@link JobService} sees it — the engine-side seam
 * phase B of the enterprise scale-out plan (§5.2) needs so that "at most one of these at a time" can mean
 * <em>across pods</em> rather than only within one JVM.
 *
 * <h3>⚠ Why this interface exists at all rather than reusing {@code RunLease}</h3>
 * 🔴 {@code com.gamma.service.RunLease} — the lease B0/B1/B2 built — lives in the <b>inspecto</b> module,
 * and <b>inspecto depends on inspecto-engine</b>, not the other way round. {@code JobService} therefore
 * cannot name it. This is the narrow engine-side view of the same idea; the host adapts its lease onto it
 * at wiring time. ⛔ Do not "simplify" by moving {@code RunLease} down into the engine: it is bound to a
 * {@code SpaceRoot} and opens operational-DB families, neither of which the engine knows about.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>{@link #tryClaim} <b>never blocks</b> and returns {@code null} when the key is already held —
 *       a job run <b>skips</b>, never queues, exactly as the in-process {@code LockingRunner} does.</li>
 *   <li>A {@link Claim} is released exactly once, in a {@code finally}, by the thread that ran the work.
 *       ⛔ A claim is <b>not reentrant</b>.</li>
 * </ul>
 *
 * @since 5.x
 */
public interface RunClaims {

    /** A held claim. Released exactly once, in a {@code finally} — by whichever thread ran the work. */
    interface Claim extends AutoCloseable {
        @Override void close();
    }

    /**
     * Claim {@code key} without blocking.
     *
     * @return the claim, or {@code null} when {@code key} is already held (the caller must skip)
     */
    Claim tryClaim(String key);

    /**
     * The no-op default: every claim is granted. ⚠ This is what a <b>bare test constructor</b> gets, and
     * it means the in-process {@code LockingRunner} is the only exclusion — i.e. exactly the behaviour
     * before phase B. The live host always wires a real lease, so Personal and single-node Standard get
     * the heap-backed guard rather than this.
     */
    RunClaims GRANT_ALL = key -> () -> { };
}
