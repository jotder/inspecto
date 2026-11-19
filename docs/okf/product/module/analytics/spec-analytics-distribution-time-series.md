---
type: Architecture
title: Analytics Kernel — canonical statistics, the column profiler, time-series primitives, the baseline and score output contracts — Distribution Steps, score contract and time-series primitives
description: Part 2 of 3 of Analytics Kernel — canonical statistics, the column profiler, time-series primitives, the baseline and score output contracts: Distribution Steps; Score output contract; Tests; Time-series primitives; Accuracy and backtest; Seasonality detection; Hour of week and seasonal profile; Change-point and regime detection; Windowed and ratio Measures
tags: [analytics, statistics, baseline, time-series, scores, similarity, entity-grouping, cohort, change-point, modules, duckdb]
timestamp: 2026-10-18T00:00:00Z
item: module.analytics
audience: internal
status: current
verified: 2026-11-19 last-edit
code: platform/inspecto-analytics
---

# Analytics Kernel — canonical statistics, the column profiler, time-series primitives, the baseline and score output contracts — Distribution Steps, score contract and time-series primitives

Part 2 of 3 of [Analytics Kernel — canonical statistics, the column profiler, time-series primitives, the baseline and score output contracts](spec-analytics.md) · previous: [The module, definitions, functions and the profiler](spec-analytics-module-functions.md) · next: [Cohorts, ranking, motifs, forecasting and model kernels](spec-analytics-cohorts-forecasting.md).

## Distribution Steps

*ANALYTICS-CORE-1 S3, built 2026-10-17.* Package `com.gamma.analytics.distribution`. Three Steps — outlier flags, bins and
correlation — built **on the definitions above**: no statistic of their own (the duplication guard's category c stays
at 0). Each SQL form names the `Stats` definition it renders and a parity test holds the two equal.

| Piece | Kernel | Definition it rests on | Step |
|---|---|---|---|
| Tukey fences per row | `OutlierSql` (`Method.IQR`) | `Stats.tukeyFences(values, k, QuantileMode)` — `Q1 − k·(Q3 − Q1)`, `Q3 + k·(Q3 − Q1)`; strictly outside = outlier | `transform.stats.outlier` |
| Robust z per row | `OutlierSql` (`Method.ROBUST_Z`) | `Stats.robustZ` with `Stats.robustZFloor` — DuckDB `median`, `mad`; flag `abs(z) > threshold` | `transform.stats.outlier` |
| Equal-width bin | `Binning.equalWidthIndex` / `equalWidthIndexSql` | `min(⌊(x − lo) / (hi − lo) · bins⌋, bins − 1)`, 0 when `hi = lo` — **also `ColumnProfiler.histogram`'s bin** | `transform.data.binning` |
| Quantile bin | `Binning.quantileEdges` / `quantileIndex` | edges `Stats.quantile(x, i / bins, mode)`; bin = interior edges at or below x | `transform.data.binning` |
| Pearson r | `CorrelationSql` (`corr`) | `Stats.correlation` — `Σ(x − x̄)(y − ȳ) / √(Σ(x − x̄)² Σ(y − ȳ)²)`, empty when a side is constant | `transform.stats.correlation` |
| Least-squares line | `CorrelationSql` (`regr_slope`, `regr_intercept`, `regr_r2`, `regr_count`) | `Stats.linearRegression` — slope `Sxy / Sxx`, intercept `ȳ − slope · x̄`, r2: empty for constant x, **1 for constant y**, else r² | `transform.stats.correlation` |

Added to `Stats` for these: `tukeyFences(values, k, mode)` (the quartiles in an explicit `QuantileMode`; the two-argument
form stays `CONTINUOUS`), `correlation(x, y)` and `linearRegression(x, y)` → `Regression(count, slope, intercept, r2)`
(input rules as every statistic: equal lengths, no NaN, not empty).

### Edge rules, written down

* **Missing values.** The SQL reads the column as `DOUBLE` with **NaN read as NULL**; a NULL is missing — it gets no
  flag, score or bin, and is not counted in its group's statistics. The JVM forms refuse NaN (as all of `Stats`), so the
  two count the same values. Correlation counts a pair only when **both** values are present (`n`).
* **Groups.** `PARTITION BY` / `GROUP BY` the group columns; a NULL key is a group of its own. No group = the whole
  column.
* **Inclusive / exclusive.** Outlier: strictly outside the fences (a value on a fence is not an outlier); `|z|` strictly
  above the threshold. Bins: `[lower, upper)`, the **last bin closed** `[lower, upper]`; a value on an edge opens the
  next bin; for quantile bins, ties always share a bin (the higher one when a value equals an edge).
