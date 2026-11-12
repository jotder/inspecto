---
type: Feature
title: "Analytics Kernel — plan"
description: "Requirement of record for the Analytics Kernel: what was required, what is built, what is left and what was refused, from BACKLOG row ANALYTICS-CORE-1 and its plan."
tags: [analytics, plan]
timestamp: 2026-10-19T00:00:00Z
---

# Analytics Kernel — plan

Row of record: `ANALYTICS-CORE-1` (P2) in [BACKLOG](../../../../BACKLOG.md); working plan
[analytics-core-plan](../../../../archived-documents/plans-archive/analytics-core-plan.md); as-built detail in [spec](spec.md).

## Required

One home for data operations and statistics, so modules stop keeping their own maths: canonical statistics,
one SQL building path, one column profiler, time-series primitives, baseline and score output contracts, and a
ratcheting duplication guard (Must items M1–M10).

## Built

- **Musts M1–M10 (2026-10-17):** the module `platform/inspecto-analytics` (Base in every Edition, D-AN1), graph
  library `platform/inspecto-graph`, `SqlIdent` / `SqlBuilder` / `SqlViews`, statistical Measure aggregates, the
  column profiler, time-series primitives and Steps, baseline and score output contracts, and the CI guard
  [`check-data-ops-duplication.mjs`](../../../../../tools/check-data-ops-duplication.mjs) (`--ratchet`).
- **Shoulds S1–S8:** similarity kit, entity grouping, distribution Steps, rank / sample / pivot, forecasting base,
  temporal motif matcher, the `analytics` Platform Service, aggregation moved to SQL.
- **Coulds:** C1 windowed and ratio Measures (first consumer: Customer Segments, `PREDICTIVE-SEGMENT-RFM-1`, 2026-11-01 — [`spec-segmentation.md`](../predictive/spec-segmentation.md)), C2 k-means, C3 logistic regression, C4 isolation forest
  (`ANOMALY-ML-SCORING-1`), C5 hour-of-week seasonality, C6 change-point kernel, C8 function catalogue
  (`GET /analytics/functions`), C9 cohort retention and funnel.
- **Could-batch kernels in the function catalogue (2026-11-12):** provider `CouldBatchFunctions` lists the calendar
  slot, seasonal profile, change points, cohort retention, funnel, k-means, logistic regression and the isolation
  forest; `stats.correlation` names `method: pearson | spearman`. Windowed and ratio Measures are Measure grammar, not
  a kernel function, so they are not catalogued.
- **Anomaly Model form authors `method` / `forest`** (D-AN29, closed 2026-10-20 with `ANOMALY-ML-SCORING-1`).
- **Steps for C6 and C9 (2026-11-01, `ANALYTICS-STEPS-TS-1`):** `transform.timeseries.changepoint`,
  `transform.cohort.retention`, `transform.cohort.funnel` (`steps:` kinds `changepoint`, `retention`, `funnel`) —
  [spec § Change-point and cohort Steps](spec.md#change-point-and-cohort-steps).

## Left

- Operator confirmation of decisions D-AN10…D-AN35 (assistant-decided).
- Hand-wrapped scratch-name sites listed on the row; `PartitionCompactor` `union_by_name` (operator-owned).
- C7 graph traversal in SQL — no longer blocked (DuckDB 2.0.0.0 is pinned since 2026-10-23); it waits for its
  benchmark on board row `DUCKDB-2-LOOP-QUERIES-1`, candidate C1 / C2 of
  [`duckdb-2-plan.md`](../../../../archived-documents/plans-archive/duckdb-2-plan.md) §7.

## Refused (Won't, this horizon)

W1 external ML runtime / ONNX / AutoML · W2 Python or R runtime, notebooks, feature store · W3 new analytics
libraries · W4 DuckDB extensions in the sandbox · W5 streaming analytics · W6 identity asserted by similarity ·
W7 training on the request path · W8 moving domain logic into the core.
