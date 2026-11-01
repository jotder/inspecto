---
type: Architecture
title: Churn Scores — the Churn Model kind and the predict.churn Job (declared coefficients)
description: As built for PREDICTIVE-CHURN-DECLARED-1 (2026-11-01) — slice S3 of the Predictive Analytics add-on. The churn-model component kind (declared intercept, inputs, transforms and coefficients), the predict.churn Job that writes a 0-100 Churn Score with per-input contributions, the insufficient rule, the churn.score.produced Signal and the afterScore pending Alert Rule, the save gates, the decisions taken (D-PC1…D-PC9, pending operator confirmation) and what is not built.
resource: docs/okf/product/module/anomaly/plan-predictive-analytics.md
tags: [predictive, churn, churn-score, logistic, job, alert-rule, analytics]
timestamp: 2026-11-01T00:00:00Z
---

# Churn Scores (declared coefficients)

*PREDICTIVE-CHURN-DECLARED-1, built 2026-11-01 — slice S3 of the Predictive Analytics design
([`plan-predictive-analytics.md`](../anomaly/plan-predictive-analytics.md) §2.2, Option A).* A **Churn Model** (the
Type) declares a logistic score; one `predict.churn` run writes one **Churn Score** (the Instance) per entity. Its
declared variables are **Inputs** ([`GLOSSARY.md`](../../../../GLOSSARY.md) §4), never *Features* (an Anomaly Model's
word). **Nothing is fitted by the platform**: the intercept and coefficients are authored (OD-4, OD-6), so every score
is hand-authored until a backtest exists (`PREDICTIVE-VALIDATE-DRIFT-1`).

Same module, same shape as [Customer Segments](spec-segmentation.md): `features/inspecto-predictive`, add-on
`predictiveAnalytics`, no routes.

| Class | Role |
|---|---|
| `ChurnModel` | the kind's record and fail-closed `fromMap` parse; an `Input`'s transform |
| `ChurnScorer` | the pure maths: contributions, logit, score, band, `insufficient`, `recompute` |
| `ChurnEvaluator` | the two windowed reads (bound statements), the output columns, the write |
| `ChurnJobType` | the `predict.churn` Job Type (`JobTypeProvider`); emits `churn.score.produced` |
| `ChurnKindValidator` | the save gates (`ComponentKindValidator`) |

In the core: `com.gamma.alert.ChurnScoreOutputs` (kind, prefix, marker — read by the pending Alert Rule seed with or
without the module), `SignalType.CHURN_SCORE_PRODUCED`, the kind in `ComponentRegistry.TYPE_BY_DIR` (`churn-models/`)
and `ComponentStore.WRITABLE_TYPES` (so governable: an approval policy can hold every save, OD-7), and
`PendingAlertRules.KINDS`. `ScoreOutputWriter` binds a `null` kind value as SQL NULL (an insufficient score).

## The kind

Stored at `registry/churn-models/<id>.toon`; the id matches `[A-Za-z0-9][A-Za-z0-9_]*` (at most 64 characters).

```
entityType: customer
dataset: orders
key: customer_id
time: order_date
window: 90
intercept: -1
inputs[3]:
  - name: quiet
    measure: recency(order_date)
    transform: cap
    cap: 120
    missing: 120
    coefficient: 0.03
  - name: orders
    measure: count
    transform: log
    coefficient: -1
  - name: spend
    measure: sum(amount)
    transform: z
    mean: 100
    scale: 50
    coefficient: -0.5
minHistoryDays: 30
elevatedThreshold: 60
highThreshold: 80
```

| Key | Meaning |
|---|---|
| `entityType`, `dataset`, `key`, `time` | required; `time` must be a DATE or TIMESTAMP column |
| `window` | the inputs' trailing days, 1…3660; absent = all history up to `as_of` |
| `intercept` | required number |
| `inputs` | 1…32, in order; each `{name, measure, transform, coefficient}` plus the transform's parameters and an optional `missing` |
| `measure` | `count`, `countDistinct(f)`, `sum(f)`, `avg(f)`, `min(f)`, `max(f)` (numeric `f`) or `recency(f)` (DATE/TIMESTAMP `f`: whole days since the latest `f` in the window) |
| `transform` | `none` (default), `log` = `ln(1 + max(x, 0))`, `cap` = `min(x, cap)`, `z` = `(x − mean) / scale` with `scale > 0`; a parameter is refused on any other transform |
| `missing` | the value an input takes when the entity has none in the window; refused on `count`, `countDistinct`, `sum` (theirs is 0) |
| `minHistoryDays` | 1…3660, default 30 |
| `elevatedThreshold`, `highThreshold` | `0 < elevated < high <= 100`, default 60 / 80 |
| `maxEntities` | 1…2 000 000; default `-Dpredict.churn.maxEntities`, else 200 000 |
| `description`, `name`, `owner`, `shares` | free text and the component envelope |