* **Out of range.** Equal-width bins over a **stated** `min` / `max`: a value below or above gets no bin (NULL index and
  label) — never clamped. Over the group's own range nothing is out of range.
* **Degenerate groups.** A group of one value: fences equal to it (never flagged); robust z 0. All values equal (one
  value included): equal-width bin 0, quantile bin `bins − 1` (every edge equals the value). Correlation: a constant side has no r,
  a constant x no line, a constant y slope 0 and r2 1; DuckDB's NaN for these is written as NULL — **never NaN**.
* **Quantile mode.** `CONTINUOUS` (`quantile_cont`, D-AN3's canonical mode) by default; `DISCRETE` (`quantile_disc`) when
  stated, for the fences and the quantile edges.

### Parity

* `OutlierDuckDbParityTest` — four groups of 7–70 seeded values with planted highs, lows and heavy ties, NULL and NaN, a
  NULL key, an all-missing group, a one-value and a constant group; IQR in both quantile modes at k 0 / 0.75 / 1.5 / 3,
  robust z at two units and thresholds: fences and z within 1e-9 × max(1, |value|), flags equal row by row.
* `BinningTest` — hand figures, then equal width (group range and stated ranges with out-of-range values) and quantile
  bins (both modes, 1–10 bins) row by row: the **bin index equal**, the label's edges within 1e-9 (DuckDB's
  `quantile_cont` and the JVM's can differ in the last bit); `ColumnProfiler.histogram`'s counts = the Step's bins.
* `CorrelationDuckDbParityTest` — groups of every size 1–30 with NULL and NaN on either side, constant x, constant y, both
  constant, a NULL key, no complete pair: within 1e-9; ⚠ a group on a **1e9 offset** agrees only within 1e-8 — DuckDB's
  streaming covariance loses digits there (measured slope error 5e-12 relative at 1e6, 1.7e-9 at 1e9, 5e-6 at 1e12; the
  two-pass JVM form does not). The Step documents it; centre such data first.

### Histogram — no separate Step

`ColumnProfiler.histogram` (M4) stays the profiler's one-column histogram (every bin listed, empty ones with 0) and now
computes its bin with `Binning.equalWidthIndexSql` — one equal-width definition. A Pipeline gets a histogram as
`binning` then `summarize` (`group_by: [bin, bin_label]`, `measures: [count]`). A separate Step would add a fourth
grammar for the same bins and differ only in listing empty bins — not worth a node type; reopen if a consumer needs the
empty bins in a Pipeline.

### The Steps

`DistributionStep` (engine) owns the three grammars; `RowShaper`, the recipe compiler and the save gate (`ConfigRoutes`)
refuse with its words. Keys, outputs and release bar: [step-catalog](../elt/spec-step-catalog.md) § `outlier`,
§ `binning`, § `correlation`. Base in every edition (D-AN7). The catalog's *Pearson & Spearman correlation matrix* is
**DELIVERED** (2026-10-18): `method: spearman` runs the same figures over the within-group *average ranks*
(`Stats.ranks`, `Stats.spearman`, `CorrelationSql.Method.SPEARMAN`; ties share the average of the ranks they span, so
ranks are multiples of 0.5 and exact; parity with the JVM form in `CorrelationDuckDbParityTest`), so `slope` /
`intercept` / `r2` then describe the line through the ranks. The default stays Pearson, so no shipped figure moved. In the engine: `RowShaperDistributionTest`,
`PipelineJobRunnerTest#runsAFlatConfigsOutlierAndBinningStepsAndWritesFlagsAndBins`,
`PipelineJobRunnerTest#runsAFlatConfigsCorrelationStepAndWritesOneRowPerPair`; demos `examples/07-steps/outliers` and
`examples/07-steps/correlation`.

## Score output contract

Decision **D-AN6** (fulfils the anomaly design's **D-AD5**, "extracted, never copied"): what every score kind writes
and hands on lives once, here, so a future Prediction kind gets it by depending on the kernel. Anomaly Scores and
Risk Scores both write through it; neither module depends on the other.

| Piece | Where | What it fixes |
|---|---|---|
| Output identity | `ScoreKind(kind, scoresPrefix, ownerMarker)`; `LATEST_SUFFIX` | history `<prefix><model>` and `<prefix><model>_latest`, both derived, never authored; the marker file that proves a directory is this model's |
| Column schema | `ScoreColumns.table(kindColumns)` | `model, entity_type, entity_key`, then the kind's own columns, then `model_version, run_id, scored_at`; each column's DuckDB type and Dataset role |
| 0–100 scale and bands | `ScoreScale` | `clamp` (Risk Score: Σ contribution held to [0, 100]); `saturating` (Anomaly Score: `100 · (1 − exp(−raw / scale))`, D-AD6); `isHigh` (inclusive); `band` (`normal` / `elevated` / `high`); `bandCounts` |
| Entity cap | `EntityCap` | default 200,000, ceiling 2,000,000, the per-kind `-D` property parse (malformed or ≤ 0 keeps the default), model cap over property. The run FAILS past it (D-AD10); the refusal's wording stays the kind's |
| Run files and retention | `ScoreRunFiles` | one file `scores-<epochMillis>-<runId>.parquet` per run (run id sanitised to `[A-Za-z0-9_-]`); `Retention(days, runs).expired(...)` — opt-in, whole runs, never `_latest` or the run just written |
| Explanation part | `ExplanationPart` (`toMap`, `weight`, `contribution`) | the explanation column is the JSON array of the parts' `toMap()`, so a score is recomputable from the row alone |

**Where the I/O lives, and why.** The kernel may depend only on the foundation layer and carries no third-party
library (D-AN8), but writing the outputs needs DuckDB, Jackson and the ownership-marker mechanics. So the writer is
`com.gamma.alert.ScoreOutputWriter` in the **engine**, beside `ScoreOutputDirs` (marker claim, atomic `_latest` swap,
reserved-prefix test), which was already the D-AD5 home both optional modules reach: `write` (claim both
directories, write `*.tmp`, atomic move, swap `_latest`, prune), `version` (12-hex SHA-256 of the model minus its
envelope, the `model_version` column), `explanationJson`, and `meta` (the Job's artifact shape from the columns).
The engine depends on the kernel; nothing depends the other way, so there is no cycle. The core's `afterScore`
pending Alert Rule (`PendingAlertRules`, D-AD7) reads each kind's `ScoreKind` from `RiskScoreOutputs.SCORE_KIND` /
`AnomalyScoreOutputs.SCORE_KIND`. The watch-list feed is `WatchListFeed.feedScored` in `inspecto-entity-list`, the
module that owns the seam: no provider installed fails the run, no high entity writes nothing, the actor is
`job:<jobType>:<model>`, the entry expires `ttlHours` after the run.

**What stays in each kind's module:** the model spec and its save gates, the arithmetic (Risk: capped
`weight × indicator`; Anomaly: robust z, weighted RMS), the explanation part's fields, `recompute`, the kind's own
columns, the run-failure and refusal wording, and which entities are "high" (Risk: `high` flag; Anomaly: band
`high`). Anomaly Scores keep no retention (every run stays); Risk Scores pass the model's `retainDays` /
`retainRuns`.

**Proof nothing moved:** `RiskScoreOutputGoldenTest` and `AnomalyScoreOutputGoldenTest` were committed against the
pre-M7 code and pass unchanged after it — every written row (Anomaly: 306 rows by SHA-256 plus the planted rows in
full), column names and types, file names, marker, model version hash, band counts, recompute, the watch-list
feed's arguments and refusal, retention and the entity cap.

## Tests

* `StatsTest` — a hand-computed figure per definition (ratio and rate included), the input rules, `LOWER` = `VolumeBaseline`'s rule and
  `NEAREST_RANK` = `ReportService.percentile` over random inputs.
* `StatsDuckDbParityTest` — the same inputs (hand-picked shapes, every size 1–41, 1,000 values with many ties,
  1,000 values around 10⁶) through `Stats` and through DuckDB 1.5.6 (`avg`, `var_pop`, `var_samp`, `stddev_pop`,
  `stddev_samp`, `median`, `mad`, `quantile_cont`, `quantile_disc` at 13 fractions, and the IQR), and `ratio` / `rate` against division by `NULLIF(·, 0)`. Agreement within
  1e-9 × max(1, |value|); `quantile_disc` and the odd-count median agree exactly.
* `RobustStatsTest`, `BaselineStatisticsTest` — the moved baseline package.
* `ColumnProfilerTest` — the profiler against DuckDB on numbers, text, dates, NULLs, an all-NULL column and an empty
  relation; both layouts, groups, approximate distinct, quantile modes against `Stats`, top values, the histogram.

## Time-series primitives

*ANALYTICS-CORE-1 M5, built 2026-10-17.* Package `com.gamma.analytics.time`. Like the column profiler, it quotes
through `inspecto-util`'s `SqlIdent` (the one quoting implementation) — still only the foundation layer.

### One truncation definition — `TimeGrain`

Grains `SECOND`, `MINUTE`, `HOUR`, `DAY`, `WEEK`, `MONTH`, `QUARTER`, `YEAR`. A week starts on **Monday** (ISO, as
DuckDB's `DATE_TRUNC('week', …)`); a quarter in January, April, July or October. Two forms, each a JVM function and a
SQL builder:

| Form | JVM | SQL | For |
|---|---|---|---|
| Wall clock | `truncate(LocalDateTime)`, `truncate(LocalDate)` | `truncateSql(expr)` = `DATE_TRUNC('<grain>', expr)` | a `DATE` or zone-less `TIMESTAMP` — the value's own clock, no zone involved |
| Zoned label | `label(Instant, ZoneId)` → local start | `labelSql(expr, zone)` → `TIMESTAMP` | a `TIMESTAMPTZ` bucketed on an explicit zone's wall clock |
| Zoned start | `truncate(Instant, ZoneId)` → instant | `truncateSql(expr, zone)` → `TIMESTAMPTZ` | the same, as an instant |

⛔ **The zone is always explicit; DuckDB's session `TimeZone` is never read.** It is the host's, so `DATE_TRUNC` over a
`TIMESTAMPTZ` would change with the machine. The zoned SQL converts with `timezone(zone, …)` both ways and truncates the
zone-less local value; the parity test runs with the session zone set to `Pacific/Chatham` to prove nothing leans on it.
A zone is a region id or UTC; a fixed offset such as `+05:30` is refused (DuckDB's ICU names do not include them).

**DST rules** (zoned start): for `SECOND`/`MINUTE`/`HOUR` the start is the input minus its local remainder, so it keeps
the input's own offset and the two occurrences of a repeated hour are two buckets (their **label** is the same). For
`DAY` and coarser the local start is resolved in the zone: inside a skipped gap it moves later by the gap (Chile's
midnight); a start that occurs twice takes the **later** offset — DuckDB's (ICU's) rule, kept so both forms agree. Only a
zone that falls back across midnight (America/Havana) meets that case, and there an instant in the first occurrence of
the repeated hour gets a day start after itself; its label is still the right day.

### Resample and shift — `TimeSeriesSql`

* `resample(Resample)` — one row per key per bucket from the first to the last bucket (given bounds, or the data's
  own), built with `generate_series` in wall-clock time. **Fill rule** (Anomaly D-AD8): `Fill.ZERO` for `count`,
  `countDistinct` and `sum`, `Fill.NULL` otherwise (`Fill.forAggregate`). Keys are joined `IS NOT DISTINCT FROM`, so an
  empty key is a series of its own. A series of `maxBuckets` or more fails the run through DuckDB's `error()`. The
  aggregates are caller-built SQL — the engine passes `MeasureCompiler.aggregateSql`, so the Measure grammar is still
  evaluated only by `MeasureCompiler`.
* `shift(Shift)` — every row gains each listed column from `periods` buckets earlier (negative = later), by a self-join
  on `label(p) + N·interval = label(c)`: a missing bucket gives NULL, never the wrong period. `duplicateBucketsSql`
  counts (key, bucket) pairs with more than one row, which a caller must refuse first.

Exposed as the Steps `transform.timeseries.resample` and `transform.timeseries.shift`
([step-catalog](../elt/spec-step-catalog.md)), through the engine's `TimeSeriesStep` (grammar, clock/type checks).
Release bar (2026-10-17): documented; real-path tests (`RowShaperTimeSeriesTest`, `PipelineJobRunnerTest`); lift/lower
round trip (`NodeConfigNameContractTest`, `PipelineEditableTest`) — the dry run walks every `transform.*` node through
the same `RowShaper` arm, with no test of its own; palette `addable` from the regenerated contract; Base in every
edition; refused at save (`StepConfigSaveFindingsTest`); demo `examples/07-steps/timeseries`, run with
`serve-example --demo`.

### The truncation copies — retired

| Copy | Now | Clock | Proof the output did not move |
|---|---|---|---|
| `FileSequenceGaps.truncate` (acquire) | `TimeGrain.of(unit).truncate(t)` | wall (a file-name date has no zone) | `FileSequenceGapsBucketStartGoldenTest` (every unit), `FileSequenceGapsTest` |
| `DeliveryCheck` current bucket (engine) | `TimeGrain.of(unit).truncate(now)` | wall, on UTC as before | `DeliveryCheckTest#theLastCompleteBucketIsTheOneBeforeNowAtMonthYearAndHourGrain` |
| `KpiDefinition.Grain.start` | `TimeGrain.valueOf(name()).truncate(d)` | `LocalDate` | `KpiPeriodStartGoldenTest`, `KpiDefinitionTest`, `KpiEvaluatorTest` |
| `MeasureCompiler.dimExpression` | `TimeGrain.parse(grain).truncateSql(col)` | wall — the SQL text is byte-identical | `MeasureCompilerTest` (pins the text), `MeasureCompilerGrainExecutionTest` |
| `DashboardDateRange` `month-` / `quarter-` / `year-to-date` (control plane, shared-Dashboard fence) | `TimeGrain.MONTH` / `QUARTER` / `YEAR` `.truncate(anchor)` | `LocalDate`; the one zone, "today" for a Dashboard with no `asOf`, is passed explicitly (`DashboardDateRange.today(zone)`, the server's `ZoneId.systemDefault()` — the SPA twin `dashboard-date-range.ts` reads the browser's and is unchanged) | `DashboardDateRangeTest#monthAndYearToDateAtEveryBoundary` (pinned first), `#quarterToDateIsCalendarCorrectAtEveryBoundary` |

Each golden test was written and committed against the old code, then passed unchanged after the switch.
⚠ **Kept, by decision:** `RecordTransform`'s `date.truncate` function (`DATE_TRUNC({unit}, {source})`) is not a rule of
its own — it hands the author's free-text unit to DuckDB, any date part included (`decade`, `millisecond`), and
narrowing it to `TimeGrain` would refuse units that run today.

### Tests

`TimeGrainTest` (hand figures, DST and week cases), `TimeGrainDuckDbParityTest` (every grain × form over instants packed
around eight transitions — Berlin both ways, New York, Santiago's midnight gap, Havana's midnight overlap, Lord Howe's
30-minute shift, Kolkata, UTC — plus ISO week 53 and year/quarter/leap-day edges; a mutation to the earlier-offset rule
turns it red), `TimeSeriesSqlTest` (resample and shift executed on DuckDB), and in the engine
`RowShaperTimeSeriesTest`, `PipelineJobRunnerTest#runsAFlatConfigsResampleAndShiftStepsAndWritesAMonthOverMonthSeries`.

* `ScoreContractTest` — the score output contract's pure parts (names, scale and bands, cap, columns, run files,
  retention); the writer is pinned by the two kinds' golden tests above.

## Accuracy and backtest

*ANALYTICS-CORE-1 S5b, built 2026-10-17.* Package `com.gamma.analytics.accuracy`. Model-agnostic: it scores any
forecast or prediction, whatever model made it.

### Accuracy measures — `Accuracy` (JVM), `AccuracySql` (DuckDB)

Error **e = predicted − actual**; a positive bias means the model over-forecasts.

| Measure | Definition | Output column |
|---|---|---|
| MAE | Σ\|e\| / n | `mae` |
| RMSE | √(Σe² / n) | `rmse` |
| MAPE (percent) | 100 · mean of \|e\| / \|a\| over the pairs whose actual is **not zero**; a zero actual is **excluded and counted** (`mapeZeroActuals`), never divided by; no value (empty / NULL) when every actual is zero | `mape`, `mape_zero_actuals` |
| sMAPE (percent, 0–200) | 100 / n · Σ 2\|e\| / (\|a\| + \|p\|) — the M3 / Hyndman–Koehler form (denominator = mean of the absolute values); a pair with a = p = 0 adds 0 and is counted | `smape` |
| Bias | Σe / n (mean error) | `bias` |
| Interval coverage | share of pairs with lower ≤ a ≤ upper, both ends inclusive | `coverage` |
| Mean interval width | Σ(upper − lower) / n | `mean_interval_width` |

**Why these choices.** MAPE's zero rule: dropping and counting is the only rule that neither invents a number nor
divides by zero, and the count tells the reader how much of the series MAPE ignored (on intermittent series, read
sMAPE or MAE instead). sMAPE: of the two common forms — (\|a\| + \|p\|) / 2 as denominator (range 0–200) or
\|a\| + \|p\| (range 0–100) — the first is the one the M3 competition and Hyndman–Koehler published, so a figure
compares with the literature. Bias sign: predicted − actual reads naturally as "how much too high".

**Input rules** are `Stats`': no pairs, unequal lengths, NaN, half a band (only one of lower and upper — the message
`a band needs both lower and upper`, as `AccuracySql.Spec`), or a band with lower > upper throw. The SQL form casts each
column to `DOUBLE`, skips a row with a NULL in any column it uses, fails the statement through `error()` on an inverted
band, and returns `n = 0` with NULL measures for no pairs. `AccuracySql.Spec` takes optional group columns: one row per
group (per entity, per model).

### Rolling-origin backtest — `Backtest`

`Backtest.run(series, Spec(minTrain, horizon, step[, maxOrigins]), ForecastFn)`. `ForecastFn` is any forecaster:
history (oldest first) and a horizon → a `Forecast(point, lower, upper)`, the band optional.

* **Origins** t = minTrain, minTrain + step, … while t + horizon ≤ n — only origins whose whole horizon is observed,
  so every origin scores exactly `horizon` pairs.
* **Expanding window**: the forecaster gets a **copy** of x[0..t−1] — everything before the origin, nothing after.
* **Result**: each origin (its actuals, forecast and `Accuracy.Summary`), the measures over every pair, and
  `byHorizon` — the measures of the 1-step-ahead pairs, the 2-step-ahead pairs, and so on.
* **Deterministic**: origins in order, nothing random — a deterministic forecaster gives the same result every run.
* **Fail closed** (D-AD10), never a silent subset: a series longer than 100,000 values; more origins than `maxOrigins`
  (default 500, ceiling 5,000); more than 1,000,000 scored pairs; a NaN in the series (gap-fill first with
  `transform.timeseries.resample`); a forecast of the wrong length, with a NaN, with lower > upper, or with a band at
  some origins and not others.

JVM only: the driver calls a JVM forecaster; the measures it reports are `Accuracy`'s, which have the SQL form above.

## Seasonality detection

*ANALYTICS-CORE-1 S5c, built 2026-10-17.* `com.gamma.analytics.time.SeasonalityDetector` — the dominant period of an
evenly spaced series, chosen among candidate periods, so a declared period becomes optional.

**ACF** — the standard (Box–Jenkins) **biased** estimator: r(k) = Σ_{t<n−k} (x_t − x̄)(x_{t+k} − x̄) / Σ_t (x_t − x̄)².
The denominator is the whole series' sum of squares (not n − k terms), so r(k) stays in [−1, 1] and shrinks by
(n − k) / n: of two equally strong periods the shorter scores higher (14 never beats 7 on a weekly signal). Undefined
(empty / NULL) for a constant series; 0 at a lag ≥ n.

**Rule.** A candidate k ≥ 2 is eligible when (1) **minimum cycles**: n ≥ 3k (`Params.minCycles`, ≥ 2); (2)
**significant**: r(k) ≥ max(0.3, 1.96 / √n) — above the 95 % white-noise band and a floor (`Params.minAcf`) that a long
series' tiny band cannot undercut; (3) **a peak**: r(k) > r(k − 1) and r(k) > r(k + 1), so a trend's slowly decaying ACF
is not read as a season. The period is the eligible candidate with the highest r(k), a tie to the shorter; none → no
period. Every candidate is returned with its r(k − 1), r(k), r(k + 1) and a verdict (`TOO_FEW_CYCLES`,
`CONSTANT_SERIES`, `NOT_SIGNIFICANT`, `NOT_A_PEAK`, `ELIGIBLE`, `CHOSEN`) — the explanation. Series longer than
1,000,000 values are refused.

**Default candidates** (`candidatesFor(TimeGrain)`): second 60; minute 60, 1440; hour 24, 168; day 7, 365; week 52;
month 12; quarter 4; year none. A caller may pass its own list.

**SQL form.** `acfSql(relation, orderColumn, valueColumn, keys, lags)` → `<keys…>, n, lag, acf` per key, the series
being the key's rows ordered by `orderColumn` (one row per bucket), NULL values left out. `lagsFor(candidates)` names
the lags the rule reads and `decide(n, candidates, params, acfAt)` applies the rule to those rows — the JVM `detect`
calls the same `decide`, so the two forms share one decision.

⚠ The series must have no gaps (resample first); a gap shifts every lag. Detrending is the caller's choice (a first
difference through `transform.timeseries.shift`); the peak rule only keeps a trend from being *chosen*.

