---
type: Architecture
title: Business Insights
description: How "Explain this change" finds the slices that carry a Measure's change (drivers), splits a ratio's change into mix and rate, and lists the Measures that moved with it — through the same gates and masking as POST /bi/query.
item: module.insights
audience: internal
status: current
verified: 2026-11-25 last-edit
code: [features/inspecto-insights, platform/inspecto-analytics, inspecto]
---

# Business Insights

> **Level 3 module page.** Layer: Add-on. Offering: Standard and above — add-on `businessInsights` (status `built`,
> module `insights`) in `offerings/standard.toon` (D-BI2 / D-BI3, operator 2026-11-25: the module is created in P2,
> not P3). Plan: `docs/superpower/2026-11-18-business-insights-plan.md` §11.8.

## 1. What it is

**Explain this change** (BI-INSIGHTS-1 P2) answers "why did this Measure move between the last period and the one
before?" for a Widget over time. One call, `POST /insights/explain`, returns three answers:

| Answer | Item | Rule |
|---|---|---|
| **What explains it** — per dimension, the fewest values whose contributions cover at least *cover* (default 80 %) of the change; dimensions ranked by surprise | I-4 | contribution = after − before; share = contribution ÷ Δ; surprise = Jensen-Shannon divergence of the value shares before and after (Adtributor). Values taken largest share first, which is the smallest set for a sum threshold |
| **Mix and rate** — for a ratio Measure only | I-5 | ΔR = Σ (w₁ − w₀)·r₀ **[mix]** + Σ w₁·(r₁ − r₀) **[rate]**, w = D_c ÷ D. Exact: mix + rate = ΔR. A value that appears or disappears is all mix (its missing rate takes the other period's) |
| **Moved with it** — other Measures | I-6 | Pearson correlation of the bucket-to-bucket CHANGES (not the levels), at least 8 pairs; \|r\| ≥ 0.5 reads "moved with it". Worded as moving together; never as a cause |

Only **sum** and **count** (and a ratio of them) are explained. A median, distinct count, percentile, min, max,
average (use the ratio sum ÷ count), standard deviation or variance is refused with a 422 that says why — never
approximated.

## 2. How it runs

- **Kernel** (`platform/inspecto-analytics`, `com.gamma.analytics.drivers`): `Drivers`, `ShiftShare`, `MovedWith` —
  pure JVM, hand-computed goldens in `DriversGoldenTest`.
- **Route** (`features/inspecto-insights`, `ExplainRoutes`, feature id `insights`, gated on `canAuthorWorkbench`).
  It builds no SQL: each statement is a `/bi/query`-shaped spec run through **`BiSpecRunner`** (`inspecto/`,
  `com.gamma.control`) — the dataset visibility check (404), `SqlGuard`, the `QueryExecutor` sandbox and
  `BiResultMasking` (vet + mask) exactly as `POST /bi/query` applies them, as the caller.
- **Statements**: one for the series (the Measure and the related Measures per bucket over `lookback` buckets,
  default 24) — it gives the two totals and I-6 — and one per dimension (the Measure grouped by the dimension and the
  bucket, windowed to the two buckets). At most 12 dimensions, 10 related Measures; a dimension with more than 200
  values is listed in `refused[]`, not cut.
- **Masking**: a classified dimension's values reach a caller without `canRevealLinkEntities` as their Space alias;
  a Measure that reaches the caller masked refuses the explanation (422). There is no public-share explain route,
  so a public share never reveals through it.

## 3. Decisions and deliberate deferrals

- **D-BI7 as built**: depth 1 is interactive. **Depth 2** (pairs of dimensions) needs the Job the plan puts in P3
  (`insight.scan`); it is not offered — board row `BI-EXPLAIN-DEPTH2-1`.
- **One statement per dimension, not one `GROUPING SETS` statement**: the per-dimension statements go through the
  unchanged `/bi/query` masking path (masking applies per grouped column of one plain SELECT); a `GROUPING SETS`
  form would need a new masking path. Cost: up to 13 statements per click, each bounded by its row cap.
- **JVM, not SQL, for the statistics**: they run over a few hundred masked rows (as `ChartOverlays`, §11.4); no
  DuckDB form was added.
- **Capability**: `canAuthorWorkbench` (Builder lens). A Business-lens viewer does not see the action yet — an
  open question for the operator, not a technical limit.
- **A Widget on a saved Query** is not explained (the request needs the Dataset); the drawer says so.

## 4. UI

The tile action **Explain this change** (light-bulb icon) appears on a Widget over a grained date when the
`insights` feature is installed and the user can author. It opens a dialog (`studio/widgets/explain.dialog.ts`) with
the headline change, per-dimension tables (cover values in bold), the mix / rate bridge on the **waterfall**
Visualization Type (Before → Mix → Rate → After) and the Moved-with list with its "does not say one causes the
other" note. The request is built by `inspecto/viz/explain-request.ts` from the Widget's own `/bi/query` body.
