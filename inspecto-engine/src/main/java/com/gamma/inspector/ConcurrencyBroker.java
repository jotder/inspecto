package com.gamma.inspector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;

/**
 * Process-wide admission control for <b>Consignment execution slots</b> — the three-tier concurrency
 * hierarchy of the scheduler-system-config plan (Part B): at most {@code processing.threads}
 * concurrent Consignments per Pipeline, at most a configured cap per space, at most a configured cap
 * per server. A worker calls {@link #admit} before heavy work and closes the returned {@link Permit}
 * after; {@code admit} parks the (virtual) thread until all three tiers have room.
 *
 * <p><b>Grants are weighted shares, never precedence.</b> Waiters queue FIFO <em>per pipeline</em>
 * (preserving Consignment arrival order within a Pipeline), and when a slot frees the next grant goes
 * to the eligible pipeline with the lowest stride-scheduling {@code pass}
 * ({@code stride = 6 / priority}, priority 1–3). A priority-3 Pipeline therefore receives ~3× the
 * throughput share of a priority-1 under saturation, and a priority-1 provably keeps a non-zero
 * share — the starvation the operator's requirement forbids cannot occur by construction. A pipeline
 * arriving after idling joins at the current virtual time, so it can neither burst on banked credit
 * nor be penalised for having been idle.
 *
 * <p><b>Inert unless configured</b> (the {@code IntakeGovernor} posture): with no space or system cap
 * the only bound is the per-pipeline cap the caller passes — exactly the pre-broker behaviour of the
 * run-local {@code Semaphore(processing.threads)} this class replaces. Caps are hot-applied via
 * {@link #setSystemCap}/{@link #setSpaceCap}; a shrink <b>drains, never interrupts</b> — in-flight
 * Consignments finish and the new ceiling gates the next admissions.
 *
 * <p>State is process-wide on the {@link #shared()} singleton (the {@code IntakeGovernor.shared()} /
 * {@code CircuitBreaker.shared()} idiom, with {@link #use} as the test escape hatch). One monitor
 * guards all counters — deliberately <em>not</em> nested semaphores, which would give no global view
 * for the fairness decision and would order-deadlock across tiers. The lock is held only for
 * bookkeeping; waiting happens on a per-waiter latch outside it.
 *
 * <p><b>Named execution pools are admission only</b> ({@code DUCKLE-C10-ADMISSION-POOLS-1}, scale-out
 * plan §4.1). A pool is a fourth tier that answers "may this Consignment start now" and can only
 * <em>refuse</em> a grant the other three would allow — it never widens {@code processing.threads}, a
 * space cap or the server cap. Pools are defined <b>server-side only</b> ({@link #setPools}, fed from
 * the server-wide {@code scheduler.toon}); a Pipeline may only <em>choose</em> one
 * ({@code processing.pool}), and a name the server does not define resolves to {@link #DEFAULT_POOL}
 * ({@link #resolvePool}) — neither a refusal nor an implicit new pool. {@code default} always exists and
 * is unbounded unless the server caps it.
 *
 * <p><b>Waiting is a recorded state.</b> Every admission is a ticket whose id is known before the grant
 * (the caller's Consignment id): while it waits it is {@code queued} with a {@code queueReason} naming
 * the tier that held it; once granted it is {@code running} and carries {@code queueMs}.
 * {@link #snapshot()} lists the live tickets and, per pool, the <b>free permits</b> — the pool metric.
 *
 * <p>⛔ <b>A supervisor takes no slot.</b> There is deliberately no API to admit a dispatcher: the thread
 * that submits Consignments and waits on them ({@code CollectorProcessor}) holds no permit, because a
 * supervisor holding a permit while waiting for a child that needs the same pool deadlocks at exactly
 * the pool size.
 */
public final class ConcurrencyBroker {

    /** Cap value meaning "no bound at this tier". */
    public static final int UNBOUNDED = 0;

    /** The pool every admission falls back to: always defined, unbounded unless the server caps it. */
    public static final String DEFAULT_POOL = "default";

