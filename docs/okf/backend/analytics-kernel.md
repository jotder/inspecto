---
type: Architecture
title: Analytics Kernel — canonical statistics, time-series primitives and the baseline contract
description: The platform module inspecto-analytics (ANALYTICS-CORE-1 M1 + the M6 contract) — one written definition per statistic (mean, variance and stddev, median modes, quantile modes, MAD, robust z, IQR, Tukey fences), the empty/NaN input rules, which DuckDB function each matches, and the baseline package moved from Anomaly Detection; the M5 time-series primitives (one time-grain truncation with an explicit zone, resample with gap fill, period shift) and the truncation copies they retired.
resource: docs/superpower/analytics-core-plan.md
tags: [analytics, statistics, baseline, time-series, modules, duckdb]
timestamp: 2026-10-17T00:00:00Z
---

# Analytics Kernel

> 🟡 **Status (2026-10-17): M1, the M6 contract and M5 (time-series primitives, below) are built; the statistics are not yet called outside the kernel.** Plan:
> [`analytics-core-plan.md`](../../superpower/analytics-core-plan.md) (row `ANALYTICS-CORE-1`). The other medians,
> percentiles and z-scores (`BaselineEvaluator`, `VolumeBaseline`, `ReportService`, the Assistant outlier tool, LA link
> statistics) still compute their own figures — moving them behind the kernel, each with a golden test first, is M2.

## The module

`platform/inspecto-analytics` — artifactId `inspecto-analytics`, manifest id `analytics`, `buildRole: platform`,
`offeringRole: base` (every edition, D-AN1). Pure functions, no routes, no state, **no third-party dependency** (D-AN8).
Main code depends on no `inspecto-*` module; `tools/check-module-deps.mjs` and the pom's enforcer rule allow only the
foundation layer (`inspecto-api`, `-config`, `-sql`, `-util`), so a feature module can depend on the kernel but the
kernel can never reach the engine or a feature. It ships as its own thin jar beside `inspecto.jar`
(`tools/bundle-modules.mjs` `CORE_MODULES`, the shade exclude in `inspecto/pom.xml`).

Packages:

* `com.gamma.analytics` — `Stats` (the definitions below), `MedianMode`, `QuantileMode`.
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

⚠ `DISCRETE` and `NEAREST_RANK` are the **same rank** written two ways; the parity test asserts they pick the same
value as `quantile_disc` on every input it runs. They stay separate modes so the LA link statistics (`quantile_disc`)
and `ReportService` can each keep their exact arithmetic when M2 moves them (the plan's first risk: no shipped figure moves).

⚠ The robust-z floor exists so a near-constant history (scaled MAD ≈ 0) cannot turn a one-unit change into an
enormous z. It is Anomaly Detection's rule (design §4.2), kept exactly.

## Input rules

| Input | Behaviour | Why |
|---|---|---|
| Empty | `IllegalArgumentException` "`<statistic>` of no values" | "No data" has no number; the caller decides what it means (a report row shows 0, a baseline is insufficient). DuckDB returns NULL. |
| One value, sample variance / stddev | `IllegalArgumentException` | Undefined (n − 1 = 0). DuckDB returns NULL. |
| NaN anywhere | `IllegalArgumentException` | NaN is the JVM's missing value; SQL aggregates skip NULL, so the caller drops missing values first (every baseline caller already does) and the JVM and SQL forms count the same rows. |
| ±Infinity | a value; IEEE arithmetic | — |
| q outside [0, 1] or NaN; a negative fence multiplier | `IllegalArgumentException` | — |
| `null` array | `NullPointerException` | — |

The input array is never modified.

## Tests

* `StatsTest` — a hand-computed figure per definition, the input rules, `LOWER` = `VolumeBaseline`'s rule and
  `NEAREST_RANK` = `ReportService.percentile` over random inputs.
* `StatsDuckDbParityTest` — the same inputs (hand-picked shapes, every size 1–41, 1,000 values with many ties,
  1,000 values around 10⁶) through `Stats` and through DuckDB 1.5.6 (`avg`, `var_pop`, `var_samp`, `stddev_pop`,
  `stddev_samp`, `median`, `mad`, `quantile_cont`, `quantile_disc` at 13 fractions, and the IQR). Agreement within
  1e-9 × max(1, |value|); `quantile_disc` and the odd-count median agree exactly.
* `RobustStatsTest`, `BaselineStatisticsTest` — the moved baseline package.

## Time-series primitives

*ANALYTICS-CORE-1 M5, built 2026-10-17.* Package `com.gamma.analytics.time`. Since M5 the kernel's main code depends
on `inspecto-util` (for `SqlIdent`, the one quoting implementation) — still only the foundation layer.

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
