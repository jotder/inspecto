---
type: Architecture
title: Analytics Kernel — canonical statistics, the column profiler, time-series primitives, the baseline and score output contracts — The module, definitions, functions and the profiler
description: Part 1 of 3 of Analytics Kernel — canonical statistics, the column profiler, time-series primitives, the baseline and score output contracts: The module; The definitions; Platform Service analytics; Function catalogue; Column profiler; Input rules; Callers consolidated; Similarity kit; Entity grouping
tags: [analytics, statistics, baseline, time-series, scores, similarity, entity-grouping, cohort, change-point, modules, duckdb]
timestamp: 2026-10-18T00:00:00Z
---

# Analytics Kernel — canonical statistics, the column profiler, time-series primitives, the baseline and score output contracts — The module, definitions, functions and the profiler

Part 1 of 3 of [Analytics Kernel — canonical statistics, the column profiler, time-series primitives, the baseline and score output contracts](spec-analytics.md) · next: [Distribution Steps, score contract and time-series primitives](spec-analytics-distribution-time-series.md).

## The module

`platform/inspecto-analytics` — artifactId `inspecto-analytics`, manifest id `analytics`, `buildRole: platform`,
`offeringRole: base` (every edition, D-AN1). Pure functions and SQL builders, no routes, no state, **no third-party dependency of its own** (D-AN8).
Main code depends on `inspecto-util`, for `SqlIdent` (the one SQL quoting implementation the profiler and the time-series builders quote
through); `tools/check-module-deps.mjs` and the pom's enforcer rule allow only the foundation layer (`inspecto-api`,
`-config`, `-sql`, `-util`) plus the JDK-only graph library `inspecto-graph` (since S2, for its `UnionFind`), so a feature module can depend on the kernel but the kernel can never reach the engine or a
feature. It ships as its own thin jar beside `inspecto.jar`
(`tools/bundle-modules.mjs` `CORE_MODULES`, the shade exclude in `inspecto/pom.xml`).

Packages:

* `com.gamma.analytics` — `Stats` (the definitions below), `MedianMode`, `QuantileMode`, `ColumnProfiler`
  (§ Column profiler), `Md5Bucket` (§ [Holdout split](spec-analytics-cohorts-forecasting.md#holdout-split)).
* `com.gamma.analytics.score` — the score output contract (M7, below).
* `com.gamma.analytics.accuracy` — `Accuracy`, `AccuracySql`, `Backtest` (S5b, § [Accuracy and backtest](spec-analytics-distribution-time-series.md#accuracy-and-backtest)).
* `com.gamma.analytics.split` — `HoldoutSplit` (S5d, § [Holdout split](spec-analytics-cohorts-forecasting.md#holdout-split)).
* `com.gamma.analytics.sample` — `Sampler` (S4, § [Ranking, sampling and pivot](spec-analytics-cohorts-forecasting.md#ranking-sampling-and-pivot)).
* `com.gamma.analytics.time` — also `SeasonalityDetector` (S5c, § [Seasonality detection](spec-analytics-distribution-time-series.md#seasonality-detection)),
  `HourOfWeek` and `SeasonalProfile` (C5), `ChangePoint` (C6).
* `com.gamma.analytics.cohort` — `Retention` and `Funnel` (C9, § [Cohort retention and funnel](spec-analytics-cohorts-forecasting.md#cohort-retention-and-funnel)).
* `com.gamma.analytics.forecast` — the forecast kernel and its prediction-interval output (S5 (a) + (e), § Forecasting).
* `com.gamma.analytics.catalogue` — the function catalogue (C8): `AnalyticsFunction`, `AnalyticsFunctionProvider`,
  `AnalyticsCatalogue`, `KernelFunctions`, `CouldBatchFunctions` (§ [Function catalogue](#function-catalogue)).
* `com.gamma.analytics.similarity` — the similarity kit (S1): `Similarity`, `JaroWinkler`, `TextUnit`,
  `SimilaritySql` (§ [Similarity kit](#similarity-kit)).
* `com.gamma.analytics.entity` — deterministic entity grouping (S2): `EntityGrouping`, `EntityGroupingSql`,
  `MatchCondition`, `KeyColumn`, `Normalization`, `SimilarityCandidate`, `EntityRecord` (§ [Entity grouping](#entity-grouping)).
* `com.gamma.analytics.distribution` — the distribution Steps' kernel (S3): `OutlierSql`, `Binning`, `CorrelationSql`
  (§ [Distribution Steps](spec-analytics-distribution-time-series.md#distribution-steps)).
* `com.gamma.analytics.model` — the model kernels (C2–C4): `KMeans`, `KMeansSql`, `LogisticRegression`, `LogisticSql`,
  `IsolationForest`, `SeededRandom`, `InputCap` (§ [Model kernels](spec-analytics-cohorts-forecasting.md#model-kernels)).
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
| Tukey fences | [Q1 − k·IQR, Q3 + k·IQR], k = 1.5 by default; a value strictly outside is an outlier | `tukeyFences(x[, k[, mode]])` → `Fences.outside(v)` | `OutlierSql` (S3) |
| Pearson correlation | Σ(x − x̄)(y − ȳ) / √(Σ(x − x̄)² Σ(y − ȳ)²); **empty** when a side is constant | `correlation(x, y)` | `corr` (NaN there → NULL) |
| Least-squares line | slope Sxy / Sxx, intercept ȳ − slope·x̄; r2 empty for constant x, 1 for constant y, else r² | `linearRegression(x, y)` | `regr_slope`, `regr_intercept`, `regr_r2`, `regr_count` |
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
Menu row, grants and dry run: [platform-services](../processor/spec-platform-services.md) §3; tests `AnalyticsServiceTest`.
## Function catalogue

ANALYTICS-CORE-1 C8 (2026-10-18; D-AN33…D-AN35). `com.gamma.analytics.catalogue` DESCRIBES what the kernel offers and
runs nothing: an `AnalyticsFunction` is an id (dotted lower-case, `stats.quantile`), a label, its `Kernel`
(statistics · profiler · time series · forecasting · similarity · entity grouping · graph · cohort analysis ·
model kernels), a `Cost` hint
(constant · linear · linearithmic · quadratic), a `ResultKind` (scalar · column · table · sql · groups · forecast ·
catalogue), the `Form`s it runs as (JVM, SQL, or both with parity tests), the pipeline Step id when it is also a Step,
and its `Param`s (type, default, bounds, choices) — the same facts LA's `Algorithm` enum publishes for graphs.

* **Registration point — the one thing a later kernel does.** Implement `AnalyticsFunctionProvider` (one `functions()`
  method) in the kernel's own package and add its class to
  `platform/inspecto-analytics/src/main/resources/META-INF/services/com.gamma.analytics.catalogue.AnalyticsFunctionProvider`.
  `AnalyticsCatalogue.load()` (ServiceLoader) merges every provider, orders by kernel then id and **fails at load on a
  duplicate id**. `KernelFunctions` is the S-tier provider (26 entries, 2026-10-18); do not extend it for a new kernel.
  `CouldBatchFunctions` (8 entries, 2026-11-12) is the second provider: calendar slot, seasonal profile and change
  points under time series, the cohort kernel (retention, funnel) and the model kernels (k-means, logistic regression,
  isolation forest). Windowed and ratio Measures are Measure grammar, not a kernel function, and are not listed.
* **Route.** `GET /analytics/functions[?kernel=…]` (`AnalyticsCatalogueRoutes`, core): read-only and ungated — no write
  root, no capability, no data — so the endpoint skill's 503 / 403 / 409 gates do not apply; an unknown `kernel` is 422
  naming the valid ones; the kernel counts stay whole when the list is narrowed. In `openapi.fragment.json`; the UI pane is
  [analytics-functions](user-guide-analytics-functions.md).
* **Graph.** Listed as `graph.algorithms` (a pointer to `GET /inv/graph/algorithms`, where `la-core`'s 29 `Algorithm`
  constants and their measured ceilings live — this module may not depend on `la-core`) and `graph.temporal_motif`.
* **Honest limits.** The cost hint is asymptotic, never a measured ceiling; the descriptions are hand-written beside
  the code they describe, so a kernel function changed without its entry drifts — the checks are structural (unique ids,
  every named Step is a real `BuiltinNodeType`, ids sit under their kernel's prefix), not semantic.
* **Tests.** `AnalyticsCatalogueTest` (analytics), `ControlApiAnalyticsCatalogueTest` (real HTTP: shape, filter, 422,
  any role reads, writes refused, Step ids real).



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

The plan's fourth consumer, **Catalog column statistics, has no code to move**: checked 2026-10-17, neither the engine's
`com.gamma.catalog` package, `CatalogRoutes` nor the SPA's catalog computes a column statistic. When the Catalog gains
one, it builds on `ColumnProfiler`.

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
SQL — a SQL-side statistic that stays SQL — and is exempted with that reason. The last two category-c copies, the Jaccard (intelligence) and the
Jaro-Winkler (screening), moved to the similarity kit (Should S1): category c is now 0 (§ [Similarity kit](#similarity-kit)).

⚠ **`VolumeBaseline` does not implement `BaselineStatistic` (M6).** That contract takes a timestamped `Double` history and
returns a `Baseline` with a median and a scaled MAD for a robust z; `VolumeBaseline` takes calendar-day `long` row
counts over a rolling window and answers a signed fractional deviation with four statuses. Fitting it would add an unused
spread and change its types and outputs, so it shares the median definition only.

**S8, JVM-side aggregation moved to the store's SQL (2026-10-17).** Goldens committed first:
`ObjectAnalyticsGoldenTest` (`inspecto-ops`; in-memory, DuckDB, Postgres when configured) and
`CaseRuleEvaluateGoldenTest` (`inspecto-case-management`).

* `ObjectService.analytics` — `DbObjectStore.rollupGroups` runs one portable `GROUP BY` over the raw `(status, priority)`
  pair: count, cycle-time count and sum, and a `ROW_NUMBER()` first-seen position. Java still applies the upper-case
  fold, the NONE rule and the workflow's terminal test to each group, so map keys keep the walk's order. Category, MTTR,
  MTTD, impact and `recordsAffected` stay in the JVM over `scanAttributes` (a two-column ordered scan). The attribute
  bag is JSON in a `VARCHAR` with no portable operator. `Long.parseLong` is not DuckDB's cast (`'1.5'` casts to 2), and
  `Impact` validates `BigDecimal` text. The in-memory store keeps the per-object walk. The means stay integer division,
  not `Stats.mean`: a `double` quotient can round up across an integer boundary.
* `CaseOperations.evaluateCaseRule` — the window is `ObjectQuery.createdFrom` (exact, every store). The Tag Rule
  filter's status aliases, priority, severity and category become an `ObjectQuery.Prefilter`, a `LOWER(...) LIKE`
  containment that only narrows. It runs on DuckDB only and keeps any value with a character outside printable ASCII.
  Postgres `LOWER` follows the database collation, so Postgres gets the window only. The exact JVM test still decides
  every match. `type` and `q` stay in the JVM: `q` matches across the `title + " " + description` join.
* ⚠ A row the old walk could not load now counts instead of failing the rollup. Examples are a NULL status, or a NULL
  attribute value that `Map.copyOf` rejects.
* Measured on 100k Incidents (file DuckDB, median of 5): analytics 3.9 s → 2.1 s; a 1-day rule 4.4 s → 0.06 s; an
  all-time rule 3.3 s → 0.19 s; an all-time rule with `q` 2.9 s → 1.2 s.

## Similarity kit

*ANALYTICS-CORE-1 S1, built 2026-10-17.* Package `com.gamma.analytics.similarity`. One definition of each string
similarity, a JVM form and a DuckDB form, held equal by a parity test. ⛔ **A score proposes a candidate; it never asserts
that two values name the same thing** (decision D-M1, the plan's Won't W6) — nothing in the kit merges, links or resolves.

### The definitions

**One empty rule:** every similarity scores **0 when either side is empty**, both empty included (nothing to match on);
DuckDB's built-ins do the same. A distance is a plain count. NULL in SQL gives NULL.

| Similarity | Definition | JVM | DuckDB |
|---|---|---|---|
| Jaro | units match when equal and at most `w = max(0, max(|s|, |t|) / 2 − 1)` apart (each used once, first free match, left to right); with `m` matches and `x` matched positions out of order: `(m/|s| + m/|t| + (m − h)/m) / 3`, `h = x / 2` | `Similarity.jaro`, `JaroWinkler.jaro` | `jaro_similarity` (with `h = ⌊x / 2⌋`, bytes — see below) |
| Jaro-Winkler | `jaro + ℓ · p · (1 − jaro)`, `ℓ` = common prefix up to `maxPrefix`, applied only when `jaro > boostThreshold`. `STANDARD`: p = 0.1, prefix ≤ 4, threshold 0.7 (Winkler) | `Similarity.jaroWinkler`, `JaroWinkler` | `jaro_winkler_similarity` |
| Levenshtein distance | fewest single-unit insertions, deletions and substitutions | `Similarity.levenshtein` | `levenshtein` (bytes) |
| Levenshtein similarity | `1 − d / max(|a|, |b|)` | `Similarity.levenshteinSimilarity` | `SimilaritySql.Method.LEVENSHTEIN` |
| Jaccard (sets) | `|A ∩ B| / |A ∪ B|` (`equals`) | `Similarity.jaccard` | — |
| Token Jaccard | Jaccard of the distinct runs of non-whitespace (whitespace = space, tab, LF, VT, FF, CR; nothing else) — no case folding inside | `Similarity.tokens`, `tokenJaccard` | `SimilaritySql.Method.TOKEN_JACCARD` (list functions) |

**Normalization** (`Similarity.normalize`, SQL `SimilaritySql.normalize`), in this order: (1) **accents stripped** —
NFD, every combining mark (general category M) removed, NFC; canonical decompositions only, so `é → e`, `Å → a`, but
`ß`, `ø`, `ł`, `æ` and compatibility forms (`ﬁ`, full-width letters, no-break space) stay; (2) **case folded** — each code
point by `Character.toLowerCase(int)`, the simple context-free mapping (`Σ → σ` always, never the final `ς`; `İ → i`);
(3) **whitespace** — each run of the six ASCII whitespace characters to one space, ends trimmed. SQL:
`trim(regexp_replace(lower(strip_accents(x)), '[ \t\n\x0B\f\r]+', ' ', 'g'), ' ')`.

**What one character is** is stated, never assumed — `TextUnit`: `CODE_POINT` (the default), `UTF16_CHAR` (a code point
above U+FFFF is two), `UTF8_BYTE` (an accented letter is two). `JaroWinkler` carries its unit, its transposition rule
(`HALF` = `x / 2.0`, `HALF_FLOORED` = `⌊x / 2⌋`), prefix scale, max prefix and boost threshold as data, so a caller's exact
arithmetic is a named instance rather than a private copy.

### DuckDB 1.5.6, as found

Checked against the engine's DuckDB (`SimilarityDuckDbParityTest`, 4,014 pairs with accents, emoji, tabs, empties and
one-swap near-duplicates, compared **bit for bit**):

| Built-in | What it computes | Kit form that equals it |
|---|---|---|
| `jaro_similarity`, `jaro_winkler_similarity` | Jaro / Jaro-Winkler over **UTF-8 bytes**, half-transpositions **rounded down**, p 0.1, prefix ≤ 4, boost above 0.7; empty → 0 | `JaroWinkler.DUCKDB` |
| `levenshtein` | edit distance over **UTF-8 bytes** (`é` ↔ `e` costs 2) | `levenshtein(a, b, UTF8_BYTE)` |
| `jaccard(s1, s2)` | ⚠ **not** a token Jaccard: Jaccard of the two strings' sets of UTF-8 **bytes**, case-sensitive, and it **raises** on an empty argument | none needed — the token Jaccard is built from `regexp_extract_all`, `list_distinct`, `list_intersect` |
| `strip_accents` + `lower` | as `normalize`, except on code points newer than DuckDB's Unicode tables | `normalize` — equal on every code point below U+0870 and in U+1E00–U+1FFF; 154 of planes 0–2 differ on JDK 27 (recent additions such as U+0897, U+1ACF–U+1ADC, U+A7CB–U+A7DC) |
| `trim(x)` | ⚠ also strips Unicode spaces (no-break space, U+3000) | the normalizer trims `' '` only |

So the textbook definition (`STANDARD`: code points, `h = x / 2`) and DuckDB's differ in two stated places — the unit
and the rounding of `h` — and agree on ASCII text with an even out-of-order count. The kit keeps both as named instances
instead of forcing one to look like the other. NULL handling in `SimilaritySql` tests NULL first: DuckDB's
`list_concat` / `list_intersect` read a NULL list as empty, and `NULL = '' OR '' = ''` is true.

### Callers

| Caller | Uses | Proof the output did not move |
|---|---|---|
| Screening `NameMatcher` (token similarity of the name method) | `TOKEN_JARO_WINKLER = new JaroWinkler(UTF16_CHAR, HALF, 0.1, 4, 0.0)` — the prefix boost always applied, exactly its old arithmetic. Folding, honorifics, transliteration, the phonetic reading and blocking **stay in Screening** | `NameMatcherGoldenTest` (bit for bit against the frozen old code over 20,000 seeded pairs; SHA-256 of 5,000 seeded `score` results) |
| Intelligence `TriageRunSimilarity` / `TriageRunStore` (Triage Run recall) | `Similarity.jaccard`; its own tokenizer (lower-case, `[a-z0-9]+`, ≥ 3 characters) stays | `TriageRunSimilarityGoldenTest` (bit for bit over 20,000 seeded set pairs; SHA-256 of 5,000 seeded `score` results) |
| Step `transform.similarity` (catalog `quality.match.fuzzy`) | `SimilaritySql.Method` + `normalize` through the engine's `SimilarityStep`, which adds a 1,000-character cap per value (a longer value fails the run) | [step-catalog § similarity](../elt/spec-step-catalog.md) |

Each golden test was committed against the old code first and passed after the switch with only its `SUBJECT` line
repointed. That closed the duplication guard's category c: **0** findings (from 2), baseline rewritten.

### Tests

`SimilarityTest` (hand figures: Winkler's reference pairs, the odd-transposition case in both modes, the boost threshold,
the three units, Levenshtein, Jaccard, normalization case by case, refused knobs) and `SimilarityDuckDbParityTest`
(above; plus NULL on either side for every method, raw and normalized). In the engine, `RowShaperSimilarityTest`.

## Entity grouping

*ANALYTICS-CORE-1 S2, built 2026-10-17.* Package `com.gamma.analytics.entity`. One definition of deterministic
entity grouping — blocking keys, exact match conditions, union-find — with a JVM form and a DuckDB form held equal by
a parity test. ⛔ **Identity is never asserted by similarity** (decision D-M1, the plan's Won't W6): only exact match
conditions form groups; a similarity score can only fill separate candidate columns for a person to review.

### The definition

1. Every key column is normalised (`Normalization`): `TEXT` — the similarity kit's normalisation (accents stripped,
   lower-cased, ASCII whitespace runs to one space, ends trimmed; the default); `DIGITS` — ASCII digits only; `NONE` —
   as written. **One empty rule:** NULL, or empty after normalising, is *absent* and never equals anything.
2. A record's **block** is the tuple of its normalised blocking keys. A record with any blocking key absent is in no
   block and stays a group of one; with no blocking keys every record is in the one block.
3. Two records **match** when they share a block and, for at least one `MatchCondition`, every column of it is
   present on both and equal. The records that share a block and one condition's values form a **match cell**.
4. **Resolution groups** are the connected components of "match". The **group id** is the key of the member whose key
   text is smallest in **code-point order** (= UTF-8 byte order = DuckDB's VARCHAR order; Java's `String.compareTo`
   is UTF-16 order and differs above U+FFFF, so `EntityGrouping.compareText` exists). The id is independent of input
   order; the Step writes it in the key column's own type, so a numeric key's id is still the smallest *as text*.

**Caps — fail closed, before anything is joined** (the same words from both forms): an empty or repeated record key;
a match cell or review block above `maxBlockSize` (default 1,000, ceiling 100,000 — in practice a placeholder value
such as `0` or `unknown` that many records carry); **candidate pairs** — the pairs of records sharing a match cell plus
the pairs sharing a review block — above `maxPairs` (default 1,000,000, ceiling 100,000,000). The SQL form also stops
after 1,000 propagation rounds.

### The two forms

| Form | How | Where |
|---|---|---|
| JVM | records grouped into match cells by hash; every cell member joined to the cell's first with the graph library's `com.gamma.graph.UnionFind` (the one union-find, M8); smallest key per root | `EntityGrouping.group` |
| DuckDB | normalised keys as columns; each member paired with its cell's smallest key (`min(key) OVER (PARTITION BY block, condition values)`); the connected components by **min-label propagation with pointer jumping** — each round a record takes the smallest label among itself and its cell partners, then the label of that label — one set-based statement pair per round until no label changes | `EntityGroupingSql.run` |

`EntityGroupingSql.run` is the kernel's one entry that executes statements on the caller's connection rather than
returning SQL text, because the number of rounds is known only by running them; its scratch tables are dropped before
it returns. A recursive CTE was not used: with `UNION` it would carry every label a record ever saw (the square of the
component size), and `USING KEY` recursion waits for DuckDB 2.0 (plan C7). Because of this the kernel now reaches
`inspecto-graph` (a JDK-only leaf) besides the foundation modules; `check-module-deps` allows exactly that.

### D-M1 in the API

- `MatchCondition` is a list of `KeyColumn(column, normalization)` — no method, score or threshold, so a similarity
  cannot be expressed as a condition. `EntityGroupingTest` pins those record components by reflection.
- `SimilarityCandidate(column, method, normalize, threshold)` is a separate type in its own `Spec` component, and needs
  blocking keys. It is scored only after the groups are final: each record's best score against a record of **another**
  group in its block (ties: the smaller group id), giving `Candidate(score, group, review)`. The groups map handed to it
  is read-only. A test runs the same data with and without a threshold-0 review (everything flagged) and asserts the
  groups are identical.
- The Step refuses `method`, `threshold`, `similarity`, `fuzzy` or `score` in its config by name, citing D-M1.

### Callers

| Caller | Uses |
|---|---|
| Step `transform.entity.link` (catalog `enrichment.entity.link`) | `EntityGroupingSql.run` through the engine's `EntityLinkStep` ([step-catalog § entity_link](../elt/spec-step-catalog.md)) |

Link Analysis' `EntityRegistry` keeps its own fold of `identity.asserted` / `retracted` facts into resolution groups
(on the same `UnionFind`): what counts as an identity fact stays in that module (plan §2). The Step's resolution group
is a Dataset column, not an Identity Fact.

### Tests

`EntityGroupingTest` (hand cases: transitive joins across conditions, blocking, multi-column conditions, input-order
independence, key refusals, both caps, normalisations, code-point order, D-M1 by reflection and by an all-flagging
review) and `EntityGroupingParityTest` (JVM = DuckDB on three seeded 600-record corpora with and without blocking,
every similarity method raw and normalised, a 300-record chain, ids beyond the Basic Multilingual Plane, identical
refusals with nothing written, a numeric key keeping its type). In the engine, `RowShaperEntityLinkTest`.
