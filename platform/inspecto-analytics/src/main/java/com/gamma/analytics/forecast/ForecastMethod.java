package com.gamma.analytics.forecast;

import java.util.Locale;

/** The forecast methods of the Analytics Kernel (ANALYTICS-CORE-1 S5 (a)); definitions in {@link Forecaster}. */
public enum ForecastMethod {
    /** ŷ(t) = y(t − period): each bucket repeats the same bucket one season earlier. */
    SEASONAL_NAIVE,
    /** Holt-Winters additive: level, trend and one seasonal term per position in the period. */
    HOLT_WINTERS;

    /** The config spelling: {@code seasonal_naive}, {@code holt_winters}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The method for a config spelling; {@link IllegalArgumentException} when there is none. */
    public static ForecastMethod parse(String s) {
        for (ForecastMethod m : values()) if (m.wire().equalsIgnoreCase(s.trim())) return m;
        throw new IllegalArgumentException("unknown forecast method '" + s + "' (seasonal_naive, holt_winters)");
    }
}
