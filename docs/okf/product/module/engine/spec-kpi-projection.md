---
type: Reference
title: KPI projection and Alert Rules on a KPI
description: Where a KPI's period in progress lands (BI-INSIGHTS-1 P1 I-12) and Alert Rules on a KPI or its projection (I-17) — the method, the route, the rule shapes, the gates and the limits
tags: [kpi, projection, forecast, alert-rule, bi-insights]
timestamp: 2026-11-25T00:00:00Z
item: module.engine
audience: internal
status: current
verified: 2026-11-25 code-reviewed
code: platform/inspecto-engine
---

# KPI projection and Alert Rules on a KPI

`BI-INSIGHTS-1` P1, capabilities I-12 and I-17 (plan [`2026-11-18-business-insights-plan.md`](../../../../superpower/2026-11-18-business-insights-plan.md)
§11.5). Computed at query time (D-BI7): nothing is stored. Standard BI (D-BI3).

## I-12 — where the period lands

`GET /kpis/{id}/value?projection=true` adds `projection` to the KPI's answer. The KPI tile asks for it on every period
read (never on a Dashboard's picked range) and shows it in words: a badge **Projected: Amber** beside the actual's
**RAG: Red**, and *Lands at SAR 2,300 (SAR 2,200–SAR 2,400, 80 % range) · 97 % chance of meeting the target*.

### Method

| Step | What runs | Where |
|---|---|---|
| History | The KPI's own Measure **per day** over the live Dataset, at most 730 days back, from the first day with data to `asOf`, in ONE bucketed `MeasureCompiler` statement cut in the KPI's zone; a day with no row is 0 | `KpiEvaluator.project` / `dailySql` |
| Season | `SeasonalityDetector` over the daily series (candidates 7 and 365); none found ⇒ 7 | Analytics Kernel |
| Landing | value to date + Σ of a Holt-Winters additive forecast (`Forecaster`, the template's α β γ) of the days left in the period | `PeriodProjection.project` |
| Interval | rolling-origin `Backtest`: at every past day, the same projection of H days was made and its **total** compared with what happened; sigma = 1.4826 × median(abs(miss)) (scaled MAD about 0, so a biased model widens it); band = landing ± z(0.8) × sigma | `PeriodProjection` |
| P(target met) | normal with that sigma: `at least` the target (direction up), `at most` (down), inside the green interval (band); no target ⇒ the green threshold; neither ⇒ none | `PredictionInterval.normalCdf` (new) |
| RAG on projection | `KpiDefinition.rag(landing)` — the same bands as the actual | `KpiEvaluator.Projection` |

The interval of a SUM of forecasts is not the sum of per-day intervals (the errors are correlated), so it is measured on
this series rather than assumed. `backtest.mapePct` and `backtest.coverage` in the answer say how well that went.

### Decision: the live Dataset, not KPI History

The projection reads the live Dataset per day, never `kpi_history_<kpi>`. Evidence: KPI History stores one row per
**whole period** at the KPI's grain (`spec-kpi-history.md` § Columns) — a month KPI holds monthly values — while a
projection needs what the **rest of the current period** holds, i.e. buckets smaller than the period. KPI History also
exists only where a `kpi.snapshot` Job was scheduled; the live Dataset always exists and is already read under the same
gates and masking as the KPI's value.

### No projection — and why

`status: none` with a `reason`, never a guessed number:

- a picked range of days (it is not a period in progress);
- a `day`-grain KPI (no smaller bucket);
- a Measure other than `count` / `sum` (an average or a distinct count does not add up day by day);
- fewer than two seasons of history (14 days), or fewer than 8 past projections of the days left to measure the
  interval on (e.g. a quarter KPI on its first day needs about 113 days of data).

## I-17 — an Alert Rule on a KPI or its projection

```
{ name: revenue-off-track, kpi: monthly_revenue, projection: probability, comparator: lt, threshold: 0.5, severity: CRITICAL }
```

| Keys | Watches | Fires when |
|---|---|---|
| `kpi` + `comparator`/`threshold` | the value to date | it crosses the threshold |
| `kpi` + `rag: amber\|red` | the RAG of the value to date | it is that status or worse (comparator and threshold fixed: `gte` the rank) |
| `kpi` + `projection: value` + `comparator`/`threshold` | the projected landing | it crosses |
| `kpi` + `projection: value` + `rag` | the RAG of the landing | that status or worse |
| `kpi` + `projection: probability` + `comparator`/`threshold` (0 < t ≤ 1) | P(target met) | it crosses |

A KPI rule takes no `dataset`, `measure`, `metric`, `window`, `maximumAge`, `when`, `by`, `investigation` or `onPipeline`; `rag` and
`projection` belong to a KPI rule only. It is a scalar rule like a Dataset measure rule: edge-triggered, healed after
`healAfterSweeps` healthy sweeps, its Alert and Incident scoped to `kpi:<id>`; at CRITICAL it opens an Incident (with
`kpi` / `projection` / `rag` attributes), a heal resolves the Alert and never the Incident, and a relapse links to it.

### Gates

- **Save** (`AlertRoutes.parseContent` → `KpiAlertProbe.requireReadable`, so every door that writes an Alert Rule meets
  it, the maker-checker hold and `DecisionRuleGuard` unchanged): the KPI exists; the rule's **owner** may view it and
  read its Dataset; its Measure reveals no masked column (`BiResultMasking.vetMeasure`, no reveal — an alert never
  reveals); a `rag` or `probability` rule needs a KPI with a target or bands; a `projection` needs a `count`/`sum` KPI
  on a week, month, quarter or year grain. 422 otherwise.
- **Sweep** (`KpiAlertProbe.of`, wired in `CollectorService` per Space): the same checks, **as the rule's owner** (as
  `kpi.snapshot` runs as its author, `PostgresPublishJobType.canView/canRead`); a refusal or no projection reads as
  unknown — nothing fires, nothing heals — and is logged. `asOf: latest` KPIs follow the newest day in the data.
- **Alert feed masking**: a `kpi:` scope is not a Dataset id, so `ObjectReadMasking` masks nothing in it (its text holds
  no key); before this, reading the feed with a KPI Alert on it failed (500).

### Limits

- A rule carries its owner's **id, not their roles**: a KPI or Dataset shared to the owner only through a role is not
  readable by the rule (fail closed, refused at save). Share it to the owner, or leave it unshared.
- The sweep re-reads a projection on every sweep (a backtest per KPI rule); fine for tens of rules, not thousands.
- The Studio Alert Rule editor does not offer `kpi` / `projection` yet (authored through `/alerts/rules` or a bundle).

## Tests

`PeriodProjectionTest` (kernel: weekly landing, probabilities, the band's mass, two-seasons and too-few-origins
refusals, `normalCdf`), `KpiEvaluatorTest` (I-12 over DuckDB: the month landing, RAG on projection beside actual,
direction and green-threshold goals, every "no projection" reason, the zoned daily statement), `KpiAlertRuleTest`
(shapes, refusals, fire / refresh / heal / relapse, status wording, the ledger pass skipping a KPI rule),
`ScalarMeasureAlertObjectsTest.aCriticalKpiRuleOpensAnIncidentHealsItsAlertAndRelinksARelapse` (real object engine),
`ControlApiKpiProjectionAlertTest` (armed HTTP: the projection answer 401/200 and its 404 for an unreadable Dataset,
the rule's save gate 401/403/200 and every 422 — falsified by removing the gate — and the sweep firing on the
projection's RAG through `/alerts/evaluate` and `/alerts`), `kpi.component.spec.ts` (the tile's words and request).
