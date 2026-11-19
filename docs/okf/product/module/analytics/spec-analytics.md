---
type: Architecture
title: Analytics Kernel — canonical statistics, the column profiler, time-series primitives, the baseline and score output contracts
description: The platform module inspecto-analytics (ANALYTICS-CORE-1 M1, M2, M4, M5, the M6 baseline contract and the M7 score output contract) — one written definition per statistic (mean, variance and stddev, median modes, quantile modes, MAD, robust z, IQR, Tukey fences, ratio and rate), the empty/NaN input rules, which DuckDB function each matches, the callers consolidated onto it (M2), the one column profiler and its callers (M4), the time-series primitives (one time-grain truncation with an explicit zone, resample with gap fill, period shift) and the truncation copies they retired (M5), the baseline package moved from Anomaly Detection, and the model-agnostic forecasting pieces of S5 (accuracy measures and a rolling-origin backtest, seasonality detection by autocorrelation, a deterministic holdout split). Also documents the similarity kit (S1: Jaro, Jaro-Winkler, Levenshtein, Jaccard, normalization, their DuckDB forms and parity, the Screening and Triage copies it retired, Step transform.similarity). Also documents the temporal motif matcher that moved to the graph library (S6), and ranking, sampling and pivot (S4: the deterministic seeded sampler with DuckDB parity, Steps transform.analytics.rank, transform.sample, transform.matrix.pivot), and deterministic entity grouping (S2: blocking keys, exact match conditions, union-find, its DuckDB form and parity, Step transform.entity.link; similarity never forms a group, D-M1). Also the distribution Steps (S3: outlier flags, equal-width and quantile bins, Pearson correlation and linear regression, their edge rules and DuckDB parity).
resource: docs/archived-documents/plans-archive/analytics-core-plan.md
tags: [analytics, statistics, baseline, time-series, scores, similarity, entity-grouping, cohort, change-point, modules, duckdb]
timestamp: 2026-10-18T00:00:00Z
item: module.analytics
audience: internal
status: current
verified: 2026-11-19 last-edit
code: platform/inspecto-analytics
---

# Analytics Kernel

> 🟡 **Status (2026-10-17): M1 (with ratio and rate), the M6 contract, the M4 column profiler, M2, M5 (time-series
> primitives) and the M7 score output contract are built.** Plan:
> [`analytics-core-plan.md`](../../../../archived-documents/plans-archive/analytics-core-plan.md) (row `ANALYTICS-CORE-1`). The column profiler
> serves `transform.profile`, the Expectation `baseline` (profile and median) and Link Analysis' column profile and
> degree quantiles (§ Column profiler); `VolumeBaseline`, `ReportService` and LA's `LinkTemporalDetector` use the kernel
> statistics (see [Callers consolidated](spec-analytics-module-functions.md#callers-consolidated)). The SQL-side statistics (`quantile_cont` /
> `quantile_disc` strings, the Assistant outlier tool's mean and population stddev — built with `StatsSql` since S7) stay SQL. The time-grain truncation, resample and
> shift serve the `transform.timeseries.*` Steps, file-sequence gaps, delivery checks, KPI periods and BI grains
> (§ [Time-series primitives](spec-analytics-distribution-time-series.md#time-series-primitives)).
> Anomaly Detection and Risk Score write their outputs through the score output contract (§ [Score output contract](spec-analytics-distribution-time-series.md#score-output-contract)).
> S5 (b), (c), (d) built 2026-10-17, model-agnostic: accuracy measures and a rolling-origin backtest, seasonality
> detection, and a deterministic holdout split (§ [Accuracy and backtest](spec-analytics-distribution-time-series.md#accuracy-and-backtest),
> [Seasonality detection](spec-analytics-distribution-time-series.md#seasonality-detection), [Holdout split](spec-analytics-cohorts-forecasting.md#holdout-split)). No caller uses them yet.
> **S1, the similarity kit, is built (2026-10-17):** Jaro, Jaro-Winkler, Levenshtein, Jaccard and normalization with their
> DuckDB forms; Screening's and Triage recall's copies retired; Step `transform.similarity` (§ [Similarity kit](spec-analytics-module-functions.md#similarity-kit)).
> **S4, ranking, sampling and pivot, is built (2026-10-17):** the deterministic seeded sampler (`Sampler`, JVM = DuckDB)
> and Steps `transform.analytics.rank`, `transform.sample`, `transform.matrix.pivot` (§ [Ranking, sampling and pivot](spec-analytics-cohorts-forecasting.md#ranking-sampling-and-pivot)).
> **S2, deterministic entity grouping, is built (2026-10-17):** blocking keys + exact match conditions + union-find,
> JVM and DuckDB forms with parity, caps that fail closed; Step `transform.entity.link`; similarity only proposes
> review candidates, never a group (§ [Entity grouping](spec-analytics-module-functions.md#entity-grouping)).
> **S3, the distribution Steps, are built (2026-10-17):** `transform.stats.outlier`, `transform.data.binning` and
> `transform.stats.correlation` on the definitions here (§ [Distribution Steps](spec-analytics-distribution-time-series.md#distribution-steps)).
> **Could batch, lane A, built 2026-10-18 (D-AN10…D-AN13 in the plan, assistant-decided, operator-confirmed 2026-11-12):**
> C5 [hour of week and seasonal profile](spec-analytics-distribution-time-series.md#hour-of-week-and-seasonal-profile), C6
> [change-point and regime detection](spec-analytics-distribution-time-series.md#change-point-and-regime-detection), C1
> [windowed and ratio Measures](spec-analytics-distribution-time-series.md#windowed-and-ratio-measures), C9 [cohort retention and funnel](spec-analytics-cohorts-forecasting.md#cohort-retention-and-funnel).
> Pipelines reach C6 and C9 through their Steps (2026-11-01, `ANALYTICS-STEPS-TS-1`):
> [Change-point and cohort Steps](spec-analytics-cohorts-forecasting.md#change-point-and-cohort-steps).
>
> **The model kernels are built (2026-10-18, Could C2, C3, C4 and the Spearman residual of S3):** rank correlation
> (`method: spearman`), k-means, logistic regression with the holdout split, and an isolation forest behind the Anomaly
> Score (§ [Model kernels](spec-analytics-cohorts-forecasting.md#model-kernels)). Decisions D-AN20…D-AN29, assistant-decided, operator-confirmed 2026-11-12.

## Parts

This page is split by topic into 3 parts; each part lists the sections it holds.

1. **[The module, definitions, functions and the profiler](spec-analytics-module-functions.md)** — The module · The definitions · Platform Service `analytics` · Function catalogue · Column profiler · Input rules · Callers consolidated · Similarity kit · Entity grouping
2. **[Distribution Steps, score contract and time-series primitives](spec-analytics-distribution-time-series.md)** — Distribution Steps · Score output contract · Tests · Time-series primitives · Accuracy and backtest · Seasonality detection · Hour of week and seasonal profile · Change-point and regime detection · Windowed and ratio Measures
3. **[Cohorts, ranking, motifs, forecasting and model kernels](spec-analytics-cohorts-forecasting.md)** — Cohort retention and funnel · Change-point and cohort Steps · Holdout split · Ranking, sampling and pivot · Temporal motif matcher · Forecasting · Model kernels · References
