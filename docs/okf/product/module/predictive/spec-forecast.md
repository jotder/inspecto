---
type: Architecture
title: Forecasts — the Forecast Model kind and the predict.forecast Job (seasonal-naive and Holt-Winters)
description: As built for PREDICTIVE-FORECAST-1 (2026-11-01) — slice S4 of the Predictive Analytics add-on. The forecast-model component kind (day or week grain, seasonal-naive or additive Holt-Winters with authored smoothing, horizon 1..90, two seasons of history else insufficient, maxSeries), the predict.forecast Job that writes one row per series per future bucket with a prediction interval and the backtest error, the save gates, the decisions taken (D-PF1…D-PF11, pending operator confirmation) and what is not built.
resource: docs/okf/product/module/anomaly/plan-predictive-analytics.md
tags: [predictive, forecast, holt-winters, seasonal-naive, backtest, job, analytics]
timestamp: 2026-11-01T00:00:00Z
---

# Forecasts (seasonal-naive and Holt-Winters)

*PREDICTIVE-FORECAST-1, built 2026-11-01 — slice S4 of the Predictive Analytics design
([`plan-predictive-analytics.md`](../anomaly/plan-predictive-analytics.md) §2.3, Options A + B; limits from OD-11).* A
**Forecast Model** (the Type) declares how a Measure is forecast per series; one `predict.forecast` run writes one
**Forecast** (the Instance): a value with an interval per series per future bucket ([`GLOSSARY.md`](../../../../GLOSSARY.md)
§4). **Nothing is fitted by the platform**: Holt-Winters' smoothing parameters are authored (OD-6); what every run
measures and stores is the method's **backtest error**, against seasonal-naive as the benchmark.

Same module, same shape as [Customer Segments](spec-segmentation.md) and [Churn Scores](spec-churn.md):
`features/inspecto-predictive`, add-on `predictiveAnalytics`, no routes.

| Class | Role |
|---|---|
| `ForecastModel` | the kind's record and fail-closed `fromMap` parse; `Grain` (season length, history limits) and `Method` |
| `Forecaster` | the pure maths: both methods as one `Fit`, the backtest, the interval, `insufficient`, `recompute` |
| `ForecastEvaluator` | the bucketed read (one bound statement), the last complete bucket, the series cap, the output columns, the write |
| `ForecastJobType` | the `predict.forecast` Job Type (`JobTypeProvider`) |
| `ForecastKindValidator` | the save gates (`ComponentKindValidator`) |

In the core: the kind in `ComponentRegistry.TYPE_BY_DIR` (`forecast-models/`) and `ComponentStore.WRITABLE_TYPES` (so
governable: an approval policy can hold every save, OD-7). Nothing else — no Signal, no Alert seed kind (D-PF10).

## The kind

Stored at `registry/forecast-models/<id>.toon`; the id matches `[A-Za-z0-9][A-Za-z0-9_]*` (at most 64 characters).

```
entityType: product
dataset: sales
key: sku
time: sold_at
measure: sum(qty)
grain: day
method: holt-winters
alpha: 0.5
beta: 0.3
gamma: 0.2
horizon: 10
history: 28
interval: 95
```

| Key | Meaning |
|---|---|
| `entityType`, `dataset`, `key`, `time` | required; one series per `key` value; `time` must be a DATE or TIMESTAMP column |
| `measure` | `count`, `countDistinct(f)` or `sum(f)` (numeric `f`) per bucket; an empty bucket is 0 |
| `grain` | `day` (season 7) or `week` (ISO week from Monday, season 52) |
| `method` | `seasonal-naive` or `holt-winters` (additive) |
| `alpha`, `beta`, `gamma` | each 0…1; required by `holt-winters`, refused on `seasonal-naive` |
| `horizon` | required, future buckets 1…90 |
| `history` | buckets read before `as_of`, two seasons…1095 (day) / 520 (week); default 364 / 156 |
| `interval` | 80, 90, 95 or 99 (percent, two-sided); default 95 |
| `maxSeries` | 1…1 000 000; default `-Dpredict.forecast.maxSeries`, else 100 000 |
| `description`, `name`, `owner`, `shares` | free text and the component envelope |

Unknown keys are refused — never ignored.

## The run

`predict.forecast` with params `model` (required, config only) and `as_of` (`YYYY-MM-DD`, default yesterday UTC;
trigger args override the config).

1. **Last complete bucket.** `day`: `as_of` itself. `week`: the Monday of the last ISO week that ends (Sunday) on or
   before `as_of` — the week holding `as_of` is excluded unless `as_of` is its Sunday.
