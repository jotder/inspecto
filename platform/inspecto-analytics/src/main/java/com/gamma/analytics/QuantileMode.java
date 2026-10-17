package com.gamma.analytics;

/** How {@link Stats#quantile(double[], double, QuantileMode)} picks or interpolates a rank (D-AN3). */
public enum QuantileMode {
    /** Linear interpolation between the closest ranks, h = (n-1)q. The canonical mode. DuckDB {@code quantile_cont}. */
    CONTINUOUS,
    /** The smallest observed value whose empirical CDF reaches q, computed as DuckDB {@code quantile_disc} does. */
    DISCRETE,
    /** The nearest-rank percentile, rank = ceil(qn) clamped to [1, n], computed as {@code ReportService.percentile} does. */
    NEAREST_RANK
}
