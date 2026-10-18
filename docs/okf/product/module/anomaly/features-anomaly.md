---
type: Feature
title: "Anomaly Detection — features"
description: "An **Anomaly Score** answers \"is this entity behaving unlike itself, or unlike its peers, and why?\" — a 0–100 number per entity per run, stored with a per-Feature explanation so it can be recomputed."
tags: [anomaly, anomaly, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Anomaly Detection — features

**Availability:** Offering: Standard and above — add-on `anomalyDetection` (status `built`, module `anomaly`) in `offerings/standard.toon`.

## What it does

An **Anomaly Score** answers "is this entity behaving unlike itself, or unlike its peers, and why?" — a 0–100
number per entity per run, stored with a per-Feature explanation so it can be recomputed. The authored Type is an
**Anomaly Model** (component kind `anomaly-model`). The module is `features/inspecto-anomaly` (id `anomaly`,
package `com.gamma.anomaly`): `AnomalyModel` (parse, fail closed), `AnomalyLists` (watch / exclusion lists),
`AnomalyScoreEvaluator` (one DuckDB statement per Feature), `AnomalyScorer` (pure maths), `AnomalyScoreJobType`
(Job Type `anomaly.score`), `AnomalyScoreRoutes` and `AnomalyKindValidator`.

It is distinct from a Risk Score (absolute weighted factors — see [scoring-entity-lists](../scoring/architecture-scoring-entity-lists.md)),
a `baseline` Expectation and the Link Analysis `suspicionScore`. Full concept:
[Anomaly Scores](spec-anomaly-scores.md).

## Where it fits

An add-on that requires `entity-list` (`requires.modules` in its `module.toon`) but **not** `scoring`. The
baseline maths come from the Analytics Kernel (`com.gamma.analytics.baseline`, see
[analytics-kernel](../analytics/architecture-overview.md)); the score output contract and its writer `ScoreOutputWriter` are shared
with Risk Scores in the engine. Without the module `/anomaly-scores*` answers 503 and the SPA hides every anomaly
surface.

More: [screens](user-guide-anomaly.md) · [API](api-anomaly.md) · [architecture](architecture-overview.md).