    /** The shape of a server-defined pool name (lowercase identifier, at most 64 chars). */
    public static final Pattern POOL_NAME = com.gamma.config.safety.ConfigSafetyValidator.POOL_NAME;

    /** Row cap on {@code snapshot().admissions}: every Consignment of a cycle is submitted up front and parks
     *  on {@link #admit}, so the live ticket list can be thousands long — a settings read must not become an
     *  unbounded export. The true total is reported beside it. */
    private static final int MAX_ADMISSION_ROWS = 100;

    /** Least common multiple of the priority weights 1..3 — keeps every stride integral. */
    private static final long STRIDE_K = 6;

    private static volatile ConcurrencyBroker shared = new ConcurrencyBroker();

    /** The process-wide broker shared by every space's ingest path. */
    public static ConcurrencyBroker shared() {
        return shared;
    }

    /** Install a broker for tests; {@code null} restores a fresh default instance. */
    public static void use(ConcurrencyBroker broker) {
        shared = (broker != null) ? broker : new ConcurrencyBroker();
    }

    /** A granted execution slot; {@link #close()} releases it (idempotent). */
    public final class Permit implements AutoCloseable {
        private final PipelineState state;
        private final Waiter ticket;
        private boolean released;

        private Permit(PipelineState state, Waiter ticket) {
            this.state = state;
            this.ticket = ticket;
        }

        /** The ticket id — the caller's id, visible in {@link #snapshot()} since the admission queued. */
        public String id() { return ticket.id; }

        /** The pool this slot was granted in (after {@link #resolvePool}). */
        public String pool() { return ticket.pool; }

        /** How long the admission waited before it became {@code running}; {@code 0} when immediate. */
        public long queueMs() { return ticket.queueMs; }

        /** Why the admission waited, or {@code null} when it was granted immediately. */
        public String queueReason() { return ticket.queueReason; }

        @Override
        public void close() {
            synchronized (lock) {
                if (released) return;
                released = true;
                tickets.remove(ticket);
                releaseSlot(state, ticket.pool);
                grantNext();
            }
        }
    }

    private static final class Waiter {
        final CountDownLatch latch = new CountDownLatch(1);
        final String id;
        final String pool;
        final long enqueuedNanos = System.nanoTime();
        boolean granted;
        String queueReason;      // null unless the first grant pass left it waiting
        long queueMs;

        Waiter(String id, String pool) {
            this.id = id;
            this.pool = pool;
        }
    }

    private static final class PipelineState {
        final String spaceId;
        final String pipelineId;
        int cap;                 // per-pipeline concurrent-Consignment cap (processing.threads)
        int weight;              // priority 1..3
        long pass;               // stride-scheduling accumulator
        int inFlight;
        final ArrayDeque<Waiter> queue = new ArrayDeque<>();

        PipelineState(String spaceId, String pipelineId) {
            this.spaceId = spaceId;
            this.pipelineId = pipelineId;
        }

        String key() {
            return key(spaceId, pipelineId);
        }

        static String key(String spaceId, String pipelineId) {
            return spaceId + "\0" + pipelineId;
        }
    }

    private final Object lock = new Object();
    private final Map<String, PipelineState> pipelines = new HashMap<>();
    private final Map<String, Integer> spaceCaps = new HashMap<>();
    private final Map<String, Integer> spaceInFlight = new HashMap<>();
    /** Server-defined pool caps; {@link #DEFAULT_POOL} is implicit (unbounded) when absent. */
    private final Map<String, Integer> poolCaps = new LinkedHashMap<>();
    private final Map<String, Integer> poolInFlight = new HashMap<>();
    /** Live tickets (queued + running), insertion-ordered; bounded by the admitted work itself. */
    private final Set<Waiter> tickets = new LinkedHashSet<>();
    private long ticketSeq;
    private int systemCap = UNBOUNDED;
    private int systemInFlight;
    /** Global virtual time: the {@code pass} of the most recent grant — the join point for
     *  newly-arriving pipelines (no banked credit, no idle penalty). */
    private long vtime;

