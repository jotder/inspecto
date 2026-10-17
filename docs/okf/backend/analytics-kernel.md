---
type: Architecture
title: Analytics Kernel — canonical statistics, the column profiler, time-series primitives, the baseline and score output contracts
description: The platform module inspecto-analytics (ANALYTICS-CORE-1 M1, M2, M4, M5, the M6 baseline contract and the M7 score output contract) — one written definition per statistic (mean, variance and stddev, median modes, quantile modes, MAD, robust z, IQR, Tukey fences, ratio and rate), the empty/NaN input rules, which DuckDB function each matches, the callers consolidated onto it (M2), the one column profiler and its callers (M4), the time-series primitives (one time-grain truncation with an explicit zone, resample with gap fill, period shift) and the truncation copies they retired (M5), the baseline package moved from Anomaly Detection, and the model-agnostic forecasting pieces of S5 (accuracy measures and a rolling-origin backtest, seasonality detection by autocorrelation, a deterministic holdout split). Also documents the temporal motif matcher that moved to the graph library (S6).
resource: docs/superpower/analytics-core-plan.md
tags: [analytics, statistics, baseline, time-series, scores, modules, duckdb]
timestamp: 2026-10-17T00:00:00Z
---

# Analytics Kernel

> 🟡 **Status (2026-10-17): M1 (with ratio and rate), the M6 contract, the M4 column profiler, M2, M5 (time-series
> primitives) and the M7 score output contract are built.** Plan:
> [`analytics-core-plan.md`](../../superpower/analytics-core-plan.md) (row `ANALYTICS-CORE-1`). The column profiler
> serves `transform.profile`, the Expectation `baseline` (profile and median) and Link Analysis' column profile and
> degree quantiles (§ Column profiler); `VolumeBaseline`, `ReportService` and LA's `LinkTemporalDetector` use the kernel
> statistics (see [Callers consolidated](#callers-consolidated)). The SQL-side statistics (`quantile_cont` /
> `quantile_disc` strings, the Assistant outlier tool's mean and population stddev — built with `StatsSql` since S7) stay SQL. The time-grain truncation, resample and
> shift serve the `transform.timeseries.*` Steps, file-sequence gaps, delivery checks, KPI periods and BI grains
> (§ [Time-series primitives](#time-series-primitives)).
> Anomaly Detection and Risk Score write their outputs through the score output contract (§ [Score output contract](#score-output-contract)).
> S5 (b), (c), (d) built 2026-10-17, model-agnostic: accuracy measures and a rolling-origin backtest, seasonality
> detection, and a deterministic holdout split (§ [Accuracy and backtest](#accuracy-and-backtest),
> [Seasonality detection](#seasonality-detection), [Holdout split](#holdout-split)). No caller uses them yet.

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
* `com.gamma.analytics.score` — the score output contract (M7, below).
* `com.gamma.analytics.accuracy` — `Accuracy`, `AccuracySql`, `Backtest` (S5b, § [Accuracy and backtest](#accuracy-and-backtest)).
* `com.gamma.analytics.split` — `HoldoutSplit` (S5d, § [Holdout split](#holdout-split)).
* `com.gamma.analytics.time` — also `SeasonalityDetector` (S5c, § [Seasonality detection](#seasonality-detection)).
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
| Classic z | (observed − mean) / stddev; stddev finite and > 0, which stddev is the caller's to state | `zScore` | `StatsSql.zScore` = `((x - mean) / stddev)` |
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

**SQL forms.** `StatsSql` (S7) renders the statistics a caller computes set-based, over data too large for the JVM:
`mean` = `avg`, `stddevPopulation` = `stddev_pop`, `stddevSample` = `stddev_samp`, and the row-level `zScore`
(the same finite-mean, positive-stddev rule as `Stats.zScore`). `StatsDuckDbParityTest` runs each against its `Stats`
twin, z row by row. The Assistant's `anomaly_scan` builds its SQL with them: z = (x − mean) / **population** stddev
over the column's non-NULL values, unchanged from before the switch (`AnomalyScanGoldenTest`, committed first).

## Platform Service `analytics`

ANALYTICS-CORE-1 S7: Jobs and packs reach the kernel through the read-only Platform Service `analytics`
(`requires: [analytics]`, `ctx.services().get(AnalyticsAccess.class)`) — the statistics above, the column profiler's
`rowPerColumn` SQL over a granted `datasets` read (`profileSql`), and the zoned time-grain truncation. The interface
`com.gamma.query.AnalyticsAccess` lives in the **engine**, its implementation `AnalyticsAccess.kernel()` only
delegates here: a Platform Service interface must be engine-published (D-12) and this module may never depend on the
engine (D-AN1). The surface reuses the kernel's own `MedianMode`, `QuantileMode` and `TimeGrain` rather than copies.
Menu row, grants and dry run: [platform-services](control-plane/platform-services.md) §3; tests `AnalyticsServiceTest`.

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

Two more category-c sites closed on 2026-10-17: `EntityRegistry.resolve` (`inspecto-entity-store`, the identity fold)
now uses the graph library's `com.gamma.graph.UnionFind` (M8; `union(a, b)` roots at `b`'s root, so the larger root is
passed first to keep group id = smallest key — `EntityResolutionFoldTest`), which made `inspecto-graph` an allowed
dependency of `inspecto-entity-store`. `DbJobRunStore.percentile` only renders `quantile_cont` / `percentile_cont`
SQL — a SQL-side statistic that stays SQL — and is exempted with that reason. Category c now counts only the Jaccard
(intelligence) and Jaro-Winkler (screening) copies, left for the similarity kit (Should S1).

⚠ **`VolumeBaseline` does not implement `BaselineStatistic` (M6).** That contract takes a timestamped `Double` history and
returns a `Baseline` with a median and a scaled MAD for a robust z; `VolumeBaseline` takes calendar-day `long` row
counts over a rolling window and answers a signed fractional deviation with four statuses. Fitting it would add an unused
spread and change its types and outputs, so it shares the median definition only.

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

**Input rules** are `Stats`': no pairs, unequal lengths, NaN, or a band with lower > upper throw. The SQL form casts each
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

## Holdout split

*ANALYTICS-CORE-1 S5d, built 2026-10-17.* `com.gamma.analytics.split.HoldoutSplit` — train / validation / test for any
model, as JVM functions and DuckDB SQL that agree. Shares are held in **basis points** (1/10,000, a fraction rounded to
the nearest), so both forms cut with integer arithmetic.

**By time — `TimeSplit`.** No shuffle and no seed (deterministic by construction): over the distinct buckets, oldest
first, the newest are test, the ones before them validation, the rest train. `lastBuckets(v, t)` takes bucket counts;
`fractions(v, t)` takes shares of the bucket count, each **rounded down** (⌊N · bp / 10000⌋), so a share never exceeds
its fraction. Fails when no train bucket is left. SQL: `sql(relation, bucketColumn, splitColumn)` adds the role column
(`'train'`, `'validation'`, `'test'`) by `dense_rank` over the relation's distinct buckets — one global cut, not per
entity; a NULL bucket gets a NULL role; no train bucket fails the statement through `error()`.

**By entity — `HashSplit(seed, train, validation)`.** bucket(key) = `md5_number_lower(seed ':' key) mod 10000`;
bucket < train → train, < train + validation → validation, else test. Every row of an entity gets the same role; the
same seed never reshuffles, a new seed does. SQL: `bucketSql`, `roleSql` and `predicateSql(column, role)` over the key
read as `VARCHAR` (an integer key hashes as its decimal text; a NULL key has no role).

**Why MD5, not DuckDB's `hash()`.** `hash()` is DuckDB's internal hash, with no published definition and no promise to
stay the same across versions: the JVM cannot reproduce it, and an upgrade could move entities between roles.
`md5_number_lower` is the lower 64 bits of the MD5 of the UTF-8 bytes (digest bytes 8..15, little-endian, unsigned) —
a fixed algorithm the JVM computes exactly, the same reading LA's index `BucketFunction` relies on.

### Tests (S5 b, c, d)

* `AccuracyTest` — hand figures for every measure, the zero-actual and a = p = 0 rules, input rules.
* `AccuracyDuckDbParityTest` — 60 seeded pair sets (zero actuals, bands) through `Accuracy` and `AccuracySql`, groups
  and NULL skipping, the inverted band failing both; agreement within 1e-9 × max(1, |value|).
* `BacktestTest` — a hand-computed naive backtest (origins, overall and per-step measures), the expanding copy, band
  coverage, determinism, every cap and forecast check.
* `SeasonalityDetectorTest` — hand ACF, weekly / hourly / monthly detection, noise, trend and constant giving no
  period, minimum cycles, highest-wins and the tie rule.
* `SeasonalityDuckDbParityTest` — `acfSql` per key against `acf` (seasonal, noise, trend, constant, short series, a
  NULL value) and `decide` over the SQL rows = `detect`, verdict for verdict.
* `HoldoutSplitTest`, `HoldoutSplitDuckDbParityTest` — golden MD5 buckets computed outside Java; shares within 2 % of
  the fractions; SQL bucket, role and predicates = JVM over 3,000+ keys (quotes, accents, CJK, emoji, mixed case, an
  integer key, NULL); the time split in both modes against `TimeSplit.role`, and its no-train failure in both forms.

## Temporal motif matcher

ANALYTICS-CORE-1 S6 (2026-10-17). `com.gamma.graph.TemporalMotifMatcher` finds ordered `fan-in` / `fan-out` stages over
timed directed legs: each stage needs at least `minBranches` distinct counterparties inside the earliest qualifying
window (`windowHours`), optionally strictly after the time the previous stage reached each frontier node and within
`maxGapHours`; the last stage may close back onto the origin layer (`closesToStart`, a round trip). Structuring,
layering and circular-financing rings are this shape. It returns each match's nodes, edges and layers and a
`truncated` flag (match `limit` reached, or the 500 000 leg-examination `WORK_BUDGET` spent).

* **Home: the graph library `platform/inspecto-graph`, not this module.** Its input is nodes and directed edges
  (legs), its output a subgraph, and it needs nothing but the JDK — the graph library's own rule (no `inspecto-*`
  dependency). This module is statistics over numeric vectors and SQL builders; putting a graph search here would
  have mixed the two and given LA no shorter path (la-core already depends on `inspecto-graph`). So the move changed
  no pom, no allow-list and no enforcer rule.
* **Neutral input.** `TemporalMotifMatcher.Stage` carries only the search: shape, breadth, window, ordering, gap,
  closure and `admitsNodes` (false = the caller's stage names a node kind its graph does not hold, so it matches
  nothing — legs are still counted against the budget). The caller applies any edge kind or value band before it
  hands over each stage's eligible legs.
* **Link Analysis keeps** `PatternQueryCompiler` (stage parsing, the 422 refusals, the necessary-condition SQL prune
  over the whole Dataset and key normalisation), its `Stage` (edge kind, node kind, threshold band), and the
  mapping `Stage.motif()` (every projected node is an `entity`); see
  [link-analysis §5](modules/link-analysis.md#5-graph-run-graph--la-core--la-storage).
* **Twin and tests.** It is a line-for-line port of `matchBranchingPattern`
  (`inspecto-ui/projects/link-analysis/src/graph/branching-pattern-engine.ts`); the TypeScript stays with Link
  Analysis. `TemporalMotifMatcherGoldenTest` (inspecto-graph) pins the exact output — written against the LA class
  and committed before the move, unchanged since; `PatternStageMotifTest` (la-core) pins the stage mapping;
  `ControlApiInvPatternTest` (geo-link) and `branching-parity.spec.ts` still assert the same
  `branching-parity.fixture.json`.

## References

* Plan and decisions D-AN1 … D-AN8: [`analytics-core-plan.md`](../../superpower/analytics-core-plan.md).
* The baselines' first consumer: [anomaly-scores](control-plane/anomaly-scores.md).
* The score kinds on the contract: [anomaly-scores](control-plane/anomaly-scores.md), [risk-scores](control-plane/risk-scores.md).
* Module axes and manifests: [module-taxonomy](module-taxonomy.md).
