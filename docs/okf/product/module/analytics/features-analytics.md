---
type: Feature
title: "Analytics kernel — features"
description: "The Analytics kernel is the module platform/inspecto-analytics: one written definition of every statistic and time-series primitive the product uses, as pure Java functions and DuckDB SQL builders."
tags: [analytics, analytics, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Analytics kernel — features

**Availability:** Offering: all tiers (a core thin jar in every edition).

## What it does

The Analytics kernel is the module `platform/inspecto-analytics`: one written definition of every
statistic and time-series primitive the product uses, as pure Java functions and DuckDB SQL builders.
It has no routes, no state and no third-party dependency. Before it existed, the same statistic was
computed several ways in several modules; the kernel is the single home, and callers were moved
onto it.

The concept page of record, with every formula, is
[analytics-kernel](spec.md); the plan is row `ANALYTICS-CORE-1` in
[`analytics-core-plan.md`](../../../../superpower/analytics-core-plan.md).

## Where it fits

Main code depends only on `inspecto-util` (for `SqlIdent`); `tools/check-module-deps.mjs` and the
pom's enforcer rule allow only the foundation layer. A feature module may depend on the kernel; the
kernel can never reach the engine or a feature. It ships as its own jar beside `inspecto.jar`
(`tools/bundle-modules.mjs` `CORE_MODULES`).

Packages under `platform/inspecto-analytics/src/main/java/com/gamma/analytics`:

| Package | Contents |
|---|---|
| `com.gamma.analytics` | `Stats`, `StatsSql`, `MedianMode`, `QuantileMode`, `ColumnProfiler` |
| `.time` | `TimeGrain` (one truncation definition), `TimeSeriesSql` (resample, shift), `SeasonalityDetector` |
| `.baseline` | `BaselineStatistic`, `BaselineStatistics`, `SeasonalBaseline`, `Seasonality`, `PeerBaseline`, `Baseline`, `BaselineScore`, `RobustStats` |
| `.score` | the score output contract: `ScoreKind`, `ScoreColumns`, `ScoreScale`, `EntityCap`, `ScoreRunFiles`, `ExplanationPart` |
| `.forecast` | `Forecaster`, `ForecastMethod`, `ForecastSql`, `ForecastOutput`, `PredictionInterval` |
| `.similarity` | `Similarity`, `JaroWinkler`, `TextUnit`, `SimilaritySql` |
| `.accuracy`, `.split` | `Accuracy`, `AccuracySql`, `Backtest`; `HoldoutSplit` |

More: [screens](user-guide-analytics.md) · [API](api-analytics.md) · [architecture](architecture-overview.md).