    /**
     * Wait for an execution slot for one Consignment of {@code pipelineId}.
     *
     * @param spaceId     the owning space ({@code EventLog.currentSpaceId()} on the ingest path)
     * @param pipelineId  the Pipeline name (unique within a space)
     * @param pipelineCap this Pipeline's concurrent-Consignment cap ({@code processing.threads});
     *                    re-stated on every call so a hot-reloaded config wins next admission
     * @param priority    this Pipeline's share weight, clamped to 1..3 ({@code processing.priority});
     *                    a change resets the pipeline's stride position to the current virtual time
     */
    public Permit admit(String spaceId, String pipelineId, int pipelineCap, int priority)
            throws InterruptedException {
        return admit(spaceId, pipelineId, DEFAULT_POOL, pipelineCap, priority, null);
    }

    /**
     * As {@link #admit(String, String, int, int)}, additionally gated by a named execution pool.
     *
     * @param pool     the Pipeline's chosen pool ({@code processing.pool}); {@code null}, blank or a
     *                 name the server does not define resolves to {@link #DEFAULT_POOL}
     * @param ticketId the admission's id (the Consignment id), listed in {@link #snapshot()} from the
     *                 moment it queues; {@code null} or blank generates one
     */
    public Permit admit(String spaceId, String pipelineId, String pool, int pipelineCap, int priority,
                        String ticketId) throws InterruptedException {
        PipelineState ps;
        Waiter w;
        synchronized (lock) {
            w = new Waiter(ticketId != null && !ticketId.isBlank() ? ticketId : "adm-" + (++ticketSeq),
                    resolvePoolLocked(pool));
            tickets.add(w);
            ps = pipelines.computeIfAbsent(PipelineState.key(spaceId, pipelineId),
                    k -> new PipelineState(spaceId, pipelineId));
            ps.cap = Math.max(1, pipelineCap);
            int weight = Math.min(3, Math.max(1, priority));
            if (ps.weight != weight) {              // priority changed (or first sight): re-join at vtime
                ps.weight = weight;
                ps.pass = Math.max(ps.pass, vtime);
            }
            if (ps.queue.isEmpty() && ps.inFlight == 0)
                ps.pass = Math.max(ps.pass, vtime); // returning from idle: no banked credit
            ps.queue.addLast(w);
            grantNext();
            if (!w.granted) w.queueReason = queueReason(ps, w);
        }
        try {
            w.latch.await();
        } catch (InterruptedException e) {
            synchronized (lock) {
                tickets.remove(w);
                if (w.granted) {                    // raced with a grant: give the slot back
                    releaseSlot(ps, w.pool);
                    grantNext();
                } else {
                    ps.queue.remove(w);
                    cleanupIfIdle(ps);
                }
            }
            throw e;
        }
        return new Permit(ps, w);
    }

    /**
     * Replace the server-defined pools (name → cap, {@link #UNBOUNDED} = no cap). Names must match
     * {@link #POOL_NAME}; an invalid entry refuses the whole map. {@link #DEFAULT_POOL} may be capped
     * here and always exists. Hot-applied with the drain/grow semantics of {@link #setSystemCap}; a
     * Permit already granted in a pool that is no longer defined still releases against it.
     */
    public void setPools(Map<String, Integer> pools) {
        Map<String, Integer> next = new LinkedHashMap<>();
        if (pools != null) {
            for (Map.Entry<String, Integer> e : pools.entrySet()) {
                if (e.getKey() == null || !POOL_NAME.matcher(e.getKey()).matches())
                    throw new IllegalArgumentException("invalid pool name '" + e.getKey() + "'");
                next.put(e.getKey(), e.getValue() == null ? UNBOUNDED : Math.max(0, e.getValue()));
            }
        }
        synchronized (lock) {
            poolCaps.clear();
            poolCaps.putAll(next);
            grantNext();
        }
    }

