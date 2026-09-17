package com.gamma.control;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-subject token-bucket throttle for the expensive routes named in {@code NO-RATE-LIMIT-EXPENSIVE-ROUTES-1}
 * ({@code /db/query}, {@code /bi/query}, {@code /recon/*}, {@code /agent/*}): one authenticated client could
 * otherwise saturate DuckDB or spend model tokens without bound. In-memory only, per {@link ControlApi}
 * instance — no config framework, no external store, matching the project's no-new-dependency bar.
 *
 * <p>Default budget: {@link #CAPACITY} requests, refilling at {@link #REFILL_PER_SECOND} tokens/second
 * (i.e. steady-state {@code CAPACITY} requests per {@code CAPACITY / REFILL_PER_SECOND} seconds, with a
 * burst up to {@code CAPACITY}). Fixed, not configurable — no existing gate in this file reads a rate-limit
 * config key, so there is no established pattern to extend.
 */
final class RateLimiter {

    /** Burst size — the most requests a subject may fire before refill catches up. */
    private static final double CAPACITY = 20.0;
    /** Steady-state throughput: one request every 3 seconds per subject. */
    private static final double REFILL_PER_SECOND = 1.0 / 3.0;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** True when {@code key} has a token to spend (and spends it); false when exhausted. */
    boolean tryConsume(String key) {
        return buckets.computeIfAbsent(key, k -> new Bucket()).tryConsume();
    }

    private static final class Bucket {
        private double tokens = CAPACITY;
        private long lastRefillNanos = System.nanoTime();

        synchronized boolean tryConsume() {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            lastRefillNanos = now;
            tokens = Math.min(CAPACITY, tokens + elapsedSeconds * REFILL_PER_SECOND);
            if (tokens < 1.0) return false;
            tokens -= 1.0;
            return true;
        }
    }
}
