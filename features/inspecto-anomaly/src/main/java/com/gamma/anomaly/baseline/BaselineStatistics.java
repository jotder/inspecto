package com.gamma.anomaly.baseline;

/** Which {@link BaselineStatistic} a model is scored with. S1 accepts {@code seasonality: none} only. */
public final class BaselineStatistics {
    private BaselineStatistics() {}

    /** The statistic for a model's {@code seasonality} and {@code minBaselinePoints}. */
    public static BaselineStatistic forModel(Seasonality seasonality, int minBaselinePoints) {
        return new SeasonalBaseline(seasonality, minBaselinePoints);
    }
}