Numbers are finite and within ±1 000 000. Input names are unique identifiers. Unknown keys, in the model or an input,
are refused — never ignored.

## The run

`predict.churn` with params `model` (required, config only) and `as_of` (`YYYY-MM-DD`, default yesterday UTC; trigger
args override the config).

1. **Population.** One statement through `MeasureCompiler` with an **open** window ending at `as_of`: `min(time)` per
   `key`. Every entity with a row up to `as_of` is scored — including one with no row in the model's window, which is
   the entity most likely to have churned. NULL keys are dropped. More entities than the cap fails the run before
   anything is written.
2. **Inputs.** One statement with `window: {field: time, asOf, days: window}`: the distinct input Measures per `key`,
   every value bound. An entity with no row in the window has no value: `count`, `countDistinct` and `sum` take 0, the
   others their `missing`.
3. **Score.** `history_days` = whole days from the entity's first row to `as_of`. If it is below `minHistoryDays`, or an
   input has no value and no `missing`, the entity is **insufficient**: `score` and `logit` NULL, `band`
   `insufficient` — never 0, so an Alert Rule over `score` never fires on it. Otherwise each input contributes
   `coefficient · transform(x)`, `logit = intercept + Σ contributions`, `score = 100 · σ(logit)`
   (`LogisticRegression.recompute`, the Analytics Kernel's stable sigmoid) and `band` is `high` / `elevated` /
   `normal` (`ScoreScale.band`).
4. **Write** `churn_scores_<model>` (history, one Parquet file per run) and `churn_scores_<model>_latest`, through the
   score output contract (`ScoreOutputWriter`, `ScoreKind`): marker `.churn-model-output`, `*.tmp` then atomic move,
   no retention.
5. **Signal** `churn.score.produced` (INFO) with `model, run, scored, elevated, high, insufficient`.

Columns: the head `model, entity_type, entity_key`, then `as_of` (TIMESTAMP), `score` (DOUBLE, NULL when
insufficient), `band`, `logit`, `history_days`, `explanation`, then the tail `model_version` (12-hex hash of the
stored model minus its envelope), `run_id`, `scored_at`.

**Recompute.** A scored row's `explanation` is `{intercept, inputs: [{name, value, imputed?, transformed, coefficient,
contribution}], logit}`; an insufficient row's is `{insufficient: history, historyDays, minHistoryDays}` or
`{insufficient: missing, inputs: [names]}`. `ChurnScorer.recompute(model, explanation)` re-applies the model's
transform and coefficient to each stored `value`; `ChurnJobTest` asserts every stored row recomputes to its own
score and band and that its contributions sum to its logit.

**Fail closed.** An unknown model or Dataset fails the run; so does the cap, an output directory without this model's
marker, and a query failure (named by model, Dataset and error class only).

## The Alert path

A per-entity Alert Rule over the read Dataset `churn_scores_<model>_latest` (`measure: max(score)`,
`by: [model, entity_key]`, `comparator: gte`, `threshold: <highThreshold>`) raises one Alert and, at `CRITICAL`, one
Incident per high entity — the existing machinery, no code here (`ChurnScoreAlertTest`).

A Space Template declares such a rule **pending** in `config/pending/alert-rules/<name>.toon` with
`afterScore: {kind: churn-model, model: <id>}`: the `_latest` store has no Schema until the model first runs, so the
rule's `by` columns cannot be checked at apply time. `churn.score.produced` → `CollectorService.onScoreProduced` →
`PendingAlertRules.onScoreProduced` creates it through the normal save gate after the first run (refused and retried
while an approval policy governs `alert-rule` or `dataset`, never overwriting an existing rule) — the mechanism
`anomaly-model` and `risk-score` already use (D-AD7).

## Save gates

`ChurnKindValidator`, run by every writer through the core's component save gate (`ComponentRoutes.validateKind`):

* structure — `ChurnModel.fromMap`;
* in the Space — every referenced column (`key`, `time`, the inputs' fields) is in the Dataset's Schema; `time` and
  every `recency` field are DATE/TIMESTAMP; every `sum` / `avg` / `min` / `max` field is numeric; an unreadable Schema
  or an unknown Dataset refuses; neither output name may already exist under the data root unless this model owns it,
  nor be the id of a Dataset over another store;
* the **reserved prefix** `churn_scores_` on every `dataset` and `sink` write: refused, except a Dataset whose id =
  `physicalRef` = `churn_scores_<model>` or `…_latest` of a saved model.

## Decisions (D-PC1…D-PC9 — assistant-decided under operator delegation, 2026-11-01, pending operator confirmation)

* **D-PC1** Output prefix `churn_scores_` and marker `.churn-model-output`, as the design named them; the naming lives
  in the core (`ChurnScoreOutputs`) beside the Risk and Anomaly ones, because the pending Alert Rule seed reads it.
* **D-PC2** Score `100 · σ(logit)`; bands default 60 / 80 like the Anomaly Model; an insufficient entity has a NULL
  score and band `insufficient`.
* **D-PC3** *History* is the days from the entity's first row (all history, not the window) to `as_of`;
  `minHistoryDays` default 30. The population is every entity with a row up to `as_of`, so a lapsed entity is scored.
* **D-PC4** With no row in the window, `count` / `countDistinct` / `sum` are 0 (their true value); the other aggregates
  need a declared `missing`, else the entity is insufficient (reason `missing`) — never a silent 0.
* **D-PC5** Transforms `none`, `log` (`ln(1 + max(x, 0))`), `cap` (upper cap), `z` (stated mean and scale); no free
  arithmetic, as Risk factors.
* **D-PC6** **The `afterScore` writer needs no new inventory row.** The backlog row said the pending-rule writer
  "needs an operator-approved inventory row". The write sites are `PendingAlertRules`'s: `#ensureLatestDataset` is listed
  in the write-funnel inventory (`ConfigWriteFunnelTest.WRITERS`, approved by the operator 2026-10-06 and generalised
  to every score kind by D-AD7), and `#onScoreProduced` passes the funnel because it checks `PendingChanges.governs`
  itself. Churn adds a kind to `KINDS`, not a writer site. **Reverse:** remove `ChurnScoreOutputs` from `KINDS`; a
  pending churn rule then fails the template seed gate.
