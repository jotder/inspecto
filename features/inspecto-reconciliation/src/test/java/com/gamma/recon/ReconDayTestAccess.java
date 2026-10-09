package com.gamma.recon;

/** Test-only access to {@link ReconDay.Cache}, which is package-private on purpose. */
public final class ReconDayTestAccess {
    private ReconDayTestAccess() {}

    public static void clearCache() { ReconDay.Cache.clear(); }
}
