package com.gamma.control;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Per-subject token-bucket throttle for the expensive routes named in {@code NO-RATE-LIMIT-EXPENSIVE-ROUTES-1}
 * ({@code /db/query}, {@code /bi/query}, {@code /recon/*}, {@code /agent/*}): one authenticated client could
 * otherwise saturate DuckDB or spend model tokens without bound. In-memory only, per {@link ControlApi}
 * instance — no config framework, no external store, matching the project's no-new-dependency bar.
 *
 * <p>Each instance is one budget: a burst of {@code capacity} requests, refilling at {@code refillPerSecond}
 * tokens/second. Three budgets exist ({@link #standard()}, {@link #dashboard()}, {@link #callback()}); all are fixed, not
 * configurable — no existing gate in this file reads a rate-limit config key, so there is no pattern to extend.
 *
 * <p>⚠ {@code /bi/query} has its OWN, larger bucket (operator decision 2026-09-24): every Studio widget fires
 * one {@code POST /bi/query}, so a 10–12 tile dashboard spent half the shared 20-token burst and the next
 * dashboard rendered "No data" tiles (429). Ad-hoc SQL, reconciliation and agent calls keep the original
 * budget — they are the routes an interactive user cannot multiply by the dozen per page view.
 */
final class RateLimiter {

    /** The original budget: burst 20, then one request every 3 seconds per subject. */
    static RateLimiter standard() { return new RateLimiter(20.0, 1.0 / 3.0); }

    /** The dashboard budget for {@code /bi/query}: burst 120 (≈ ten 12-tile dashboards), then 2 requests/second. */
    static RateLimiter dashboard() { return new RateLimiter(120.0, 2.0); }

    /** The public delivery-status callback budget (SEC review F1): burst 60, then 5 requests/second per
     *  caller IP. Unauthenticated, so the key is the IP; a provider posting from a handful of addresses
     *  batches its events and retries a 429 later, so this bounds abuse without dropping receipts. */
    static RateLimiter callback() { return new RateLimiter(60.0, 5.0); }

    /** The push-ingest budget for {@code POST /streams/{id}/records} (ASSURE-PUSH-INGEST-1): burst 60, then 10
     *  requests/second per caller. A producer batches records into one request, so this bounds a runaway
     *  client's file count in the inbox without throttling a sane one. */
    static RateLimiter push() { return new RateLimiter(60.0, 10.0); }

    /** The expensive Link Analysis budget (projection, traversal, pattern, Graph Run, Index build): same shape
     *  as {@link #standard()} but its own bucket, so a graph session cannot drain ad-hoc SQL and vice versa. */
    static RateLimiter linkAnalysis() {
        return new RateLimiter(positive("control.rateLimit.linkAnalysis.capacity", 20.0),
                positive("control.rateLimit.linkAnalysis.refillPerSecond", 1.0 / 3.0));
    }

    /** An operator-tunable budget (operator, 2026-10-06): the system property when it parses to a positive
     *  number, else {@code fallback} — a malformed or non-positive value never disables the limiter. */
    static double positive(String property, double fallback) {
        String raw = System.getProperty(property);
        if (raw == null) return fallback;
        try {
            double v = Double.parseDouble(raw.strip());
            return v > 0 && Double.isFinite(v) ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private final double capacity;
    private final double refillPerSecond;
    /** Nanosecond clock; {@link System#nanoTime} in production, replaced by tests so the refill is deterministic. */
    private volatile LongSupplier clock = System::nanoTime;
    /** How often (at most) a {@link #tryConsume} call sweeps the map for evictable buckets. */
    static final long SWEEP_INTERVAL_NANOS = 60_000_000_000L;
    private final AtomicLong lastSweepNanos = new AtomicLong(System.nanoTime());

    RateLimiter(double capacity, double refillPerSecond) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** Test seam: drive the refill (and the eviction sweep) from {@code nanos} instead of the wall clock. */
    void useClock(LongSupplier nanos) {
        this.clock = nanos;
        this.lastSweepNanos.set(nanos.getAsLong());
    }

    /** Number of live buckets - visible for the bounded-memory test. */
    int size() { return buckets.size(); }

    /** True when {@code key} has a token to spend (and spends it); false when exhausted. */
    boolean tryConsume(String key) {
        long now = clock.getAsLong();
        sweepIfDue(now);
        while (true) {
            Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, refillPerSecond, now));
            Boolean r = b.tryConsume(now);
            if (r != null) return r;
            buckets.remove(key, b);   // evicted under our feet: retry against a fresh (identical, full) bucket
        }
    }

    /**
     * Idle-bucket eviction (bounded memory): drop a bucket only once it has refilled to FULL capacity. A full
     * bucket and a freshly created one are indistinguishable, so eviction can never hand a client a budget it
     * would not already have by waiting - a caller cannot reset its budget faster than a full refill
     * ({@code capacity / refillPerSecond}). A bucket is marked evicted under its own lock, so a request that
     * fetched it concurrently retries instead of spending from an orphan.
     */
    private void sweepIfDue(long now) {
        long last = lastSweepNanos.get();
        if (now - last < SWEEP_INTERVAL_NANOS || !lastSweepNanos.compareAndSet(last, now)) return;
        buckets.entrySet().removeIf(e -> e.getValue().evictIfFull(now));
    }

    private static final class Bucket {
        private final double capacity;
        private final double refillPerSecond;
        private double tokens;
        private long lastRefillNanos;
        private boolean evicted;

        Bucket(double capacity, double refillPerSecond, long now) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
            this.tokens = capacity;
            this.lastRefillNanos = now;
        }

        private void refill(long now) {
            if (now > lastRefillNanos) {
                tokens = Math.min(capacity, tokens + (now - lastRefillNanos) / 1_000_000_000.0 * refillPerSecond);
                lastRefillNanos = now;
            }
        }

        /** True/false = spent/exhausted; {@code null} = this bucket was evicted, look up again. */
        synchronized Boolean tryConsume(long now) {
            if (evicted) return null;
            refill(now);
            if (tokens < 1.0) return false;
            tokens -= 1.0;
            return true;
        }

        synchronized boolean evictIfFull(long now) {
            refill(now);
            if (tokens < capacity) return false;
            evicted = true;
            return true;
        }
    }
}
