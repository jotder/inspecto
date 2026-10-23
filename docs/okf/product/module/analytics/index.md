---
type: ProductItem
title: Analytics Kernel (canonical statistics and baselines)
item: module.analytics
description: Documentation hub for module.analytics — every document about it, by slot.
tags: [product-item, module]
---

# Analytics Kernel (canonical statistics and baselines) (`module.analytics`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.analytics`](../../../../../product-map/module/analytics.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Analytics Kernel — plan](plan.md) |
| spec | current | [Analytics Kernel — canonical statistics, the column profiler, time-series primitives, the baseline and score output contracts](spec.md) |
| architecture | current | [Analytics kernel](architecture-overview.md) |
| features | current | [Analytics kernel — features](features-analytics.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Analytics functions](user-guide-analytics-functions.md) · [Analytics kernel — screens](user-guide-analytics.md) · [KPI & Reports](user-guide-kpi-reports.md) |
| admin-guide | current | [Analytics Kernel — admin guide](admin-guide.md) |
| api | current | [Analytics kernel — API](api-analytics.md) |
| tests | current | [Analytics Kernel (canonical statistics and baselines) — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.analytics` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `ANALYTICS-CORE-1` | P2 | feature | one home for data operations and statistics; minimise duplicate data-operations code across modules. | — |
| `ANALYTICS-STEPS-TS-1` | P3 | feature | Steps for the Analytics Kernel time-series and cohort kernels: transform.timeseries.changepoint (ChangePoint, input a residual column + σ… | — |
| `DUCKDB-2-LOOP-QUERIES-1` | P3 | feature | the looping / graph-style query candidates now that 2.0.0.0 is pinned: C1 / C2 USING KEY recursion behind SqlGraphEngine (Analytics C7),… | — |
| `PREDICTIVE-ANALYTICS-1` | P3 | feature | Predictive Analytics add-on | — |
| `PREDICTIVE-SEGMENT-RFM-1` | P3 | feature | a Segmentation Model component kind and a predict.segment Job: declared RFM bins and a label table; its Instance is a Customer Segment as… | — |
| `PREDICTIVE-CHURN-DECLARED-1` | P3 | feature | a Churn Model kind: a declared-coefficient logistic score with contributions, insufficient when history is short, and an afterScore pendi… | — |
| `PREDICTIVE-FORECAST-1` | P3 | feature | a Forecast Model kind: seasonal-naive and Holt-Winters with an interval and the backtest error stored. | — |
| `PREDICTIVE-SURFACE-1` | P3 | feature | the read and preview routes (four gates), Studio panes, the entity panel and the forecast chart for the Predictive add-on. | — |
