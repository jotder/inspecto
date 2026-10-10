package com.gamma.anomaly.baseline;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * A feature's SELF baseline as the {@code anomaly.score} Job asks for it (ANOMALY-DETECTION-1): one entity's
 * bucket history in, one {@link Baseline} out. Pure — no DuckDB, no I/O. The Job calls only this interface and gets
 * its implementation from {@link BaselineStatistics#forModel}, so a new statistic is added there, not in the Job.
 * {@link SeasonalBaseline} is the implementation (S1 uses it with {@link Seasonality#NONE}).
 */
public interface BaselineStatistic {

    /** The basis recorded in the explanation's {@code baseline.kind}. */
    String kind();

    /** The baseline of {@code history}; the {@code scored} bucket is never part of it. */
    Baseline compute(Map<LocalDateTime, Double> history, LocalDateTime scored);
}
