package com.gamma.inspector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

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

    /** Which lease the check belongs to — a run's, or a remote acquisition's. */
    public enum Scope { RUN, ACQUIRE }

    /** Thrown at a commit point when the pipeline's lease has been taken over. */
    public static final class LeaseLostException extends IllegalStateException {
        LeaseLostException(String msg) { super(msg); }
    }

    /** A registration; closing it removes the check. */
    public interface Held extends AutoCloseable {
        @Override void close();
    }

    private static final Map<String, BooleanSupplier> CHECKS = new ConcurrentHashMap<>();

    private CommitFence() {}

    private static String key(Scope scope, String pipeline) {
        return scope + "\u0000" + com.gamma.event.EventLog.currentSpaceId() + "\u0000" + pipeline;
    }

    /** Register {@code stillHeld} for {@code pipeline} in the current thread's Space until the handle closes. */
    public static Held hold(Scope scope, String pipeline, BooleanSupplier stillHeld) {
        String k = key(scope, pipeline);
        CHECKS.put(k, stillHeld);
        return () -> CHECKS.remove(k, stillHeld);
    }

    /** Refuse the commit when {@code pipeline}'s registered lease is no longer held. */
    public static void check(Scope scope, String pipeline) {
        BooleanSupplier c = CHECKS.get(key(scope, pipeline));
        if (c == null || c.getAsBoolean()) return;
        log.warn("Run lease lost for '{}' ({}) — another node took it over; aborting without committing, the "
                + "files stay in the inbox for the new holder", pipeline, scope);
        throw new LeaseLostException("lease lost: '" + pipeline + "' was taken over by another node — not committed");
    }
}
