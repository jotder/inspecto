package com.gamma.acquire;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import com.gamma.event.EventLog;

/**
 * A per-source circuit breaker for acquisition connectivity (Data Acquisition roadmap Phase F). When a source's
 * connector repeatedly fails to reach/list its endpoint, the breaker trips {@link State#OPEN} and the engine
 * <em>skips</em> that source for a cooldown window instead of hammering a dead endpoint every poll cycle. After
 * the cooldown a single {@link State#HALF_OPEN} trial is allowed; success closes the breaker, another failure
 * re-opens it.
 *
 * <p>State is <b>per space</b>, keyed by {@code source.id} within the space's own {@link #shared()} instance —
 * the same cross-cycle-state idiom as {@link StabilityGate#shared()} / {@link AcquisitionLedgers#shared()},
 * since each static poll cycle is a fresh run. The clock is injectable so tests can advance cooldowns
 * deterministically.
 *
 * <p>🔴 <b>The space dimension was added 2026-09-10 (SPACE-UNKEYED-STATICS-1) and this javadoc previously
 * claimed it.</b> The paragraph above said "process-wide … the same idiom as {@code StabilityGate#shared()}"
 * while those siblings key on {@link EventLog#currentSpaceId()} and this class held a single instance keyed on
 * a bare {@code source.id}. Two spaces using the same collector id — which is what applying one Space template
 * twice produces — shared a breaker, so one space's dead endpoint tripped acquisition in the other. The
 * comment asserted the parity that did not exist, which is why it survived review.
 *
 * <p>Thresholds/cooldowns are passed per call (from {@code source.circuit_breaker:}) rather than stored, so a
 * space's single instance serves every pipeline in it without per-source configuration coupling.
 */
public final class CircuitBreaker {

    /** Breaker state for one source. */
    public enum State { CLOSED, OPEN, HALF_OPEN }

    /** Per-space breakers, keyed by space id — see the class javadoc on why this is not one instance. */
    private static final Map<String, CircuitBreaker> SHARED = new ConcurrentHashMap<>();

    /** The breaker the static poll path shares <em>for the calling thread's space</em>. */
    public static CircuitBreaker shared() {
        return SHARED.computeIfAbsent(EventLog.currentSpaceId(), k -> new CircuitBreaker(System::currentTimeMillis));
    }

    /**
     * Drop the breaker for {@code spaceId} (on space deletion), releasing its retained per-source state.
     *
     * ⚠ Named {@code forgetSpace} rather than {@code forget} on purpose: this class already has an
     * <em>instance</em> {@link #forget(String)} that drops ONE collector's entry (called on pipeline
     * deletion), and two same-arity {@code forget(String)} overloads one static and one instance apart is
     * exactly the ambiguity a reader resolves wrongly.
     */
    public static void forgetSpace(String spaceId) {
        if (spaceId != null) SHARED.remove(spaceId);
    }

    private final LongSupplier clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** Visible for tests; production code uses {@link #shared()}. */
    public CircuitBreaker(LongSupplier clock) {
        this.clock = clock;
    }

    private static final class Entry {
        State state = State.CLOSED;
        int consecutiveFailures;
        long openedAt;
    }

    /**
     * May the engine interact with {@code sourceId} this cycle? {@code true} when CLOSED, or when an OPEN breaker's
     * {@code cooldownMillis} has elapsed (it then transitions to HALF_OPEN to allow one trial). {@code false} while
     * a tripped breaker is still cooling down.
     */
    public synchronized boolean allow(String sourceId, long cooldownMillis) {
        Entry e = entries.computeIfAbsent(sourceId, k -> new Entry());
        if (e.state == State.OPEN && clock.getAsLong() - e.openedAt >= cooldownMillis) {
            e.state = State.HALF_OPEN;   // cooldown elapsed — let one trial through
        }
        return e.state != State.OPEN;
    }

    /** Record a successful interaction — closes the breaker and clears the failure count. */
    public synchronized void recordSuccess(String sourceId) {
        Entry e = entries.computeIfAbsent(sourceId, k -> new Entry());
        e.state = State.CLOSED;
        e.consecutiveFailures = 0;
    }

    /**
     * Record a connectivity failure. A failure during HALF_OPEN re-opens immediately; otherwise the breaker opens
     * once {@code failureThreshold} consecutive failures accumulate. Returns {@code true} iff this call tripped the
     * breaker OPEN (so the caller can emit the trip event exactly once).
     */
    public synchronized boolean recordFailure(String sourceId, int failureThreshold) {
        Entry e = entries.computeIfAbsent(sourceId, k -> new Entry());
        if (e.state == State.HALF_OPEN) {
            e.openedAt = clock.getAsLong();
            e.state = State.OPEN;
            return true;
        }
        e.consecutiveFailures++;
        if (e.state == State.CLOSED && e.consecutiveFailures >= Math.max(1, failureThreshold)) {
            e.openedAt = clock.getAsLong();
            e.state = State.OPEN;
            return true;
        }
        return false;
    }

    /** Current state for {@code sourceId} (CLOSED if never seen) — for observability/tests. */
    public synchronized State state(String sourceId) {
        Entry e = entries.get(sourceId);
        return e == null ? State.CLOSED : e.state;
    }

    /**
     * Drop one source's breaker state when its collector is unregistered, so {@link #entries} cannot
     * accumulate an orphan per deleted pipeline for the life of the process — the same leak-under-churn
     * reason {@code IntakeGovernor.forget} and {@code PipelineRunGuard.forget} exist.
     *
     * <p>⚠ The key is {@code source.id()} — the <b>collector</b> id, which defaults to the pipeline name but
     * is overridable via {@code source.id}. Calling this with a pipeline id silently misses every collector
     * that declares its own, which is why the caller resolves the config's collector id rather than reusing
     * the pipeline id it already has.
     */
    public synchronized void forget(String sourceId) {
        entries.remove(sourceId);
    }

    /** Forget all breaker state — for test isolation. */
    public synchronized void reset() {
        entries.clear();
    }
}
