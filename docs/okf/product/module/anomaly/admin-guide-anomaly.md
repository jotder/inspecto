---
type: Guide
title: "Anomaly Detection — admin guide"
description: "Enabling the anomaly add-on, permissions, config keys and flags, storage, retention and common failures."
resource: features/inspecto-anomaly/src/main/resources/META-INF/inspecto/module.toon
tags: [anomaly, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Anomaly Detection — admin guide

## Enabling

- Optional module `features/inspecto-anomaly` (manifest id `anomaly`,
  [module.toon](../../../../../features/inspecto-anomaly/src/main/resources/META-INF/inspecto/module.toon)), add-on
  `anomalyDetection` in [offerings/standard.toon](../../../../../offerings/standard.toon): Standard edition and above.
  It requires the `entity-list` module.
- Absent module: `GET /anomaly-scores/{model}/{entityKey}` and `POST /anomaly-scores/preview` answer 503
  `CAPABILITY_UNAVAILABLE` naming `inspecto-anomaly`; the SPA hides the anomaly surfaces.
  `GET /components/anomaly-model` stays an ordinary empty 200 (pinned by `NoAnomalyShipsInThePersonalBuildTest`).

## Permissions

| Action | Capability |
|---|---|
| Read a score, preview a saved model | `canWorkIncidents` |
| Preview unsaved model content; author models | `canAuthorWorkbench` |
| See the entity key unmasked | `canRevealLinkEntities` |

A model's optional `dataScope` limits who can read its scores. Details: [routes](spec-anomaly-scores.md).

## Config keys and flags

- Model file `registry/anomaly-models/<id>.toon` (generic component store: history, diff, restore, maker-checker).
  Keys and limits: [the model](spec-anomaly-scores.md).
- Job Type `anomaly.score`, params `model` and optional `as_of` (YYYY-MM-DD); a trigger's `params.as_of` runs a
  one-off back-dated score without a config edit.
- `-Danomaly.score.maxEntities`: system default entity cap when the model sets no `maxEntities`. Past the cap the
  Run fails; it never scores a subset (D-AD10).
- Large populations: at the default 1 GB SQL sandbox the bucket query ran out of memory at about 20k entities × 29
  days and needed `-Dassist.sql.memory_limit=8GB` (measured 2026-10-11; see [D-AD9 closure](spec-anomaly-scores.md)).
  Other launch flags: [launch flags](../../layer/platform/admin-guide-launch-flags.md).

## Storage and retention

Output goes through the shared `ScoreOutputWriter`: a history parquet per run plus an `anomaly_scores_<id>_latest`
relation swapped atomically. Anomaly scores have no retention setting; every run is kept. Classified evidence is
stored as `masked:` tokens.

## Raising Incidents

Declare `anomaly_scores_<id>_latest` as a Dataset and point a per-entity Alert Rule at it; templates use the
pending seed `config/pending/alert-rules/<name>.toon` with `afterScore: {kind: anomaly-model, model}` — see
[Incidents](spec-anomaly-scores.md).

## Common failures

- Run fails naming the entity cap: raise the model's `maxEntities` or `-Danomaly.score.maxEntities`.
- Run fails because a watch or exclusion Entity List is absent: create the list or remove it from the model.
- Save refused 422 "not in the Schema": the message names the `features[<n>]` path of the missing column.
- A query failure reports model, Feature, Dataset and error class only, never the DuckDB message; reproduce
  with the preview route.
