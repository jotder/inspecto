---
type: Architecture
title: Business Insights
description: How "Explain this change" finds the slices that carry a Measure's change, and how the Insight scan finds what is unusual across an Insight Profile's slices, ranks it and stores it as a Dataset — both through the same gates and masking as POST /bi/query.
item: module.insights
audience: internal
status: current
verified: 2026-11-26 last-edit
code: [features/inspecto-insights, platform/inspecto-analytics, inspecto]
---

# Business Insights

> **Level 3 module page.** Layer: Add-on. Offering: Standard and above — add-on `businessInsights` (status `built`,
> module `insights`) in `offerings/standard.toon` (D-BI2 / D-BI3, operator 2026-11-25: the module is created in P2,
> not P3). Plan: `docs/superpower/2026-11-18-business-insights-plan.md` §11.8 (Explain) and §11.9 (Insight scan).

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
- **Mix and rate is not reachable from a tile yet**: no Widget authors a ratio today (`ratioMeasure` in
  `inspecto/viz/query-spec.ts` has no production caller; ratios come from KPIs and Risk factors), so the drawer's
  waterfall bridge appears only for a request that carries `ratio`. I-5 is proven at the route
  (`ControlApiExplainTest`, an exact split) and in the dialog spec. An `avg` Widget is not mapped to sum ÷ count:
  `count` counts rows, `avg` skips NULLs, so the split would not be exact.

## 4. UI

The tile action **Explain this change** (light-bulb icon) appears on a Widget over a grained date when the
`insights` feature is installed and the user can author. It opens a dialog (`studio/widgets/explain.dialog.ts`) with
the headline change, per-dimension tables (cover values in bold), the mix / rate bridge on the **waterfall**
Visualization Type (Before → Mix → Rate → After) and the Moved-with list with its "does not say one causes the
other" note. The request is built by `inspecto/viz/explain-request.ts` from the Widget's own `/bi/query` body.

## 5. The Insight scan (P3: I-8, Profiles, feed, feedback, replay, calendar)

An **Insight Profile** (component kind `insight-profile`, `registry/insight-profiles/`) names a Measure (sum or
count — impact is a share of the whole, which only an additive Measure has), its Dataset, date column and grain,
up to 12 dimensions, the tests, and the knobs: `sensitivity` (q ≤ 0.01 / 0.05 / 0.10), `minImpact`, `minSupport`,
`maxValues`, `lookback`, `season`, `noveltyRuns`, `maxInsights` and a business `calendar`. The Job **`insight.scan`**
(parameter `profile`) scans it; `InsightProfileValidator` checks it at save and reserves the store prefix `insights_`.

| Step | Rule |
|---|---|
| Read | One statement for the total and one per dimension, through `BiSpecRunner.runAsJob` — the `/bi/query` compiler, `SqlGuard`, sandbox and masking **as a reader who may not reveal**: a Measure over a masked column refuses the run; a classified slice value is stored as its alias. The Job runs as its author (`canView` the Profile, `canRead` the Dataset), as `kpi.snapshot`. A dimension with more than `maxValues` values is skipped and named in the run message |
| "Now" | The last **complete** bucket; the bucket in progress is never scanned |
| Insight Types (kernel `com.gamma.analytics.insight`) | **anomaly** (a spike) — robust z of now against the same season slot (`season: auto` = 7 for day, 52 for week, 12 for month; when ≥ 4 hold a value) or every earlier bucket; **trend-break** (a level shift) — largest CUSUM of the mean (σ from first differences), Kolmogorov tail, only when the change is in the last 2 × minSegment buckets; **drift** (a steady climb or slide) — Mann-Kendall with ties, Sen's slope. Per dimension (I-9, I-10; opt-in in `types`, need ≥ 1 dimension, the default stays the three above): **concentration** — the share held by the top 3 values (re-ranked per bucket) as a series, robust z of now against the earlier buckets (1 share point floor; a dimension of ≤ 3 values is not tested), with HHI, Gini and the 80 % Pareto count in the explanation (`Concentration`); **distribution-shift** — PSI of now's value mix against the baseline window, p = its empirical rank among each earlier bucket's leave-one-out PSI ((# ≥ now + 1) ÷ (n + 1); PSI is too skewed for a normal tail), with total variation, the KS distance (nominal KS p for a count Measure only) and the value that moved most (`DistributionShift`). Their impact is the share of the whole that moved (share points; total variation), not \|Δ\| ÷ the Measure; a dimension with a negative amount is not tested |
| False discoveries | Benjamini-Hochberg across every slice × test of the run; kept at q ≤ α |
| Filters | slice support ≥ `minSupport` of the window; \|Δ\| ≥ `minImpact` × the Measure in that bucket; `exclude` calendar days are removed from every series, a finding dated on an `expected` day is dropped (and counted) |
| Score 0–100 | impact × surprise × novelty (`InsightScore`, bands 40 / 70); novelty = 1 ÷ (1 + times the same type × slice was reported in the last `noveltyRuns` runs), 0 after *not useful* or *expected* feedback |
| Store | Dataset `insights_<profile>` (system, sharing envelope = Profile ∩ Dataset, as KPI History); one Parquet rewritten atomically per run, 400 days of runs; `feedback.json` beside it |
| Signals | `insight.scan.completed`; `insight.found` per new elevated or high Insight (≤ 20 a run) for Notification Rules |

**Routes**: `GET /insights?profile=` (read-shaped, like `/bi/query`: whoever may view the Insights Dataset; latest
run, best first, current feedback laid over `status`); `POST /insights/{profile}/{insightId}/feedback` (status
useful / not_useful / expected, kept per type × slice); `POST /insights/preview` (an unsaved Profile replayed as of
each of the last 4–52 buckets, as the caller, nothing saved — "you would have seen N Insights"). Both POSTs are gated
on `canAuthorWorkbench`.

**Deliberate deferrals**: no Profile editor or Insight Feed Visualization Type yet (UI slice); feedback is
Builder-gated because no reader-level capability exists — D-BI4 wants Business users to give it (operator
question); owners tuning within Builder bounds (D-BI4) is not enforced — who may save the Profile tunes it; the
sharing envelope does not narrow by a Mapping Dataset's `basedOn` base (the same gap as KPI History); no
separate `_latest` store (the latest run is `max(run_id)` in the one store, so one envelope covers it); the stored
`query` is the dimension's grouped `/bi/query` body (a masked slice cannot be filtered back by its alias); I-9
concentration and I-10 distribution shift are not built.

**Gotcha found while building**: a DuckDB `TIMESTAMP` written through a JDBC `Timestamp` is stored in the host zone
and read back by `epoch_ms` as UTC, so each rewrite shifted every stored run by the host offset. `InsightStore`
writes timestamps as UTC text (`CAST(? AS TIMESTAMP)`); `InsightScanJobTest` pins it.
