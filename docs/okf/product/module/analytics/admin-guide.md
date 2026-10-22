---
type: Feature
title: "Analytics Kernel — admin guide"
description: "Operating the Analytics Kernel: no install step, the entity-cap flags that bound score runs, the function catalogue route, the CI duplication guard and common failures."
tags: [analytics, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Analytics Kernel — admin guide

The kernel (`platform/inspecto-analytics`) is a library in every Edition: no install step, no state, no files on
disk. What an operator controls is how much work a score run may take on.

## Entity cap

[`EntityCap`](../../../../../platform/inspecto-analytics/src/main/java/com/gamma/analytics/score/EntityCap.java)
bounds the entities one score run may score. Past the cap the run **fails** — it never scores a silent subset,
and the cap cannot be disabled.

| Flag | Default | Used by |
|---|---|---|
| `-Danomaly.score.maxEntities` | `200000` | Anomaly score runs |
| `-Drisk.score.maxEntities` | `200000` | Risk Score runs |

A malformed or non-positive value keeps the default. A model's own `maxEntities` (1 to 2,000,000) overrides the
flag. The model kernels (k-means, logistic regression, isolation forest) use the same numbers and refuse, never
sample, past them (D-AN24 in the [plan](../../../../archived-documents/plans-archive/analytics-core-plan.md)).

## Monitor

- `GET /analytics/functions` (optional `?kernel=`) lists the registered functions; read-only, ungated; an
  unknown `kernel` is 422. Providers register through
  `META-INF/services/com.gamma.analytics.catalogue.AnalyticsFunctionProvider`.
- CI runs `node tools/check-data-ops-duplication.mjs --ratchet`; a new private statistic or SQL quote helper
  fails it.

## Common failures

| Symptom | Cause / fix |
|---|---|
| Score run fails with an entity-cap refusal | Population larger than the cap; raise the flag or the model's `maxEntities`, or narrow the input. |
| Isolation-forest model refused by preview (422) | By design: scored by the `anomaly.score` Job only (D-AN28). |
| A kernel missing from the catalogue | Not yet registered as a provider (open item in the [plan](plan.md)). |