## Hour of week and seasonal profile

*ANALYTICS-CORE-1 C5, built 2026-10-18.* Hour buckets were already in the M5 primitives (`TimeGrain.HOUR`, zoned,
DST rules above) and the hourly candidates 24 and 168 already in `SeasonalityDetector`; C5 adds what was missing, the
**calendar slot** and the **profile over it**.

* `HourOfWeek` (`HOUR_OF_DAY` 24 slots, `WEEKDAY` 7, `HOUR_OF_WEEK` 168) — `slot(LocalDateTime)`, `slot(Instant, ZoneId)`,
  `slotSql(expr)` (wall clock) and `slotSql(expr, zone)` (a `TIMESTAMPTZ` on an explicit zone's wall clock). **The clock is
  always stated; never DuckDB's session zone.** Weekday is ISO, Monday = 0; hour of week = weekday × 24 + hour (Monday
  00h = 0). Both occurrences of a repeated fall-back hour share a slot; a skipped hour has no observations.
* `SeasonalProfile` — `of(kind, slots, values)` / `profileSql` (per key and slot: `n`, `mean`) and `strengthSql` (per key:
  `n`, `strength`). **Strength** = Σ_obs (slot mean − mean)² / Σ_obs (x − mean)², population arithmetic, in [0, 1], NULL
  for a constant series: "does a weekday×hour shape explain this series?". It complements `SeasonalityDetector`
  (which period repeats, on an evenly spaced series); it needs no gap fill. ⚠ With 168 free means a short noise series
  scores well above 0 (about 168/n), so read strength against the observations per slot.
