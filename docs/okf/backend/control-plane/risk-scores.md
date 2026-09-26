---
type: Concept
title: Risk Scores — weighted-factor scoring per entity
description: The risk-score component kind, the risk.score Job that writes the scores Dataset, GET /risk-scores/{model}/{entityKey}, and Incident priority through a per-entity Alert Rule.
resource: inspecto-engine/src/main/java/com/gamma/risk/RiskScoreEvaluator.java
tags: [control-plane, risk-score, job, alert-rule, incident, assurance, ws-22]
timestamp: 2026-09-27T00:00:00Z
---

# Risk Scores

A **Risk Score** (ASSURE-RISK-SCORE-1, WS-22; term chosen by operator decision D-P1) is an explainable
0–100 number per entity — subscriber, account, device, SIM, dealer, channel, partner, or a free-form
entity type. It is **computed on the server by a Job**, never by a Step Processor (the operator's hold on
new Step Processors stands). The Link Analysis `suspicionScore` is a different thing: a client-side graph
centrality composite. Keep the two apart.

## The model — the `risk-score` component kind

It is stored at `registry/risk-scores/<id>.toon` through `ComponentStore` / `ComponentRoutes`, so history,
diff, restore and the maker-checker hold come with the generic component routes. It is parsed and
validated by `com.gamma.risk.RiskScoreModel`.

The scores Datasets are **always** `risk_scores_<id>` and `risk_scores_<id>_latest`. The name is derived,
never authored: sending `scoresDataset` is a 422. A model id is letters, digits and `_` only, so the mapping
is injective (`a-b` and `a_b` cannot share an output).

| Key | Meaning |
|---|---|
| `entityType` | `subscriber` · `account` · `device` · `sim` · `dealer` · `channel` · `partner`, or any plain token |
| `highThreshold` | a score at or above this is `high` (in (0, 100]) |
| `dataScope` | optional; a data-scoped caller must hold it to read a score |
| `factors[]` | `id`, `label?`, `dataset`, `key`, `measure`, `filters?`, `weight`, `cap?`, `evidence?` |

A factor's **indicator** is a Measure (`count` or `agg(field)`, with the Measure grammar's `filters`) over
one declared Dataset, grouped by that Dataset's entity-key column `key`. It is compiled by
`MeasureCompiler`, the same guarded compiler `/bi/query` and the Alert Rule probe use. There is **no raw
SQL key**, and an unknown key is refused.

### Save-time gates (fail closed, 422)

1. `ComponentRoutes.validateKind` → `RiskScoreModel.fromMap`. It checks the structure and refuses unknown
   keys. Weights and caps must be finite numbers (a numeric string is accepted). Every factor's value query
   and evidence query is **compiled** there, so an unknown aggregation, an unknown filter operator or an
   unsafe identifier fails the save.
2. `RiskScoreRoutes.requireStorable`, which needs the Space:
   - Every column a factor names (key, measure field, filter fields, evidence) must be in its Dataset's
     Schema, as `DatasetMeasureProbe.columns` reads it. A Dataset whose Schema cannot be read refuses the
     save. This is the same rule as the Alert Rule `by` check.
   - The derived output names must collide with nothing. A directory of that name under the data root that
     this model did not create is refused, and so is a Dataset of that id over another store.
3. Then `PendingChanges.hold`, the maker-checker funnel.

**Every writer runs gates 1 and 2.** `ComponentRoutes.validateKind(api, …)` is called by the component
route and by both bulk writers (`BundleRoutes`, `BiTemplates`), so a bundle cannot plant a model the
authoring route refuses.

🔴 **The writer never deletes what it does not own.** Each scores directory carries a
`.risk-score-output` marker naming its model. `RiskScoreEvaluator.write` creates the directory with the
marker, or accepts one whose marker names this model. Anything else fails the run, untouched. The
`_latest` swap removes only `scores-*.parquet`. Before this fix, an authored `scoresDataset: orders`
wiped a real Dataset's files, and two models sharing an output erased each other.

## The `risk.score` Job

`RiskScoreJobType` takes one parameter, `model`, and emits `risk.score.produced`. It reads the registry
from `SpaceConfigRoot` and writes under the Space `dataDir`. Trigger it on a `cron` schedule, or with
`on_pipeline:` on the pipeline that lands its Datasets.

The math is in `RiskScorer` and is pure:

- `contribution = weight × indicator`, then **held to at most `cap`**. The cap bounds the contribution, not
  the raw indicator.
- A **missing** indicator is `0` and is flagged `missing: true`. This covers an entity with no rows for the
  factor, or a NULL aggregate.
- `score = Σ contribution`, clamped to `[0, 100]`.
- `high = score ≥ highThreshold`.

The Job scores every entity that **any** factor names. It writes two outputs:

- `<dataDir>/risk_scores_<id>/scores-<ts>-<runId>.parquet` is the **history**: one row per entity per run.
- `<dataDir>/risk_scores_<id>_latest/` is swapped to this run's rows alone. This is the relation an Alert
  Rule watches.

Both files are written as `*.tmp` and revealed by an atomic move.

