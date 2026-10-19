---
type: Guide
title: "Scoring — admin guide"
description: "Enabling Risk Scores, permissions, config keys and flags, storage, retention, the masking key and common failures."
resource: features/inspecto-scoring/src/main/resources/META-INF/inspecto/module.toon
tags: [scoring, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Scoring — admin guide

## Enabling

- Optional module `features/inspecto-scoring` (manifest id `scoring`,
  [module.toon](../../../../../features/inspecto-scoring/src/main/resources/META-INF/inspecto/module.toon)); sold
  with `entity-list` as add-on `scoringLists` in [offerings/standard.toon](../../../../../offerings/standard.toon):
  Standard edition and above. It requires `entity-list`.
- Absent module: the routes answer the manifest's `absentMessage` ("Standard edition and above").
- The Decision Kernel in this hub is engine base and ships in every edition.

## Permissions

| Action | Capability |
|---|---|
| Read a score, preview a saved model | `canWorkIncidents` |
| Author models; preview unsaved content | `canAuthorWorkbench` |
| See the entity key unmasked | `canRevealLinkEntities` |

A model's `dataScope` is stricter than for objects: an unscoped model is readable by unscoped callers alone
(see the read route in the [spec](spec-risk-scores.md)).

## Config keys and flags

- Model keys: the `risk-score` component kind in the [spec](spec-risk-scores.md).
- Job Type `risk.score`, param `model`; trigger on `cron` or `on_pipeline:`.
- `-Drisk.score.maxEntities` (default 200 000, any positive int): the system entity cap when the model sets no `maxEntities` (the model's own range is 1..2 000 000)
  (1..2 000 000). A malformed value keeps 200 000; the cap cannot be disabled. Past it the Run fails.
  Other launch flags: [launch flags](../../layer/platform/admin-guide-launch-flags.md).

## Storage and retention

- History `<dataDir>/risk_scores_<id>/scores-<ts>-<runId>.parquet`; `<dataDir>/risk_scores_<id>_latest/` is swapped
  each run. Both are written as `*.tmp` and moved atomically.
- Retention is OFF by default. Model key `retainDays` or `retainRuns` (not both) prunes older run files; a pruned
  run's score is no longer reproducible.
- Masking key `<config root>.secrets/.risk-score-mask.key`: skipped by backup, refused by `PathJail`, served by no
  route. Tokens are deterministic per Space, and a leak of the key exposes every masked value.

## Raising Incidents

Declare `risk_scores_<id>_latest` as a Dataset and add a per-entity Alert Rule at the model's `highThreshold`
(Incident priority section of the [spec](spec-risk-scores.md)).

## Common failures

- The Run fails naming the cap: raise `maxEntities` or `-Drisk.score.maxEntities`.
- A query failure carries model, factor, Dataset and error class only; reproduce with `POST /risk-scores/preview`.
- An evidence value written before its column was classified stays raw; re-run the Job to rewrite `_latest`.
- `sum(amount - fee)` refused at save: put the arithmetic in a Dataset `calculated` column.
