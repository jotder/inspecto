---
type: Architecture
title: Analytics Kernel — canonical statistics, the column profiler, time-series primitives and the baseline contract
description: The platform module inspecto-analytics (ANALYTICS-CORE-1 M1, M2, M4, M5 and the M6 contract) — one written definition per statistic (mean, variance and stddev, median modes, quantile modes, MAD, robust z, IQR, Tukey fences, ratio and rate), the empty/NaN input rules, which DuckDB function each matches, the callers consolidated onto it (M2), the one column profiler and its callers (M4), the time-series primitives (one time-grain truncation with an explicit zone, resample with gap fill, period shift) and the truncation copies they retired (M5), and the baseline package moved from Anomaly Detection.
resource: docs/superpower/analytics-core-plan.md
tags: [analytics, statistics, baseline, time-series, modules, duckdb]
timestamp: 2026-10-17T00:00:00Z
---

# Analytics Kernel

> 🟡 **Status (2026-10-17): M1 (with ratio and rate), the M6 contract, the M4 column profiler, M2 and M5 (time-series
> primitives) are built.** Plan:
> [`analytics-core-plan.md`](../../superpower/analytics-core-plan.md) (row `ANALYTICS-CORE-1`). The column profiler
> serves `transform.profile`, the Expectation `baseline` (profile and median) and Link Analysis' column profile and
> degree quantiles (§ Column profiler); `VolumeBaseline`, `ReportService` and LA's `LinkTemporalDetector` use the kernel
> statistics (see [Callers consolidated](#callers-consolidated)). The SQL-side statistics (`quantile_cont` /
> `quantile_disc` strings, the Assistant outlier tool's `stddev_pop`) stay SQL. The time-grain truncation, resample and
> shift serve the `transform.timeseries.*` Steps, file-sequence gaps, delivery checks, KPI periods and BI grains
> (§ [Time-series primitives](#time-series-primitives)).

## The module

`platform/inspecto-analytics` — artifactId `inspecto-analytics`, manifest id `analytics`, `buildRole: platform`,
`offeringRole: base` (every edition, D-AN1). Pure functions and SQL builders, no routes, no state, **no third-party dependency of its own** (D-AN8).
Main code depends on `inspecto-util` only, for `SqlIdent` (the one SQL quoting implementation the profiler and the time-series builders quote
through); `tools/check-module-deps.mjs` and the pom's enforcer rule allow only the foundation layer (`inspecto-api`,
`-config`, `-sql`, `-util`), so a feature module can depend on the kernel but the kernel can never reach the engine or a
feature. It ships as its own thin jar beside `inspecto.jar`
(`tools/bundle-modules.mjs` `CORE_MODULES`, the shade exclude in `inspecto/pom.xml`).

Packages:

* `com.gamma.analytics` — `Stats` (the definitions below), `MedianMode`, `QuantileMode`, `ColumnProfiler`
  (§ Column profiler).
* `com.gamma.analytics.baseline` — the baseline contract, moved unchanged from `features/inspecto-anomaly`
  (`com.gamma.anomaly.baseline`) by M6: `BaselineStatistic` (the pluggable interface), `BaselineStatistics`
  (the factory), `SeasonalBaseline` + `Seasonality`, `PeerBaseline`, `Baseline`, `BaselineScore`, `RobustStats`.
  `RobustStats` now delegates every member to `Stats`; `RobustStatsTest` pins its figures bit for bit against the
  pre-move code.

## The definitions

Values are sorted ascending as `v[0..n-1]`.

| Statistic | Definition | `Stats` method | DuckDB |
|---|---|---|---|
| Mean | Σx / n | `mean` | `avg` |
| Population variance / stddev | Σ(x − mean)² / n, and its square root (two-pass) | `variancePopulation`, `stddevPopulation` | `var_pop`, `stddev_pop` |
| Sample variance / stddev | Σ(x − mean)² / (n − 1), and its square root; needs n ≥ 2 | `varianceSample`, `stddevSample` | `var_samp`, `stddev_samp` |
| Median, `STANDARD` (default) | odd n: `v[n/2]`; even n: `(v[n/2−1] + v[n/2]) / 2` | `median(x)`, `median(x, STANDARD)` | `median` |
| Median, `LOWER` | odd n: `v[n/2]`; even n: `v[n/2−1]` — always an observed value (`VolumeBaseline`'s rule, D-AN4) | `median(x, LOWER)` | — |
| Quantile, `CONTINUOUS` (canonical, D-AN3) | h = (n−1)q; `v[⌊h⌋] + (h − ⌊h⌋)(v[⌈h⌉] − v[⌊h⌋])` (Hyndman–Fan type 7) | `quantile(x, q)` | `quantile_cont` |
| Quantile, `DISCRETE` | `v[max(1, n − ⌊n − nq⌋) − 1]` — the smallest value whose empirical CDF reaches q (type 1), DuckDB's own index arithmetic | `quantile(x, q, DISCRETE)` | `quantile_disc` |
| Quantile, `NEAREST_RANK` | `v[min(max(⌈qn⌉, 1), n) − 1]`, `ReportService.percentile`'s arithmetic with q = p / 100 | `quantile(x, q, NEAREST_RANK)` | same value as `quantile_disc` |
| MAD (raw) | median(\|x − median(x)\|), both medians `STANDARD` | `mad(x)`, `mad(x, centre)` | `mad` |
| Scaled MAD | 1.4826 × MAD — consistent with σ for normal data | `scaledMad(x)`, `scaledMad(x, centre)` | `1.4826 * mad(x)` |
| Robust z | (observed − median) / max(scaledMad, floor), floor = max(unit, 5 % of \|median\|) | `robustZ`, `robustZFloor` | — |
| IQR | Q3 − Q1, both `CONTINUOUS` | `iqr` | `quantile_cont(x, 0.75) - quantile_cont(x, 0.25)` |
| Tukey fences | [Q1 − k·IQR, Q3 + k·IQR], k = 1.5 by default; a value strictly outside is an outlier | `tukeyFences(x[, k])` → `Fences.outside(v)` | — |
| Ratio | numerator / denominator; **empty** (`OptionalDouble`) when the denominator is ±0 — never NaN | `ratio(num, den)` | `num / NULLIF(den, 0)` |
| Rate | events / total, a share in [0, 1] of a count; **empty** when total = 0 | `rate(events, total)` | `events::DOUBLE / NULLIF(total, 0)` |

⚠ `DISCRETE` and `NEAREST_RANK` are the **same rank** written two ways; the parity test asserts they pick the same
value as `quantile_disc` on every input it runs. They stay separate modes so the LA link statistics (`quantile_disc`)
and `ReportService` can each keep their exact arithmetic when M2 moves them (the plan's first risk: no shipped figure moves).

⚠ The robust-z floor exists so a near-constant history (scaled MAD ≈ 0) cannot turn a one-unit change into an
enormous z. It is Anomaly Detection's rule (design §4.2), kept exactly.

## Column profiler

`ColumnProfiler` (M4) is the one place that writes column-profile SQL. It **builds DuckDB SQL only**: the caller runs it
on its own connection, sandbox or Dataset port. Every identifier is quoted and every literal escaped through `SqlIdent`;
the relation is the caller's already-safe SQL (a quoted table name, or a subquery with its alias). A `Spec` names the
columns, optional group columns, the statistics in output order, and the modes below.

| Statistic | Output column | Definition |
|---|---|---|
| `ROW_COUNT` | `row_count` | `count(*)` — every row, NULLs included |
| `NULL_COUNT` | `null_count` | `count(*) − count(c)` |
| `NULL_RATE` | `null_rate` | null count / row count as DOUBLE; NULL for an empty relation |
| `DISTINCT_COUNT` | `distinct_count` | distinct non-NULL values of `distinctOf(c)` (identity by default); an all-NULL column has 0. `EXACT` = `count(DISTINCT …)`; `APPROXIMATE` = `least(approx_count_distinct(…), count(c))`, a HyperLogLog sketch clamped to the non-NULL rows |
| `MIN`, `MAX` | `min_value`, `max_value` | bounds `TEXT`: compared in the column's own type, reported as VARCHAR (text order for text, date order for dates); bounds `NUMERIC`: over the numeric reading, so a text or date column has none |
| `MEAN` | `mean` | `avg` of the numeric reading = `Stats.mean` of the values that read as numbers |
| `STDDEV` | `stddev` | `stddev_samp` of the numeric reading = `Stats.stddevSample`; NULL below two numbers |
| quantile q | `p<percent>` (`p50`, `p99_5`) | over the numeric reading: `CONTINUOUS` (default, D-AN3) = `quantile_cont`; `DISCRETE` and `NEAREST_RANK` = `quantile_disc` (the same rank) |

The **numeric reading** is `TRY_CAST(c AS DOUBLE)`: a number keeps its value, a boolean reads as 1/0, a text that parses
as a number reads as that number, and anything else (other text, dates, timestamps) is NULL and is not counted.

**Layouts.** `rowPerColumn` — one row per (group, column): `group_<j>…`, `column_name` (as the caller spelled it), the
statistics, the quantiles; one `SELECT` per column joined by `UNION ALL`. `rowPerGroup` — one row per group:
`group_<j>…`, `row_count` once, then per column `i` each statistic as `<output column>_<i>` (`null_count_0`, `p50_1`);
with no column it is the row count per group. Group values are VARCHAR so groups of any type share one shape.

**One column's distribution.** `topValues(relation, column, k)` — `value` (VARCHAR) and `frequency` of the `k` most
frequent non-NULL values, ties by value ascending in the column's own type. `histogram(relation, column, bins)` —
equal-width bins over the numeric reading: `bin`, `lower_bound`, `upper_bound`, `frequency`, empty bins included; bin
`i` covers `[min + i·w, min + (i+1)·w)` with `w = (max − min) / bins`, the last bin closed so the maximum is counted;
equal values all fall in bin 0; no numeric value, no rows. `quantile(expr, q, mode)` is the single-quantile SQL form for
a caller that writes the rest of its statement itself.

**Callers** — each pinned by a golden test written before the switch and green before and after:

| Caller | Uses | Golden test |
|---|---|---|
| `transform.profile` Step (`RowShaper.profile`, engine) | `rowPerColumn`; statistics `ROW_COUNT, NULL_COUNT, DISTINCT_COUNT, MIN, MAX`, bounds `TEXT`, exact distinct — the Step's output columns are unchanged and no statistic is added | `RowShaperProfileGoldenTest` |
| Expectation `baseline` (`BaselineEvaluator.profileSql`, processor) | `rowPerGroup` grouped by `groupBy`; statistics `ROW_COUNT, NULL_COUNT, DISTINCT_COUNT, MIN, MAX, MEAN`, bounds `NUMERIC`; `null_rate` stays computed in Java from the counts. Its median is `Stats.median(…, STANDARD)` | `BaselineEvaluatorGoldenTest` |
| LA `ColumnProfile.stats` (overlap profile, Entity Type profile) | `rowPerGroup`; statistics `ROW_COUNT, NULL_COUNT, DISTINCT_COUNT`, `APPROXIMATE` distinct over the caller's `distinctOf` (the value as VARCHAR, or the normalised Entity key) | `ColumnProfileGoldenTest` |
| LA `DatasetLinkStats.degreeSummarySql` (Links section, Dataset fallback) | `quantile("d", q, DISCRETE)` for p50 / p90 / p99 — LA keeps `quantile_disc` as the explicit mode (D-AN3) | `ColumnProfileGoldenTest` |

Left in place because they are not column profiles: the rest of `EntityProfiles` (key shapes — lengths, prefixes,
character classes, sentinels, per-day spread over the union of normalised keys), the `IndexLinkStats` index read and
the `ValueMeasures` median.

⚠ The Expectation `baseline` does **not** implement `BaselineStatistic`. That contract takes one entity's time-bucketed
history and returns a robust median plus scaled MAD under a minimum-points rule; the Expectation judges each cell against
the plain median of the accepted profiles (ordered, not time-keyed) or of the prior day groups. Fitting it would mean
inventing bucket timestamps, so it shares the kernel's median definition instead.

## Input rules

| Input | Behaviour | Why |
|---|---|---|
| Empty | `IllegalArgumentException` "`<statistic>` of no values" | "No data" has no number; the caller decides what it means (a report row shows 0, a baseline is insufficient). DuckDB returns NULL. |
| One value, sample variance / stddev | `IllegalArgumentException` | Undefined (n − 1 = 0). DuckDB returns NULL. |
| NaN anywhere | `IllegalArgumentException` | NaN is the JVM's missing value; SQL aggregates skip NULL, so the caller drops missing values first (every baseline caller already does) and the JVM and SQL forms count the same rows. |
| ±Infinity | a value; IEEE arithmetic | — |
| q outside [0, 1] or NaN; a negative fence multiplier | `IllegalArgumentException` | — |
| Zero denominator (`ratio`), zero total (`rate`) | empty `OptionalDouble` | No number to state; the caller decides what an empty share shows. DuckDB returns NULL. |
| `ratio` with NaN or ±Infinity; `rate` with events < 0 or > total | `IllegalArgumentException` | Keeps the result NaN-free and a rate a true share. |
| `null` array | `NullPointerException` | — |

The input array is never modified.

## Callers consolidated

ANALYTICS-CORE-1 M2. Each caller's golden test was committed **before** its switch and passes unchanged after it: hand
figures for odd and even counts, ties, n = 1 and 2 and huge values, plus seeded inputs checked against a frozen copy of
the pre-kernel arithmetic.

| Caller | Was | Kernel call | Golden test |
|---|---|---|---|
| `VolumeBaseline.assess` (`inspecto-engine`, completeness K3) | sort + `get((n − 1) / 2)` | `Stats.median(x, LOWER)` | `VolumeBaselineTest.golden*` |
| `ReportService.percentile` (`inspecto`, batch-audit p50/p95/p99) | nearest rank on a sorted copy | `Stats.quantile(x, p / 100, NEAREST_RANK)` | `ReportServiceTest.goldenNearestRank*` |
| `LinkTemporalDetector.periodicity` (`inspecto-la-core`, regular-series period and CV) | hand mean, population stddev and integer median | `Stats.mean`, `Stats.stddevPopulation`, `(long) Stats.median(x)` (`STANDARD`) | `LinkTemporalDetectorTest.golden*` |

Each converts its `long` values to `double` and casts the answer back. That is exact below 2^53 (row counts,
millisecond durations and epoch-ms gaps are far below it), so `LOWER` and `NEAREST_RANK` return the observed value and
the `STANDARD` median of two integer gaps truncates exactly as the integer `(a + b) / 2` it replaced.

⚠ `ReportService.percentile` remains as a two-line delegate that keeps the report's "an empty window reads 0" rule; the
duplication guard exempts it by name (`tools/data-ops-duplication.exemptions.json`), and the category-c baseline fell by one.

⚠ **`VolumeBaseline` does not implement `BaselineStatistic` (M6).** That contract takes a timestamped `Double` history and
returns a `Baseline` with a median and a scaled MAD for a robust z; `VolumeBaseline` takes calendar-day `long` row
counts over a rolling window and answers a signed fractional deviation with four statuses. Fitting it would add an unused
spread and change its types and outputs, so it shares the median definition only.

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
([step-catalog](pipeline-graph/step-catalog.md)), through the engine's `TimeSeriesStep` (grammar, clock/type checks).
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

Each golden test was written and committed against the old code, then passed unchanged after the switch.
⚠ **Kept, by decision:** `RecordTransform`'s `date.truncate` function (`DATE_TRUNC({unit}, {source})`) is not a rule of
its own — it hands the author's free-text unit to DuckDB, any date part included (`decade`, `millisecond`), and
narrowing it to `TimeGrain` would refuse units that run today. ⚠ `DashboardDateRange` (`month-to-date`,
`year-to-date` in the control plane) still computes its own period start; it was not in the plan's survey and is left
for a follow-up.

### Tests

`TimeGrainTest` (hand figures, DST and week cases), `TimeGrainDuckDbParityTest` (every grain × form over instants packed
around eight transitions — Berlin both ways, New York, Santiago's midnight gap, Havana's midnight overlap, Lord Howe's
30-minute shift, Kolkata, UTC — plus ISO week 53 and year/quarter/leap-day edges; a mutation to the earlier-offset rule
turns it red), `TimeSeriesSqlTest` (resample and shift executed on DuckDB), and in the engine
`RowShaperTimeSeriesTest`, `PipelineJobRunnerTest#runsAFlatConfigsResampleAndShiftStepsAndWritesAMonthOverMonthSeries`.

## References

* Plan and decisions D-AN1 … D-AN8: [`analytics-core-plan.md`](../../superpower/analytics-core-plan.md).
* The baselines' first consumer: [anomaly-scores](control-plane/anomaly-scores.md).
* Module axes and manifests: [module-taxonomy](module-taxonomy.md).
