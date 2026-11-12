package com.gamma.analytics.catalogue;

import com.gamma.analytics.catalogue.AnalyticsFunction.Cost;
import com.gamma.analytics.catalogue.AnalyticsFunction.Form;
import com.gamma.analytics.catalogue.AnalyticsFunction.Kernel;
import com.gamma.analytics.catalogue.AnalyticsFunction.Param;
import com.gamma.analytics.catalogue.AnalyticsFunction.ParamType;
import com.gamma.analytics.catalogue.AnalyticsFunction.ResultKind;

import java.util.List;
import java.util.Set;

/**
 * The functions of the ANALYTICS-CORE-1 Could batch (2026-10-18): the calendar-slot profile and change-point detection
 * of the time-series kernel, the cohort kernel (retention, funnel) and the model kernels (k-means, logistic regression,
 * isolation forest). Windowed and ratio Measures (C1) are NOT here: they are part of the Measure grammar
 * ({@code MeasureCompiler}), not a kernel function.
 */
public final class CouldBatchFunctions implements AnalyticsFunctionProvider {

    private static final Set<Form> BOTH = Set.of(Form.JVM, Form.SQL);
    private static final Set<Form> JVM = Set.of(Form.JVM);

    private static final List<String> SLOT_KINDS = List.of("hour_of_day", "weekday", "hour_of_week");
    private static final List<String> COHORT_GRAINS = List.of("hour", "day", "week", "month", "quarter", "year");

    private static AnalyticsFunction f(String id, String label, Kernel kernel, Cost cost, ResultKind result, Set<Form> forms,
                                       String step, String summary, Param... params) {
        return new AnalyticsFunction(id, label, kernel, cost, result, forms, step, summary, List.of(params));
    }