The columns are `model, entity_type, entity_key, score, high, factors (JSON), model_version, run_id,
scored_at`. Each element of `factors` is `{indicator, label?, value, missing, weight, cap?, capped,
contribution, evidence[]}`, with up to 3 evidence rows per entity. `model_version` is a 12-hex SHA-256 of
the stored model, excluding the `name`/`owner`/`shares` envelope.

**Every score is reproducible from its factors.** `RiskScorer.recompute(factors)` re-derives each
contribution from `weight`, `value` and `cap`; it does not trust the stored `contribution`. The Job test
and the HTTP test both assert that it matches the stored score.

Limits: a factor that names more than 200 000 entities **fails the run** rather than scoring a subset.
Evidence reads at most 20 000 rows per factor, and the run log reports `evidenceTruncated` when that cap
is hit.

## Incident priority — through the existing Alert Rule machinery

No new alerting path exists. To raise Incidents, declare the `_latest` relation as a Dataset and point one
per-entity Alert Rule at it. Use `by: [model, entity_key]` and set the threshold to the model's
`highThreshold`:

```
POST /components/dataset     {"id": "risk_scores_subs_latest", "physicalRef": "risk_scores_subs_latest"}
POST /components/alert-rule  {"id": "high-risk-subscriber", "dataset": "risk_scores_subs_latest",
                              "measure": "max(score)", "by": ["model", "entity_key"],
                              "comparator": "gte", "threshold": 50, "severity": "CRITICAL",
                              "description": "High Risk Score"}
```

`CRITICAL` or `ERROR` is what opens an Incident. Each high entity gets one Alert and one Incident, with
the attributes `key.model` and `key.entity_key`. When the score falls back below the threshold on the next
run, the Alert heals, as every per-entity Alert does. That healing is why the rule watches `_latest` and
not the history. `RiskScoreAlertTest` proves the flow end to end over real DuckDB.

## The read route — `GET /risk-scores/{model}/{entityKey}`

`RiskScoreRoutes` returns the entity's latest score with its factors. The latest row is ordered by
`scored_at`, then `run_id`. The gates are:

- `canWorkIncidents`.
- Then an **existence-hiding 404** for each of these cases, all indistinguishable: an unknown model; a
  model whose `dataScope` the caller's data scopes lack; a `RowScope` DENY on the resource kind
  `risk-score`; or no score for the entity.

**Data scopes are stricter than for objects.** A data-scoped caller reads only a model whose `dataScope`
it holds. An **unscoped** model is readable by unscoped callers alone, because its evidence is raw source
rows. The entity key is a **bound parameter**. It is never spliced into SQL and never resolved as a path.
Real-HTTP coverage with an armed Subject is in `ControlApiRiskScoreTest`.

⚠ "Dataset data scopes" do not exist as a separate mechanism. `Subject.dataScopes` filters case-typed
objects (SEC-7d), and nothing scopes Dataset rows. The route applies that rule to the model's optional
`dataScope`.

### Masking (render time)

🔴 Before this rule, `evidence` returned raw source values (up to 8 columns × 3 rows) and the raw entity
key to anyone holding `canWorkIncidents`. `RiskScoreMasking` now masks on the way out. The stored scores
Dataset stays raw, so every score stays recomputable; masking touches no number.

- **What is sensitive.** A column is sensitive when its Dataset's registry `columns[].classification` is
  `MSISDN`, `IMSI`, `ACCOUNT` or `PII` (case-insensitive). This is the same source Link Analysis's typed
  masking reads.
- **The entity key** is masked when **any** factor's key column is sensitive, because a key does not
  record which Dataset it came from.
- **An evidence value** is masked when its column is sensitive in that factor's Dataset.
- **The pseudonym** is `masked:<16 hex>`, an HMAC-SHA256 under a random per-Space key
  (`<dataDir>/.risk-score-mask.key`, created on first use, never served). It is stable across requests.
- The response carries a `masking` block naming the masked columns.

⛔ **There is no reveal.** Link Analysis's audited reveal (`EntityMasking`, `canRevealLinkEntities`) is bound
to an Investigation's sealed log in the optional `inspecto-geo-link` module. The core cannot reach it, and
a Risk Score has no Investigation to bind one to. A masked value simply stays masked.

⚠ Two limits remain:
- The caller looks a score up by its **raw** key. That key is the Incident's `key.entity_key`, which the
  Alert already stores raw, and the envelope's `links.self` echoes the caller's own request path.
- A column the registry leaves unclassified is not masked.

## UI

`inspecto-risk-score-panel` (`inspecto/components/`) shows the score and one contribution bar per factor.
Each bar is relative to the largest contribution, and missing and capped factors are named. It appears in
two places:

- On an Incident's detail view, whenever the Incident's attributes carry `key.model` and `key.entity_key`.
- In the Link Analysis toolbox's scoring section, once a model id is entered. Each row's **Factors** button
  uses the node id as the entity key.

A 404 renders nothing.

## Residuals

- **The watch Entity List is not fed.** "Above a threshold → watch Entity List" waits on
  `ASSURE-ENTITY-LISTS-1`, which is on hold.
- There is no authoring pane: models are written through `/components/risk-score`.
- An indicator is a Measure. There is no free-form arithmetic expression, and no reference to a saved
  Measure component, because none exists.
- The history Dataset grows by one file per run. No retention is applied yet.
