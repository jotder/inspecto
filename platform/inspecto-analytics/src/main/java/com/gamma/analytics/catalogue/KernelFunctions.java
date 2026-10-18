package com.gamma.analytics.catalogue;

import com.gamma.analytics.catalogue.AnalyticsFunction.Cost;
import com.gamma.analytics.catalogue.AnalyticsFunction.Form;
import com.gamma.analytics.catalogue.AnalyticsFunction.Kernel;
import com.gamma.analytics.catalogue.AnalyticsFunction.Param;
import com.gamma.analytics.catalogue.AnalyticsFunction.ResultKind;

import java.util.List;
import java.util.Set;

/**
 * The S-tier kernels' functions, as ANALYTICS-CORE-1 stood on 2026-10-17: statistics, column profiler, time series,
 * forecasting, similarity, entity grouping and the graph library. Later kernels do NOT edit this class: each ships its own
 * {@link AnalyticsFunctionProvider} (see that interface), so two lanes adding functions never touch one file.
 */
public final class KernelFunctions implements AnalyticsFunctionProvider {

    private static final Set<Form> BOTH = Set.of(Form.JVM, Form.SQL);
    private static final Set<Form> JVM = Set.of(Form.JVM);
    private static final Set<Form> SQL = Set.of(Form.SQL);

    private static final List<String> QUANTILE_MODES = List.of("continuous", "discrete", "nearest_rank");
    private static final List<String> GRAINS = List.of("second", "minute", "hour", "day", "week", "month", "quarter", "year");

    private static AnalyticsFunction f(String id, String label, Kernel kernel, Cost cost, ResultKind result, Set<Form> forms,
                                       String step, String summary, Param... params) {
        return new AnalyticsFunction(id, label, kernel, cost, result, forms, step, summary, List.of(params));
    }