2. **Read.** One statement through `MeasureCompiler` with `window: {field: time, asOf: <that bucket's last day>,
   days: history × bucket days}`, `groupBy: [key, time]` and `grains: {time: <grain>}`, every value bound; the result is
   folded to one row per series (`STRING_AGG` of `bucket=value`, ordered), so the row limit is the series cap. More
   series than the cap fails the run before anything is written. NULL keys are dropped.
3. **Series.** From the series' first non-empty bucket in the history window to the last complete bucket, one value per
   bucket; an empty bucket is 0 (D-AD8: count and sum over no rows). `history_buckets` is that length.
4. **Insufficient.** Fewer than two seasons of buckets (14 days, 104 weeks): one row with `status: insufficient`, every
   forecast column NULL — never 0.
5. **Backtest.** The last `b = min(horizon, season)` buckets are held out; the method is fitted on the rest and
   forecasts them. Stored: `backtest_mae`, `backtest_smape` (percent; a bucket where actual and forecast are both 0
   counts 0) and `backtest_rel_mae` = the method's MAE ÷ seasonal-naive's MAE on the same hold-out (NULL when
   seasonal-naive's is 0; 1 for seasonal-naive itself).
6. **Forecast.** Both methods reduce to a level `L`, a trend `T` and one seasonal value per slot; step `h` forecasts
   `L + h·T + S[(h−1) mod m]`.
   * **seasonal-naive**: `L = T = 0`, `S` = the last season.
   * **holt-winters**: initialised from the first season (and the trend from the first two: mean difference ÷ m, else
     0), with the level placed at the end of the first season, so a series that is exactly trend + season is reproduced
     exactly; then for each later bucket `L = α(y − S) + (1−α)(L + T)`, `T = β(L − L′) + (1−β)T`,
     `S = γ(y − L) + (1−γ)S`.
7. **Interval.** `forecast ± z · σ · √k`: `z` the normal quantile of `interval`, `σ` the backtest RMSE, `k = ⌊(h−1)/m⌋ + 1`
   the seasons ahead (seasonal-naive's variance growth, used for both methods). Not clamped at 0.
8. **Write** `forecasts_<model>` (history, one Parquet file per run) and `forecasts_<model>_latest`, through the score
   output contract (`ScoreOutputWriter`, `ScoreKind`): marker `.forecast-model-output`, `*.tmp` then atomic move, no
   retention.

Columns: the head `model, entity_type, entity_key` (the series key), then `as_of` (TIMESTAMP), `status` (`ok` /
`insufficient`), `step` (1…horizon), `bucket` (TIMESTAMP, the bucket's first day), `forecast`, `lower`, `upper`,
`backtest_mae`, `backtest_smape`, `backtest_rel_mae` (DOUBLE; the series' backtest, the same on each of its rows),
`history_buckets`, `explanation`, then the tail `model_version` (12-hex hash of the stored model minus its envelope),
`run_id`, `scored_at`.

**Recompute.** A forecast row's `explanation` is `{method, level, trend, season, sigma, z, seasonsAhead}`; an
insufficient row's is `{insufficient: history, historyBuckets, minHistoryBuckets}`.
`Forecaster.recompute(step, explanation)` rebuilds `forecast`, `lower` and `upper` from the row alone;
`ForecastJobTest` asserts every stored row recomputes.

**Fail closed.** An unknown model or Dataset fails the run; so does a stored model the parse refuses (a horizon past
90 written around the save gate), the cap, an output directory without this model's marker, and a query failure (named
by model, Dataset and error class only).

## Save gates

`ForecastKindValidator`, run by every writer through the core's component save gate (`ComponentRoutes.validateKind`):

* structure — `ForecastModel.fromMap`;
* in the Space — `key`, `time` and the measure's field are in the Dataset's Schema; `time` is DATE/TIMESTAMP; a `sum`
  field is numeric; an unreadable Schema or an unknown Dataset refuses; neither output name may already exist under the
  data root unless this model owns it, nor be the id of a Dataset over another store;
* the **reserved prefix** `forecasts_` on every `dataset` and `sink` write: refused, except a Dataset whose id =
  `physicalRef` = `forecasts_<model>` or `…_latest` of a saved model.

## Decisions (D-PF1…D-PF11 — assistant-decided under operator delegation, 2026-11-01, pending operator confirmation)

* **D-PF1** Output prefix `forecasts_` and marker `.forecast-model-output`, as the design named them; the naming lives in
  the module (`ForecastModel.SCORE_KIND`), like segmentation, because no core reader needs it (no Alert seed).
* **D-PF2** Holt-Winters' α, β, γ are **authored**, each 0…1 — no grid search: the design's "fixed grid search on a
  hold-out" is fitting, which OD-6 keeps out of v1.
* **D-PF3** The season is fixed by the grain (7 days, 52 weeks); no `season` key. A daily series with a yearly season
  needs week grain. Seasonal-naive is the last season alone — the design's "averaged over N seasons" variant is not
  built (N = 1).
* **D-PF4** Holt-Winters' initial state from the first season (level at its end, seasonals around its trend line) and
  the trend from the first two seasons, else 0 — chosen so trend + season is reproduced exactly (the golden test).
* **D-PF5** The backtest holds out `min(horizon, season)` buckets, so a two-season series always has one season to fit
  on. Metrics MAE, sMAPE and MAE relative to seasonal-naive on the same hold-out — **not MASE**: its in-sample scale
  needs more than one season of training, which a two-season series does not always have. One hold-out, not a rolling
  origin.
* **D-PF6** Interval from the backtest RMSE with a normal quantile (levels 80 / 90 / 95 / 99) and √(seasons ahead)
  widening for both methods — an approximation, stated; not clamped at 0 (a negative bound says the model is unsure,
  hiding it would not).
* **D-PF7** Measures `count`, `countDistinct`, `sum` only: those whose empty bucket is honestly 0. An `avg` per bucket
  has no value for an empty bucket; a `sum` over only NULL values is 0 too. A series starts at its first non-empty bucket inside the history window.
* **D-PF8** Only complete buckets are history: the ISO week holding `as_of` is excluded unless `as_of` is its Sunday;
  forecast buckets start after the last complete one.
* **D-PF9** `history` bounds the read (default 364 days / 156 weeks; at most 1095 / 520) and so the memory a run takes;
  `maxSeries` has its own property and default (100 000, OD-11) and ceiling (1 000 000), not `EntityCap`'s 200 000.
  At the ceiling a run holds every series' history in heap (several GB at 1 000 000 × 1095); the cap is the operator's
  lever.
* **D-PF10** No Signal and no `afterScore` kind: a forecast is not a per-entity score an Alert Rule waits for. The design's
  "flag degradation versus seasonal-naive" is stored as `backtest_rel_mae` (> 1 = worse than the benchmark); turning it
  into a flag or Signal is drift work (`PREDICTIVE-VALIDATE-DRIFT-1`).
* **D-PF11** One `key` column per series; a composite series (SKU × store) is a view Dataset with a composite key column.

## Not built

* Read / preview routes, the forecast chart, the Studio pane, provenance labels — `PREDICTIVE-SURFACE-1`.
* A degradation flag or Signal, drift across runs — `PREDICTIVE-VALIDATE-DRIFT-1`; fitted smoothing parameters
  (grid search) — gated on OD-6 / OD-9.
* Month grain, a declared season, a rolling-origin backtest, multiplicative or damped Holt-Winters.

## Tests

`features/inspecto-predictive/src/test/java/com/gamma/predictive/`, synthetic data only (`ForecastCorpus`: three daily
series, a series after `as_of`, a NULL key, and a 104-week series, as of 2026-06-30; the expected forecasts in its
header).

| Class | What it pins |
|---|---|
| `ForecasterTest` | golden answers: seasonal-naive repeats an exact season (zero error, zero-width interval); a hand-computed noisy season (MAE 6/7, RMSE √(6/7), sMAPE, the 80 % interval and its √2 widening); Holt-Winters reproduces trend + season exactly while seasonal-naive misses by 14 (relative MAE 0); a hand-computed Holt-Winters recursion (exact binary fractions); 13 buckets insufficient, 14 forecast; recompute and a tampered row |
| `ForecastModelTest` | defaults, horizon 90 accepted and 91 / 0 / absent refused, `maxSeries` 1 000 000 accepted and 1 000 001 refused, history bounds per grain, smoothing required / refused / ranged, closed lists, unknown keys |
| `ForecastJobTest` | a real `JobService` run: markers, history vs `_latest`, the column order, Holt-Winters' golden forecasts, empty buckets and an all-NULL `sum` read as 0, rows before the history (including the day just before) ignored, the NULL insufficient row, seasonal-naive's persisted backtest (MAE 14, relative MAE 1, sMAPE, the 80 % interval), week grain over complete ISO weeks only, recompute of every row, unknown model, the series cap (2 refused, 3 accepted; and with every series at full history, 3 refused, 4 accepted), a stored horizon of 91, an output folder another model owns |
| `ForecastKindValidatorTest` | Schema columns, time / numeric types, unknown Dataset, output clash, the reserved prefix and its read-Dataset exception |
| `ControlApiForecastModelTest` | real HTTP: a horizon of 91 and a non-time `time` are 422 with nothing stored, a good one saves, a Dataset under the prefix is 422 |
| `ForecastJobTypeTckTest` | the JobTypeProvider TCK (manifest parity: `SegmentJobTypeManifestTest`, module-wide) |
