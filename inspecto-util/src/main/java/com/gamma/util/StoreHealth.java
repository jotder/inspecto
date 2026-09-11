package com.gamma.util;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each operational store family actually resolved to, per space — so {@code GET /health/details} can
 * answer {@code VER-3} ("every intended subsystem {@code UP}, <b>not</b> silently {@code NOT_CONFIGURED} or
 * an in-memory fallback") instead of aspiring to it.
 *
 * <p><b>Why this exists.</b> Thirteen store openers catch an open failure, log at WARN and return an
 * in-memory or {@code null} store — {@code ServiceStores} (8), {@code OpsEngineProvider} (4) and
 * {@code AcquisitionLedgers} (1). Graceful degradation is deliberate and stays: observability must never
 * block the service. But a WARN on a boot log nobody is tailing is not an operator-visible fact, and
 * {@code HealthDetails} inspected none of the thirteen — so an operator who configured Postgres and got
 * memory had no way to find out from the product. For the Standard DR tier that is the failure that makes
 * DR fake: a standby silently running on memory is not a standby.
 *
 * <p><b>Three states, recorded by the opener that knows.</b> {@link Status#UP} (durable backend opened),
 * {@link Status#NOT_CONFIGURED} (the family's {@code *.backend} toggle is off — not a failure) and
 * {@link Status#DEGRADED} (a durable backend was asked for and could not be opened). Only the third is a
 * health failure. An opener records exactly one outcome per call, so re-opening a family <b>replaces</b> its
 * entry rather than accumulating — a family that recovers on a later open stops reporting DEGRADED, and a
 * stale failure can never outlive the condition that caused it.
 *
 * <p>⛔ <b>In a partitioned topology, recording a {@link Status#DEGRADED} outcome THROWS</b>
 * ({@link Topology}). Graceful degradation is right for one node and is silent split-brain across several, so
 * {@code -Dinspecto.topology=partitioned} turns it into a boot failure. The check lives in
 * {@link #record} — one enforcement point, not one per opener.
 *
 * <p>⚠ <b>A family with no entry was never opened, which is not the same as {@link Status#NOT_CONFIGURED}.</b>
 * Openers are called where the store is needed, and some are conditional — {@code openJobRunStore} runs only
 * when the space has at least one job configured, because it is an argument to the {@code JobService}
 * constructor that is skipped entirely when {@code jobConfigs.isEmpty()}. ⛔ So absence must never be rendered
 * as {@code UP}: nothing has been measured. {@code HealthDetails} reports only families that recorded, which
 * is why a subsystem can be missing from the response rather than present-and-green.
 *
 * <p>⛔ <b>The space id is passed in, never read from the thread.</b> Stores open during per-space bootstrap,
 * which runs with <b>no space MDC bound</b> — {@code AcquisitionLedgers.register} says so in its own javadoc,
 * and it is why that method takes an explicit id. Keying this registry off {@link CurrentSpace#id()} would
 * therefore file every boot-time degradation under the default space and report a clean bill of health for
 * the space that actually degraded. Callers pass {@code SpaceRoot.id()}, which is also what the read side
 * resolves, so both ends of the key have one definition. This is the {@code SPACE-UNKEYED-STATICS-1} lesson
 * applied before the fact rather than after it.
 *
 * @since 2026-09-11 (scale-out phase A)
 */
public final class StoreHealth {

    private StoreHealth() {}

    /** What an opener resolved to. Only {@link #DEGRADED} is a health failure. */
    public enum Status {
        /** A durable backend was configured and opened. */
        UP,
        /** The family's backend toggle is off — the operator did not ask for a durable store. */
        NOT_CONFIGURED,
        /** A durable backend was configured and could NOT be opened; the store fell back or is disabled. */
        DEGRADED
    }

    /**
     * One family's resolved state.
     *
     * @param family   the store family, e.g. {@code "jobRuns"} — matches {@code OperationalDb.Family}'s name
     *                 in lowerCamel where one exists ({@code "events"} has no family, it is its own toggle)
     * @param status   what it resolved to
     * @param target   the URL or backend that was attempted, or the toggle value when not configured
     * @param detail   a one-line human explanation; the open failure's message when {@link Status#DEGRADED}
     */
    public record Resolved(String family, Status status, String target, String detail) {}

    /** space id → (family → resolved). Bounded by the number of hosted spaces × families. */
    private static final Map<String, Map<String, Resolved>> SPACES = new ConcurrentHashMap<>();

    /**
     * Record what {@code family} resolved to in {@code spaceId}, replacing any previous outcome for it.
     *
     * @param spaceId the owning space, from {@code SpaceRoot.id()} — never the thread's MDC (see class doc)
     */
    public static void record(String spaceId, String family, Status status, String target, String detail) {
        if (spaceId == null || family == null || status == null) return;
        SPACES.computeIfAbsent(spaceId, k -> new ConcurrentHashMap<>())
              .put(family, new Resolved(family, status, target, detail));
        // ⛔ Phase A's invariant, enforced HERE and nowhere else: in a partitioned topology no operational
        // store may degrade, so a degradation is a boot failure. This sits at the recording seam rather than
        // in thirteen catch blocks on purpose — thirteen checks are thirteen places a fourteenth store can
        // forget one, and a guard that some call sites skip is the silent exemption this repo keeps paying
        // for. Recorded before throwing so the entry exists for anything that catches and inspects.
        if (status == Status.DEGRADED && Topology.partitioned())
            throw new IllegalStateException("Store '" + family + "' degraded in space '" + spaceId
                    + "' and -D" + Topology.PROPERTY + "=partitioned forbids that: " + detail
                    + " (target: " + target + "). Several processes share this state, so a node silently "
                    + "holding its own in-memory copy is split-brain, not degraded service. Fix the backend, "
                    + "or run this node with -D" + Topology.PROPERTY + "=single if it genuinely owns its "
                    + "state alone.");
    }

    /** Shorthand for the failure path: a durable backend was asked for and could not be opened. */
    public static void degraded(String spaceId, String family, String target, String reason) {
        record(spaceId, family, Status.DEGRADED, target, reason);
    }

    /**
     * Every family recorded for {@code spaceId}, ordered by family name; empty when none.
     *
     * <p>⚠ Sorted, not insertion-ordered: the backing map is concurrent and the openers run in whatever order
     * bootstrap calls them, so an insertion-ordered copy would render {@code /health/details} in an unstable
     * order and make any assertion over it flaky.
     */
    public static Map<String, Resolved> of(String spaceId) {
        Map<String, Resolved> byFamily = (spaceId == null) ? null : SPACES.get(spaceId);
        return (byFamily == null) ? Map.of() : new TreeMap<>(byFamily);
    }

    /** True when any family in {@code spaceId} resolved {@link Status#DEGRADED}. */
    public static boolean anyDegraded(String spaceId) {
        return of(spaceId).values().stream().anyMatch(r -> r.status() == Status.DEGRADED);
    }

    /** Drop everything recorded for {@code spaceId} — on space deletion, and for test hygiene. */
    public static void clear(String spaceId) {
        if (spaceId != null) SPACES.remove(spaceId);
    }

    /** ⚠ Test hygiene only: drop every space's record. Static state outlives a test class otherwise. */
    public static void clearAll() {
        SPACES.clear();
    }
}
