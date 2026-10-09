package com.gamma.recon;

/** Test-only access to {@link ReconDay.Cache}, which is package-private on purpose. */
public final class ReconDayTestAccess {
    private ReconDayTestAccess() {}

    public static void clearCache() { ReconDay.Cache.clear(); }

    /** Cache reads of one key kind ({@code grain}, {@code breaks}, {@code rows}, ...) that found an entry. */
    public static long cacheHits(String kind) { return ReconDay.Cache.hits(kind); }

    /** Lower (or restore, with 200_000) the most grain rows a cached day holds. */
    public static void setRowCap(int cap) { ReconRoutes.rowCap = cap; }
}
