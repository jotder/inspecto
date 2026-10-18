package com.gamma.analytics.forecast;

import com.gamma.analytics.score.ScoreColumns.Column;
import java.util.List;

/**
 * The prediction-interval output shape (ANALYTICS-CORE-1 S5 (e)): what every forecast writes, so BI, KPI bands and
 * Alert Rules read any forecast the same way. Long form: one row per series per bucket per interval level.
 *
 * <pre>
 *   series keys…, &lt;time column&gt;, horizon, actual, forecast, level, lower, upper
 * </pre>
 *
 * <ul>
 *   <li>{@code horizon} — 0 for a bucket of the history, 1…H for the buckets after its last one.</li>
 *   <li>{@code actual} — the observed value; empty on a future bucket.</li>
 *   <li>{@code forecast} — the in-sample one-step-ahead forecast, or the H-step forecast from the last bucket; empty
 *       on the first season (the warm-up has no forecast).</li>
 *   <li>{@code level}, {@code lower}, {@code upper} — the central interval holding {@code level} of the
 *       outcomes ({@link PredictionInterval}); empty where {@code forecast} is.</li>
 * </ul>
 *
 * The columns are described with the score output contract's {@link Column} (name, DuckDB type, Dataset role) — the
 * same column vocabulary — but not its schema: a forecast is indexed by time, not by entity, and has no 0–100 scale,
 * band names, model version or {@code _latest} swap (see {@code docs/okf/product/module/analytics/spec.md} § Forecasting).
 */
public final class ForecastOutput {
    private ForecastOutput() {}

    public static final String HORIZON = "horizon";
    public static final String ACTUAL = "actual";
    public static final String FORECAST = "forecast";
    public static final String LEVEL = "level";
    public static final String LOWER = "lower";
    public static final String UPPER = "upper";

    /** The columns after the keys and the time column, in order. */
    public static final List<Column> VALUE_COLUMNS = List.of(
            Column.dimension(HORIZON, "INTEGER"),
            Column.measure(ACTUAL, "DOUBLE"),
            Column.measure(FORECAST, "DOUBLE"),
            Column.dimension(LEVEL, "DOUBLE"),
            Column.measure(LOWER, "DOUBLE"),
            Column.measure(UPPER, "DOUBLE"));

    /** Every output column name the keys and the time column must not take. */
    public static List<String> valueColumnNames() {
        return VALUE_COLUMNS.stream().map(Column::name).toList();
    }

    /**
     * One output row of one series, the JVM form of a row of the shape above. {@code position} is the bucket's
     * 1-based place in the series ({@code n + h} for a future bucket); {@code null} is an empty value.
     */
    public record Row(int position, int horizon, Double actual, Double forecast, double level, Double lower,
                      Double upper) {}
}
