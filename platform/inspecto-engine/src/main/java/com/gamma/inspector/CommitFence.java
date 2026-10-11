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
    public static class LeaseLostException extends IllegalStateException {
        LeaseLostException(String msg) { super(msg); }
    }

    /**
     * Thrown at a checkpoint or commit point when the host is closing and cancelled the run
     * (HA-GRACEFUL-STOP-LEASE-RELEASE-1). A {@link LeaseLostException} on purpose: every existing handler for
     * "the run stopped for a reason that is not this Consignment's fault" — no retry attempt spent, no
     * quarantine, no output cleanup (the deterministic batch id makes the next holder's re-plan overwrite
     * in place), inputs left in the inbox — is exactly what a cancelled run needs.
     */
    public static final class RunCancelledException extends LeaseLostException {
        RunCancelledException(String msg) { super(msg); }
    }

    /** A registration; closing it removes the check. */
    public interface Held extends AutoCloseable {
        @Override void close();
    }

    private static final Map<String, Supplier<State>> CHECKS = new ConcurrentHashMap<>();

    /** Cooperative-cancel signals (HA-GRACEFUL-STOP-LEASE-RELEASE-1), same key as {@link #CHECKS}. Deliberately
     *  separate from the lease state: the claim is still ours, the host is merely closing and asks the run to
     *  stop so the claim can be released safely. Cheap — a flag read, never a lease-table read. */
    private static final Map<String, BooleanSupplier> CANCELS = new ConcurrentHashMap<>();

    private CommitFence() {}

    private static String key(Scope scope, String pipeline) {
        return scope + "\u0000" + com.gamma.event.EventLog.currentSpaceId() + "\u0000" + pipeline;
    }

    /** Register {@code state} for {@code pipeline} in the current thread's Space until the handle closes. */
    public static Held hold(Scope scope, String pipeline, Supplier<State> state) {
        return hold(scope, pipeline, state, null);
    }

    /** As above, plus a {@code cancelled} signal the engine polls at its checkpoints ({@code null} = never cancelled). */
    public static Held hold(Scope scope, String pipeline, Supplier<State> state, BooleanSupplier cancelled) {
        String k = key(scope, pipeline);
        CHECKS.put(k, state);
        if (cancelled != null) CANCELS.put(k, cancelled);
        return () -> { CHECKS.remove(k, state); if (cancelled != null) CANCELS.remove(k, cancelled); };
    }

    /** {@link #hold(Scope, String, Supplier)} for a plain held / not-held answer. */
    public static Held hold(Scope scope, String pipeline, BooleanSupplier stillHeld) {
        return hold(scope, pipeline, () -> stillHeld.getAsBoolean() ? State.HELD : State.LOST);
    }

    /** Refuse the commit unless {@code pipeline}'s registered lease is held; no registration means nothing to lose. */
    public static void check(Scope scope, String pipeline) {
        checkpoint(scope, pipeline);   // a cancel is refused at every commit point too
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

    /**
     * A cooperative-cancellation checkpoint (HA-GRACEFUL-STOP-LEASE-RELEASE-1): throws {@link RunCancelledException}
     * only when the host cancelled the run; a lost or unreadable lease is ignored here (those are verdicts for the
     * commit points, which keep calling {@link #check}, and which also refuse a cancel). Placed before an output write so a cancelled run stops
     * writing; safety does NOT depend on its density — the claim is released only after the run thread ends.
     */
    public static void checkpoint(Scope scope, String pipeline) {
        BooleanSupplier c = CANCELS.get(key(scope, pipeline));
        if (c != null && c.getAsBoolean()) throw cancelled(scope, pipeline);
    }

    /**
     * The registered cancel signal itself (never {@code null}; "never cancelled" when none), resolved NOW on the
     * calling thread's Space. For a loop that must stop between units without throwing, and for handing to worker
     * threads that do not carry the caller's MDC Space (HA-CANCEL-RESIDUALS-1 (2), the remote fetch).
     */
    public static BooleanSupplier cancelSignal(Scope scope, String pipeline) {
        BooleanSupplier c = CANCELS.get(key(scope, pipeline));
        return c != null ? c : () -> false;
    }

    private static RunCancelledException cancelled(Scope scope, String pipeline) {
        log.info("Run '{}' ({}) cancelled — the host is closing; stopping without committing, the files stay in "
                + "the inbox and the claim is released once this run has stopped", pipeline, scope);
        return new RunCancelledException("run cancelled: '" + pipeline + "' — the host is closing; not committed");
    }
}