    @Override
    public List<AnalyticsFunction> functions() {
        Kernel st = Kernel.STATISTICS;
        return List.of(
                // ── statistics ──────────────────────────────────────────────────────────────────────────────
                f("stats.mean", "Mean", st, Cost.LINEAR, ResultKind.SCALAR, BOTH, null,
                        "Arithmetic mean over the non-NULL values."),
                f("stats.stddev", "Standard deviation", st, Cost.LINEAR, ResultKind.SCALAR, BOTH, null,
                        "Population or sample standard deviation.",
                        Param.choice("kind", "population", List.of("population", "sample"), "Divide by n or by n − 1.")),
                f("stats.median", "Median", st, Cost.LINEARITHMIC, ResultKind.SCALAR, JVM, null,
                        "Median; the mode decides what an even count resolves to.",
                        Param.choice("mode", "standard", List.of("standard", "lower"), "Average the two middle values, or take the lower.")),
                f("stats.quantile", "Quantile", st, Cost.LINEARITHMIC, ResultKind.SCALAR, BOTH, null,
                        "The q-quantile under one of three interpolation modes.",
                        Param.decimal("q", null, 0, 1, "The quantile, 0 to 1."),
                        Param.choice("mode", "continuous", QUANTILE_MODES, "Interpolation rule.")),
                f("stats.robust_z", "Robust z-score", st, Cost.LINEARITHMIC, ResultKind.COLUMN, JVM, null,
                        "(x − median) / scaled MAD, with a floor so a near-constant history cannot explode the score.",
                        Param.decimal("unit", null, 0, 1_000_000, "The smallest change that matters; sets the floor.")),
                f("stats.z_score", "z-score", st, Cost.LINEAR, ResultKind.COLUMN, BOTH, null,
                        "(x − mean) / stddev, undefined when the stddev is not positive."),
                f("stats.tukey_fences", "Tukey fences", st, Cost.LINEARITHMIC, ResultKind.TABLE, JVM, null,
                        "Lower and upper fences at Q1 − k·IQR and Q3 + k·IQR.",
                        Param.decimal("k", 1.5, 0, 100, "Fence multiplier."),
                        Param.choice("mode", "continuous", QUANTILE_MODES, "Quantile rule for Q1 and Q3.")),
                f("stats.outlier", "Outlier flag", st, Cost.LINEARITHMIC, ResultKind.COLUMN, BOTH, "transform.stats.outlier",
                        "Flags values outside the IQR fences, or above a robust-z threshold, per group.",
                        Param.column("column", "The measured column."),
                        Param.columns("groupBy", "Optional group columns."),
                        Param.choice("method", "iqr", List.of("iqr", "robust_z"), "Detection rule.")),
                f("stats.correlation", "Correlation and regression", st, Cost.LINEAR, ResultKind.TABLE, BOTH,
                        "transform.stats.correlation",
                        "Pearson correlation, slope, intercept and r² for pairs of columns, per group.",
                        Param.columns("pairs", "x / y column pairs."),
                        Param.columns("groupBy", "Optional group columns.")),
                f("stats.binning", "Binning", st, Cost.LINEARITHMIC, ResultKind.COLUMN, BOTH, "transform.data.binning",
                        "Assigns each value to an equal-width or quantile bin.",
                        Param.column("column", "The binned column."),
                        Param.integer("bins", null, 1, 1000, "Number of bins."),
                        Param.choice("method", "equal_width", List.of("equal_width", "quantile"), "Bin edges rule.")),
                f("stats.sample", "Deterministic sample", st, Cost.LINEARITHMIC, ResultKind.TABLE, BOTH, "transform.sample",
                        "A reproducible sample by size or fraction, per group, chosen by a seeded hash of the key.",
                        Param.integer("seed", 0, Integer.MIN_VALUE, Integer.MAX_VALUE, "Seed of the key hash."),
                        Param.integer("size", null, 1, 1_000_000, "Rows per group (or the whole group if smaller)."),
                        Param.decimal("fraction", null, 0, 1, "Fraction of rows, instead of a size.")),

                // ── column profiler ─────────────────────────────────────────────────────────────────────────
                f("profiler.columns", "Column profile", Kernel.PROFILER, Cost.LINEAR, ResultKind.SQL, SQL, "transform.profile",
                        "One row per column (or per group and column): counts, nulls, distinct, extremes, quantiles.",
                        Param.columns("columns", "Columns to profile."),
                        Param.columns("groupBy", "Optional group columns.")),
                f("profiler.top_values", "Top values", Kernel.PROFILER, Cost.LINEARITHMIC, ResultKind.SQL, SQL, null,
                        "The k most frequent values of a column with their counts.",
                        Param.column("column", "The column."),
                        Param.integer("k", 10, 1, 1000, "How many values.")),
                f("profiler.histogram", "Histogram", Kernel.PROFILER, Cost.LINEAR, ResultKind.SQL, SQL, null,
                        "Equal-width counts of a numeric column.",
                        Param.column("column", "The column."),
                        Param.integer("bins", 10, 1, 1000, "Number of bins.")),

                // ── time series ─────────────────────────────────────────────────────────────────────────────
                f("timeseries.resample", "Resample", Kernel.TIME_SERIES, Cost.LINEAR, ResultKind.TABLE, SQL,
                        "transform.timeseries.resample",
                        "Buckets a time column to a grain in a zone, aggregates measures and fills the empty buckets.",
                        Param.column("timeColumn", "The event-time column."),
                        Param.choice("grain", "day", GRAINS, "Bucket size."),
                        Param.text("zone", "UTC", "IANA zone the buckets are cut in."),
                        Param.columns("keys", "Optional series keys.")),
                f("timeseries.shift", "Shift (lag / lead)", Kernel.TIME_SERIES, Cost.LINEARITHMIC, ResultKind.COLUMN, SQL,
                        "transform.timeseries.shift",
                        "Adds the value of a column a number of buckets earlier or later, per series.",
                        Param.columns("columns", "Columns to shift."),
                        Param.integer("periods", 1, -1_000_000, 1_000_000, "Buckets to shift; negative leads.")),
                f("timeseries.seasonality", "Seasonality detection", Kernel.TIME_SERIES, Cost.LINEAR, ResultKind.SCALAR, BOTH, null,
                        "The strongest period by autocorrelation among a grain's calendar candidates, or none.",
                        Param.decimal("minAcf", 0.3, 0, 1, "Smallest autocorrelation that counts."),
                        Param.integer("minCycles", 3, 1, 1000, "Full cycles the history must hold.")),

                // ── forecasting ─────────────────────────────────────────────────────────────────────────────
                f("forecast.forecast", "Forecast", Kernel.FORECAST, Cost.LINEAR, ResultKind.FORECAST, BOTH,
                        "transform.timeseries.forecast",
                        "Seasonal-naive or Holt-Winters forecast with prediction intervals.",
                        Param.choice("method", "holt_winters", List.of("seasonal_naive", "holt_winters"), "Forecast method."),
                        Param.integer("period", null, 2, 100_000, "Season length in buckets."),
                        Param.integer("horizon", null, 1, 100_000, "Buckets to forecast."),
                        Param.decimal("alpha", 0.25, 0, 1, "Level smoothing."),
                        Param.decimal("beta", 0.02, 0, 1, "Trend smoothing."),
                        Param.decimal("gamma", 0.15, 0, 1, "Seasonal smoothing.")),
                f("forecast.accuracy", "Accuracy measures", Kernel.FORECAST, Cost.LINEAR, ResultKind.TABLE, BOTH, null,
                        "MAE, RMSE, MAPE, sMAPE, bias, and interval coverage and width."),
                f("forecast.backtest", "Rolling-origin backtest", Kernel.FORECAST, Cost.QUADRATIC, ResultKind.TABLE, JVM, null,
                        "Re-fits a forecast at successive origins and scores each against what then happened.",
                        Param.integer("minTrain", null, 1, 1_000_000, "Observations before the first origin."),
                        Param.integer("horizon", null, 1, 100_000, "Buckets scored per origin."),
                        Param.integer("step", null, 1, 100_000, "Buckets between origins.")),
                f("forecast.holdout_split", "Holdout split", Kernel.FORECAST, Cost.LINEAR, ResultKind.COLUMN, BOTH, null,
                        "A train / validation / test label per row: by time, or by a seeded hash of the key."),

                // ── similarity ──────────────────────────────────────────────────────────────────────────────
                f("similarity.score", "Text similarity", Kernel.SIMILARITY, Cost.LINEAR, ResultKind.COLUMN, BOTH,
                        "transform.similarity",
                        "Similarity of two text columns in [0, 1]: Jaro, Jaro-Winkler, Levenshtein or token Jaccard.",
                        Param.column("left", "First text column."),
                        Param.column("right", "Second text column."),
                        Param.choice("method", "jaro_winkler", List.of("jaro", "jaro_winkler", "levenshtein", "token_jaccard"),
                                "Similarity method.")),
                f("similarity.jaccard", "Set Jaccard", Kernel.SIMILARITY, Cost.LINEAR, ResultKind.SCALAR, JVM, null,
                        "|A ∩ B| / |A ∪ B| of two sets; 1 for two empty sets."),

                // ── entity grouping ─────────────────────────────────────────────────────────────────────────
                f("grouping.entity", "Entity grouping", Kernel.GROUPING, Cost.QUADRATIC, ResultKind.GROUPS, BOTH,
                        "transform.entity.link",
                        "Deterministic connected components over match conditions within blocking cells; the id is the smallest key.",
                        Param.column("keyColumn", "The record key."),
                        Param.columns("blocking", "Columns records must share to be compared."),
                        Param.integer("maxBlockSize", null, 2, 1_000_000, "Largest blocking cell compared."),
                        Param.integer("maxPairs", null, 1, Integer.MAX_VALUE, "Most candidate pairs per run.")),

                // ── graph ───────────────────────────────────────────────────────────────────────────────────
                f("graph.algorithms", "Graph algorithms", Kernel.GRAPH, Cost.LINEAR, ResultKind.CATALOGUE, JVM, null,
                        "The 29 graph algorithms of the graph library, with their own parameters and node ceilings: "
                                + "see GET /inv/graph/algorithms."),
                f("graph.temporal_motif", "Temporal motif matcher", Kernel.GRAPH, Cost.QUADRATIC, ResultKind.TABLE, JVM, null,
                        "Finds ordered fan-in / fan-out stages over timed directed legs; a stage needs enough distinct "
                                + "counterparties inside its window, and the last may close back onto the origin.",
                        Param.choice("shape", null, List.of("fan-in", "fan-out"), "Per stage: many to one, or one to many."),
                        Param.integer("minBranches", null, 1, 1_000_000, "Distinct counterparties a stage needs."),
                        Param.decimal("windowHours", null, 0, 1_000_000, "Width of a stage's window, in hours."),
                        Param.decimal("maxGapHours", null, 0, 1_000_000, "Longest wait between stages, in hours."),
                        Param.integer("limit", null, 1, 1_000_000, "Matches returned before it reports truncated.")));
    }
}
