package com.gamma.control;

import java.util.concurrent.ConcurrentHashMap;

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

    private final double capacity;
    private final double refillPerSecond;

    RateLimiter(double capacity, double refillPerSecond) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** True when {@code key} has a token to spend (and spends it); false when exhausted. */
    boolean tryConsume(String key) {
        return buckets.computeIfAbsent(key, k -> new Bucket(capacity, refillPerSecond)).tryConsume();
    }

    private static final class Bucket {
        private final double capacity;
        private final double refillPerSecond;
        private double tokens;
        private long lastRefillNanos = System.nanoTime();

        Bucket(double capacity, double refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
            this.tokens = capacity;
        }

        synchronized boolean tryConsume() {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            lastRefillNanos = now;
            tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
            if (tokens < 1.0) return false;
            tokens -= 1.0;
            return true;
        }
    }
}
