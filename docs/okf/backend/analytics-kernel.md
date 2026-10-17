---
type: Architecture
title: Analytics Kernel — canonical statistics and the baseline contract
description: The platform module inspecto-analytics (ANALYTICS-CORE-1 M1, the M6 baseline contract and the M7 score output contract) — one written definition per statistic (mean, variance and stddev, median modes, quantile modes, MAD, robust z, IQR, Tukey fences), the empty/NaN input rules, which DuckDB function each matches, the baseline package moved from Anomaly Detection, and the score output contract Anomaly and Risk Scores write through.
resource: docs/superpower/analytics-core-plan.md
tags: [analytics, statistics, baseline, scores, modules, duckdb]
timestamp: 2026-10-17T00:00:00Z
---

# Analytics Kernel

> 🟡 **Status (2026-10-17): M1, the M6 baseline contract and the M7 score output contract are built.** Plan:
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
* `com.gamma.analytics.score` — the score output contract (M7, below).
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

* `StatsTest` — a hand-computed figure per definition, the input rules, `LOWER` = `VolumeBaseline`'s rule and
  `NEAREST_RANK` = `ReportService.percentile` over random inputs.
* `StatsDuckDbParityTest` — the same inputs (hand-picked shapes, every size 1–41, 1,000 values with many ties,
  1,000 values around 10⁶) through `Stats` and through DuckDB 1.5.6 (`avg`, `var_pop`, `var_samp`, `stddev_pop`,
  `stddev_samp`, `median`, `mad`, `quantile_cont`, `quantile_disc` at 13 fractions, and the IQR). Agreement within
  1e-9 × max(1, |value|); `quantile_disc` and the odd-count median agree exactly.
* `RobustStatsTest`, `BaselineStatisticsTest` — the moved baseline package.
* `ScoreContractTest` — the score output contract's pure parts (names, scale and bands, cap, columns, run files,
  retention); the writer is pinned by the two kinds' golden tests above.

## References

* Plan and decisions D-AN1 … D-AN8: [`analytics-core-plan.md`](../../superpower/analytics-core-plan.md).
* The baselines' first consumer: [anomaly-scores](control-plane/anomaly-scores.md).
* The score kinds on the contract: [anomaly-scores](control-plane/anomaly-scores.md), [risk-scores](control-plane/risk-scores.md).
* Module axes and manifests: [module-taxonomy](module-taxonomy.md).
