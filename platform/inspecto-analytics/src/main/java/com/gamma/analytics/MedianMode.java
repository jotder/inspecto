package com.gamma.analytics;

/** How {@link Stats#median(double[], MedianMode)} resolves an even count (D-AN4). An odd count has one middle value. */
public enum MedianMode {
    /** The average of the two middle values. DuckDB {@code median}; the anomaly baselines. */
    STANDARD,
    /** The lower of the two middle values: always an observed value, never above the middle (VolumeBaseline's rule). */
    LOWER
}
