package com.gamma.control;

import com.gamma.entitystore.LaLimit;
import com.gamma.entitystore.LaLimitValues;
import com.gamma.entitystore.LaLimits;

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
 * tokens/second. The budgets below are fixed except {@link #linkAnalysis()} and {@link #embed()}, which are the install-wide
 * Link Analysis limits {@code rate.*} ({@code LA-CONFIG-LIMITS-1} CL-13; precedence: the server-wide {@code la-limits.toon}
 * value, else the older {@code control.rateLimit.*} system property, else the catalogue default).
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

    /** The embedded-Investigation view's PER-TOKEN budget ({@code GET /public/investigations/{token}}, LA-EMBED-VIEW-1): burst 60, then
     *  2 requests/second per link ({@code rate.embed_burst} / {@code rate.embed_per_s}). A viewer polls or reloads a read-only page; one
     *  leaked link cannot spend more than this however many hosts present it. Unknown tokens are bounded by the per-IP
     *  {@link #callback()} bucket, since each distinct guess would otherwise get a fresh per-token bucket. */
    static RateLimiter embed() {
        return embed(LaLimits.forSpace(null));
    }

    /** {@link #embed()} from the install-wide values {@code install}. */
    static RateLimiter embed(LaLimitValues install) {
        return new RateLimiter(tuned(install, LaLimit.RATE_EMBED_BURST, "control.rateLimit.embed.capacity"),
                tuned(install, LaLimit.RATE_EMBED_PER_S, "control.rateLimit.embed.refillPerSecond"));
    }

    /** The expensive Link Analysis budget (projection, traversal, pattern, Graph Run, Index build): its own bucket,
     *  so a graph session cannot drain ad-hoc SQL and vice versa. Burst 30, then one request per 2 seconds: about 3x
     *  what an analyst spends (opening an Investigation spends 0, each projection / neighbour list / server algorithm
     *  / path search / index build is 1; a hard drill-down is about 10 in a minute) - LA-DAILY-INGEST-1, measured
     *  2026-10-14; a runaway loop is still refused after the first 30. {@code rate.link_analysis_burst} / {@code rate.link_analysis_per_s}. */
    static RateLimiter linkAnalysis() {
        return linkAnalysis(LaLimits.forSpace(null));
    }

    /** {@link #linkAnalysis()} from the install-wide values {@code install}. */
    static RateLimiter linkAnalysis(LaLimitValues install) {
        return new RateLimiter(tuned(install, LaLimit.RATE_LINK_ANALYSIS_BURST, "control.rateLimit.linkAnalysis.capacity"),
                tuned(install, LaLimit.RATE_LINK_ANALYSIS_PER_S, "control.rateLimit.linkAnalysis.refillPerSecond"));
    }

    /** An install-wide rate: the value stated in the server-wide document, else {@code property} when it is a positive
     *  number, else the catalogue default ({@code LA-CONFIG-LIMITS-1}). */
    static double tuned(LaLimitValues install, LaLimit item, String property) {
        Number stated = install.stated(item);
        return stated != null ? stated.doubleValue() : positive(property, item.defaultValue().doubleValue());
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

    /** The burst. */
    double capacity() { return capacity; }

    /** The refill, tokens per second. */
    double refillPerSecond() { return refillPerSecond; }

    /**
     * {@code next} when its budget differs from this one (taking over this limiter's clock), else this limiter unchanged -
     * so re-applying an unchanged setting never refills anyone's bucket. A changed budget starts every caller full.
     */
    RateLimiter retunedTo(RateLimiter next) {
        if (next.capacity == capacity && next.refillPerSecond == refillPerSecond) return this;
        next.clock = clock;
        next.lastSweepNanos.set(clock.getAsLong());
        return next;
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