    /** The server-defined pools (name → cap), without the implicit {@link #DEFAULT_POOL}. */
    public Map<String, Integer> pools() {
        synchronized (lock) {
            return new LinkedHashMap<>(poolCaps);
        }
    }

    /** The pool an admission asking for {@code requested} lands in: that pool if the server defines it,
     *  else {@link #DEFAULT_POOL}. */
    public String resolvePool(String requested) {
        synchronized (lock) {
            return resolvePoolLocked(requested);
        }
    }

    /** Free permits in {@code pool} right now, or {@code null} when it is unbounded — "not applicable"
     *  must stay distinguishable from a real zero. An undefined name reads as {@link #DEFAULT_POOL}. */
    public Integer freePermits(String pool) {
        synchronized (lock) {
            String p = resolvePoolLocked(pool);
            int cap = poolCaps.getOrDefault(p, UNBOUNDED);
            return cap > 0 ? Math.max(0, cap - poolInFlight.getOrDefault(p, 0)) : null;
        }
    }

    /** Hot-apply the server-wide Consignment cap ({@link #UNBOUNDED} disables the tier). A shrink
     *  drains; a grow immediately admits eligible waiters. */
    public void setSystemCap(int cap) {
        synchronized (lock) {
            systemCap = Math.max(0, cap);
            grantNext();
        }
    }

    public int systemCap() {
        synchronized (lock) {
            return systemCap;
        }
    }

    /** Hot-apply one space's Consignment cap ({@link #UNBOUNDED} or negative removes the tier for
     *  that space). Same drain/grow semantics as {@link #setSystemCap}. */
    public void setSpaceCap(String spaceId, int cap) {
        synchronized (lock) {
            if (cap <= 0) spaceCaps.remove(spaceId);
            else spaceCaps.put(spaceId, cap);
            grantNext();
        }
    }

    public int spaceCap(String spaceId) {
        synchronized (lock) {
            return spaceCaps.getOrDefault(spaceId, UNBOUNDED);
        }
    }

