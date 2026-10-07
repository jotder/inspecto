package com.gamma.la.core;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * A TIME budget for taking input fingerprints (D-3 design 5.3a): a directory listing must never stall a request, however slow
 * the disk or large the directory tree, so a listing is abandoned when its budget runs out and the fingerprint is the
 * {@code unknown:timeout} sentinel. One budget may cover several listings (a {@code GET /inv/index} over N Datasets): once it is
 * {@link #exhausted()} the remaining Datasets are not listed at all ({@code unknown:budget}). The clock is injectable so a test
 * needs neither a slow disk nor a sleep.
 */
public final class FingerprintBudget {

    /** The longest one listing may run. */
    public static final long LISTING_NANOS = 2_000_000_000L;
    /** The longest one request may spend listing, across all its Datasets. */
    public static final long REQUEST_NANOS = 5_000_000_000L;

    private final long deadline;
    private final LongSupplier nanoClock;

    /** @param totalNanos the whole budget, counted from now; @param nanoClock a monotonic nanosecond clock */
    public FingerprintBudget(long totalNanos, LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        this.deadline = nanoClock.getAsLong() + totalNanos;
    }

    /** One listing's budget on the real clock. */
    public static FingerprintBudget forListing() {
        return new FingerprintBudget(LISTING_NANOS, System::nanoTime);
    }

    /** A whole request's budget on the real clock. */
    public static FingerprintBudget forRequest() {
        return new FingerprintBudget(REQUEST_NANOS, System::nanoTime);
    }

    /** Whether no time is left. */
    public boolean exhausted() {
        return nanoClock.getAsLong() - deadline >= 0;
    }

    /** A check for ONE listing: true once the listing has run {@link #LISTING_NANOS} or this budget is exhausted. */
    public BooleanSupplier listingExpiry() {
        long listingDeadline = Math.min(deadline - nanoClock.getAsLong(), LISTING_NANOS) + nanoClock.getAsLong();
        return () -> nanoClock.getAsLong() - listingDeadline >= 0;
    }
}
