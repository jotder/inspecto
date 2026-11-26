---
type: Reference
title: Model output Datasets — score, forecast and segment outputs registered automatically
description: How a model run registers its history and _latest outputs as system Datasets (BI-INSIGHTS-1 P0 I-14) — columns, masking, sharing, approval policy and failure rules
tags: [anomaly, churn, forecast, segmentation, risk-score, dataset, bi-insights]
timestamp: 2026-11-24T00:00:00Z
item: module.engine
audience: internal
status: current
verified: 2026-11-24 code-reviewed
code: platform/inspecto-engine
---

# Model output Datasets

`BI-INSIGHTS-1` P0, capability I-14 (plan [`2026-11-18-business-insights-plan.md`](../../../../superpower/2026-11-18-business-insights-plan.md)).
Every model kind writes two stores through `ScoreOutputWriter`: the history `<prefix><model>` (one file per run) and
`<prefix><model>_latest` (the newest run). Until I-14 a Builder declared them as Datasets by hand; now each run of
`anomaly.score`, `predict.churn`, `predict.forecast`, `predict.segment` and `risk.score` registers both, right after it
writes them (`com.gamma.alert.ScoreDatasets.register`, called by each Job Type).

| Kind | Prefix |
|---|---|
| anomaly-model | `anomaly_scores_` |
| churn-model | `churn_scores_` |
| forecast-model | `forecasts_` |
| segmentation-model | `customer_segments_` |
| risk-score | `risk_scores_` |

## What is registered

- `physicalRef` = the Dataset id, `origin: system`, tag `model-output`, a one-line description.
- **Every column declared** with its role (`ScoreColumns`: dimension, measure, temporal); `entity_key` is an `entity`
  column classified with the **strictest of the model's Entity Type and the class of every input's key column** (its
  declaration and its lineage), so BI reads alias it whenever the keys it holds are masked at their source — the
  `telco-bypass` template scores `entityType: sim` over an `msisdn` key, so its `entity_key` is classified `MSISDN`. An
  input key whose class cannot be traced registers nothing (fail closed). (An undeclared column over a store without
  Pipeline lineage would be masked wholesale, so none is left undeclared.)

## Rules (operator, 2026-11-24)

- **Readable only by who may read the model and every Dataset it reads**, a Mapping input with its base,
  recursively through Mappings (`com.gamma.pipeline.SharingEnvelope.of(store, …)`, the rule KPI History uses too;
  operator 2026-11-26): an unshared component is open, so the shared
  ones' `owner` / `shares` apply. A Dataset this code registered is re-enveloped each run.
- **Any refusal narrows.** When the outputs cannot be registered as they are now (sources shared differently, an
  untraceable key, a failure), the system Datasets an earlier run registered are narrowed to `shares: []`, owned by
  the model's owner only when that owner may see every shared source (its owner, or a user share naming them), else by
  no one — readable only by who administers access. The run says why; new scores never land under a wider, stale
  envelope.
- **System Datasets regardless of a Dataset approval policy** (as D-BI11 for KPI History).
- **A Dataset declared by hand is never touched** (any `origin` but `system`), except a bare entry with no sharing —
  only `physicalRef`, as `PendingAlertRules` registers a template's `_latest` — which is adopted (adopting can only
  narrow who reads it). One of that id over another store is reported and left alone. The kind's reserved-prefix save
  check still refuses any other Dataset over these stores.
- **Never fails the run.** The scores are written first and Alert Rules read them; a registration problem is logged
  as a warning and named in the run message (`…; output Datasets not registered: <why>`).

## Limits (deliberate)

- A Dataset someone declares by hand over these stores (the reserved-prefix exception) is theirs: its sharing and
  columns are not governed by these rules.
- A model's system Datasets stay after the model is deleted; delete them with it.

## Tests

`ScoreDatasetsTest` (columns and roles, the strictest key class and the untraceable key, the envelope with a Mapping's
base, narrowing on refusal, adopting a bare `_latest`, hand-declared and foreign Datasets, an output the model did not
write), `ForecastJobTest.aRunRegistersItsOutputsAsSystemDatasets` (a real JobService run;
falsified by removing the call), `KpiSnapshotJobTest` (the shared envelope rule).
