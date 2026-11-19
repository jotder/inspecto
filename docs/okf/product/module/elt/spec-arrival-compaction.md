---
type: Concept
title: Arrival compaction — compact mode arrivals
description: The compact maintenance task's arrivals mode (ArrivalCompactor) - one import Dataset's files merged in arrival order with its merge map and retention, the merged-away files marked COMPACTED_AWAY so a reprocess is refused, and its two triggers (the import's commit step and a maintenance Job).
resource: platform/inspecto-engine/src/main/java/com/gamma/job/ArrivalCompactor.java, platform/inspecto-engine/src/main/java/com/gamma/job/MaintenanceJob.java, inspecto/src/main/java/com/gamma/control/DatasetCompaction.java, inspecto/src/main/java/com/gamma/control/ImportCommitSteps.java
tags: [elt, engine, maintenance, compaction, import, merge-map, retention, reprocess, onboarding]
timestamp: 2026-11-19T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-20 last-edit
code: platform/inspecto-engine
---

# Arrival compaction — `compact` `mode: arrivals`

(`BASIC-ELT-PROFILE-1` B6, as built 2026-11-19.) Add data's compaction (`LA-DATASET-COMPACT-1`, built in the control
plane's `DatasetCompaction`) is now an engine maintenance task: the `compact` task's `mode: arrivals`, class
`com.gamma.job.ArrivalCompactor`. `DatasetCompaction` keeps its three routes and calls it. The Link Analysis hub keeps
the user-facing story (routes, the merge map's fields, retention, the swap, the link index):
[architecture-data-onboarding.md §6.19](../la-core/architecture-data-onboarding.md#619-dataset-storage-compaction-the-merge-map-and-retention-la-dataset-compact-1-as-built-2026-11-05).

## Two modes of one task

| `mode` | Class | Merges | Order, map | Retention |
|---|---|---|---|---|
| `partitions` (the default, unchanged) | `PartitionCompactor` | each partition directory under `dir` holding `min_files` files older than `min_age_days` | none | none |
| `arrivals` | `ArrivalCompactor` | every Parquet file of ONE import Dataset (`dataset`), `<dataRoot>/<id>/database` | arrival order; the merge map `<dataRoot>/<id>/compaction.json` | the Dataset entry's `retention` where the edition has `pipeline.automation` |

Both mark every file they merge away `COMPACTED_AWAY` in the Consignment output registry
(`ConsignmentOutputStores.markCompactedAway`), so `ReprocessCommand` refuses a reprocess of a Consignment whose output
was merged instead of re-reading its backup beside the merged file. Any other `mode` is refused
(`IllegalArgumentException`). A dry run of either mode does nothing (`noPreview`). The two Job parameters are declared
on the `maintenance` Job Type (`JobService`): `mode` (options `partitions`, `arrivals`; default `partitions`) and the
existing `dataset`.

## Triggers

- **The import's commit step and Add data** (unchanged behaviour): `import.compaction` (`ImportCommitSteps`, order 100,
  phase `COMMIT`; see [spec-pipeline-execution-run-steps.md](spec-pipeline-execution-run-steps.md)) and Add data's in-run
  path call `DatasetCompaction.afterImport` → `ArrivalCompactor.compact`, past `THRESHOLD` (20) files. *Compact now*
  (`POST /datasets/{id}/compact`) calls it at any count. Both hold the import's claim (`OnboardImport.IN_FLIGHT`) and tell
  Add data's follow-ons (`OnboardImport.followOns`).
- **A maintenance Job** `{type: maintenance, task: compact, mode: arrivals, dataset: <id>}`: at any count, as *Compact
  now*. It reads the Dataset entry from the Space's registry (`SpaceConfigRoot.requireCurrent`) and the Job's data root
  (as `materialize` does); the follow-ons are the entitled `ImportFollowOn` providers, told with actor `scheduler`.
  Refused: no `dataset`, a Dataset that is not there, a Dataset an import did not write (`origin` other than `import`:
  its store is not `<dataRoot>/<id>/database`, so its files are compacted with `mode: partitions`). Nothing arms such a
  Job: Add data's behaviour is unchanged; an operator authors one (for instance to apply a *keep N days* policy on days
  with no new file).

## The claim

A Job takes the same claim an import takes, through the `ArrivalCompactor.Claims` seam: the control plane installs it
(`DatasetCompaction.register`) as `IN_FLIGHT.add(api.writeRoot() + "|" + id)`, where `api.writeRoot()` resolves the Job
thread's Space through its Space MDC, the way the import's commit steps key their claim. A Dataset someone else holds
(an import, *Replace the period*, a delete, *Compact now*, a run's own pre-run claim) is left: the Job succeeds with
"compacted at the next run". An engine with no control plane grants every claim (nothing else changes an import's files
there). The claim is released on every path.

## The registry marking

`finish` (the last step of the swap, also run by `heal` after a crash) marks every input it removes, including one a
reader still holds open, **before** deleting them: the merged file is in by then, so the input's rows are in it (or gone
by retention) and a reprocess must be refused even while the old file is still on disk. A crash between the marking and
the map commit leaves the pending map, so the next `heal` marks again (idempotent) and finishes. The previous merged file
is never in the registry (no Consignment wrote it), so marking it is a no-op. Without a registry
(`-Dconsignment.outputs.backend=none`) nothing is marked and nothing is decidable, as for `partitions`.

**The defect this fixes.** Before B6 the arrival compaction marked nothing: `POST /runs/{id}/reprocess` of an Add data
arrival whose file was merged found its output path absent, restored the member from `backup/`, and re-ingested it
beside the merged file — 10 rows became 15
(`ControlApiDatasetCompactionTest#aReprocessOfAMergedArrivalIsRefusedNotDoubled`, red before the change). Now the
reprocess is refused and the rows stay. ⚠ The route answers the refusal as `500 INTERNAL` with a correlation id: the
engine's message names server paths. A `409` with a path-free reason is not done (board `REPROCESS-REFUSAL-500-1`).

## The merge map moved, not changed

Same file (`<dataRoot>/<id>/compaction.json` and its `.pending`), same fields, same reader rules: a map written before B6
is read as it is, with no migration. `ArrivalCompactor` owns every reader and writer: `merged`, `trustedSegments`,
`segmentsRewritten` / `restoreMap` (*Replace the period*, `DatasetPeriod`), `summary` (the storage answer), `heal`,
`parquetFiles`, `retentionInForce` / `retentionOf`. The Dataset entry keys it reads: `retention`, `eventDate`
(`ArrivalCompactor.EVENT_DATE_KEY`, which `DatasetRoutes.EVENT_DATE_KEY` now names).

## Not done here

- Arrivals mode for a Standard Pipeline's Dataset (store at its own `dirs.database`, no merge map yet): later BE-2 work.
- *Replace everything* and *Replace the period* do not change registry rows (B4, B13).
- The claim still lives in the control plane (`IN_FLIGHT`); moving it into the engine is B11.
- No reader-side swap lock: between the move and the deletes a reader may count rows twice, as before B6.

Tests: `ArrivalCompactorTest` (engine: a Job merges in arrival order and marks the registry, retention on Standard only,
a held Dataset is left, the refusals, `partitions` stays the default and an unknown mode is refused, a heal marks),
`ControlApiDatasetCompactionTest` (the Add data side over HTTP, now with the reprocess case and
`aCompactJobInArrivalsModeTakesTheImportsClaim`: a Job authored through `POST /jobs` leaves a Dataset Add data holds,
then compacts it and gives the claim back),
`ControlApiImportCommitStepsTest`, `PartitionCompactorRegistryTest`, `MaintenanceTaskContractTest`.
