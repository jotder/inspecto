package com.gamma.acquire;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The <b>continuous lane</b> for one {@code trigger: {type: stream}} Pipeline (ASSURE-PUSH-INGEST-1; option B of
 * STREAM-CONSUMER-1, built once D-P7 named a latency target: p95 ≤ 30 s event → Incident).
 *
 * <p>A virtual thread holds the Collector's connector OPEN (for Kafka: one consumer for the lane's life) and
 * probes {@link CollectorConnector#pendingRecords()} every {@code probeMs}. It fires {@code drain} — the
 * ordinary acquire + ingest of that Pipeline — when the backlog reaches {@code records} (N), or once a
 * non-empty backlog has waited {@code maxWaitMs} (T). A connector that cannot count ({@code -1}) drains on T.
 *
 * <p><b>What it does NOT do, deliberately.</b> It does not read records or move offsets itself: the drain is
 * option A's path unchanged, so the in-flight fence (one uncommitted slice per partition), the durable slice
 * frontier and land-then-commit all still apply, and delivery stays at-least-once at slice level — a crash
 * mid-slice re-delivers that one slice. The probe skips fenced partitions, so an uncommitted slice cannot make
 * the lane spin.
 */
public final class StreamLane implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StreamLane.class);

    private final String name;
    private final long records;
    private final long maxWaitMs;
    private final long probeMs;
    private final Supplier<CollectorConnector> open;
    private final Runnable drain;
    private final LongSupplier clock;

    private CollectorConnector connector;
    private long waitingSince = -1;
    private long drains;
    private volatile boolean stopped;
    /** Set when a probe or drain threw something that is not an ordinary failure (an Error, an unexpected
     *  exception out of the drain): the lane stops and {@link #dead()} tells its owner to replace it. */
    private volatile boolean dead;
    private Thread thread;
    /** Spacing of replacements after deaths; owned by the lane's owner so it outlives each replaced lane. */
    private final Backoff deathBackoff;
    /** Spacing of re-probes after ordinary probe failures (broker down); per lane. */
    private final Backoff probeBackoff;

    public StreamLane(String name, long records, long maxWaitMs, long probeMs,
                      Supplier<CollectorConnector> open, Runnable drain, LongSupplier clock) {
        this(name, records, maxWaitMs, probeMs, open, drain, clock,
                new Backoff(Backoff.DEFAULT_BASE_MS, Backoff.DEFAULT_MAX_MS, clock));
    }

    /** As above, with the owner's {@code deathBackoff} shared across replacements (ASSURE-STREAM-LANE-BACKOFF-1). */
    public StreamLane(String name, long records, long maxWaitMs, long probeMs,
                      Supplier<CollectorConnector> open, Runnable drain, LongSupplier clock, Backoff deathBackoff) {
        this.deathBackoff = deathBackoff;
        this.probeBackoff = new Backoff(deathBackoff.baseMs, deathBackoff.maxMs, clock);
        this.name = name;
        this.records = records;
        this.maxWaitMs = maxWaitMs;
        this.probeMs = Math.max(10, probeMs);
        this.open = open;
        this.drain = drain;
        this.clock = clock;
    }

    /**
     * One probe and decision. Returns whether it drained. Exposed for tests; {@link #start} loops it.
     * A probe failure closes the connector (the next step reopens it) and drains nothing.
     */
    public synchronized boolean step() {
        if (dead || !probeBackoff.ready()) return false;
        long pending;
        try {
            if (connector == null) connector = open.get();
            pending = connector.pendingRecords();
        } catch (AcquisitionException e) {
            // An ordinary source failure (broker down): keep the lane, reopen after a backoff; WARN once per streak.
            int n = probeBackoff.fail();
            if (n == 1) log.warn("Stream lane '{}': backlog probe failed, reopening with backoff: {}", name, e.getMessage());
            else log.debug("Stream lane '{}': backlog probe failed ({} in a row): {}", name, n, e.getMessage());
            closeConnector();
            return false;
        } catch (Throwable t) {
            return die("backlog probe", t);
        }
        if (probeBackoff.streak() > 0) log.info("Stream lane '{}': backlog probe recovered", name);
        probeBackoff.success();
        if (pending == 0) {
            waitingSince = -1;
            deathBackoff.success();
            return false;
        }
        long now = clock.getAsLong();
        if (waitingSince < 0) waitingSince = now;
        boolean byCount = pending >= records;
        boolean byWait = now - waitingSince >= maxWaitMs;
        if (!byCount && !byWait) {
            deathBackoff.success();
            return false;
        }
        waitingSince = -1;
        drains++;
        try {
            drain.run();
            deathBackoff.success();
        } catch (Throwable t) {
            die("drain", t);
        }
        return true;
    }

    /** Mark the lane dead: ERROR log (stack once per death streak) + {@code inspecto_stream_lane_deaths_total},
     *  connector closed, loop stops; the owner replaces it once {@code deathBackoff} is {@link Backoff#ready}. */
    private boolean die(String where, Throwable t) {
        dead = true;
        int n = deathBackoff.fail();
        if (n == 1) log.error("Stream lane '{}' died in its {} — its owner restarts it with backoff", name, where, t);
        else log.error("Stream lane '{}' died in its {} again ({} in a row, next restart in {} ms): {}",
                name, where, n, deathBackoff.remainingMs(), String.valueOf(t));
        com.gamma.metrics.MetricRegistry.global().inc("inspecto_stream_lane_deaths_total",
                "Continuous stream lanes that died and were marked for restart", Map.of("pipeline", name));
        closeConnector();
        return false;
    }

    /** Whether the lane died (see {@link #die}); a dead lane never steps again and must be replaced. */
    public boolean dead() {
        return dead;
    }

    /** Drains fired so far. */
    public synchronized long drains() {
        return drains;
    }

    /** Start the lane's virtual thread, carrying {@code mdc} (the Space routing key) onto it. */
    public synchronized void start(Map<String, String> mdc) {
        if (thread != null) return;
        thread = Thread.ofVirtual().name("stream-lane-" + name).start(() -> {
            if (mdc != null) MDC.setContextMap(mdc);
            try {
                while (!stopped && !dead) {
                    step();
                    try {
                        Thread.sleep(Math.max(probeMs, probeBackoff.remainingMs()));
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            } finally {
                MDC.clear();
            }
        });
    }

    @Override
    public void close() {
        stopped = true;
        Thread t;
        synchronized (this) { t = thread; }
        if (t != null) {
            t.interrupt();
            try {
                t.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) { closeConnector(); }
    }

    private void closeConnector() {
        if (connector == null) return;
        try {
            connector.close();
        } catch (Exception ignore) {
            // best effort
        }
        connector = null;
    }

    /**
     * Bounded exponential backoff with equal jitter, driven by an injectable clock. The first failure of a streak
     * waits nothing (so a one-off fault retries promptly); failure {@code n >= 2} waits a jittered value in
     * {@code [d/2, d]} with {@code d = min(max, base * 2^(n-2))}. {@link #success} resets the streak.
     */
    public static final class Backoff {
        public static final long DEFAULT_BASE_MS = 1_000;
        public static final long DEFAULT_MAX_MS = 60_000;

        final long baseMs;
        final long maxMs;
        private final LongSupplier clock;
        private int streak;
        private long nextAt;

        public Backoff(long baseMs, long maxMs, LongSupplier clock) {
            this.baseMs = Math.max(1, baseMs);
            this.maxMs = Math.max(this.baseMs, maxMs);
            this.clock = clock;
        }

        /** Record a failure; returns the streak length including it. */
        public synchronized int fail() {
            streak++;
            long d = streak == 1 ? 0 :Math.min(maxMs, baseMs << Math.min(streak - 2, 30));
            nextAt = clock.getAsLong() + (d == 0 ? 0 : d / 2 + ThreadLocalRandom.current().nextLong(d / 2 + 1));
            return streak;
        }

        public synchronized void success() {
            streak = 0;
            nextAt = 0;
        }

        public synchronized boolean ready() {
            return clock.getAsLong() >= nextAt;
        }

        public synchronized long remainingMs() {
            return Math.max(0, nextAt - clock.getAsLong());
        }

        public synchronized int streak() {
            return streak;
        }
    }
}
