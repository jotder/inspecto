package com.gamma.la.api;

import java.util.function.LongSupplier;

/**
 * Same-package accessor so a test in another module can reach {@link WorkingSetRoutes}' package-private test seams without
 * the production class exposing a public mutable static. TEST CODE ONLY: it lives in the test source set.
 */
public final class WorkingSetTestSeams {

    private WorkingSetTestSeams() {}

    /** Replace the trust window's clock; it must be MONOTONIC (differences only). */
    public static void clock(LongSupplier monotonicMs) {
        WorkingSetRoutes.clockMs = monotonicMs;
    }

    /** Back to the production monotonic clock and the production window. */
    public static void reset() {
        WorkingSetRoutes.clockMs = WorkingSetRoutes::monotonicMs;
        WorkingSetRoutes.trustMs = WorkingSetRoutes.WORKING_SET_TRUST_MS;
    }

    /** Full content reads served so far (a miss, or a re-validation after the trust window). */
    public static long fullReads() {
        return WorkingSetRoutes.FULL_READS.sum();
    }
}
