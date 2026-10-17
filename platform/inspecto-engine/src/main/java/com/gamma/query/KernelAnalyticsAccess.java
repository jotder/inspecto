package com.gamma.query;

import com.gamma.analytics.ColumnProfiler;
import com.gamma.analytics.MedianMode;
import com.gamma.analytics.QuantileMode;
import com.gamma.analytics.Stats;
import com.gamma.analytics.time.TimeGrain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.OptionalDouble;

/** {@link AnalyticsAccess#kernel()}: every call delegates to the Analytics Kernel; stateless. */
final class KernelAnalyticsAccess implements AnalyticsAccess {
    static final KernelAnalyticsAccess INSTANCE = new KernelAnalyticsAccess();

    private KernelAnalyticsAccess() {}

    @Override public double mean(double[] values) { return Stats.mean(values); }

    @Override public double variance(double[] values, Spread spread) {
        return switch (spread) {
            case POPULATION -> Stats.variancePopulation(values);
            case SAMPLE -> Stats.varianceSample(values);
        };
    }

    @Override public double stddev(double[] values, Spread spread) {
        return switch (spread) {
            case POPULATION -> Stats.stddevPopulation(values);
            case SAMPLE -> Stats.stddevSample(values);
        };
    }

    @Override public double median(double[] values, MedianMode mode) { return Stats.median(values, mode); }

    @Override public double quantile(double[] values, double q, QuantileMode mode) { return Stats.quantile(values, q, mode); }

    @Override public double mad(double[] values) { return Stats.mad(values); }

    @Override public double scaledMad(double[] values) { return Stats.scaledMad(values); }

    @Override public double zScore(double observed, double mean, double stddev) { return Stats.zScore(observed, mean, stddev); }

    @Override public double robustZ(double observed, double[] values, double unit) { return Stats.robustZ(observed, values, unit); }

    @Override public double iqr(double[] values) { return Stats.iqr(values); }

    @Override public OptionalDouble ratio(double numerator, double denominator) { return Stats.ratio(numerator, denominator); }

    @Override public OptionalDouble rate(long events, long total) { return Stats.rate(events, total); }

    @Override public String profileSql(DatasetAccess.Read dataset, List<String> columns, double... quantiles) {
        return ColumnProfiler.rowPerColumn("(" + dataset.relationSql() + ") AS __dataset",
                ColumnProfiler.Spec.of(columns).withQuantiles(QuantileMode.CONTINUOUS, quantiles));
    }

    @Override public LocalDateTime truncate(LocalDateTime t, TimeGrain grain) { return grain.truncate(t); }

    @Override public Instant truncate(Instant t, TimeGrain grain, ZoneId zone) { return grain.truncate(t, zone); }

    @Override public String truncateSql(String expr, TimeGrain grain, ZoneId zone) { return grain.truncateSql(expr, zone); }
}