* No Step: the slot is a one-line SQL expression and the profile feeds `SeasonalBaseline` callers; a Step can follow
  the first consumer (D-AN10).

Tests: `HourOfWeekTest` (Monday/Sunday ends, zone changes the slot, labels, a pure shape explained to 1.0, constant and
bad input), `HourOfWeekDuckDbParityTest` (slot SQL = JVM for 3 kinds × 4 zones across the New York and Berlin DST
weeks, under two different session zones; profile and strength per key, a NULL value left out, no-key form).

## Change-point and regime detection

*ANALYTICS-CORE-1 C6, built 2026-10-18.* `com.gamma.analytics.time.ChangePoint` — the regime-change and drift logic of
the Business Assurance forecast template, lifted out of its Holt-Winters recursion into a function of the **residuals**
`e_t = actual − expected`, so any model (the S5 forecaster, a baseline, a peer median) can feed it. JVM `detect(e,
Params)` and DuckDB `sql(relation, orderColumn, residualColumn, sigmaColumn, keys, Params)` (a recursive CTE per key),
one rule.

**Rule**, per point with `a_t = e_t − offset` and σ the caller's residual scale: *outside* = |a_t| > z·σ; *run* = consecutive
outside points; **regime change** = run ≥ K, raised once on the K-th point then the run restarts; a two-sided **CUSUM**
over inside points only (`S⁺ ← max(0, S⁺ + a/σ − k)`, `S⁻ ← max(0, S⁻ − a/σ − k)`; an outside point leaves the sums
alone, so a spike or a shift never feeds them); **drift** = an inside, non-regime point with S⁺ or S⁻ > h. After a regime
change or a drift the run and the sums restart at 0. **`outside_band`** = an outside point in a run whose longest
`run_at` stayed below K (a run that became a regime change is that one event); it looks at the whole run, so it is
retrospective, as the template's. Leading `warmup` points update the state but carry no flag. Defaults `Params.of(σ)`
are the template's, tuned on its corpus: z 4, K 3, k 0.25, h 8.

