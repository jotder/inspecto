package com.gamma.query;

import com.gamma.analytics.MedianMode;
import com.gamma.analytics.QuantileMode;
import com.gamma.analytics.time.TimeGrain;
import com.gamma.api.PublicApi;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Platform Service {@code analytics} (ANALYTICS-CORE-1 S7): the Analytics Kernel's read-only functions, granted to a
 * Run via {@code requires: [analytics]} and looked up as {@code ctx.services().get(AnalyticsAccess.class)}. A deliberately
 * small surface over {@code platform/inspecto-analytics}: the canonical statistics, the column profiler's SQL over a
 * granted Dataset read, and the one time-grain truncation. Every definition, input rule and DuckDB twin is the kernel's
 * ({@code docs/okf/backend/analytics-kernel.md}); this interface adds none of its own.
 *
 * <h3>Why the interface is here and the maths is there</h3>
 * The kernel is foundation-only and may never reach the engine, while a Platform Service interface must be
 * engine-published (D-12: consumers match on {@code Class} identity). So the interface lives in the engine and its one
 * implementation, {@link #kernel()}, delegates every call to the kernel.
 *
 * <h3>Reads, never opens</h3>
 * {@link #profileSql} builds SQL over a {@link DatasetAccess.Read} the caller already holds through its own
 * {@code datasets} grant; this service opens no file and no connection, so it widens no Run's reach.
 *
 * <h3>Dry-run contract</h3>
 * Pure functions with no effect: unaffected by a dry run; the real service is handed through unchanged.
 *
 * @since 4.0.0
 */
@PublicApi(since = "4.0.0")
public interface AnalyticsAccess {

    /** Which variance a caller means: divide by n ({@code var_pop}) or by n − 1 ({@code var_samp}, needs n ≥ 2). */
    enum Spread { POPULATION, SAMPLE }

    /** Σx / n. Empty or NaN input throws {@link IllegalArgumentException} (the kernel's input rules). */
    double mean(double[] values);

    double variance(double[] values, Spread spread);

    double stddev(double[] values, Spread spread);

    double median(double[] values, MedianMode mode);

    /** {@code q} in [0, 1]; {@link QuantileMode#CONTINUOUS} is the canonical mode (D-AN3). */
    double quantile(double[] values, double q, QuantileMode mode);

    /** Raw median absolute deviation. */
    double mad(double[] values);

    /** 1.4826 × MAD, consistent with σ for normal data. */
    double scaledMad(double[] values);

    /** Classic z, {@code (observed − mean) / stddev}; {@code stddev} must be finite and &gt; 0. */
    double zScore(double observed, double mean, double stddev);

    /** Robust z of {@code observed} against {@code values}: median, scaled MAD and the floor {@code max(unit, 5 % |median|)}. */
    double robustZ(double observed, double[] values, double unit);

    /** Q3 − Q1, both continuous. */
    double iqr(double[] values);

    /** {@code numerator / denominator}; empty when the denominator is 0. */
    OptionalDouble ratio(double numerator, double denominator);

    /** {@code events / total}, a share in [0, 1]; empty when {@code total} is 0. */
    OptionalDouble rate(long events, long total);

    /**
     * The column profile of {@code columns} over a granted Dataset read, one row per column: {@code column_name},
     * {@code row_count}, {@code null_count}, {@code null_rate}, {@code distinct_count}, {@code min_value},
     * {@code max_value}, {@code mean}, {@code stddev} (sample), then {@code p<percent>} per continuous quantile.
     * DuckDB SQL only: the caller runs it where it runs the read.
     */
    String profileSql(DatasetAccess.Read dataset, List<String> columns, double... quantiles);

    /** Wall-clock truncation of a zone-less timestamp (a week starts on Monday). */
    LocalDateTime truncate(LocalDateTime t, TimeGrain grain);

    /** The start of {@code t}'s bucket on {@code zone}'s wall clock; the zone is always explicit. */
    Instant truncate(Instant t, TimeGrain grain, ZoneId zone);

    /** SQL form of {@link #truncate(Instant, TimeGrain, ZoneId)} for a {@code TIMESTAMPTZ} expression; never reads the session zone. */
    String truncateSql(String expr, TimeGrain grain, ZoneId zone);

    /** The production implementation: every call delegates to the Analytics Kernel. Stateless, so one is shared. */
    static AnalyticsAccess kernel() {
        return KernelAnalyticsAccess.INSTANCE;
    }
}