* **D-PC7** Signal `churn.score.produced` is a core constant (`SignalType`), matched by `CollectorService`, like the
  anomaly one; the module emits it directly (no module alias class).
* **D-PC8** No outcome label, backtest or "validated" mark (OD-10 and OD-13 stay open; S6
  `PREDICTIVE-VALIDATE-DRIFT-1`). No Entity Lists feed, no key masking (no read route yet), no Risk Score factor wiring
  (OD-16: `churn_scores_<model>_latest` can already be read as a Dataset).
* **D-PC9** `sum`, `avg`, `min` and `max` read a numeric column only; the dates of activity are read through `recency`.

## Not built

* The preview route, the Studio pane, provenance labels — `PREDICTIVE-STUDIO-PREVIEW-1` (the read route and entity panel shipped: [spec-surface](spec-surface.md)).
* Backtest (AUC / lift), the outcome Dataset, drift — `PREDICTIVE-VALIDATE-DRIFT-1`; fitted coefficients —
  `PREDICTIVE-CHURN-FITTED-1` (gated on OD-6 / OD-9).
* A shipped Space Template with a pending churn rule (the mechanism is built and tested; no template uses it).
* A test of `CollectorService.onScoreProduced`'s Signal-to-kind mapping: the tests call `PendingAlertRules.onScoreProduced` directly (the anomaly kind has the same gap).

## Tests

`features/inspecto-predictive/src/test/java/com/gamma/predictive/`, synthetic data only (`ChurnCorpus`: six customers
and a NULL key, as of 2026-06-30, hand-computed logits, scores and bands in its header).

| Class | What it pins |
|---|---|
| `ChurnScorerTest` | contributions = coefficient × transform and sum to the logit, the corpus figures, absent inputs (0 vs `missing`), the history boundary (29 insufficient, 30 scored), a missing input without `missing`, the transforms, recompute and a tampered row |
| `ChurnModelTest` | defaults, every refusal names its field |
| `ChurnJobTest` | a real `JobService` run: markers, history vs `_latest`, the column order, hand-computed scores and bands, the NULL insufficient score, rows after `as_of` and NULL keys, stored contributions sum to the stored logit, recompute of every row, the `churn.score.produced` Signal, unknown model, the cap, an output folder another model owns |
| `ChurnScoreAlertTest` | a per-entity Alert Rule raises Incidents for the two high entities only (the NULL insufficient score is pinned by `ChurnJobTest`); a pending `afterScore: {kind: churn-model}` rule is created by the first run |
| `ChurnKindValidatorTest` | Schema columns, time / recency / numeric types, unknown Dataset, output clash, the reserved prefix and its read-Dataset exception |
| `ControlApiChurnModelTest` | real HTTP: a malformed and an unstorable model are 422 with nothing stored, a good one saves, a Dataset under the prefix is 422 |
| `ChurnJobTypeTckTest` | the JobTypeProvider TCK (manifest parity: `SegmentJobTypeManifestTest`, module-wide) |