**`rebase`** (default off). Without it the detector assumes the model re-levels itself after a regime change (the
template's Holt-Winters does: its forecast restarts from the actual). A model that does not keeps producing large
residuals and the rule would raise a regime change every K points; `rebase` moves the offset by `a_t` at each regime
change or drift so the new level reads as residual 0. Level only — a drift's slope is not estimated.

**σ.** `scaleOfDifferences(x, lag)` = 1.4826 · MAD(x_t − x_{t−lag}) / √2 (a difference of two equal-variance errors has
variance 2σ²), the template's estimate (lag 7 for a daily series with a weekly season). The SQL form reads σ from a column
that is constant per key, so per-key σ is the caller's one-line aggregate.

**Golden — the template is the reference.** `ChangePointDuckDbParityTest#theKernelReproducesTheTemplatesOwnFlagsFromItsResiduals`
runs `ba_revenue_forecast_view.toon` as shipped and in four variants (level shift up, down, ramp up, down) and feeds the
kernel the template's own `actual − forecast`: `outside_band`, `regime_change` and `drift_change` are equal on every one
of its 175 days in all five corpora (rows 8..182; warm-up 21 = the template's 28 days). The template itself is **not
rewired** to the kernel: its recursion is one state machine shared with the forecaster, and no shipped number moves.

**Step** (2026-11-01): `transform.timeseries.changepoint` — [Change-point and cohort Steps](spec-analytics-cohorts-forecasting.md#change-point-and-cohort-steps).
The template still raises its flags in a `sql.template` Job (D-AN11); a pipeline gets the residual from a `sql` Step
after a `forecast` (`actual - forecast` where `horizon = 0`).

Tests: `ChangePointTest` (spike, K-run, one short of K, persistent shift with and without `rebase`, ramp, outside points
not feeding the CUSUM, warm-up, negative shift, σ estimate by hand, input rules); `ChangePointDuckDbParityTest` (SQL =
JVM for 3 keys with different σ, a NULL key, a NULL residual, `rebase` × warm-up; and the template golden above).

## Windowed and ratio Measures

*ANALYTICS-CORE-1 C1 (`PREDICTIVE-WINDOWED-MEASURES-1`), built 2026-10-18; decision D-AN12.* Entity Measures over a
trailing window as of a date — recency, frequency, sum — and ratios of aggregates, the shared inputs of RFM and churn.
Three additions to `MeasureCompiler.Spec`, all optional, so no existing spec, call site or shipped number moves; the
Java compiler, its TypeScript twin (`viz/query-spec.ts`) and the contract JSON change in one commit.

* **`window: {field, asOf, days?}`** — only rows whose `field`, **read as a DATE** (a timestamp's own wall-clock date, no
  zone involved), is in `(asOf − days, asOf]` feed *every* measure and ratio of the spec; `days` absent = no lower bound.
  `asOf` (`YYYY-MM-DD`) is mandatory — never "now" — so a run is reproducible. `days` 1..3660. Unknown keys refused.
  It is a **row restriction, not a Measure**: a Measure stays an `{agg, field}` pair (parked-rows D10 stands).
* **`recency`** — the one window-relative aggregate, in its own list `AS_OF_AGGS` (not in `AGGS`): whole days from the
  latest `field` in the window to `asOf`, `DATE_DIFF('day', CAST(MAX(f) AS DATE), asOf)`. **Refused without a
  window** (and inside a ratio). Kept out of `AGGS` on purpose: summarize, KPI, Alert Rule and report take `agg(field)`
  shorthand and have no as-of to give, so they keep refusing it at save time instead of failing at run time.
  An entity with **no row in the window has no output row** — so recency is "within the window"; give the window the
  horizon the question needs (churn: at least the inactivity limit), or leave `days` off.
* **`ratios: [{num: {agg, field}, den: {agg, field}}]`** — `CAST(num AS DOUBLE) / NULLIF(CAST(den AS DOUBLE), 0)`:
  **NULL for a zero or empty denominator**, never 0 or ∞. Column id `<numId>_per_<denId>` (`sum_refund_per_sum_amount`).
  A separate list, so "Risk factors take no free arithmetic" (D11) stands: nothing parses an expression; only the fixed
  `AGGS` may appear in `num` / `den`. Frequency is `count` (or `countDistinct` on the date field for active days) and the
  monetary value `sum`, under the window; `recency` + those three are an RFM row.
* **Bound, not rendered.** Both window bounds and `recency`'s as-of are `CAST(? AS DATE)` parameters (parameters are
  collected in text order: select list, then filters, `when`, window); `render` prints `DATE '…'` for display only.
* **The BI route is the boundary.** `POST /bi/query` accepts `window` and `ratios`; `BiResultMasking.vet` refuses a
  ratio whose aggregate reveals values (`max`, `sum`, statistics…) over a masked column and a `window` on a masked
  column, as it does for flat measures and filters. `BiRoutes.overBound` re-points the spec with `Spec.withDataset`, which
  carries the window and ratios (a copy through the old constructor would have dropped them silently). The OpenAPI
  fragment and `docs/api/openapi-v1.json` describe both keys.
* **Not covered:** two windows in one spec (last 30 days against the 90 before — run two specs); a window on a
  `transform.summarize` / KPI / Alert Rule measure (their shorthand has no clause for it); a window anchored on "today"
  (state the date). A Predictive consumer reads this through `MeasureCompiler.parse` / `compile` with its own as-of.

**TypeScript twin.** `QuerySpec.window`, `QueryMeasure.ratio`, `recencyMeasure(field, asOf)`, `ratioMeasure(num, den)`;
`compileSpec` ANDs the window's date terms (`SUBSTRING(f, 1, 10)` compared as ISO text) after the filters; offline
`RECENCY(col, 'asOf')` is registered in `stat-aggregates.ts` (it exists only offline — live, the wire carries
`{agg: 'recency', field}`); `biQueryBody` sends `window` and moves ratio measures to `ratios`. ⚠ The offline window needs
**ISO date strings** in the column (a `Date` object would not compare).

**Contract.** `measure-grammar.contract.json` gains `asOfAggregations`, `window` (`keys`, `maxDays`), `ratio.idSeparator`
and a golden `windowed` corpus (8 events, 4 customers, asOf 2026-06-30; the edges are in it: the as-of day itself is in,
`asOf − 30` is out, a day after the as-of is out, an event 90 days back shows only in the open window, a zero
denominator is NULL). **First consumer (2026-11-01):** the `predict.segment` Job (`PREDICTIVE-SEGMENT-RFM-1`) reads `recency`, `count` / `countDistinct` and `sum` under one window as of its `as_of` and bins them with this module's quantile `Binning` — [`spec-segmentation.md`](../predictive/spec-segmentation.md). Tests: `MeasureWindowTest` (compiled text and parameter order, open window, ratio SQL, every refusal,
the seams `withLimit` / `withOrderBy` / `withDataset`, and the corpus RUN on DuckDB), `MeasureGrammarContractTest`
(the new pins), `ControlApiBiQueryMaskingTest#aRatioOrWindowOverAMaskedColumnIsRefusedForAViewer`, and the vitest
`windowed and ratio measures` block in `query-spec.spec.ts` (the same corpus run offline to the same figures).