    /** Live occupancy for gauges and the settings routes: system/space in-flight, free slots, and
     *  per-pipeline {@code inFlight}/{@code waiting} (insertion-ordered, bounded by the pipeline
     *  count — a pipeline with neither in-flight nor queued work is evicted, so this cannot grow). */
    public Map<String, Object> snapshot() {
        synchronized (lock) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("system_cap", systemCap);
            m.put("system_in_flight", systemInFlight);
            // Free slots right now, or null when the tier is unbounded — "not applicable" must stay
            // distinguishable from a real zero, which is exactly the state an operator reads as "wedged".
            m.put("system_free", systemCap > 0 ? Math.max(0, systemCap - systemInFlight) : null);
            m.put("space_in_flight", new LinkedHashMap<>(spaceInFlight));
            Map<String, Object> perPipeline = new LinkedHashMap<>();
            for (PipelineState ps : pipelines.values()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("space", ps.spaceId);
                row.put("in_flight", ps.inFlight);
                row.put("waiting", ps.queue.size());
                row.put("priority", ps.weight);
                perPipeline.put(ps.pipelineId, row);
            }
            m.put("pipelines", perPipeline);
            Map<String, Object> pools = new LinkedHashMap<>();
            Set<String> names = new LinkedHashSet<>();
            names.add(DEFAULT_POOL);
            names.addAll(poolCaps.keySet());
            names.addAll(poolInFlight.keySet());     // a pool undefined while its Permits still drain
            for (String name : names) {
                int cap = poolCaps.getOrDefault(name, UNBOUNDED);
                int inFlight = poolInFlight.getOrDefault(name, 0);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("cap", cap);
                row.put("in_flight", inFlight);
                row.put("free", cap > 0 ? Math.max(0, cap - inFlight) : null);
                pools.put(name, row);
            }
            m.put("pools", pools);
            List<Map<String, Object>> admissions = new ArrayList<>();
            long now = System.nanoTime();
            for (Waiter w : tickets) {
                if (admissions.size() >= MAX_ADMISSION_ROWS) break;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", w.id);
                row.put("pool", w.pool);
                row.put("state", w.granted ? "running" : "queued");
                row.put("queueReason", w.queueReason);
                row.put("queueMs", w.granted ? w.queueMs : (now - w.enqueuedNanos) / 1_000_000L);
                admissions.add(row);
            }
            m.put("admissions", admissions);
            m.put("admissions_total", tickets.size());
            m.put("admissions_truncated", tickets.size() > admissions.size());
            return m;
        }
    }

    // ── monitor-held internals ────────────────────────────────────────────────

    private String resolvePoolLocked(String requested) {
        if (requested == null) return DEFAULT_POOL;
        String p = requested.trim();
        return poolCaps.containsKey(p) ? p : DEFAULT_POOL;
    }

    private boolean poolFull(String pool) {
        int cap = poolCaps.getOrDefault(pool, UNBOUNDED);
        return cap > 0 && poolInFlight.getOrDefault(pool, 0) >= cap;
    }

    /** Which tier left {@code w} waiting — evaluated right after a grant pass that did not grant it. */
    private String queueReason(PipelineState ps, Waiter w) {
        if (poolFull(w.pool))
            return "pool '" + w.pool + "' full (" + poolCaps.get(w.pool) + " in use)";
        if (ps.inFlight >= ps.cap)
            return "pipeline cap reached (processing.threads=" + ps.cap + ")";
        int sc = spaceCaps.getOrDefault(ps.spaceId, UNBOUNDED);
        if (sc > 0 && spaceInFlight.getOrDefault(ps.spaceId, 0) >= sc)
            return "space cap reached (" + sc + ")";
        if (systemCap > 0 && systemInFlight >= systemCap)
            return "server cap reached (" + systemCap + ")";
        return "behind earlier admissions of this pipeline";
    }

    private void releaseSlot(PipelineState ps, String pool) {
        poolInFlight.merge(pool, -1, Integer::sum);
        if (poolInFlight.getOrDefault(pool, 0) <= 0) poolInFlight.remove(pool);
        ps.inFlight--;
        systemInFlight--;
        spaceInFlight.merge(ps.spaceId, -1, Integer::sum);
        if (spaceInFlight.getOrDefault(ps.spaceId, 0) <= 0) spaceInFlight.remove(ps.spaceId);
        cleanupIfIdle(ps);
    }

    private void cleanupIfIdle(PipelineState ps) {
        if (ps.queue.isEmpty() && ps.inFlight == 0) pipelines.remove(ps.key());
    }

    /** Drain every currently-grantable waiter: repeatedly pick the eligible pipeline with the lowest
     *  {@code pass} (stable tie-break on the key) and grant its FIFO head. */
    private void grantNext() {
        while (systemCap <= 0 || systemInFlight < systemCap) {
            PipelineState best = null;
            for (PipelineState ps : pipelines.values()) {
                if (ps.queue.isEmpty()) continue;
                if (ps.inFlight >= ps.cap) continue;
                int sc = spaceCaps.getOrDefault(ps.spaceId, UNBOUNDED);
                if (sc > 0 && spaceInFlight.getOrDefault(ps.spaceId, 0) >= sc) continue;
                if (poolFull(ps.queue.peekFirst().pool)) continue;   // admission only: refuses, never widens
                if (best == null || ps.pass < best.pass
                        || (ps.pass == best.pass && ps.key().compareTo(best.key()) < 0))
                    best = ps;
            }
            if (best == null) return;
            vtime = Math.max(vtime, best.pass);
            best.pass += STRIDE_K / best.weight;
            best.inFlight++;
            systemInFlight++;
            spaceInFlight.merge(best.spaceId, 1, Integer::sum);
            Waiter w = best.queue.pollFirst();
            poolInFlight.merge(w.pool, 1, Integer::sum);
            w.queueMs = (System.nanoTime() - w.enqueuedNanos) / 1_000_000L;
            w.granted = true;
            w.latch.countDown();
        }
    }
}
