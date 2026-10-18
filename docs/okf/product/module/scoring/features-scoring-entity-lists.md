---
type: Feature
title: "Risk Scores and Entity Lists — features"
description: "Two modules sold as one add-on:  - **Risk Scores** — features/inspecto-scoring (id scoring, package com.gamma.risk): an explainable 0–100 score   per entity from weighted factors, computed by the risk.score Job."
tags: [scoring, scoring-entity-lists, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Risk Scores and Entity Lists — features

**Availability:** Offering: Standard and above — add-on `scoringLists` (status `built`, modules `entity-list`, `scoring`) in `offerings/standard.toon`.

## What it does

Two modules sold as one add-on:

- **Risk Scores** — `features/inspecto-scoring` (id `scoring`, package `com.gamma.risk`): an explainable 0–100 score
  per entity from weighted factors, computed by the `risk.score` Job. Classes: `RiskScoreModel` (spec + validation),
  `RiskScorer` (pure arithmetic, `recompute`), `RiskScoreEvaluator` (DuckDB evaluation and output),
  `RiskScoreJobType`, `RiskScoreRoutes`, `RiskScoreKindValidator`.
- **Entity Lists** — `features/inspecto-entity-list` (id `entity-list`, package `com.gamma.entitylist`): a
  Space-scoped set of typed entity keys with a purpose `allow | block | watch | exclusion`, stored as facts in the
  hash-chained log `EntityFactLog` (`platform/inspecto-entity-store`). Classes: `EntityListRoutes`,
  `RiskWatchListFeed` (implements the `WatchListFeed` SPI), `EntityListGovernableKinds`.

`scoring` requires `entity-list`; `entity-list` requires `entity-store`. Full concepts:
[Risk Scores](spec-risk-scores.md), [Entity Lists](../entity-list/spec.md).

## Where it fits

Add-ons over the platform base. Scores are written through the shared score output contract (engine
`ScoreOutputWriter`, see [analytics-kernel](../analytics/architecture-overview.md)); Incidents come from the ordinary Alert Rule
machinery ([alerts](../../addon/incidents/architecture-alerts.md)). Without the modules `/risk-scores*` and `/entity-lists*` answer 503 and
`/bootstrap` reports `features.scoring` / `features.entityList` false.

More: [screens](user-guide-scoring-entity-lists.md) · [API](api-scoring-entity-lists.md) · [architecture](architecture-scoring-entity-lists.md).