    @Override
    public List<AnalyticsFunction> functions() {
        return List.of(
                // ── time series ─────────────────────────────────────────────────────────────────────────────
                f("timeseries.calendar_slot", "Calendar slot", Kernel.TIME_SERIES, Cost.CONSTANT, ResultKind.COLUMN, BOTH, null,
                        "The slot of an instant in a stated zone: hour of day (0–23), weekday (0–6, Monday = 0) or "
                                + "weekday × hour (0–167).",
                        Param.choice("kind", null, SLOT_KINDS, "Which calendar slot."),
                        Param.text("zone", null, "IANA zone an instant is read in; blank reads the value's own clock.")),
                f("timeseries.seasonal_profile", "Seasonal profile", Kernel.TIME_SERIES, Cost.LINEAR, ResultKind.TABLE, BOTH, null,
                        "The mean per calendar slot, and the share of the variance the slots explain; needs no gap fill.",
                        Param.choice("kind", null, SLOT_KINDS, "Which calendar slot."),
                        Param.column("valueColumn", "The measured column."),
                        Param.columns("keys", "Optional series keys.")),
                f("timeseries.changepoint", "Change points", Kernel.TIME_SERIES, Cost.LINEAR, ResultKind.COLUMN, BOTH,
                        "transform.timeseries.changepoint",
                        "Flags residuals outside a band, a run of them (a regime change) and slow drift (CUSUM), per series.",
                        Param.column("orderColumn", "Orders each series; evenly spaced, no gap."),
                        Param.column("residualColumn", "Actual minus expected."),
                        Param.decimal("sigma", null, 0, Double.MAX_VALUE,
                                "The residual's typical spread, above 0; the SQL form reads it from the sigma column."),
                        Param.column("sigmaColumn", "A column holding each series' own sigma (SQL form)."),
                        Param.columns("keys", "Optional series keys; one series per key."),
                        Param.decimal("z", 4.0, 0, 1_000_000, "Band half-width, in sigma."),
                        Param.integer("regimeRun", 3, 2, 1_000_000, "Consecutive outside points that make a regime change."),
                        Param.decimal("cusumK", 0.25, 0, 1_000_000, "CUSUM allowance, in sigma."),
                        Param.decimal("cusumH", 8.0, 0, 1_000_000, "CUSUM decision limit, in sigma."),
                        Param.integer("warmup", 0, 0, 1_000_000, "Leading points that carry no flag."),
                        new Param("rebase", ParamType.BOOLEAN, "false", null, null, List.of(),
                                "After a regime change or drift, measure later residuals from the new level.")),

                // ── cohort ──────────────────────────────────────────────────────────────────────────────────
                f("cohort.retention", "Cohort retention", Kernel.COHORT, Cost.LINEARITHMIC, ResultKind.TABLE, BOTH,
                        "transform.cohort.retention",
                        "Per cohort (the bucket of an entity's first event) and period: the cohort's size and how many "
                                + "are active. A period not yet observed is absent, never 0.",
                        Param.column("entityColumn", "Who is followed."),
                        Param.column("timeColumn", "When each event happened."),
                        Param.choice("grain", null, COHORT_GRAINS, "The cohort and period bucket."),
                        Param.text("through", null, "The as-of: later events and periods are not observed."),
                        Param.text("zone", null, "IANA zone an instant is bucketed in; blank reads the column's own clock."),
                        Param.text("from", null, "First cohort bucket reported; blank for all."),
                        Param.choice("mode", "exact", List.of("exact", "unbounded"),
                                "Active in the period itself, or in it or any later one."),
                        Param.integer("maxPeriods", 120, 0, 1_000_000, "The highest period reported.")),
                f("cohort.funnel", "Funnel", Kernel.COHORT, Cost.LINEARITHMIC, ResultKind.TABLE, BOTH, "transform.cohort.funnel",
                        "Entities reaching each ordered step, from the earliest step-1 event, with the rate from the "
                                + "previous step and from the first.",
                        Param.column("entityColumn", "Who goes through the funnel."),
                        Param.column("stepColumn", "Names what each event is."),
                        Param.column("timeColumn", "When each event happened."),
                        Param.text("steps", null, "2 to 12 distinct step names, in funnel order."),
                        Param.text("within", null, "Longest time from the first step to a later one; blank for no limit.")),

                // ── model kernels ───────────────────────────────────────────────────────────────────────────
                f("model.kmeans", "k-means clustering", Kernel.MODEL, Cost.LINEAR, ResultKind.GROUPS, BOTH, null,
                        "Seeded k-means over z-scored columns; clusters are numbered by centroid order. The fit runs on "
                                + "the JVM; the SQL form assigns rows to stored centroids.",
                        Param.integer("k", null, 2, 64, "Number of clusters."),
                        Param.integer("seed", null, Integer.MIN_VALUE, Integer.MAX_VALUE, "Seed of the k-means++ start."),
                        Param.integer("maxIterations", 100, 1, 1_000, "Sweeps before the fit stops."),
                        Param.integer("maxPoints", 200_000, 1, 2_000_000, "Most rows fitted; more is refused, never sampled.")),
                f("model.logistic_regression", "Logistic regression", Kernel.MODEL, Cost.LINEAR, ResultKind.COLUMN, BOTH, null,
                        "Ridge-penalised logistic regression fitted on the train rows of a seeded holdout split; metrics "
                                + "for train, validation and test. The fit runs on the JVM, offline; the SQL form scores rows.",
                        Param.integer("splitSeed", null, Integer.MIN_VALUE, Integer.MAX_VALUE,
                                "Seed of the holdout split's key hash."),
                        Param.integer("train", null, 0, 10_000, "Train share, in basis points."),
                        Param.integer("validation", null, 0, 10_000, "Validation share, in basis points; test is the rest."),
                        Param.decimal("l2", 1.0, 0, 1_000_000, "Ridge penalty on the standardised coefficients; above 0."),
                        Param.integer("maxIterations", 25, 1, 100, "Newton steps before the fit stops."),
                        Param.integer("maxRows", 200_000, 1, 2_000_000, "Most rows fitted; more is refused, never sampled.")),
                f("model.isolation_forest", "Isolation forest", Kernel.MODEL, Cost.LINEARITHMIC, ResultKind.COLUMN, JVM, null,
                        "Seeded isolation forest: a score in [0, 1] from how quickly random splits isolate a point. "
                                + "Behind an Anomaly Model with method isolation_forest.",
                        Param.integer("trees", 100, 1, 1_000, "Number of trees."),
                        Param.integer("sampleSize", 256, 2, 4_096, "Rows drawn per tree, without replacement."),
                        Param.integer("seed", null, Integer.MIN_VALUE, Integer.MAX_VALUE, "Seed of the draws."),
                        Param.integer("maxPoints", 200_000, 1, 2_000_000, "Most rows fitted; more is refused, never sampled.")));
    }
}
