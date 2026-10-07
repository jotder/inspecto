package com.gamma.inspector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The engine side of a run lease's validity check (LEASE-TAKEOVER-INFLIGHT-1): the service layer registers
 * "is my claim still held?" for a pipeline while it runs, and the engine asks it at each commit point — the
 * Consignment's source finalisation ({@link ConsignmentIngestor#finalizeSource}) and the landing of acquired
 * files. A claim another node has taken over makes the commit throw {@link LeaseLostException}, so the batch
 * fails, nothing is registered, backed up or marked, and its files stay in the inbox for the new holder.
 *
 * <p>⚠ Keyed by <b>(scope, Space, pipeline)</b>: a pipeline id is unique only within a Space, and a run and
 * an acquisition hold separate leases. The Space is the thread's MDC Space, which the batch workers inherit.
 * No registration means no lease to lose (a CLI or test run), and the check passes.
 */
public final class CommitFence {

    private static final Logger log = LoggerFactory.getLogger(CommitFence.class);

    /** Which lease the check belongs to — an ingest run's, a remote acquisition's, or a Job run's (keyed by Run id). */
    public enum Scope { RUN, ACQUIRE, JOB }

    /** What a lease check found. UNREADABLE is not a verdict — the commit is refused, the claim is not written off. */
    public enum State { HELD, LOST, UNREADABLE }

    /** Thrown at a commit point when the pipeline's lease is lost (taken over) or could not be verified. */
    public static final class LeaseLostException extends IllegalStateException {
        LeaseLostException(String msg) { super(msg); }
    }

    /** A registration; closing it removes the check. */
    public interface Held extends AutoCloseable {
        @Override void close();
    }

    private static final Map<String, Supplier<State>> CHECKS = new ConcurrentHashMap<>();

    private CommitFence() {}

    private static String key(Scope scope, String pipeline) {
        return scope + "\u0000" + com.gamma.audit.EventLog.currentSpaceId() + "\u0000" + pipeline;
    }

    /** Register {@code state} for {@code pipeline} in the current thread's Space until the handle closes. */
    public static Held hold(Scope scope, String pipeline, Supplier<State> state) {
        String k = key(scope, pipeline);
        CHECKS.put(k, state);
        return () -> CHECKS.remove(k, state);
    }

    /** {@link #hold(Scope, String, Supplier)} for a plain held / not-held answer. */
    public static Held hold(Scope scope, String pipeline, BooleanSupplier stillHeld) {
        return hold(scope, pipeline, () -> stillHeld.getAsBoolean() ? State.HELD : State.LOST);
    }

    /** Refuse the commit unless {@code pipeline}'s registered lease is held; no registration means nothing to lose. */
    public static void check(Scope scope, String pipeline) {
        Supplier<State> c = CHECKS.get(key(scope, pipeline));
        State st = c == null ? State.HELD : c.get();
        if (st == State.HELD) return;
        if (st == State.UNREADABLE) {
            log.warn("Run lease for '{}' ({}) could not be read — the lease table is unreachable; refusing the "
                    + "commit without spending a retry attempt, the files stay in the inbox for the next cycle",
                    pipeline, scope);
            throw new LeaseLostException("lease unverifiable: '" + pipeline
                    + "' — the lease table could not be read; not committed, will retry next cycle");
        }
        log.warn("Run lease lost for '{}' ({}) — another node took it over; aborting without committing, the "
                + "files stay in the inbox for the new holder", pipeline, scope);
        throw new LeaseLostException("lease lost: '" + pipeline + "' was taken over by another node — not committed");
    }
}
